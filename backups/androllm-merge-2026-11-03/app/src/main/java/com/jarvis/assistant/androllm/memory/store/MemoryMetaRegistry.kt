package com.jarvis.assistant.androllm.memory.store

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide, lock-free snapshot of AndroLLM memory metadata, keyed by
 * Jarvis `memories.id`.
 *
 * Jarvis' synchronous/context-free memory code ([com.jarvis.assistant.ai.memory.MemoryRanker],
 * [com.jarvis.assistant.ai.memory.VectorMemoryEngine]) reads pinned / archived /
 * access-count / priority signals from here without touching the database.
 * When empty (feature never used) every lookup returns null and Jarvis ranking
 * behaves exactly as before.
 */
object MemoryMetaRegistry {

    private val cache = ConcurrentHashMap<Long, MemoryMetaEntity>()

    /** Persists access counts; installed by [AndroMemoryStore]. */
    @Volatile
    var accessSink: ((List<Long>) -> Unit)? = null

    fun get(memoryId: Long): MemoryMetaEntity? = cache[memoryId]

    fun put(meta: MemoryMetaEntity) {
        cache[meta.memoryId] = meta
    }

    fun remove(memoryId: Long) {
        cache.remove(memoryId)
    }

    fun replaceAll(all: List<MemoryMetaEntity>) {
        cache.clear()
        all.forEach { cache[it.memoryId] = it }
    }

    fun clear() = cache.clear()

    fun snapshot(): Map<Long, MemoryMetaEntity> = HashMap(cache)

    /** Archived memories, and expired non-pinned memories, are hidden from retrieval. */
    fun isHidden(memoryId: Long, now: Long = System.currentTimeMillis()): Boolean {
        val meta = cache[memoryId] ?: return false
        if (meta.isArchived) return true
        val expiry = meta.expiryAt ?: return false
        return !meta.isPinned && expiry <= now
    }

    fun isPinned(memoryId: Long): Boolean = cache[memoryId]?.isPinned == true

    /** Bumps in-memory access counters and forwards the ids to [accessSink]. */
    fun recordAccess(ids: List<Long>, now: Long = System.currentTimeMillis()) {
        if (ids.isEmpty()) return
        var any = false
        for (id in ids) {
            val meta = cache[id] ?: continue
            cache[id] = meta.copy(accessCount = meta.accessCount + 1, lastAccessedAt = now)
            any = true
        }
        if (any) accessSink?.invoke(ids.filter { cache.containsKey(it) })
    }
}
