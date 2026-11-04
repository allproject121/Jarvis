package com.jarvis.assistant.maya.guardian

import kotlin.math.sqrt

/**
 * Basic on-device face matcher — NOT a neural face model.
 *
 * Maya downloads YuNet/SFace TFLite models from its server; those are not available here, so
 * Jarvis uses ML Kit only to *find* and *align* a face and then describes the aligned
 * 96x96 grayscale crop with a hand-made feature vector:
 *
 *  1. histogram equalisation (illumination normalisation)
 *  2. 3x3 box blur (sensor-noise suppression — LBP is very noise sensitive)
 *  3. (a) 8x8 block mean intensities                                        → 64 values
 *     (b) uniform 8-neighbour LBP (threshold [LBP_THRESHOLD]) histograms in a 4x4 grid → 16 x 59 values
 *
 * Similarity = 0.4 * corr(block part) + 0.6 * corr(LBP part), where corr is the cosine of the
 * mean-centred vectors (Pearson correlation). Centring matters: raw LBP histograms are all
 * positive, so a plain cosine is ~0.9 even for different people. A person is a set of enrolled
 * vectors; the match score is the max over all of them and it has to reach the threshold
 * (default [DEFAULT_THRESHOLD], adjustable in Faces → "Face Lock & more").
 *
 * Expect: good at telling the owner from strangers under similar lighting, poor against twins,
 * masks or a photo held in front of the camera. Pure Kotlin so it can be unit tested.
 */
object FaceMatcher {

    const val SIZE = 96
    const val DEFAULT_THRESHOLD = 0.82f
    const val MIN_THRESHOLD = 0.60f
    const val MAX_THRESHOLD = 0.95f

    const val BLOCK_GRID = 8
    const val LBP_GRID = 4
    /** Neighbour must exceed the centre by this much to set a bit (robust, "LTP-like" LBP). */
    const val LBP_THRESHOLD = 3

    const val BLOCK_DIMS = BLOCK_GRID * BLOCK_GRID

    /** Uniform-pattern lookup: 58 uniform codes + 1 bin for all non-uniform codes. */
    private val uniformTable = IntArray(256)
    val LBP_BINS: Int

    init {
        var idx = 0
        val nonUniform = BooleanArray(256)
        for (p in 0 until 256) {
            var transitions = 0
            for (i in 0 until 8) {
                val a = (p shr i) and 1
                val b = (p shr ((i + 1) % 8)) and 1
                if (a != b) transitions++
            }
            if (transitions <= 2) uniformTable[p] = idx++ else nonUniform[p] = true
        }
        for (p in 0 until 256) if (nonUniform[p]) uniformTable[p] = idx
        LBP_BINS = idx + 1
    }

    val VECTOR_SIZE: Int get() = BLOCK_DIMS + LBP_GRID * LBP_GRID * LBP_BINS

    /** Classic global histogram equalisation on 0..255 grayscale values. */
    fun histogramEqualize(gray: IntArray): IntArray {
        val hist = IntArray(256)
        for (v in gray) hist[v.coerceIn(0, 255)]++
        val lut = IntArray(256)
        var cdf = 0
        var cdfMin = -1
        val total = gray.size
        for (i in 0 until 256) {
            cdf += hist[i]
            if (cdfMin < 0 && hist[i] > 0) cdfMin = cdf
            lut[i] = if (cdfMin < 0) 0
            else Math.round((cdf - cdfMin).toFloat() * 255f / maxOf(1, total - cdfMin).toFloat())
        }
        return IntArray(gray.size) { lut[gray[it].coerceIn(0, 255)].coerceIn(0, 255) }
    }

