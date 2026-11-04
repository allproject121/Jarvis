package com.jarvis.assistant.ai.cloud

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache for stable prompt prefixes (system instructions, tool definitions, static templates).
 * Prevents redundant token re-serialization and optimizes prefix caching for local and cloud models.
 */
class PromptCache(
    private val maxEntries: Int = 64
) {
    companion object {
        @Volatile
        private var instance: PromptCache? = null

        fun getInstance(): PromptCache {
            return instance ?: synchronized(this) {
                instance ?: PromptCache().also { instance = it }
            }
        }

        fun sha256(input: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            return digest.fold("") { str, it -> str + "%02x".format(it) }
        }
    }

    private val cache = ConcurrentHashMap<String, String>()

    fun get(key: String): String? = cache[key]

    fun put(key: String, content: String) {
        if (cache.size >= maxEntries) {
            val firstKey = cache.keys().nextElement()
            cache.remove(firstKey)
        }
        cache[key] = content
    }

    fun getOrCompute(key: String, compute: () -> String): String {
        cache[key]?.let { return it }
        val computed = compute()
        put(key, computed)
        return computed
    }

    fun clear() {
        cache.clear()
    }
}
