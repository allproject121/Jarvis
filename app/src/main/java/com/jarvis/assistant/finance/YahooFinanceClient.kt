package com.jarvis.assistant.finance

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class StockQuote(
    val symbol: String,
    val name: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val currency: String,
    val marketState: String
)

class YahooFinanceClient(private val context: Context) {
    companion object {
        private const val TAG = "YahooFinanceClient"
        private const val PREFS_NAME = "jarvis_finance_watchlist"
        private const val KEY_WATCHLIST = "watchlist"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun getQuote(symbol: String): StockQuote? = withContext(Dispatchers.IO) {
        val cleanSymbol = normalizeSymbol(symbol)
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$cleanSymbol?interval=1d&range=1d"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val chart = json.getJSONObject("chart")
                val result = chart.getJSONArray("result").getJSONObject(0)
                val meta = result.getJSONObject("meta")

                val regularMarketPrice = meta.optDouble("regularMarketPrice", 0.0)
                val previousClose = meta.optDouble("previousClose", regularMarketPrice)
                val change = regularMarketPrice - previousClose
                val changePercent = if (previousClose > 0) (change / previousClose) * 100.0 else 0.0
                val currency = meta.optString("currency", "USD")
                val shortName = meta.optString("shortName", cleanSymbol)
                val marketState = meta.optString("tradingPeriod", "REGULAR")

                return@withContext StockQuote(
                    symbol = cleanSymbol,
                    name = shortName,
                    price = regularMarketPrice,
                    change = change,
                    changePercent = changePercent,
                    currency = currency,
                    marketState = marketState
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching quote for $symbol: ${e.message}")
            null
        }
    }

    suspend fun getTrending(): List<String> = withContext(Dispatchers.IO) {
        val url = "https://query1.finance.yahoo.com/v1/finance/trending/IN"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val finance = json.getJSONObject("finance")
                val result = finance.getJSONArray("result").getJSONObject(0)
                val quotes = result.getJSONArray("quotes")
                val symbols = mutableListOf<String>()
                for (i in 0 until quotes.length()) {
                    symbols.add(quotes.getJSONObject(i).getString("symbol"))
                }
                return@withContext symbols
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching trending stocks: ${e.message}")
            emptyList()
        }
    }

    fun getWatchlist(): Set<String> {
        return prefs.getStringSet(KEY_WATCHLIST, setOf("RELIANCE.NS", "TCS.NS", "AAPL", "BTC-USD")) ?: emptySet()
    }

    fun addToWatchlist(symbol: String): Boolean {
        val current = getWatchlist().toMutableSet()
        val normalized = normalizeSymbol(symbol)
        current.add(normalized)
        prefs.edit().putStringSet(KEY_WATCHLIST, current).apply()
        return true
    }

    fun removeFromWatchlist(symbol: String): Boolean {
        val current = getWatchlist().toMutableSet()
        val normalized = normalizeSymbol(symbol)
        current.remove(normalized)
        prefs.edit().putStringSet(KEY_WATCHLIST, current).apply()
        return true
    }

    private fun normalizeSymbol(raw: String): String {
        val s = raw.trim().uppercase()
        return when (s) {
            "RELIANCE" -> "RELIANCE.NS"
            "TCS" -> "TCS.NS"
            "INFOSYS", "INFY" -> "INFY.NS"
            "HDFC", "HDFCBANK" -> "HDFCBANK.NS"
            "TATA MOTORS", "TATAMOTORS" -> "TATAMOTORS.NS"
            "NIFTY", "NIFTY50" -> "^NSEI"
            "SENSEX" -> "^BSESN"
            "BITCOIN", "BTC" -> "BTC-USD"
            "ETHEREUM", "ETH" -> "ETH-USD"
            else -> s
        }
    }

    // ------------------------------------------------------------------------------------------
    // Maya business/finance pack additions (additive; nothing above changed).
    // ------------------------------------------------------------------------------------------

