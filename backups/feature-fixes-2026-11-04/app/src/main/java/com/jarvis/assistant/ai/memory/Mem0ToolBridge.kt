package com.jarvis.assistant.ai.memory

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.data.memory.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mem0-compatible Persistent Memory & Tool Bridge.
 * Exposes standardized memory operations to Gemini Live, Local Host AI (Ollama),
 * and Offline Conversation Engine:
 *  - add_memory
 *  - search_memories
 *  - delete_memory
 *  - get_user_profile_context
 */
class Mem0ToolBridge private constructor(
    private val context: Context,
    private val repository: MemoryRepository,
    private val vectorEngine: VectorMemoryEngine
) {
    companion object {
        private const val TAG = "Mem0ToolBridge"

        @Volatile
        private var instance: Mem0ToolBridge? = null

        fun getInstance(context: Context): Mem0ToolBridge {
            return instance ?: synchronized(this) {
                val app = context.applicationContext as JarvisApp
                instance ?: Mem0ToolBridge(
                    context.applicationContext,
                    app.memoryRepository,
                    app.vectorMemoryEngine
                ).also { instance = it }
            }
        }

        /**
         * Standard Tool Declaration schema compatible with Gemini function calling and Ollama tools.
         */
        val TOOL_DEFINITIONS_JSON: JSONArray by lazy {
            JSONArray().apply {
                // 1. add_memory
                put(JSONObject().apply {
                    put("name", "add_memory")
                    put("description", "Stores a persistent user preference, fact, routine, contact, or life detail into long-term memory.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("fact", JSONObject().apply {
                                put("type", "string")
                                put("description", "The factual statement or preference to permanently remember.")
                            })
                            put("category", JSONObject().apply {
                                put("type", "string")
                                put("description", "Category: 'preferences', 'routines', 'contacts', 'work', or 'notes'.")
                            })
                        })
                        put("required", JSONArray().apply { put("fact") })
                    })
                })

                // 2. search_memories
                put(JSONObject().apply {
                    put("name", "search_memories")
                    put("description", "Searches long-term semantic memory for relevant facts, routines, or past user details.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("query", JSONObject().apply {
                                put("type", "string")
                                put("description", "Semantic search query or topic to recall.")
                            })
                        })
                        put("required", JSONArray().apply { put("query") })
                    })
                })

                // 3. delete_memory
                put(JSONObject().apply {
                    put("name", "delete_memory")
                    put("description", "Deletes an outdated, corrected, or requested fact from long-term memory.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("keyword", JSONObject().apply {
                                put("type", "string")
                                put("description", "Keyword or phrase matching the fact to remove.")
                            })
                        })
                        put("required", JSONArray().apply { put("keyword") })
                    })
                })
            }
        }
    }

    /**
     * Stores a new memory fact and immediately indexes it in the vector engine.
     */
    suspend fun addMemory(fact: String, category: String = MemoryEntity.CATEGORY_NOTES): String = withContext(Dispatchers.IO) {
        val cleanFact = fact.trim()
        if (cleanFact.isBlank()) return@withContext "Empty fact cannot be stored."

        val existing = repository.searchMemories(cleanFact)
        if (existing.isNotEmpty()) {
            return@withContext "Sir, this fact is already preserved in long-term memory."
        }

        val id = repository.saveMemory(
            rawText = cleanFact,
            category = category,
            key = cleanFact.take(30),
            value = cleanFact
        )
        vectorEngine.indexMemory(id, cleanFact, category)
        Log.i(TAG, "Mem0: Indexed memory #$id ($category): '$cleanFact'")
        "Sir, I have permanently recorded that to my long-term memory."
    }

    /**
     * Retrieves top semantic memories matching the query.
     */
    suspend fun searchMemories(query: String, limit: Int = 4): String = withContext(Dispatchers.IO) {
        val matches = vectorEngine.searchSimilarMemories(query, topK = limit, minScore = 0.35f)
        if (matches.isEmpty()) {
            val kwMatches = repository.searchMemories(query)
            if (kwMatches.isEmpty()) return@withContext "No matching memories found for '$query'."
            return@withContext "Recalled facts:\n" + kwMatches.take(limit).joinToString("\n") { "• ${it.rawText}" }
        }

        "Recalled facts:\n" + matches.joinToString("\n") { match ->
            "• [${match.memory.category.uppercase(java.util.Locale.ROOT)}] ${match.memory.rawText}"
        }
    }

    /**
     * Deletes memories matching the specified keyword.
     */
    suspend fun deleteMemory(keyword: String): String = withContext(Dispatchers.IO) {
        val deletedCount = repository.deleteMemoriesByKeyword(keyword)
        vectorEngine.removeMemory(keyword.hashCode().toLong())
        Log.i(TAG, "Mem0: Removed $deletedCount memories matching '$keyword'")
        if (deletedCount > 0) {
            "Sir, I have deleted $deletedCount related record(s) from my memory."
        } else {
            "Sir, no records matched '$keyword'."
        }
    }

    /**
     * Assembles a structured context string for prompt injection.
     */
    suspend fun buildPersistentProfileContext(query: String): String = withContext(Dispatchers.IO) {
        vectorEngine.buildMemoryRAGContext(query)
    }
}
