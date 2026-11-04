package com.jarvis.assistant.maya.business

import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Technical indicators computed on the phone (pure Kotlin, unit-tested).
 * All inputs are chronological (oldest first).
 */
object TechnicalIndicators {

    fun sma(values: List<Double>, period: Int): Double? {
        if (period <= 0 || values.size < period) return null
        var sum = 0.0
        for (i in values.size - period until values.size) sum += values[i]
        return sum / period
    }

    fun smaSeries(values: List<Double>, period: Int): List<Double?> {
        if (period <= 0) return values.map { null }
        val out = ArrayList<Double?>(values.size)
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            out.add(if (i >= period - 1) sum / period else null)
        }
        return out
    }

    fun ema(values: List<Double>, period: Int): Double? = emaSeries(values, period).lastOrNull()

    fun emaSeries(values: List<Double>, period: Int): List<Double> {
        if (period <= 0 || values.isEmpty()) return emptyList()
        val k = 2.0 / (period + 1)
        val out = ArrayList<Double>(values.size)
        var prev = values[0]
        out.add(prev)
        for (i in 1 until values.size) {
            prev = values[i] * k + prev * (1 - k)
            out.add(prev)
        }
        return out
    }

    /** Wilder's RSI. Needs at least period+1 closes. */
    fun rsi(values: List<Double>, period: Int = 14): Double? {
        if (period <= 0 || values.size < period + 1) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = values[i] - values[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        for (i in period + 1 until values.size) {
            val d = values[i] - values[i - 1]
            val g = if (d > 0) d else 0.0
            val l = if (d < 0) -d else 0.0
            avgGain = (avgGain * (period - 1) + g) / period
            avgLoss = (avgLoss * (period - 1) + l) / period
        }
        if (avgLoss == 0.0) return 100.0
        if (avgGain == 0.0) return 0.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    /** MACD(12,26,9): returns (macd, signal, histogram). */
    fun macd(values: List<Double>, fast: Int = 12, slow: Int = 26, signal: Int = 9): Triple<Double, Double, Double>? {
        if (values.size < slow + signal) return null
        val fastE = emaSeries(values, fast)
        val slowE = emaSeries(values, slow)
        val macdLine = ArrayList<Double>(values.size)
        for (i in values.indices) macdLine.add(fastE[i] - slowE[i])
        val sig = emaSeries(macdLine.subList(slow - 1, macdLine.size), signal)
        val m = macdLine.last()
        val s = sig.last()
        return Triple(m, s, m - s)
    }

    /** Bollinger bands: (upper, middle, lower). */
    fun bollinger(values: List<Double>, period: Int = 20, mult: Double = 2.0): Triple<Double, Double, Double>? {
        val mid = sma(values, period) ?: return null
        var sq = 0.0
        for (i in values.size - period until values.size) {
            val d = values[i] - mid
            sq += d * d
        }
        val sd = sqrt(sq / period)
        return Triple(mid + mult * sd, mid, mid - mult * sd)
    }

    fun pctChange(values: List<Double>, barsBack: Int): Double? {
        if (values.size <= barsBack || barsBack <= 0) return null
        val then = values[values.size - 1 - barsBack]
        if (then == 0.0) return null
        return (values.last() - then) / then * 100.0
    }

    fun describeRsi(rsi: Double?): String = when {
        rsi == null -> "n/a"
        rsi >= 70 -> "overbought"
        rsi <= 30 -> "oversold"
        rsi >= 60 -> "strong"
        rsi <= 40 -> "weak"
        else -> "neutral"
    }

    data class Snapshot(
        val last: Double,
        val sma20: Double?,
        val sma50: Double?,
        val sma200: Double?,
        val rsi14: Double?,
        val macd: Double?,
        val macdSignal: Double?,
        val bbUpper: Double?,
        val bbLower: Double?,
        val high52: Double?,
        val low52: Double?,
        val support: Double?,
        val resistance: Double?,
        val change1w: Double?,
        val change1m: Double?,
        val change3m: Double?,
        val trend: String
    ) {
        /** 0 = at the 52-week low, 100 = at the high. */
        val rangePosition: Double?
            get() {
                val h = high52 ?: return null
                val l = low52 ?: return null
                if (h <= l) return null
                return (last - l) / (h - l) * 100.0
            }

        fun toJson(): JSONObject = JSONObject().apply {
            put("last", last)
            put("sma20", sma20 ?: JSONObject.NULL)
            put("sma50", sma50 ?: JSONObject.NULL)
            put("sma200", sma200 ?: JSONObject.NULL)
            put("rsi14", rsi14 ?: JSONObject.NULL)
            put("macd", macd ?: JSONObject.NULL)
            put("macdSignal", macdSignal ?: JSONObject.NULL)
            put("bbUpper", bbUpper ?: JSONObject.NULL)
            put("bbLower", bbLower ?: JSONObject.NULL)
            put("high52", high52 ?: JSONObject.NULL)
            put("low52", low52 ?: JSONObject.NULL)
            put("support", support ?: JSONObject.NULL)
            put("resistance", resistance ?: JSONObject.NULL)
            put("change1w", change1w ?: JSONObject.NULL)
            put("change1m", change1m ?: JSONObject.NULL)
            put("change3m", change3m ?: JSONObject.NULL)
            put("rangePosition", rangePosition ?: JSONObject.NULL)
            put("trend", trend)
        }

        fun describe(): String {
            val sb = StringBuilder()
            sb.append("Last ").append(fmt(last)).append(" | trend ").append(trend)
            sb.append(" | RSI14 ").append(rsi14?.let { fmt(it) + " (" + describeRsi(it) + ")" } ?: "n/a")
            sb.append(" | SMA20 ").append(fmt(sma20)).append(" SMA50 ").append(fmt(sma50)).append(" SMA200 ").append(fmt(sma200))
            sb.append(" | MACD ").append(fmt(macd)).append('/').append(fmt(macdSignal))
            sb.append(" | BB ").append(fmt(bbLower)).append('-').append(fmt(bbUpper))
            sb.append(" | S/R ").append(fmt(support)).append('/').append(fmt(resistance))
            sb.append(" | 52w ").append(fmt(low52)).append('-').append(fmt(high52))
            rangePosition?.let { sb.append(" (").append(fmt(it)).append("% of range)") }
            sb.append(" | 1w ").append(fmtPct(change1w)).append(" 1m ").append(fmtPct(change1m)).append(" 3m ").append(fmtPct(change3m))
            return sb.toString()
        }

        private fun fmt(v: Double?): String = if (v == null) "n/a" else String.format(java.util.Locale.US, "%.2f", v)
        private fun fmtPct(v: Double?): String = if (v == null) "n/a" else String.format(java.util.Locale.US, "%+.1f%%", v)
    }

    /** [closes], [highs], [lows] are daily bars, oldest first. */
    fun snapshot(closes: List<Double>, highs: List<Double> = closes, lows: List<Double> = closes): Snapshot? {
        if (closes.isEmpty()) return null
        val last = closes.last()
        val s20 = sma(closes, 20)
        val s50 = sma(closes, 50)
        val s200 = sma(closes, 200)
        val m = macd(closes)
        val bb = bollinger(closes)
        val lookback = minOf(252, closes.size)
        val hi52 = highs.takeLast(lookback).maxOrNull()
        val lo52 = lows.takeLast(lookback).minOrNull()
        val srWindow = minOf(20, closes.size)
        val support = lows.takeLast(srWindow).minOrNull()
        val resistance = highs.takeLast(srWindow).maxOrNull()
        val trend = when {
            s20 != null && s50 != null && last > s20 && s20 > s50 -> "uptrend"
            s20 != null && s50 != null && last < s20 && s20 < s50 -> "downtrend"
            s20 != null && last > s20 -> "mild up"
            s20 != null && last < s20 -> "mild down"
            else -> "sideways"
        }
        return Snapshot(
            last = last,
            sma20 = s20, sma50 = s50, sma200 = s200,
            rsi14 = rsi(closes, 14),
            macd = m?.first, macdSignal = m?.second,
            bbUpper = bb?.first, bbLower = bb?.third,
            high52 = hi52, low52 = lo52,
            support = support, resistance = resistance,
            change1w = pctChange(closes, 5),
            change1m = pctChange(closes, 21),
            change3m = pctChange(closes, 63),
            trend = trend
        )
    }
}

/** A price alert the owner armed; fires once and is kept with [firedAt] for history. */
data class PriceAlert(
    val id: String = UUID.randomUUID().toString().take(8),
    val symbol: String,
    val direction: String, // above | below
    val price: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val firedAt: Long = 0L,
    val firedPrice: Double = 0.0
) {
    val isArmed: Boolean get() = firedAt == 0L

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("symbol", symbol); put("direction", direction); put("price", price)
        put("createdAt", createdAt); put("firedAt", firedAt); put("firedPrice", firedPrice)
    }

    companion object {
        fun fromJson(o: JSONObject) = PriceAlert(
            id = o.optString("id"),
            symbol = o.optString("symbol"),
            direction = o.optString("direction", "above"),
            price = o.optDouble("price", 0.0),
            createdAt = o.optLong("createdAt"),
            firedAt = o.optLong("firedAt", 0L),
            firedPrice = o.optDouble("firedPrice", 0.0)
        )
    }
}

