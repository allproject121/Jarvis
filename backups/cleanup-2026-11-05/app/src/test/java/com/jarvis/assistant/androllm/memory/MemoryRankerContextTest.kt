package com.jarvis.assistant.androllm.memory

import com.jarvis.assistant.ai.memory.MemoryRanker
import com.jarvis.assistant.ai.memory.ScoredMemory
import com.jarvis.assistant.ai.memory.VectorMemoryEntry
import com.jarvis.assistant.androllm.memory.context.ContextBuilder
import com.jarvis.assistant.androllm.memory.store.MemoryMetaEntity
import com.jarvis.assistant.androllm.memory.store.MemoryMetaRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRankerContextTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 3600 * 1000

    @After
    fun tearDown() = MemoryMetaRegistry.clear()

    private fun scored(id: Long, text: String, score: Float, timestamp: Long = now, category: String = "Notes") =
        ScoredMemory(VectorMemoryEntry(id, text, category, FloatArray(0), timestamp), score)

    @Test
    fun legacyScoringUnchangedWithoutMetadata() {
        val legacy = MemoryRanker.scoreOf(0.5f, "User saw a bird", "Notes", now - 60 * day, "", now)
        // 0.5 base, no boosts, no recency, legacy decay (sim < 0.6, > 30 days)
        assertEquals(0.40f, legacy, 1e-4f)
    }

    @Test
    fun semanticRelevanceDominatesRecency() {
        val ranked = MemoryRanker.rank(
            listOf(scored(1, "User bought milk once", 0.1f, now), scored(2, "User prefers dark mode", 0.9f, now - 20 * day)),
            query = "",
            now = now
        )
        assertEquals(2L, ranked.first().memory.memoryId)
    }

    @Test
    fun pinnedOutranksEqualScoreUnpinned() {
        MemoryMetaRegistry.put(MemoryMetaEntity(memoryId = 10, isPinned = true))
        val ranked = MemoryRanker.rank(
            listOf(scored(11, "User prefers dark mode", 0.5f), scored(10, "User prefers dark mode", 0.5f)),
            query = "",
            now = now
        )
        assertEquals(10L, ranked.first().memory.memoryId)
    }

    @Test
    fun frequentlyUsedOldMemoryDoesNotDecay() {
        MemoryMetaRegistry.put(MemoryMetaEntity(memoryId = 20, priority = 1, accessCount = 0))
        MemoryMetaRegistry.put(MemoryMetaEntity(memoryId = 21, priority = 1, accessCount = 9))
        val old = now - 60 * day
        val ranked = MemoryRanker.rank(
            listOf(scored(20, "User saw a thing", 0.7f, old), scored(21, "User saw a thing", 0.7f, old)),
            query = "",
            now = now
        )
        assertEquals(21L, ranked.first().memory.memoryId)
    }

    @Test
    fun archivedAndExpiredAreHidden() {
        MemoryMetaRegistry.put(MemoryMetaEntity(memoryId = 30, isArchived = true))
        MemoryMetaRegistry.put(MemoryMetaEntity(memoryId = 31, expiryAt = now - 1))
        MemoryMetaRegistry.put(MemoryMetaEntity(memoryId = 32, expiryAt = now - 1, isPinned = true))
        assertTrue(MemoryMetaRegistry.isHidden(30, now))
        assertTrue(MemoryMetaRegistry.isHidden(31, now))
        assertFalse(MemoryMetaRegistry.isHidden(32, now))
        assertFalse(MemoryMetaRegistry.isHidden(99, now))
    }

    private fun memory(id: Long, content: String, pinned: Boolean = false, updatedAt: Long = 0L) = Memory(
        id = id, category = MemoryCategory.PREFERENCES, content = content, importance = 3,
        createdAt = 0L, updatedAt = updatedAt, isPinned = pinned
    )

    @Test
    fun contextBuilderIncludesMemoriesAndSummaries() {
        val text = ContextBuilder.buildSystemText(
            memories = listOf(MemorySearchResult(memory(1, "User prefers Kotlin"), 0.91f)),
            summaries = listOf(MemorySummary("c1", "Working on a weather app", 10, 0L, 0L)),
            maxMemories = 5,
            maxSummaries = 2
        )
        assertTrue(text.contains("Relevant memories"))
        assertTrue(text.contains("User prefers Kotlin"))
        assertTrue(text.contains("PREFERENCES"))
        assertTrue(text.contains("weather app"))
    }

    @Test
    fun contextBuilderRespectsLimitsAndRelevance() {
        val memories = (1..10).map { MemorySearchResult(memory(it.toLong(), "M$it"), 0.5f + it / 100f) }
        val text = ContextBuilder.buildSystemText(memories, emptyList(), maxMemories = 3, maxSummaries = 0)
        assertFalse(text.contains("M4"))
        assertTrue(text.contains("M10"))
        // Irrelevant, unpinned memories are never injected.
        assertEquals("", ContextBuilder.buildSystemText(listOf(MemorySearchResult(memory(1, "User saw a bird"), 0.1f)), emptyList(), 5, 0))
        assertEquals("", ContextBuilder.buildSystemText(emptyList(), emptyList(), 5, 2))
    }

    @Test
    fun contextBuilderKeepsOnlyWinnerOfContradiction() {
        val text = ContextBuilder.buildSystemText(
            memories = listOf(
                MemorySearchResult(memory(1, "User prefers dark mode", updatedAt = 1_000L), 0.8f),
                MemorySearchResult(memory(2, "User prefers light mode", updatedAt = 2_000L), 0.8f)
            ),
            summaries = emptyList(),
            maxMemories = 5,
            maxSummaries = 0
        )
        assertTrue(text.contains("light mode"))
        assertFalse(text.contains("dark mode"))
    }
}
