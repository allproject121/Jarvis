package com.jarvis.assistant.ai.memory

import android.content.Context
import android.util.Log
import com.jarvis.assistant.data.memory.MemoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance On-Device Semantic Vector Memory Engine.
 * Connects directly with Jarvis's persistent SQLite Room Database, generating and indexing
 * dense vectors for real-time Retrieval-Augmented Generation (RAG).
 */
class VectorMemoryEngine private constructor(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "VectorMemoryEngine"

        @Volatile
        private var instance: VectorMemoryEngine? = null

        fun getInstance(context: Context, scope: CoroutineScope): VectorMemoryEngine {
            return instance ?: synchronized(this) {
                instance ?: VectorMemoryEngine(context.applicationContext, scope).also { instance = it }
            }
        }
    }

    private val embeddingEngine = TextEmbeddingEngine.getInstance(context)
    private val mutex = Mutex()

    // In-memory index for sub-millisecond similarity scans
    private val memoryIndex = ConcurrentHashMap<Long, VectorMemoryEntry>()

    private val _indexedCount = MutableStateFlow(0)
    val indexedCount: StateFlow<Int> = _indexedCount.asStateFlow()

    /**
     * Synchronizes and indexes an array of Room Database memory entities.
     */
    suspend fun syncWithRoomMemories(entities: List<MemoryEntity>) = withContext(Dispatchers.Default) {
        mutex.withLock {
            var newIndexed = 0
            for (entity in entities) {
                if (!memoryIndex.containsKey(entity.id)) {
                    val embedding = embeddingEngine.generateEmbedding(entity.rawText)
                    val entry = VectorMemoryEntry(
                        memoryId = entity.id,
                        rawText = entity.rawText,
                        category = entity.category,
                        embedding = embedding,
                        timestamp = entity.timestamp
                    )
                    memoryIndex[entity.id] = entry
                    newIndexed++
                }
            }
            _indexedCount.value = memoryIndex.size
            if (newIndexed > 0) {
                Log.i(TAG, "Indexed $newIndexed new memories into vector core. Total indexed: ${memoryIndex.size}")
            }
        }
    }

    /**
     * Indexes a single new memory into the vector store.
     */
    suspend fun indexMemory(id: Long, text: String, category: String) = withContext(Dispatchers.Default) {
        val embedding = embeddingEngine.generateEmbedding(text)
        val entry = VectorMemoryEntry(
            memoryId = id,
            rawText = text,
            category = category,
            embedding = embedding
        )
        memoryIndex[id] = entry
        _indexedCount.value = memoryIndex.size
        Log.i(TAG, "Indexed memory #$id: '$text' (${embedding.size} dims)")
    }

    /**
     * Removes a memory from the vector index when deleted from Room DB.
     */
    fun removeMemory(id: Long) {
        memoryIndex.remove(id)
        _indexedCount.value = memoryIndex.size
    }

    /**
     * Performs semantic similarity search across all indexed memories.
     */
    suspend fun searchSimilarMemories(
        query: String,
        topK: Int = 4,
        minScore: Float = 0.45f,
        categoryFilter: String? = null
    ): List<ScoredMemory> = withContext(Dispatchers.Default) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank() || memoryIndex.isEmpty()) return@withContext emptyList()

        val queryEmbedding = embeddingEngine.generateEmbedding(cleanQuery)
        val scoredList = mutableListOf<ScoredMemory>()

        for ((_, entry) in memoryIndex) {
            if (categoryFilter != null && !entry.category.equals(categoryFilter, ignoreCase = true)) {
                continue
            }

            val score = VectorMath.cosineSimilarity(queryEmbedding, entry.embedding)
            if (score >= minScore) {
                scoredList.add(ScoredMemory(entry, score))
            }
        }

        // Rank by highest similarity score
        scoredList.sortByDescending { it.similarityScore }
        val topResults = scoredList.take(topK)
        Log.d(TAG, "Query '$cleanQuery' matched ${topResults.size} semantic memories (top score=${topResults.firstOrNull()?.similarityScore ?: 0f})")
        return@withContext topResults
    }

    /**
     * Constructs a compact contextual injection block for system prompts (used by Gemini Live, ChatGPT, or Local AI).
     */
    suspend fun buildMemoryRAGContext(userQuery: String): String {
        val matches = searchSimilarMemories(userQuery, topK = 3, minScore = 0.50f)
        if (matches.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("[RELEVANT USER MEMORIES FROM PERSISTENT CORE]:\n")
        for (match in matches) {
            sb.append("- ").append(match.memory.rawText)
                .append(" (relevance: ").append(match.percentageConfidence).append("%)\n")
        }
        return sb.toString().trim()
    }

    fun clearIndex() {
        memoryIndex.clear()
        _indexedCount.value = 0
    }
}
