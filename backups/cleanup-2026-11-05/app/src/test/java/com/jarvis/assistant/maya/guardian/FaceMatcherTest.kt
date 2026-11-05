package com.jarvis.assistant.maya.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The basic LBP + block-mean matcher on synthetic 96x96 "faces" (smooth waves + blobs, unique
 * per seed). Same seed with sensor noise / a 1 px shift must stay ≥ threshold; different seeds
 * must stay below it.
 */
class FaceMatcherTest {

    /** Deterministic 64-bit LCG so results don't depend on java.util.Random. */
    private class Lcg(seed: Long) {
        private var s = seed
        fun next(): Double {
            s = s * 6364136223846793005L + 1442695040888963407L
            return (s ushr 11).toDouble() / (1L shl 53).toDouble()
        }
        fun u(a: Double, b: Double) = a + (b - a) * next()
        fun gauss(): Double { var t = 0.0; repeat(12) { t += next() }; return t - 6.0 }
    }

    private fun synth(seed: Long, noise: Double = 0.0, shift: Int = 0): IntArray {
        val n = FaceMatcher.SIZE
        val r = Lcg(seed)
        val rn = Lcg(seed * 31 + 7)
        val waves = List(4) { doubleArrayOf(r.u(0.05, 0.4), r.u(0.05, 0.4), r.u(0.0, 6.28), r.u(15.0, 40.0)) }
        val blobs = List(5) {
            val cx = r.u(10.0, 86.0); val cy = r.u(10.0, 86.0); val rr = r.u(5.0, 15.0)
            val am = if (r.next() < 0.5) -70.0 else 70.0
            doubleArrayOf(cx, cy, rr, am)
        }
        val px = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val xs = (x - shift).toDouble()
            var v = 128.0
            for (w in waves) v += w[3] * sin(w[0] * xs + w[1] * y + w[2])
            for (b in blobs) if (hypot(xs - b[0], y - b[1]) < b[2]) v += b[3]
            if (noise > 0) v += noise * rn.gauss()
            px[y * n + x] = v.coerceIn(0.0, 255.0).toInt()
        }
        return px
    }

    @Test
    fun vectorHasExpectedSize() {
        assertEquals(59, FaceMatcher.LBP_BINS)
        assertEquals(64 + 16 * 59, FaceMatcher.VECTOR_SIZE)
        assertEquals(FaceMatcher.VECTOR_SIZE, FaceMatcher.extract(synth(1)).size)
    }

    @Test
    fun identicalImageScoresOne() {
        val v = FaceMatcher.extract(synth(11))
        assertEquals(1f, FaceMatcher.similarity(v, v), 1e-4f)
    }

    @Test
    fun sameFaceWithNoiseAndShiftIsAboveThreshold() {
        for (seed in longArrayOf(11, 22, 33, 44)) {
            val enrolled = FaceMatcher.extract(synth(seed))
            val probe = FaceMatcher.extract(synth(seed, noise = 6.0, shift = 1))
            val s = FaceMatcher.similarity(enrolled, probe)
            assertTrue("seed $seed same-face score $s", s >= FaceMatcher.DEFAULT_THRESHOLD)
        }
    }

    @Test
    fun differentFacesAreBelowThreshold() {
        val seeds = longArrayOf(11, 22, 33, 44)
        val vecs = seeds.map { FaceMatcher.extract(synth(it)) }
        for (i in vecs.indices) for (j in vecs.indices) if (i < j) {
            val s = FaceMatcher.similarity(vecs[i], vecs[j])
            assertTrue("${seeds[i]} vs ${seeds[j]} score $s", s < FaceMatcher.DEFAULT_THRESHOLD)
        }
    }

    @Test
    fun bestMatchPicksEnrolledPersonOrNobody() {
        val people = mapOf(
            "Boss" to listOf(FaceMatcher.extract(synth(11)), FaceMatcher.extract(synth(11, noise = 4.0))),
            "Riya" to listOf(FaceMatcher.extract(synth(22)))
        )
        val m = FaceMatcher.bestMatch(FaceMatcher.extract(synth(11, noise = 6.0, shift = 1)), people)
        assertNotNull(m)
        assertEquals("Boss", m!!.name)
        assertNull(FaceMatcher.bestMatch(FaceMatcher.extract(synth(44)), people))
    }

    @Test
    fun histogramEqualisationStretchesRange() {
        val dark = IntArray(100) { 40 + it % 20 }
        val eq = FaceMatcher.histogramEqualize(dark)
        assertEquals(0, eq.minOrNull())
        assertEquals(255, eq.maxOrNull())
    }

    @Test
    fun mismatchedVectorsScoreZero() {
        assertEquals(0f, FaceMatcher.similarity(FloatArray(3), FloatArray(3)), 0f)
    }
}
