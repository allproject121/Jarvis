package com.jarvis.assistant.androllm.memory.embedding

import com.jarvis.assistant.androllm.cloud.CloudGateway
import com.jarvis.assistant.androllm.engine.support.Result
import com.jarvis.assistant.androllm.memory.MemorySettingsStore
import com.jarvis.assistant.androllm.memory.util.MemoryLogger

/**
 * Cloud-backed embedding source (ported from AndroLLM CloudEmbeddingProvider):
 * vectors are produced by the ACTIVE AndroLLM cloud-gateway provider using the
 * configured embedding model id (e.g. "openai/text-embedding-3-small").
 * Best-effort — a failure is a [Result.Error] that [RoutingEmbeddingProvider]
 * turns into a local fallback, so memory never breaks because a cloud call failed.
 *
 * Never touches Gemini Live.
 */
class CloudEmbeddingProvider(
    private val cloudGateway: CloudGateway,
    private val settingsStore: MemorySettingsStore,
    private val logger: MemoryLogger = MemoryLogger
) {

    /** True when a cloud embedding model id is configured. */
    fun isConfigured(): Boolean = settingsStore.current().cloudEmbeddingModel.isNotBlank()

    /** True when a model id is configured AND the gateway has a usable provider. */
    suspend fun isAvailable(): Boolean =
        isConfigured() && try {
            cloudGateway.isConfigured()
        } catch (_: Exception) {
            false
        }

    suspend fun embed(texts: List<String>): Result<List<FloatArray>> {
        if (texts.isEmpty()) return Result.Success(emptyList())
        return try {
            val modelId = settingsStore.current().cloudEmbeddingModel
            val vectors = cloudGateway.embed(texts, modelId = modelId.takeIf { it.isNotBlank() })
            logger.info("Cloud embedding: ${texts.size} text(s) via $modelId")
            Result.Success(vectors.map { it.toFloatArray() })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