    /** 3x3 box blur; border pixels are copied unchanged. */
    fun boxBlur3(gray: IntArray, width: Int, height: Int): IntArray {
        val out = gray.copyOf()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var s = 0
                for (dy in -1..1) for (dx in -1..1) s += gray[(y + dy) * width + x + dx]
                out[y * width + x] = s / 9
            }
        }
        return out
    }

    /**
     * Feature vector for a [SIZE]x[SIZE] grayscale image (row-major, values 0..255).
     * Returns [VECTOR_SIZE] floats.
     */
    fun extract(gray96: IntArray): FloatArray {
        require(gray96.size == SIZE * SIZE) { "expected ${SIZE * SIZE} pixels, got ${gray96.size}" }
        val px = boxBlur3(histogramEqualize(gray96), SIZE, SIZE)
        val out = FloatArray(VECTOR_SIZE)
        var o = 0

        // (a) block means
        val bs = SIZE / BLOCK_GRID
        for (by in 0 until BLOCK_GRID) {
            for (bx in 0 until BLOCK_GRID) {
                var s = 0L
                for (y in by * bs until (by + 1) * bs) {
                    for (x in bx * bs until (bx + 1) * bs) s += px[y * SIZE + x]
                }
                out[o++] = s.toFloat() / (bs * bs * 255f)
            }
        }

        // (b) uniform LBP histograms per cell
        val cs = SIZE / LBP_GRID
        for (gy in 0 until LBP_GRID) {
            for (gx in 0 until LBP_GRID) {
                val hist = IntArray(LBP_BINS)
                var count = 0
                val y0 = maxOf(1, gy * cs)
                val y1 = minOf(SIZE - 1, (gy + 1) * cs)
                val x0 = maxOf(1, gx * cs)
                val x1 = minOf(SIZE - 1, (gx + 1) * cs)
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val c = px[y * SIZE + x] + LBP_THRESHOLD
                        var code = 0
                        if (px[(y - 1) * SIZE + x - 1] >= c) code = code or 1
                        if (px[(y - 1) * SIZE + x] >= c) code = code or 2
                        if (px[(y - 1) * SIZE + x + 1] >= c) code = code or 4
                        if (px[y * SIZE + x + 1] >= c) code = code or 8
                        if (px[(y + 1) * SIZE + x + 1] >= c) code = code or 16
                        if (px[(y + 1) * SIZE + x] >= c) code = code or 32
                        if (px[(y + 1) * SIZE + x - 1] >= c) code = code or 64
                        if (px[y * SIZE + x - 1] >= c) code = code or 128
                        hist[uniformTable[code]]++
                        count++
                    }
                }
                val n = maxOf(1, count).toFloat()
                for (b in 0 until LBP_BINS) out[o++] = hist[b] / n
            }
        }
        return out
    }

    /** Pearson correlation (cosine of mean-centred vectors) over [from, to). */
    private fun corr(a: FloatArray, b: FloatArray, from: Int, to: Int): Float {
        if (to <= from || a.size < to || b.size < to) return 0f
        var ma = 0.0
        var mb = 0.0
        for (i in from until to) { ma += a[i]; mb += b[i] }
        val n = (to - from).toDouble()
        ma /= n
        mb /= n
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in from until to) {
            val x = a[i] - ma
            val y = b[i] - mb
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na <= 0.0 || nb <= 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /** Similarity in [-1, 1]; ≥ threshold means "same person" for this matcher. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != VECTOR_SIZE || b.size != VECTOR_SIZE) return 0f
        val blocks = corr(a, b, 0, BLOCK_DIMS)
        val lbp = corr(a, b, BLOCK_DIMS, VECTOR_SIZE)
        return (0.4f * blocks + 0.6f * lbp).coerceIn(-1f, 1f)
    }

    /** Max similarity of [probe] against every enrolled vector of one person. */
    fun personScore(probe: FloatArray, vectors: List<FloatArray>): Float {
        var best = -1f
        for (v in vectors) {
            val s = similarity(probe, v)
            if (s > best) best = s
        }
        return best
    }

    data class Match(val name: String, val score: Float)

    /**
     * Best person for [probe]; null when nobody reaches [threshold].
     * [people] = name → enrolled vectors.
     */
    fun bestMatch(
        probe: FloatArray,
        people: Map<String, List<FloatArray>>,
        threshold: Float = DEFAULT_THRESHOLD
    ): Match? {
        var best: Match? = null
        for ((name, vectors) in people) {
            val s = personScore(probe, vectors)
            if (s >= threshold && (best == null || s > best.score)) best = Match(name, s)
        }
        return best
    }
}
