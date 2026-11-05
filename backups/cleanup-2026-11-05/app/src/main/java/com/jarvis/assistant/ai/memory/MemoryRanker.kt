package com.jarvis.assistant.ai.memory

import com.jarvis.assistant.androllm.memory.store.MemoryMetaRegistry

/**
 * Hybrid relevance ranker for semantic vector memories.
 * Local and cloud generation query here to rank memories based on:
 * - Cosine semantic similarity (dominant 0..1)
 * - Keyword matching boost (+0.06)
 * - Preference category boost (+0.05 for PREFERENCES/PERSONAL)
 * - Recency weight (0.00..0.08 with 30-day decay)
 * - Weak-memory decay (-0.10 penalty for stale, rarely accessed memories)
 *
 * Upgraded with the AndroLLM ranker signals, read from
 * [MemoryMetaRegistry] when a memory has AndroLLM metadata:
 * - pinned boost (+0.08, replaces the category boost for pinned memories)
 * - priority weight (+0.02 per priority point above 1)
 * - decay only for weak (priority<=2), rarely used (accessCount<=1), old memories
 * Memories without metadata are scored exactly as before.
 */
object MemoryRanker {

    const val KEYWORD_BOOST = 0.06f
    const val PREFERENCE_BOOST = 0.05f
    const val PINNED_BOOST = 0.08f
    const val PRIORITY_STEP = 0.02f
    const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000

    /** Lifecycle signals for one memory (from AndroLLM metadata). */
    data class MemorySignals(
        val isPinned: Boolean = false,
        val priority: Int = 1,
        val accessCount: Int = 0
    )

    fun scoreOf(
        baseSimilarity: Float,
        rawText: String,
        category: String,
        timestamp: Long,
        query: String,
        now: Long = System.currentTimeMillis()
    ): Float = scoreOf(baseSimilarity, rawText, category, timestamp, query, null, now)

    fun scoreOf(
        baseSimilarity: Float,
        rawText: String,
        category: String,
        timestamp: Long,
        query: String,
        signals: MemorySignals?,
        now: Long = System.currentTimeMillis()
    ): Float {
        var score = baseSimilarity

        // Keyword boost
        if (query.isNotBlank() && rawText.contains(query, ignoreCase = true)) {
            score += KEYWORD_BOOST
        }

        // Pinned memories outrank incidental facts; otherwise category preference boost
        if (signals?.isPinned == true) {
            score += PINNED_BOOST
        } else if (category.equals("PREFERENCES", ignoreCase = true) ||
            category.equals("PERSONAL", ignoreCase = true)
        ) {
            score += PREFERENCE_BOOST
        }

        // Extractor/user priority (only for memories with metadata)
        if (signals != null) {
            score += (signals.priority.coerceIn(1, 5) - 1) * PRIORITY_STEP
        }

        // Recency boost (fresher memories get up to +0.08)
        val ageMs = (now - timestamp).coerceAtLeast(0L)
        score += recencyWeight(timestamp, now)

        // Weak memory decay: nothing is deleted, the penalty only affects ordering
        score -= decayPenalty(baseSimilarity, ageMs, signals)

        return score
    }

    /** 0.08 for brand-new memories, 0.0 for >30 days, linear between. */
    fun recencyWeight(timestamp: Long, now: Long = System.currentTimeMillis()): Float {
        val ageMs = (now - timestamp).coerceAtLeast(0L)
        return (1f - (ageMs.toFloat() / THIRTY_DAYS_MS).coerceIn(0f, 1f)) * 0.08f
    }

    /**
     * Legacy rule (no metadata): old + weakly similar memories sink.
     * With metadata (AndroLLM rule): old + low priority + rarely used memories sink;
     * pinned memories never decay.
     */
    fun decayPenalty(baseSimilarity: Float, ageMs: Long, signals: MemorySignals?): Float {
        if (ageMs <= THIRTY_DAYS_MS) return 0f
        if (signals == null) return if (baseSimilarity < 0.60f) 0.10f else 0f
        if (signals.isPinned) return 0f
        return if (signals.priority <= 2 && signals.accessCount <= 1) 0.10f else 0f
    }

    private fun signalsFor(memoryId: Long): MemorySignals? =
        MemoryMetaRegistry.get(memoryId)?.let {
            MemorySignals(isPinned = it.isPinned, priority = maxOf(it.priority, it.importance), accessCount = it.accessCount)
        }

    /**
     * Ranks a list of ScoredMemory entries by combined score descending.
     * Pinned first on ties, then recency, then id (stable).
     */
    fun rank(
        candidates: List<ScoredMemory>,
        query: String,
        now: Long = System.currentTimeMillis()
    ): List<ScoredMemory> {
        val scored = candidates.map { candidate ->
            val signals = signalsFor(candidate.memory.memoryId)
            val hybridScore = scoreOf(
                baseSimilarity = candidate.similarityScore,
                rawText = candidate.memory.rawText,
                category = candidate.memory.category,
                timestamp = candidate.memory.timestamp,
                query = query,
                signals = signals,
                now = now
            )
            Triple(candidate, hybridScore, signals?.isPinned == true)
        }

        return scored.sortedWith(
            compareByDescending<Triple<ScoredMemory, Float, Boolean>> { it.second }
                .thenByDescending { it.third }
                .thenByDescending { it.first.memory.timestamp }
                .thenBy { it.first.memory.memoryId }
        ).map { it.first }
    }
}
