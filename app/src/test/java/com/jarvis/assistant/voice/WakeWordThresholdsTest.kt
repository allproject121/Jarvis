package com.jarvis.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordThresholdsTest {

    private val eps = 1e-6

    @Test
    fun defaultSliderPositionsKeepTodaysThresholds() {
        // Includes the Studio slider's own float math for progress 4 (0.50) and 7 (0.65, the reset default).
        for (s in listOf(0.50f, 0.55f, 0.60f, 0.65f, 0.30f + 4 * 0.05f, 0.30f + 7 * 0.05f)) {
            assertEquals("sensitivity $s", WakeWordThresholds.DEFAULT, WakeWordThresholds.forSensitivity(s))
        }
        assertEquals(0.70, WakeWordThresholds.DEFAULT.jarvisWord, eps)
        assertEquals(0.75, WakeWordThresholds.DEFAULT.standalone, eps)
        assertEquals(0.65, WakeWordThresholds.DEFAULT.phrase, eps)
    }

    @Test
    fun higherSensitivityLowersThresholds() {
        val t = WakeWordThresholds.forSensitivity(0.85f)
        assertEquals(0.70 - 0.2 * 0.6, t.jarvisWord, eps)
        assertEquals(0.75 - 0.2 * 0.6, t.standalone, eps)
        assertEquals(0.65 - 0.2 * 0.6, t.phrase, eps)
    }

    @Test
    fun lowerSensitivityRaisesThresholds() {
        val t = WakeWordThresholds.forSensitivity(0.30f)
        assertEquals(0.70 + 0.2 * 0.6, t.jarvisWord, eps)
        assertEquals(0.75 + 0.2 * 0.6, t.standalone, eps)
        assertEquals(0.65 + 0.2 * 0.6, t.phrase, eps)
    }

    @Test
    fun monotonicAcrossTheWholeRange() {
        var prev = WakeWordThresholds.forSensitivity(0.1f)
        var s = 0.15f
        while (s <= 1.0f) {
            val cur = WakeWordThresholds.forSensitivity(s)
            assertTrue("jarvis @ $s", cur.jarvisWord <= prev.jarvisWord + eps)
            assertTrue("standalone @ $s", cur.standalone <= prev.standalone + eps)
            assertTrue("phrase @ $s", cur.phrase <= prev.phrase + eps)
            prev = cur
            s += 0.05f
        }
    }

    @Test
    fun extremesAreClampedToTheSafeRange() {
        for (s in listOf(-5f, 0f, 0.1f, 1.0f, 2f, Float.NaN)) {
            val t = WakeWordThresholds.forSensitivity(s)
            for (v in listOf(t.jarvisWord, t.standalone, t.phrase)) {
                assertTrue("$s -> $v", v >= WakeWordThresholds.MIN_THRESHOLD - eps && v <= WakeWordThresholds.MAX_THRESHOLD + eps)
            }
        }
        // Maximum sensitivity: the phrase threshold hits the 0.45 floor.
        assertEquals(0.45, WakeWordThresholds.forSensitivity(1.0f).phrase, eps)
        // Minimum sensitivity: the standalone threshold hits the 0.90 ceiling.
        assertEquals(0.90, WakeWordThresholds.forSensitivity(0.1f).standalone, eps)
        // Out-of-range input behaves like the nearest end; NaN is neutral.
        assertEquals(WakeWordThresholds.forSensitivity(1.0f), WakeWordThresholds.forSensitivity(2f))
        assertEquals(WakeWordThresholds.DEFAULT, WakeWordThresholds.forSensitivity(Float.NaN))
    }
}
