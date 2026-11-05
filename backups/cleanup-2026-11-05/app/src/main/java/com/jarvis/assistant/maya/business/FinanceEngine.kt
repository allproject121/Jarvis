package com.jarvis.assistant.maya.business

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.assistant.finance.StockQuoteDetail
import com.jarvis.assistant.finance.YahooFinanceClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.TimeUnit

/** portfolio.json + price_alerts.json */
object FinanceStore {
    const val PORTFOLIO = "portfolio.json"
    const val ALERTS = "price_alerts.json"

    fun holdings(context: Context): List<Holding> = JsonFiles.toList(JsonFiles.readArray(context, PORTFOLIO)).map { Holding.fromJson(it) }

    fun saveHoldings(context: Context, list: List<Holding>) =
        JsonFiles.writeArray(context, PORTFOLIO, JSONArray().apply { list.forEach { put(it.toJson()) } })

    fun addHolding(context: Context, symbol: String, qty: Double, price: Double, note: String): Holding {
        val list = holdings(context).toMutableList()
        val idx = list.indexOfFirst { it.symbol.equals(symbol, true) }
        val h = if (idx >= 0) PortfolioMath.merge(list[idx], qty, price).also { list[idx] = it }
        else Holding(symbol, qty, price, note = note).also { list.add(it) }
        saveHoldings(context, list)
        return h
    }

    fun removeHolding(context: Context, symbol: String, qty: Double?): Holding? {
        val list = holdings(context).toMutableList()
        val idx = list.indexOfFirst { it.symbol.equals(symbol, true) }
        if (idx < 0) return null
        val h = list[idx]
        if (qty == null || qty >= h.quantity) list.removeAt(idx) else list[idx] = h.copy(quantity = h.quantity - qty)
        saveHoldings(context, list)
        return h
    }

    fun alerts(context: Context): List<PriceAlert> = JsonFiles.toList(JsonFiles.readArray(context, ALERTS)).map { PriceAlert.fromJson(it) }

    fun saveAlerts(context: Context, list: List<PriceAlert>) =
        JsonFiles.writeArray(context, ALERTS, JSONArray().apply { list.takeLast(100).forEach { put(it.toJson()) } })
}

object FinanceEngine {
    private const val TAG = "FinanceEngine"

    fun client(context: Context) = YahooFinanceClient(context.applicationContext)

    /** Company name or ticker -> ticker. Prefers NSE listings, like Maya's get_stock_quote. */
    suspend fun resolve(context: Context, raw: String): String {
        val c = client(context)
        val norm = c.resolveSymbol(raw)
        if (!norm.contains(' ') && (norm.contains('.') || norm.startsWith("^") || norm.contains('=') || norm.contains('-'))) return norm
        val matches = c.searchSymbols(raw, 8)
        if (matches.isEmpty()) return norm.replace(' ', '-')
        if (matches.any { it.symbol == norm }) return norm
        return (matches.firstOrNull { it.symbol.endsWith(".NS") }
            ?: matches.firstOrNull { it.symbol.endsWith(".BO") }
            ?: matches.first()).symbol
    }

    fun fmt(v: Double?): String = if (v == null) "n/a" else String.format(Locale.US, "%,.2f", v)
    fun pct(v: Double?): String = if (v == null) "n/a" else String.format(Locale.US, "%+.2f%%", v)

    fun describeQuote(q: StockQuoteDetail): String {
        val sb = StringBuilder()
        sb.append(q.name).append(" (").append(q.symbol).append("): ").append(q.currency).append(' ').append(fmt(q.price))
            .append(", aaj ").append(pct(q.changePercent)).append(" (").append(fmt(q.change)).append(")")
        if (q.dayLow != null && q.dayHigh != null) sb.append(", day range ").append(fmt(q.dayLow)).append("-").append(fmt(q.dayHigh))
        if (q.fiftyTwoWeekLow != null && q.fiftyTwoWeekHigh != null) sb.append(", 52w ").append(fmt(q.fiftyTwoWeekLow)).append("-").append(fmt(q.fiftyTwoWeekHigh))
        if (q.volume > 0) sb.append(", volume ").append(q.volume)
        val ageMin = if (q.marketTime > 0) (System.currentTimeMillis() - q.marketTime) / 60000 else -1
        sb.append(if (ageMin in 0..20) " - market khula hai" else " - market band / last close")
        return sb.toString()
    }

    suspend fun indicators(context: Context, symbol: String): TechnicalIndicators.Snapshot? {
        val bars = client(context).getHistory(symbol, "1y", "1d")
        if (bars.size < 15) return null
        return TechnicalIndicators.snapshot(bars.map { it.close }, bars.map { it.high }, bars.map { it.low })
    }