    /** Public alias for the symbol normaliser used by the finance tool pack. */
    fun resolveSymbol(raw: String): String = normalizeSymbol(raw)

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET failed ${url.substringBefore('?')}: ${e.message}")
            null
        }
    }

    /** Daily (or intraday) bars from the v8 chart endpoint. range: 1mo|3mo|6mo|1y|2y|5y, interval: 1d|1wk|1h. */
    suspend fun getHistory(symbol: String, range: String = "6mo", interval: String = "1d"): List<PricePoint> = withContext(Dispatchers.IO) {
        val sym = normalizeSymbol(symbol)
        val body = get("https://query1.finance.yahoo.com/v8/finance/chart/$sym?range=$range&interval=$interval") ?: return@withContext emptyList()
        try {
            val result = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val ts = result.optJSONArray("timestamp") ?: return@withContext emptyList()
            val quote = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            val closes = quote.optJSONArray("close")
            val opens = quote.optJSONArray("open")
            val highs = quote.optJSONArray("high")
            val lows = quote.optJSONArray("low")
            val vols = quote.optJSONArray("volume")
            val out = ArrayList<PricePoint>(ts.length())
            for (i in 0 until ts.length()) {
                val c = closes?.optDouble(i, Double.NaN) ?: Double.NaN
                if (c.isNaN()) continue
                val o = opens?.optDouble(i, Double.NaN) ?: Double.NaN
                val h = highs?.optDouble(i, Double.NaN) ?: Double.NaN
                val l = lows?.optDouble(i, Double.NaN) ?: Double.NaN
                out.add(
                    PricePoint(
                        timestamp = ts.optLong(i) * 1000L,
                        open = if (o.isNaN()) c else o,
                        high = if (h.isNaN()) c else h,
                        low = if (l.isNaN()) c else l,
                        close = c,
                        volume = vols?.optLong(i, 0L) ?: 0L
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "history parse failed for $sym: ${e.message}")
            emptyList()
        }
    }

    /** Symbol search (company name -> tickers). Indian NSE listings are preferred by the caller. */
    suspend fun searchSymbols(query: String, limit: Int = 8): List<SymbolMatch> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val body = get("https://query2.finance.yahoo.com/v1/finance/search?q=$q&quotesCount=$limit&newsCount=0") ?: return@withContext emptyList()
        try {
            val quotes = JSONObject(body).optJSONArray("quotes") ?: return@withContext emptyList()
            val out = ArrayList<SymbolMatch>()
            for (i in 0 until quotes.length()) {
                val o = quotes.optJSONObject(i) ?: continue
                val sym = o.optString("symbol", "")
                if (sym.isBlank()) continue
                out.add(
                    SymbolMatch(
                        symbol = sym,
                        name = o.optString("shortname", o.optString("longname", sym)),
                        exchange = o.optString("exchDisp", o.optString("exchange", "")),
                        type = o.optString("quoteType", "")
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "search parse failed: ${e.message}")
            emptyList()
        }
    }

    /** Richer quote (52-week range, volume, day range) from the chart meta block. */
    suspend fun getQuoteDetail(symbol: String): StockQuoteDetail? = withContext(Dispatchers.IO) {
        val sym = normalizeSymbol(symbol)
        val body = get("https://query1.finance.yahoo.com/v8/finance/chart/$sym?interval=1d&range=5d") ?: return@withContext null
        try {
            val result = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val meta = result.getJSONObject("meta")
            val price = meta.optDouble("regularMarketPrice", Double.NaN)
            if (price.isNaN()) return@withContext null
            val prev = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", price))
            val change = price - prev
            fun optD(key: String): Double? { val v = meta.optDouble(key, Double.NaN); return if (v.isNaN()) null else v }
            StockQuoteDetail(
                symbol = sym,
                name = meta.optString("shortName", meta.optString("longName", sym)),
                price = price,
                previousClose = prev,
                change = change,
                changePercent = if (prev > 0) change / prev * 100.0 else 0.0,
                currency = meta.optString("currency", "USD"),
                exchange = meta.optString("exchangeName", meta.optString("fullExchangeName", "")),
                dayHigh = optD("regularMarketDayHigh"),
                dayLow = optD("regularMarketDayLow"),
                fiftyTwoWeekHigh = optD("fiftyTwoWeekHigh"),
                fiftyTwoWeekLow = optD("fiftyTwoWeekLow"),
                volume = meta.optLong("regularMarketVolume", 0L),
                marketTime = meta.optLong("regularMarketTime", 0L) * 1000L
            )
        } catch (e: Exception) {
            Log.e(TAG, "quote detail failed for $sym: ${e.message}")
            null
        }
    }

    /** Forex: 1 [from] in [to]. USD->INR uses INR=X; anything else uses FROMTO=X. */
    suspend fun getForexRate(from: String, to: String): Double? {
        val f = from.trim().uppercase().ifBlank { "USD" }
        val t = to.trim().uppercase().ifBlank { "INR" }
        if (f == t) return 1.0
        val pair = if (f == "USD") "$t=X" else "$f$t=X"
        val q = getQuote(pair) ?: return null
        return if (q.price > 0) q.price else null
    }

    /** Headlines from Yahoo Finance RSS (symbol-specific or the general index). */
    suspend fun getNewsHeadlines(symbol: String? = null, limit: Int = 8): List<NewsHeadline> = withContext(Dispatchers.IO) {
        val url = if (symbol.isNullOrBlank()) "https://finance.yahoo.com/news/rssindex"
        else "https://finance.yahoo.com/rss/headline?s=" + java.net.URLEncoder.encode(normalizeSymbol(symbol), "UTF-8")
        val body = get(url) ?: return@withContext emptyList()
        parseRss(body, limit)
    }

    private fun parseRss(xml: String, limit: Int): List<NewsHeadline> {
        val out = ArrayList<NewsHeadline>()
        val itemRegex = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
        fun tag(block: String, name: String): String {
            val m = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block) ?: return ""
            return m.groupValues[1].replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").trim()
        }
        for (m in itemRegex.findAll(xml)) {
            val block = m.groupValues[1]
            val title = tag(block, "title")
            if (title.isBlank()) continue
            out.add(NewsHeadline(title, tag(block, "link"), tag(block, "pubDate"), tag(block, "source")))
            if (out.size >= limit) break
        }
        return out
    }
}

/** One OHLCV bar (epoch millis). */
data class PricePoint(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

data class SymbolMatch(val symbol: String, val name: String, val exchange: String, val type: String)

data class StockQuoteDetail(
    val symbol: String,
    val name: String,
    val price: Double,
    val previousClose: Double,
    val change: Double,
    val changePercent: Double,
    val currency: String,
    val exchange: String,
    val dayHigh: Double?,
    val dayLow: Double?,
    val fiftyTwoWeekHigh: Double?,
    val fiftyTwoWeekLow: Double?,
    val volume: Long,
    val marketTime: Long
)

data class NewsHeadline(val title: String, val link: String, val pubDate: String, val source: String)
