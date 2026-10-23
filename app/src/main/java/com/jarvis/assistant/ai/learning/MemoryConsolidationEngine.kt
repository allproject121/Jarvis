package com.jarvis.assistant.ai.learning

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ai.memory.VectorMemoryEngine
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.data.memory.MemoryRepository
import com.jarvis.assistant.data.model.ChatTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Background Memory Consolidation & Reflection Engine.
 * Simulates human sleep-time memory consolidation:
 * Reviews conversational logs, extracts persistent facts, prunes redundant chatter,
 * and synchronizes new dense vectors into the Vector Memory Core.
 */
class MemoryConsolidationEngine private constructor(
    private val context: Context,
    private val repository: MemoryRepository,
    private val vectorMemoryEngine: VectorMemoryEngine,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MemoryConsolidationEngine"

        @Volatile
        private var instance: MemoryConsolidationEngine? = null

        fun getInstance(
            context: Context,
            repository: MemoryRepository,
            vectorMemoryEngine: VectorMemoryEngine,
            scope: CoroutineScope
        ): MemoryConsolidationEngine {
            return instance ?: synchronized(this) {
                instance ?: MemoryConsolidationEngine(
                    context.applicationContext,
                    repository,
                    vectorMemoryEngine,
                    scope
                ).also { instance = it }
            }
        }
    }

    /**
     * Consolidates a batch of recent chat turns into permanent knowledge facts.
     */
    suspend fun consolidateChatTurns(turns: List<ChatTurn>) = withContext(Dispatchers.IO) {
        if (turns.isEmpty()) return@withContext

        Log.i(TAG, "Starting memory reflection on ${turns.size} recent conversational turns...")
        var factsConsolidated = 0

        for (turn in turns) {
            val user = turn.userTranscript.trim()
            if (user.isBlank()) continue

            val fact = extractPermanentFact(user)
            if (fact != null) {
                val (category, text) = fact
                val existing = repository.searchMemories(text)
                if (existing.isEmpty()) {
                    val id = repository.saveMemory(
                        rawText = text,
                        category = category,
                        key = text.take(30),
                        value = text
                    )
                    vectorMemoryEngine.indexMemory(id, text, category)
                    factsConsolidated++
                    Log.i(TAG, "Consolidated new long-term fact ($category): '$text'")
                }
            }
        }

        Log.i(TAG, "Memory consolidation complete. Extracted $factsConsolidated permanent facts.")
    }

    /**
     * Extracts salient facts and categories from raw conversational text.
     */
    private fun extractPermanentFact(text: String): Pair<String, String>? {
        val lower = text.lowercase(Locale.ROOT)

        return when {
            lower.contains("i like ") || lower.contains("i love ") || lower.contains("mera favorite") || lower.contains("mujhe pasand hai") -> {
                Pair(MemoryEntity.CATEGORY_PREFERENCES, text)
            }
            lower.contains("i wake up") || lower.contains("i sleep at") || lower.contains("gym at") || lower.contains("subah ") -> {
                Pair(MemoryEntity.CATEGORY_ROUTINES, text)
            }
            lower.contains("my brother") || lower.contains("my sister") || lower.contains("my friend") || lower.contains("mera bhai") || lower.contains("meri behen") -> {
                Pair(MemoryEntity.CATEGORY_CONTACTS, text)
            }
            lower.contains("i am working on") || lower.contains("project") || lower.contains("mera kaam") -> {
                Pair(MemoryEntity.CATEGORY_NOTES, text)
            }
            else -> null
        }
    }
}
