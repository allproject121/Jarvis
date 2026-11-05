package com.jarvis.assistant.ai.memory

/**
 * An indexed semantic memory entry with dense vector embeddings.
 */
data class VectorMemoryEntry(
    val memoryId: Long,
    val rawText: String,
    val category: String,
    val embedding: FloatArray,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VectorMemoryEntry
        return memoryId == other.memoryId
    }

    override fun hashCode(): Int {
        return memoryId.hashCode()
    }
}

/**
 * Result of a semantic similarity search query ranked by relevance.
 */
data class ScoredMemory(
    val memory: VectorMemoryEntry,
    val similarityScore: Float
) {
    val percentageConfidence: Int
        get() = (similarityScore * 100).toInt().coerceIn(0, 100)
}

/**
 * Search parameters for semantic memory retrieval.
 */
data class MemorySearchQuery(
    val query: String,
    val topK: Int = 5,
    val minScore: Float = 0.55f,
    val categoryFilter: String? = null
)