    suspend fun livePrices(context: Context, symbols: Collection<String>): Map<String, Double?> = coroutineScope {
        val c = client(context)
        symbols.distinct().map { s -> async { s to c.getQuote(s)?.price?.takeIf { it > 0 } } }.awaitAll().toMap()
    }

    suspend fun portfolioText(context: Context): String {
        val holdings = FinanceStore.holdings(context)
        if (holdings.isEmpty()) return "Koi holding record nahi hai. Bolo - 'Reliance 10 share 1400 pe liye the', main note kar lungi."
        val s = PortfolioMath.summarize(holdings, livePrices(context, holdings.map { it.symbol }))
        val sb = StringBuilder("# Portfolio\n## Positions\n")
        for (p in s.positions) {
            sb.append("- ").append(p.holding.symbol).append(": ").append(KpiStore.fmt(p.holding.quantity)).append(" @ ").append(fmt(p.holding.avgPrice))
            if (p.livePrice != null) sb.append(" -> ").append(fmt(p.livePrice)).append(" | P&L ").append(PortfolioMath.money(p.pnl)).append(" (").append(pct(p.pnlPct)).append(")")
            else sb.append(" -> live price nahi mila")
            sb.append('\n')
        }
        sb.append("## Total\n- Invested ").append(PortfolioMath.money(s.totalInvested)).append(", value ").append(PortfolioMath.money(s.totalValue))
            .append(", P&L ").append(PortfolioMath.money(s.totalPnl)).append(" (").append(pct(s.totalPnlPct)).append(")\n")
        if (s.topWeightSymbol != null) sb.append("- Sabse badi position: ").append(s.topWeightSymbol).append(" (").append(String.format(Locale.US, "%.0f", s.topWeightPct)).append("% of book)\n")
        if (s.missingPrices.isNotEmpty()) sb.append("- Price nahi mila: ").append(s.missingPrices.joinToString()).append(" (total me shamil nahi)\n")
        return sb.toString().trimEnd()
    }

    suspend fun snapshotText(context: Context): String {
        val c = client(context)
        val items = listOf("NIFTY 50" to "^NSEI", "SENSEX" to "^BSESN", "Nasdaq" to "^IXIC", "Gold" to "GC=F", "USD/INR" to "INR=X")
        val sb = StringBuilder("Market snapshot:\n")
        var ok = 0
        coroutineScope {
            items.map { (label, sym) -> async { label to c.getQuote(sym) } }.awaitAll()
        }.forEach { (label, q) ->
            if (q != null && q.price > 0) { ok++; sb.append("- ").append(label).append(": ").append(fmt(q.price)).append(" (").append(pct(q.changePercent)).append(")\n") }
            else sb.append("- ").append(label).append(": data nahi aaya\n")
        }
        return if (ok == 0) "Market data nahi aaya — internet check karo, ya thodi der baad poocho." else sb.toString().trimEnd()
    }

    fun startAnalysis(context: Context, symbolRaw: String): BoardRecord? =
        BusinessJobs.start(context, "analysis", "Analysis: $symbolRaw", symbolRaw, { r ->
            "Market analysis ready: '${r.title}' (id ${r.id}). 3-4 line me batao; ye information hai, advice nahi."
        }) { rec ->
            val sym = resolve(context, symbolRaw)
            BusinessJobs.progress(context, rec, "Market data le rahi hoon…")
            val q = client(context).getQuoteDetail(sym)
            val snap = indicators(context, sym)
            val news = client(context).getNewsHeadlines(sym, 8)
            if (q == null && snap == null) return@start "'$symbolRaw' ka data nahi mila. search_stock se sahi symbol nikaal ke dobara try karo."
            BusinessJobs.progress(context, rec, "Report likh rahi hoon…")
            val prompt = buildString {
                append("SYMBOL: ").append(sym).append('\n')
                q?.let { append("QUOTE: ").append(describeQuote(it)).append('\n') }
                snap?.let { append("INDICATORS (computed on phone, exact): ").append(it.describe()).append('\n') }
                append("NEWS HEADLINES:\n").append(news.joinToString("\n") { "- ${it.title} (${it.pubDate})" }.ifBlank { "- none" }).append("\n\n")
                append("Write a board script: '# <name> - analysis', '## Chart read' (use the exact numbers), '## News', '## Bull case', ")
                append("'## Bear case', '## Levels to watch', '## Andaaza' (one balanced paragraph marked as an estimate). ")
                append("Never tell the user to buy or sell. Simple Hinglish, no emoji, no bold. Under 40 lines.")
            }
            val out = BusinessRuntime.llm(context, "You are a careful equity analyst who explains, never advises.", prompt, 0.3, 1800)
            if (out.isBlank()) "" else out.trimEnd() + "\n" + BusinessPrompts.NOT_ADVICE_NOTE
        }

