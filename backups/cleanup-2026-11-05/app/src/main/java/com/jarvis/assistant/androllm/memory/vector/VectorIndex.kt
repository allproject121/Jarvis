package com.jarvis.assistant.androllm.memory.vector

import com.jarvis.assistant.ai.memory.VectorMath
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstraction over the vector store used for similarity search (ported from
 * AndroLLM `core/memory/vector/VectorIndex`).
 *
 * The default implementation ([CosineVectorIndex]) keeps embeddings in memory
 * (loaded from the `memory_embeddings` BLOBs) and brute-force scans them —
 * sub-millisecond for the hundreds-to-low-thousands of vectors a phone holds.
 * Ids are Jarvis `memories.id` values rendered as strings.
 */
interface VectorIndex {

    val size: Int

    /** Number of dimensions every stored vector must have. */
    val dimension: Int

    fun upsert(id: String, vector: FloatArray)

    fun upsertAll(entries: Map<String, FloatArray>)

    fun remove(id: String)

    fun clear()

    /**
     * Returns up to [topK] entries ordered by descending similarity to [query].
     * When [candidates] is non-null, only those ids are considered (metadata
     * pre-filtering, e.g. project / tag filters).
     */
    fun search(query: FloatArray, topK: Int, candidates: Collection<String>? = null): List<ScoredId>

    data class ScoredId(val id: String, val score: Float)
}

/**
 * In-memory brute-force cosine index over normalized vectors. Thread-safe and
 * hardened: corrupted vectors (NaN/Inf), empty queries and dimension mismatches
 * are ignored instead of crashing retrieval.
 */
class CosineVectorIndex(override val dimension: Int) : VectorIndex {

    private val vectors = ConcurrentHashMap<String, FloatArray>()

    override val size: Int get() = vectors.size

    override fun upsert(id: String, vector: FloatArray) {
        if (id.isBlank()) return
        if (vector.isEmpty()) return
        if (vector.any { it.isNaN() || it.isInfinite() }) return
        if (vector.size != dimension) {
            throw IllegalArgumentException("Vector dimension ${vector.size} != index dimension $dimension")
        }
        vectors[id] = VectorMath.normalize(vector.copyOf())
    }

    override fun upsertAll(entries: Map<String, FloatArray>) {
        for ((id, v) in entries) {
            try {
                upsert(id, v)
            } catch (_: Exception) {
            }
        }
    }

    override fun remove(id: String) {
        if (id.isBlank()) return
        vectors.remove(id)
    }

    override fun clear() {
        vectors.clear()
    }

    override fun search(
        query: FloatArray,
        topK: Int,
        candidates: Collection<String>?
    ): List<VectorIndex.ScoredId> {
        if (topK <= 0 || vectors.isEmpty() || query.isEmpty()) return emptyList()
        if (query.size != dimension) return emptyList()
        if (query.any { it.isNaN() || it.isInfinite() }) return emptyList()
        val q = VectorMath.normalize(query.copyOf())
        if (q.any { it.isNaN() || it.isInfinite() }) return emptyList()

        val scored = ArrayList<VectorIndex.ScoredId>()
        val ids: Iterable<String> = candidates ?: vectors.keys
        for (id in ids) {
            val v = vectors[id] ?: continue
            if (v.size != q.size) continue
            scored.add(VectorIndex.ScoredId(id, dot(q, v)))
        }
        // Stable sort: score descending, then id for deterministic tie-breaking.
        scored.sortWith(compareByDescending<VectorIndex.ScoredId> { it.score }.thenBy { it.id })
        return scored.take(topK.coerceAtMost(1000))
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum.coerceIn(-1f, 1f)
    }
}
