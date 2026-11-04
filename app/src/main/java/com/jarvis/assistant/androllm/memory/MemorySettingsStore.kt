package com.jarvis.assistant.androllm.memory

import android.content.Context
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.memory.embedding.EmbeddingSource
import com.jarvis.assistant.androllm.memory.intelligence.MemoryIntelligenceRoute

/**
 * User-configurable AndroLLM memory settings (port of AndroLLM MemorySettings).
 * On/off switches live in [AndroLlmFlags] (extraction ON, background indexing OFF by default); tuning values live
 * in their own SharedPreferences file. Nothing here leaves the device.
 */
data class MemorySettings(
    /** LLM extraction after each exchange ([AndroLlmFlags.MEMORY_EXTRACTION], default ON). */
    val extractionEnabled: Boolean = true,
    /** Periodic WorkManager indexing ([AndroLlmFlags.MEMORY_BACKGROUND_INDEXING]). */
    val backgroundIndexingEnabled: Boolean = false,
    /** Minimum similarity before an extracted memory updates an existing one. */
    val similarityThreshold: Float = DEFAULT_THRESHOLD,
    /** Maximum memories injected by [com.jarvis.assistant.androllm.memory.context.ContextBuilder]. */
    val maxContextMemories: Int = 5,
    /** Maximum conversation summaries injected. */
    val maxContextSummaries: Int = 2,
    /** Summarize a conversation every N messages. */
    val summarizationInterval: Int = 20,
    /** Which LLM backing extraction/summarization may use. */
    val route: MemoryIntelligenceRoute = MemoryIntelligenceRoute.AUTO,
    /**
     * Which embedder backs semantic memory. [EmbeddingSource.HASHING] (default)
     * keeps Jarvis' built-in hashing projection; the others install an
     * AndroLLM [com.jarvis.assistant.androllm.memory.embedding.EmbeddingProvider]
     * into Jarvis' TextEmbeddingEngine.
     */
    val embeddingSource: EmbeddingSource = EmbeddingSource.HASHING,
    /** Absolute path of the LiteRT `.tflite` embedding model ("" = not configured). */
    val embeddingModelPath: String = "",
    /** Embedding model id routed through the AndroLLM cloud gateway ("" = none). */
    val cloudEmbeddingModel: String = "",
    /** Prefix prepended to queries (e.g. "task: search result | query:"). */
    val queryPrefix: String = "",
    /** Prefix prepended to stored passages. */
    val passagePrefix: String = "",
    /** Memory Inspector minimum log level. */
    val logLevel: MemoryLogLevel = MemoryLogLevel.INFO
) {
    companion object {
        /**
         * AndroLLM default. With Jarvis' hashing embedder only near-verbatim
         * restatements reach it; paraphrases are caught by the normalized /
         * word-overlap dedupe and contradiction checks in the pipeline.
         */
        const val DEFAULT_THRESHOLD = 0.78f
        const val THRESHOLD_MIN = 0.5f
        const val THRESHOLD_MAX = 0.99f
        const val SUMMARIZATION_MIN = 4
        const val SUMMARIZATION_MAX = 100
    }
}

class MemorySettingsStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(): MemorySettings = MemorySettings(
        extractionEnabled = AndroLlmFlags.isMemoryExtractionEnabled(appContext),
        backgroundIndexingEnabled = AndroLlmFlags.isEnabled(appContext, AndroLlmFlags.MEMORY_BACKGROUND_INDEXING),
        similarityThreshold = prefs.getFloat(KEY_THRESHOLD, MemorySettings.DEFAULT_THRESHOLD)
            .coerceIn(MemorySettings.THRESHOLD_MIN, MemorySettings.THRESHOLD_MAX),
        maxContextMemories = prefs.getInt(KEY_MAX_MEMORIES, 5).coerceIn(0, 20),
        maxContextSummaries = prefs.getInt(KEY_MAX_SUMMARIES, 2).coerceIn(0, 5),
        summarizationInterval = prefs.getInt(KEY_SUMMARY_INTERVAL, 20)
            .coerceIn(MemorySettings.SUMMARIZATION_MIN, MemorySettings.SUMMARIZATION_MAX),
        route = MemoryIntelligenceRoute.fromName(prefs.getString(KEY_ROUTE, null)),
        embeddingSource = EmbeddingSource.fromName(prefs.getString(KEY_EMBEDDING_SOURCE, null)),
        embeddingModelPath = prefs.getString(KEY_EMBEDDING_MODEL_PATH, "").orEmpty(),
        cloudEmbeddingModel = prefs.getString(KEY_CLOUD_EMBEDDING_MODEL, "").orEmpty(),
        queryPrefix = prefs.getString(KEY_QUERY_PREFIX, "").orEmpty(),
        passagePrefix = prefs.getString(KEY_PASSAGE_PREFIX, "").orEmpty(),
        logLevel = prefs.getString(KEY_LOG_LEVEL, null)
            ?.let { raw -> MemoryLogLevel.entries.firstOrNull { it.name == raw } }
            ?: MemoryLogLevel.INFO
    )

    fun setExtractionEnabled(enabled: Boolean) =
        AndroLlmFlags.setEnabled(appContext, AndroLlmFlags.MEMORY_EXTRACTION, enabled)

    fun setBackgroundIndexingEnabled(enabled: Boolean) =
        AndroLlmFlags.setEnabled(appContext, AndroLlmFlags.MEMORY_BACKGROUND_INDEXING, enabled)

    fun setSimilarityThreshold(value: Float) =
        prefs.edit().putFloat(KEY_THRESHOLD, value.coerceIn(MemorySettings.THRESHOLD_MIN, MemorySettings.THRESHOLD_MAX)).apply()

    fun setMaxContextMemories(value: Int) = prefs.edit().putInt(KEY_MAX_MEMORIES, value.coerceIn(0, 20)).apply()

    fun setMaxContextSummaries(value: Int) = prefs.edit().putInt(KEY_MAX_SUMMARIES, value.coerceIn(0, 5)).apply()

    fun setSummarizationInterval(value: Int) = prefs.edit().putInt(
        KEY_SUMMARY_INTERVAL,
        value.coerceIn(MemorySettings.SUMMARIZATION_MIN, MemorySettings.SUMMARIZATION_MAX)
    ).apply()

    fun setRoute(route: MemoryIntelligenceRoute) = prefs.edit().putString(KEY_ROUTE, route.name).apply()

    fun setEmbeddingSource(source: EmbeddingSource) =
        prefs.edit().putString(KEY_EMBEDDING_SOURCE, source.name).apply()

    fun setEmbeddingModelPath(path: String) =
        prefs.edit().putString(KEY_EMBEDDING_MODEL_PATH, path.trim()).apply()

    fun setCloudEmbeddingModel(modelId: String) =
        prefs.edit().putString(KEY_CLOUD_EMBEDDING_MODEL, modelId.trim()).apply()

    fun setQueryPrefix(prefix: String) = prefs.edit().putString(KEY_QUERY_PREFIX, prefix.trim()).apply()

    fun setPassagePrefix(prefix: String) = prefs.edit().putString(KEY_PASSAGE_PREFIX, prefix.trim()).apply()

    fun setLogLevel(level: MemoryLogLevel) = prefs.edit().putString(KEY_LOG_LEVEL, level.name).apply()

    // ── Pipeline bookkeeping (status shown in settings) ──

    fun recordIndexing(result: String, at: Long = System.currentTimeMillis()) =
        prefs.edit().putLong(KEY_LAST_INDEXING_AT, at).putString(KEY_LAST_INDEXING_RESULT, result).apply()

    fun recordExtraction(result: String, at: Long = System.currentTimeMillis()) =
        prefs.edit().putLong(KEY_LAST_EXTRACTION_AT, at).putString(KEY_LAST_EXTRACTION_RESULT, result).apply()

    fun lastIndexingAt(): Long = prefs.getLong(KEY_LAST_INDEXING_AT, 0L)
    fun lastIndexingResult(): String = prefs.getString(KEY_LAST_INDEXING_RESULT, "").orEmpty()
    fun lastExtractionAt(): Long = prefs.getLong(KEY_LAST_EXTRACTION_AT, 0L)
    fun lastExtractionResult(): String = prefs.getString(KEY_LAST_EXTRACTION_RESULT, "").orEmpty()

    /** Embedding space the vector index was last built with. */
    fun indexedEmbedderSignature(): String = prefs.getString(KEY_EMBEDDER_SIGNATURE, "").orEmpty()
    fun setIndexedEmbedderSignature(signature: String) = prefs.edit().putString(KEY_EMBEDDER_SIGNATURE, signature).apply()

    /** Monotonic per-conversation message counter used for summarization scheduling. */
    fun incrementMessageCount(conversationId: String, by: Int): Int {
        val key = KEY_MSG_COUNT_PREFIX + conversationId
        val next = prefs.getInt(key, 0) + by
        prefs.edit().putInt(key, next).apply()
        return next
    }

    companion object {
        private const val PREFS = "androllm_memory_settings"
        private const val KEY_THRESHOLD = "similarity_threshold"
        private const val KEY_MAX_MEMORIES = "max_context_memories"
        private const val KEY_MAX_SUMMARIES = "max_context_summaries"
        private const val KEY_SUMMARY_INTERVAL = "summarization_interval"
        private const val KEY_ROUTE = "intelligence_route"
        private const val KEY_LAST_INDEXING_AT = "last_indexing_at"
        private const val KEY_LAST_INDEXING_RESULT = "last_indexing_result"
        private const val KEY_LAST_EXTRACTION_AT = "last_extraction_at"
        private const val KEY_LAST_EXTRACTION_RESULT = "last_extraction_result"
        private const val KEY_EMBEDDER_SIGNATURE = "indexed_embedder_signature"
        private const val KEY_MSG_COUNT_PREFIX = "msg_count_"
        private const val KEY_EMBEDDING_SOURCE = "embedding_source"
        private const val KEY_EMBEDDING_MODEL_PATH = "embedding_model_path"
        private const val KEY_CLOUD_EMBEDDING_MODEL = "cloud_embedding_model"
        private const val KEY_QUERY_PREFIX = "embedding_query_prefix"
        private const val KEY_PASSAGE_PREFIX = "embedding_passage_prefix"
        private const val KEY_LOG_LEVEL = "inspector_log_level"
    }
}