    fun startWatchlistScan(context: Context): BoardRecord? =
        BusinessJobs.start(context, "watchlist_scan", "Watchlist scan", "watchlist", { r ->
            "Watchlist scan ready (id ${r.id}). Batao kis pe nazar rakhni hai - advice nahi."
        }) { rec ->
            val list = client(context).getWatchlist().toList()
            val lines = StringBuilder()
            list.forEachIndexed { i, s ->
                BusinessJobs.progress(context, rec, "Data ${i + 1}/${list.size}: $s")
                val q = client(context).getQuoteDetail(s)
                val snap = indicators(context, s)
                lines.append("- ").append(s).append(": ").append(q?.let { describeQuote(it) } ?: "quote n/a").append(" | ").append(snap?.describe() ?: "indicators n/a").append('\n')
            }
            val out = BusinessRuntime.llm(context, "You summarise a stock watchlist. Explain, never advise.",
                "WATCHLIST DATA:\n$lines\n\n'# Watchlist scan' first line.\n${BusinessPrompts.WATCHLIST_SCAN_FORMAT}", 0.3, 1600)
            if (out.isBlank()) "" else if (out.contains("advice nahi")) out else out.trimEnd() + "\n" + BusinessPrompts.NOT_ADVICE_NOTE
        }

    fun startPortfolioReview(context: Context): BoardRecord? =
        BusinessJobs.start(context, "portfolio_review", "Portfolio review", "portfolio", { r ->
            "Portfolio review ready (id ${r.id}). P&L aur concentration batao - kharidne/bechne ka suggestion mat do."
        }) { rec ->
            BusinessJobs.progress(context, rec, "Live prices le rahi hoon…")
            val numbers = portfolioText(context)
            val out = BusinessRuntime.llm(context, "You describe an investor's recorded positions. Never suggest buying or selling.",
                "$numbers\n\nWrite '# Portfolio review' then '## Kaun chala raha hai', '## Kaun kheench raha hai', '## Concentration risk', '## Dhyan dene layak'. " +
                    "Use only these numbers. Simple Hinglish, no emoji, no bold.", 0.3, 1200)
            if (out.isBlank()) numbers else out.trimEnd() + "\n" + BusinessPrompts.NOT_ADVICE_NOTE
        }

    /** Arms / disarms the 15-minute alert worker depending on whether any alert is armed. */
    fun syncAlertWorker(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (FinanceStore.alerts(context).any { it.isArmed }) {
            val req = PeriodicWorkRequestBuilder<PriceAlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(PriceAlertWorker.UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
        } else {
            wm.cancelUniqueWork(PriceAlertWorker.UNIQUE)
        }
    }

    /** One check pass (worker + "check now" button). Returns alerts that fired. */
    suspend fun checkAlerts(context: Context): List<PriceAlert> {
        val alerts = FinanceStore.alerts(context)
        val armed = alerts.filter { it.isArmed }
        if (armed.isEmpty()) return emptyList()
        val prices = livePrices(context, armed.map { it.symbol })
        val fired = PriceAlertLogic.evaluate(armed, prices)
        if (fired.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val firedIds = fired.map { it.id }.toSet()
        FinanceStore.saveAlerts(context, alerts.map { if (it.id in firedIds) it.copy(firedAt = now, firedPrice = prices[it.symbol] ?: 0.0) else it })
        for (a in fired) {
            val p = prices[a.symbol]
            val dirWord = if (a.direction == "above") "upar" else "neeche"
            val text = "${a.symbol} ${fmt(p)} pe hai - aapka alert ${fmt(a.price)} ke $dirWord ka tha."
            BusinessEvents.notify(context, 0x5400 + (a.id.hashCode() and 0xFF), "Price alert: ${a.symbol}", text, BusinessActivity.TAB_FINANCE)
            BusinessEvents.injectSystemEvent("Price alert hit ho gaya: $text User ko abhi batao (advice mat do).")
        }
        return fired
    }
}

class PriceAlertWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        const val UNIQUE = "maya_price_alerts"
    }

    override suspend fun doWork(): Result = try {
        FinanceEngine.checkAlerts(applicationContext)
        FinanceEngine.syncAlertWorker(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Log.w("PriceAlertWorker", "check failed: ${e.message}")
        Result.retry()
    }
}
