package com.jarvis.assistant.androllm.memory.embedding

import com.jarvis.assistant.androllm.engine.embedding.LiteRtEmbeddingEngine
import com.jarvis.assistant.androllm.engine.support.Result
import com.jarvis.assistant.androllm.engine.support.map
import com.jarvis.assistant.androllm.engine.support.onError
import com.jarvis.assistant.androllm.engine.support.onSuccess
import com.jarvis.assistant.androllm.memory.MemorySettings
import com.jarvis.assistant.androllm.memory.MemorySettingsStore
import com.jarvis.assistant.androllm.memory.util.MemoryLogger
import java.io.File

/** Which embedder backs Jarvis' semantic memory. */
enum class EmbeddingSource(val label: String) {
    /** Jarvis' built-in 384-d hashing projection (default, no model, no network). */
    HASHING("Built-in hashing (default)"),
    /** Cloud embeddings when configured and usable, otherwise the on-device LiteRT model. */
    AUTO("Auto (cloud, then on-device LiteRT)"),
    /** On-device LiteRT EmbeddingGemma `.tflite` model only. */
    LITERT("On-device LiteRT model"),
    /** AndroLLM cloud gateway embeddings only. */
    CLOUD("Cloud gateway embeddings");

    companion object {
        fun fromName(raw: String?): EmbeddingSource =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: HASHING
    }
}

/**
 * Abstraction over sentence-embedding generation (ported from AndroLLM
 * `core/memory/embedding/EmbeddingProvider`). The production implementation
 * runs the EmbeddingGemma 300M `.tflite` model through the LiteRT interpreter —
 * fully on-device, no network.
 */
interface EmbeddingProvider {

    /** Embedding dimension of the loaded model (0 = none loaded / unknown). */
    val dimension: Int

    fun isModelLoaded(): Boolean

    /** Loads the configured model (no-op when already loaded). */
    suspend fun ensureLoaded(): Result<Unit>

    suspend fun embed(texts: List<String>): Result<List<FloatArray>>

    suspend fun embed(text: String): Result<FloatArray>

    /** Unloads the model from memory. */
    suspend fun unload(): Result<Unit>
}

/**
 * LiteRT-backed [EmbeddingProvider] over Jarvis' ported [LiteRtEmbeddingEngine];
 * the model path comes from [MemorySettings.embeddingModelPath] and the tokenizer
 * is the sibling `tokenizer.model` file.
 */
class LiteRtEmbeddingProvider(
    private val engine: LiteRtEmbeddingEngine,
    private val settingsStore: MemorySettingsStore,
    private val logger: MemoryLogger = MemoryLogger
) : EmbeddingProvider {

    override val dimension: Int get() = engine.dimension

    override fun isModelLoaded(): Boolean = engine.isLoaded()

    /** True when a model path is configured. */
    fun isConfigured(): Boolean = currentSettings()?.embeddingModelPath?.isNotBlank() == true

    override suspend fun ensureLoaded(): Result<Unit> {
        val settings = currentSettings() ?: return Result.error("Memory settings unavailable")
        val path = settings.embeddingModelPath
        if (path.isBlank()) {
            return Result.error("No embedding model configured")
        }
        if (engine.isLoaded() && engine.loadedModelPath == File(path).absolutePath) return Result.Success(Unit)

        val result = engine.loadModel(modelPath = path)
        result
            .onSuccess { logger.info("Embedding model loaded (dim=$it): ${File(path).name}") }
            .onError { logger.warn("Embedding model load failed: ${it.message}") }
        return result.map { }
    }

    override suspend fun embed(texts: List<String>): Result<List<FloatArray>> {
        if (texts.isEmpty()) return Result.Success(emptyList())
        val loaded = ensureLoaded()
        if (loaded is Result.Error) return Result.error(loaded.exception)
        return engine.embed(texts)
    }

    override suspend fun embed(text: String): Result<FloatArray> =
        embed(listOf(text)).map { it.firstOrNull() ?: FloatArray(0) }

    override suspend fun unload(): Result<Unit> {
        if (engine.isLoaded()) logger.info("Embedding model unloaded")
        return engine.unloadModel()
    }

    private fun currentSettings(): MemorySettings? = try {
        settingsStore.current()
    } catch (_: Exception) {
        null
    }
}
