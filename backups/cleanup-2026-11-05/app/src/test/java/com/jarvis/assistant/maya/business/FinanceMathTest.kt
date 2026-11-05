package com.jarvis.assistant.maya.business

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceMathTest {

    private val eps = 1e-6

    // ---------------------------------------------------------------- SMA / RSI

    @Test
    fun smaOfLastWindow() {
        val v = (1..10).map { it.toDouble() }
        assertEquals(8.0, TechnicalIndicators.sma(v, 5)!!, eps) // 6..10
        assertNull(TechnicalIndicators.sma(v, 20))
        val series = TechnicalIndicators.smaSeries(v, 3)
        assertNull(series[1])
        assertEquals(2.0, series[2]!!, eps)
        assertEquals(9.0, series[9]!!, eps)
    }

    @Test
    fun rsiExtremes() {
        val up = (1..30).map { it.toDouble() }
        assertEquals(100.0, TechnicalIndicators.rsi(up)!!, eps)
        val down = (30 downTo 1).map { it.toDouble() }
        assertEquals(0.0, TechnicalIndicators.rsi(down)!!, eps)
        assertNull(TechnicalIndicators.rsi(listOf(1.0, 2.0)))
    }

    @Test
    fun rsiFirstWindowMatchesSimpleRatio() {
        // 14 changes: 7 x +2, 7 x -1 -> avgGain 1.0, avgLoss 0.5 -> RS 2 -> RSI 66.666...
        val closes = ArrayList<Double>()
        var p = 100.0
        closes.add(p)
        repeat(7) { p += 2; closes.add(p); p -= 1; closes.add(p) }
        assertEquals(15, closes.size)
        assertEquals(66.6666667, TechnicalIndicators.rsi(closes, 14)!!, 1e-5)
    }

    @Test
    fun rsiWilderSmoothing() {
        val closes = ArrayList<Double>()
        var p = 100.0
        closes.add(p)
        repeat(7) { p += 2; closes.add(p); p -= 1; closes.add(p) }
        closes.add(p + 3) // one more gain of 3
        // avgGain = (1*13 + 3)/14 = 16/14, avgLoss = (0.5*13)/14 = 6.5/14 -> RS = 16/6.5
        val rs = 16.0 / 6.5
        assertEquals(100 - 100 / (1 + rs), TechnicalIndicators.rsi(closes, 14)!!, 1e-9)
    }

    @Test
    fun snapshotTrendAndRange() {
        val closes = (1..60).map { 100.0 + it }
        val snap = TechnicalIndicators.snapshot(closes)!!
        assertEquals("uptrend", snap.trend)
        assertEquals(160.0, snap.last, eps)
        assertEquals(100.0, snap.rangePosition!!, eps)
        assertEquals(TechnicalIndicators.describeRsi(snap.rsi14), "overbought")
    }

    // ---------------------------------------------------------------- price alerts

    @Test
    fun alertDirections() {
        val above = PriceAlert(symbol = "TCS.NS", direction = "above", price = 4000.0)
        val below = PriceAlert(symbol = "TCS.NS", direction = "below", price = 3500.0)
        assertTrue(PriceAlertLogic.shouldFire(above, 4000.0))
        assertTrue(PriceAlertLogic.shouldFire(above, 4100.0))
        assertFalse(PriceAlertLogic.shouldFire(above, 3999.99))
        assertTrue(PriceAlertLogic.shouldFire(below, 3400.0))
        assertFalse(PriceAlertLogic.shouldFire(below, 3600.0))
    }

    @Test
    fun alertIgnoresMissingPriceAndFiredAlerts() {
        val a = PriceAlert(symbol = "X", direction = "above", price = 10.0)
        assertFalse(PriceAlertLogic.shouldFire(a, null))
        assertFalse(PriceAlertLogic.shouldFire(a, Double.NaN))
        assertFalse(PriceAlertLogic.shouldFire(a, 0.0))
        assertFalse(PriceAlertLogic.shouldFire(a.copy(firedAt = 1L), 50.0))
    }

    @Test
    fun evaluateUsesPerSymbolPrice() {
        val a = PriceAlert(id = "1", symbol = "A", direction = "above", price = 10.0)
        val b = PriceAlert(id = "2", symbol = "B", direction = "below", price = 5.0)
        val fired = PriceAlertLogic.evaluate(listOf(a, b), mapOf("A" to 11.0, "B" to 6.0))
        assertEquals(listOf("1"), fired.map { it.id })
    }

    @Test
    fun directionNormalisation() {
        assertEquals("above", PriceAlertLogic.normalizeDirection("Above"))
        assertEquals("below", PriceAlertLogic.normalizeDirection("neeche"))
        assertEquals("below", PriceAlertLogic.normalizeDirection("<"))
        assertEquals("above", PriceAlertLogic.normalizeDirection("upar"))
    }

    // ---------------------------------------------------------------- portfolio P&L

    @Test
    fun singlePositionPnl() {
        val h = Holding("RELIANCE.NS", 10.0, 1400.0)
        val p = PortfolioMath.pnl(h, 1500.0)
        assertEquals(15000.0, p.value!!, eps)
        assertEquals(1000.0, p.pnl!!, eps)
        assertEquals(7.142857, p.pnlPct!!, 1e-5)
        assertNull(PortfolioMath.pnl(h, null).pnl)
    }

    @Test
    fun portfolioTotalsSkipMissingPrices() {
        val holdings = listOf(Holding("A", 10.0, 100.0), Holding("B", 5.0, 200.0), Holding("C", 1.0, 50.0))
        val s = PortfolioMath.summarize(holdings, mapOf("A" to 120.0, "B" to 180.0, "C" to null))
        assertEquals(2000.0, s.totalInvested, eps)
        assertEquals(2100.0, s.totalValue, eps)
        assertEquals(100.0, s.totalPnl, eps)
        assertEquals(5.0, s.totalPnlPct, eps)
        assertEquals(listOf("A"), listOf(s.topWeightSymbol))
        assertEquals(1200.0 / 2100.0 * 100, s.topWeightPct, 1e-9)
        assertEquals(listOf("C"), s.missingPrices)
    }

    @Test
    fun mergeUsesWeightedAverage() {
        val merged = PortfolioMath.merge(Holding("A", 10.0, 100.0), 10.0, 200.0)
        assertEquals(20.0, merged.quantity, eps)
        assertEquals(150.0, merged.avgPrice, eps)
    }
}
