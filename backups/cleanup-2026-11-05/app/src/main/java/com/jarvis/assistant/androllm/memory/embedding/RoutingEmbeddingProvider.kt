package com.jarvis.assistant.androllm.memory.embedding

import com.jarvis.assistant.androllm.engine.support.Result
import com.jarvis.assistant.androllm.engine.support.map
import com.jarvis.assistant.androllm.memory.MemorySettingsStore
import com.jarvis.assistant.androllm.memory.util.MemoryLogger

/**
 * Routed [EmbeddingProvider] (ported from AndroLLM RoutingEmbeddingProvider).
 * Embeddings are an OPTIONAL indexing optimization, never a prerequisite:
 *
 *  - [EmbeddingSource.AUTO]: cloud model configured + gateway usable ->
 *    [CloudEmbeddingProvider]; otherwise / on cloud failure -> [LiteRtEmbeddingProvider].
 *  - [EmbeddingSource.LITERT] / [EmbeddingSource.CLOUD]: only that source.
 *  - [EmbeddingSource.HASHING]: no neural source — calls fail cleanly and Jarvis
 *    keeps its hashing embedder and keyword retrieval.
 */
class RoutingEmbeddingProvider(
    private val cloud: CloudEmbeddingProvider,
    private val local: LiteRtEmbeddingProvider,
    private val settingsStore: MemorySettingsStore,
    private val logger: MemoryLogger = MemoryLogger
) : EmbeddingProvider {

    override val dimension: Int
        get() = local.dimension

    override fun isModelLoaded(): Boolean = local.isModelLoaded()

    /**
     * Label of the embedding space the next vectors will belong to ("" = no
     * neural source). Stored as `memory_embeddings.model_path`.
     */
    suspend fun activeLabel(): String {
        val settings = settingsStore.current()
        val cloudLabel = "cloud:${settings.cloudEmbeddingModel}"
        val localLabel = "litert:${settings.embeddingModelPath}"
        return when (settings.embeddingSource) {
            EmbeddingSource.HASHING -> ""
            EmbeddingSource.CLOUD -> if (cloud.isConfigured()) cloudLabel else ""
            EmbeddingSource.LITERT -> if (local.isConfigured()) localLabel else ""
            EmbeddingSource.AUTO -> when {
                cloud.isAvailable() -> cloudLabel
                local.isConfigured() -> localLabel
                else -> ""
            }
        }
    }

    override suspend fun ensureLoaded(): Result<Unit> {
        val settings = settingsStore.current()
        return when (settings.embeddingSource) {
            EmbeddingSource.HASHING -> Result.error("Neural embeddings disabled (hashing embedder selected)")
            EmbeddingSource.CLOUD ->
                if (cloud.isConfigured()) Result.Success(Unit) else Result.error("No cloud embedding model configured")
            EmbeddingSource.LITERT -> local.ensureLoaded()
            EmbeddingSource.AUTO -> {
                // Cloud route needs no local model in memory.
                if (cloud.isAvailable()) {
                    logger.info("Embedding route: cloud (${settings.cloudEmbeddingModel}); no local model load")
                    Result.Success(Unit)
                } else {
                    local.ensureLoaded()
                }
            }
        }
    }

    override suspend fun embed(texts: List<String>): Result<List<FloatArray>> {
        if (texts.isEmpty()) return Result.Success(emptyList())
        val settings = settingsStore.current()
        return when (settings.embeddingSource) {
            EmbeddingSource.HASHING -> Result.error("Neural embeddings disabled (hashing embedder selected)")
            EmbeddingSource.CLOUD -> cloud.embed(texts)
            EmbeddingSource.LITERT -> local.embed(texts)
            EmbeddingSource.AUTO -> {
                if (cloud.isAvailable()) {
                    val cloudVectors = cloud.embed(texts)
                    if (cloudVectors is Result.Success) return cloudVectors
                    logger.warn(
                        "Cloud embedding failed (${(cloudVectors as Result.Error).exception.message}); falling back to local"
                    )
                }
                local.embed(texts)
            }
        }
    }

    override suspend fun embed(text: String): Result<FloatArray> =
        embed(listOf(text)).map { it.firstOrNull() ?: FloatArray(0) }

    override suspend fun unload(): Result<Unit> = local.unload()
}
