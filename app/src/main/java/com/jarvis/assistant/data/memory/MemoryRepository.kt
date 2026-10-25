package com.jarvis.assistant.data.memory

import com.jarvis.assistant.ai.memory.VectorMemoryEngine
import kotlinx.coroutines.flow.Flow

class MemoryRepository(private val memoryDao: MemoryDao) {

    /**
     * Optional vector-memory index.
     *
     * When attached, every memory written through this repository is indexed
     * for semantic recall. Without it the vector index silently stays empty
     * and `search_memories` degrades to a plain keyword LIKE query.
     */
    @Volatile
    private var vectorEngine: VectorMemoryEngine? = null

    /**
     * Attaches the vector memory engine so saved memories become semantically
     * searchable. Safe to call more than once.
     */
    fun attachVectorEngine(engine: VectorMemoryEngine?) {
        vectorEngine = engine
    }

    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = memoryDao.getAllMemoriesFlow()

    suspend fun getAllMemories(): List<MemoryEntity> = memoryDao.getAllMemories()

    fun getMemoriesByCategoryFlow(category: String): Flow<List<MemoryEntity>> {
        return if (category == "All") {
            memoryDao.getAllMemoriesFlow()
        } else {
            memoryDao.getMemoriesByCategoryFlow(category)
        }
    }

    fun searchMemoriesFlow(query: String): Flow<List<MemoryEntity>> = memoryDao.searchMemoriesFlow(query)

    suspend fun searchMemories(query: String): List<MemoryEntity> = memoryDao.searchMemories(query)

    suspend fun saveMemory(
        rawText: String,
        category: String = MemoryEntity.CATEGORY_NOTES,
        key: String = "",
        value: String = ""
    ): Long {
        val calculatedKey = key.ifBlank {
            rawText.take(30).trim()
        }
        val calculatedValue = value.ifBlank { rawText }

        val entity = MemoryEntity(
            category = category,
            key = calculatedKey,
            value = calculatedValue,
            rawText = rawText
        )
        val id = memoryDao.insertMemory(entity)

        // Keep the semantic index in lock-step with the database so that
        // `search_memories` can actually recall what was just learned.
        vectorEngine?.let { engine ->
            runCatching { engine.indexMemory(id, rawText, category) }
        }

        return id
    }

    suspend fun updateMemory(memory: MemoryEntity) {
        memoryDao.updateMemory(memory)
        vectorEngine?.let { engine ->
            runCatching { engine.indexMemory(memory.id, memory.rawText, memory.category) }
        }
    }

    suspend fun deleteMemoryById(id: Long) {
        memoryDao.deleteMemoryById(id)
        vectorEngine?.removeMemory(id)
    }

    suspend fun deleteMemoriesByKeyword(keyword: String): Int {
        val deleted = memoryDao.deleteMemoriesByKeyword(keyword)
        return deleted
    }

    suspend fun clearAll() {
        memoryDao.clearAllMemories()
        vectorEngine?.clearIndex()
    }
}