object PriceAlertLogic {
    fun normalizeDirection(raw: String): String {
        val d = raw.trim().lowercase()
        return when {
            d.startsWith("ab") || d == ">" || d == ">=" || d.contains("upar") || d.contains("cross") || d.contains("up") -> "above"
            d.startsWith("be") || d == "<" || d == "<=" || d.contains("neeche") || d.contains("niche") || d.contains("down") || d.contains("drop") -> "below"
            else -> "above"
        }
    }

    fun shouldFire(alert: PriceAlert, currentPrice: Double?): Boolean {
        if (currentPrice == null || currentPrice.isNaN() || currentPrice <= 0.0) return false
        if (!alert.isArmed) return false
        return when (alert.direction) {
            "above" -> currentPrice >= alert.price
            "below" -> currentPrice <= alert.price
            else -> false
        }
    }

    /** Returns the alerts that should fire given [prices] (symbol -> live price). */
    fun evaluate(alerts: List<PriceAlert>, prices: Map<String, Double?>): List<PriceAlert> =
        alerts.filter { shouldFire(it, prices[it.symbol]) }
}

/** One position the owner recorded. Quantity and average price only; no broker link. */
data class Holding(
    val symbol: String,
    val quantity: Double,
    val avgPrice: Double,
    val addedAt: Long = System.currentTimeMillis(),
    val note: String = ""
) {
    val invested: Double get() = quantity * avgPrice

    fun toJson(): JSONObject = JSONObject().apply {
        put("symbol", symbol); put("quantity", quantity); put("avgPrice", avgPrice); put("addedAt", addedAt); put("note", note)
    }

    companion object {
        fun fromJson(o: JSONObject) = Holding(
            symbol = o.optString("symbol"),
            quantity = o.optDouble("quantity", 0.0),
            avgPrice = o.optDouble("avgPrice", 0.0),
            addedAt = o.optLong("addedAt"),
            note = o.optString("note", "")
        )
    }
}

data class HoldingPnl(
    val holding: Holding,
    val livePrice: Double?,
    val value: Double?,
    val pnl: Double?,
    val pnlPct: Double?
)

data class PortfolioSummary(
    val positions: List<HoldingPnl>,
    val totalInvested: Double,
    val totalValue: Double,
    val totalPnl: Double,
    val totalPnlPct: Double,
    val missingPrices: List<String>,
    /** Largest position's share of the (priced) book, 0..100. */
    val topWeightSymbol: String?,
    val topWeightPct: Double
)

object PortfolioMath {

    fun pnl(h: Holding, live: Double?): HoldingPnl {
        if (live == null || live.isNaN() || live <= 0.0) return HoldingPnl(h, null, null, null, null)
        val value = h.quantity * live
        val pnl = value - h.invested
        val pct = if (h.invested != 0.0) pnl / h.invested * 100.0 else 0.0
        return HoldingPnl(h, live, value, pnl, pct)
    }

    /** Merges a new lot into an existing position (weighted average price). */
    fun merge(existing: Holding, qty: Double, price: Double): Holding {
        val totalQty = existing.quantity + qty
        if (totalQty <= 0.0) return existing.copy(quantity = 0.0)
        val avg = (existing.invested + qty * price) / totalQty
        return existing.copy(quantity = totalQty, avgPrice = avg)
    }

    fun summarize(holdings: List<Holding>, prices: Map<String, Double?>): PortfolioSummary {
        val positions = holdings.map { pnl(it, prices[it.symbol]) }
        var invested = 0.0
        var value = 0.0
        val missing = ArrayList<String>()
        for (p in positions) {
            if (p.value == null) { missing.add(p.holding.symbol); continue }
            invested += p.holding.invested
            value += p.value
        }
        val totalPnl = value - invested
        val pct = if (invested != 0.0) totalPnl / invested * 100.0 else 0.0
        val top = positions.filter { it.value != null }.maxByOrNull { it.value!! }
        val topPct = if (top?.value != null && value > 0.0) top.value / value * 100.0 else 0.0
        return PortfolioSummary(
            positions = positions,
            totalInvested = invested,
            totalValue = value,
            totalPnl = totalPnl,
            totalPnlPct = pct,
            missingPrices = missing,
            topWeightSymbol = top?.holding?.symbol,
            topWeightPct = topPct
        )
    }

    fun money(v: Double?): String {
        if (v == null) return "n/a"
        val a = abs(v)
        val s = if (a >= 1_00_00_000) String.format(java.util.Locale.US, "%.2f Cr", a / 1_00_00_000)
        else if (a >= 1_00_000) String.format(java.util.Locale.US, "%.2f L", a / 1_00_000)
        else String.format(java.util.Locale.US, "%,.2f", a)
        return if (v < 0) "-$s" else s
    }
}
