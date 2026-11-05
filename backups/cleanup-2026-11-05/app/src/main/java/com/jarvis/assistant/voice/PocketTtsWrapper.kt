package com.jarvis.assistant.voice

import android.util.Log
import com.getmaya.android.agentic.ondevice.PcmSink
import com.getmaya.android.agentic.ondevice.PocketTtsNative
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Custom exception representing PocketTTS errors.
 */
class PocketTtsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Abstraction layer for PocketTTS native JNI calls.
 * Enables unit testing with test doubles on host JVM environments without native .so libraries.
 */
interface PocketTtsBridge {
    fun loadLibrary(): String?
    fun createEngine(threads: Int): Long
    fun configure(
        id: Long,
        temperature: Float,
        eosThreshold: Float,
        eosExtraFrames: Int,
        firstChunkFrames: Int,
        maxChunkFrames: Int,
        maxTokensPerChunk: Int,
        padShortInputs: Boolean,
        appendTerminalPunctuation: Boolean,
        voiceStateCache: Int
    ): Int
    fun markReady(id: Long): Int
    fun speak(id: Long, utf8: ByteArray, sink: PcmSink): Int
    fun cut(id: Long)
    fun close(id: Long)
    fun lastError(id: Long): String?
    fun info(id: Long): String?
}

/**
 * Production bridge delegating directly to [PocketTtsNative] JNI symbols.
 */
object DefaultPocketTtsBridge : PocketTtsBridge {
    override fun loadLibrary(): String? = PocketTtsNative.INSTANCE.load()
    override fun createEngine(threads: Int): Long = PocketTtsNative.nativeNew(threads)
    override fun configure(
        id: Long,
        temperature: Float,
        eosThreshold: Float,
        eosExtraFrames: Int,
        firstChunkFrames: Int,
        maxChunkFrames: Int,
        maxTokensPerChunk: Int,
        padShortInputs: Boolean,
        appendTerminalPunctuation: Boolean,
        voiceStateCache: Int
    ): Int = PocketTtsNative.nativeConfigure(
        id, temperature, eosThreshold, eosExtraFrames, firstChunkFrames,
        maxChunkFrames, maxTokensPerChunk, padShortInputs, appendTerminalPunctuation, voiceStateCache
    )
    override fun markReady(id: Long): Int = PocketTtsNative.nativeReady(id)
    override fun speak(id: Long, utf8: ByteArray, sink: PcmSink): Int = PocketTtsNative.nativeSpeak(id, utf8, sink)
    override fun cut(id: Long) = PocketTtsNative.nativeCut(id)
    override fun close(id: Long) = PocketTtsNative.nativeClose(id)
    override fun lastError(id: Long): String? = PocketTtsNative.nativeLastError(id)
    override fun info(id: Long): String? = PocketTtsNative.nativeInfo(id)
}

/**
 * Robust, maintainable Kotlin wrapper around the PocketTTS native engine.
 *
 * Guarantees:
 * 1. Safe non-crashing initialization and lifecycle management.
 * 2. Background thread execution avoiding main thread blocking.
 * 3. Graceful handling of native errors and cancellation.
 * 4. Full testability via dependency injection of [PocketTtsBridge].
 */
class PocketTtsWrapper(
    private val bridge: PocketTtsBridge = DefaultPocketTtsBridge,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : AutoCloseable {

    companion object {
        private const val TAG = "PocketTtsWrapper"
        const val DEFAULT_SAMPLE_RATE_HZ = 24000
    }

    private val engineId = AtomicLong(0L)
    private val isInitialized = AtomicBoolean(false)
    private val isSynthesizing = AtomicBoolean(false)

    /**
     * Initializes the native PocketTTS engine.
     *
     * @param threads Number of worker threads for neural vocoder synthesis (default 2).
     * @return [Result] containing the engine ID or the failure cause.
     */
    @Synchronized
    fun initialize(threads: Int = 2): Result<Long> {
        if (isInitialized.get() && engineId.get() != 0L) {
            return Result.success(engineId.get())
        }

        try {
            val loadErr = bridge.loadLibrary()
            if (!loadErr.isNullOrBlank()) {
                val err = "PocketTTS native library load failed: $loadErr"
                Log.w(TAG, err)
                return Result.failure(PocketTtsException(err))
            }

            val id = bridge.createEngine(threads.coerceAtLeast(1))
            if (id == 0L) {
                val err = "Failed to allocate native PocketTTS engine instance (nativeNew returned 0)"
                Log.w(TAG, err)
                return Result.failure(PocketTtsException(err))
            }

            bridge.configure(
                id = id,
                temperature = 0.3f,
                eosThreshold = -4.0f,
                eosExtraFrames = -1,
                firstChunkFrames = 1,
                maxChunkFrames = 1,
                maxTokensPerChunk = 120,
                padShortInputs = false,
                appendTerminalPunctuation = true,
                voiceStateCache = 6
            )

            bridge.markReady(id)

            engineId.set(id)
            isInitialized.set(true)
            Log.i(TAG, "PocketTTS engine successfully initialized (id=$id)")
            return Result.success(id)
        } catch (t: Throwable) {
            val err = "PocketTTS initialization exception: ${t.message}"
            Log.e(TAG, err, t)
            return Result.failure(PocketTtsException(err, t))
        }
    }

    /**
     * Returns true if the native engine is initialized and ready for synthesis.
     */
    fun isReady(): Boolean = isInitialized.get() && engineId.get() != 0L

    /**
     * Returns the active engine ID (or 0L if uninitialized).
     */
    fun getEngineId(): Long = engineId.get()

    /**
     * Synthesizes [text] into PCM audio stream via [onPcmChunk] callback.
     * Synthesis executes on [dispatcher] to prevent blocking the caller thread.
     *
     * @param text The input text to synthesize.
     * @param onPcmChunk Invoked with each PCM chunk. Return false to stop/cancel synthesis early.
     * @return [Result] containing the native status code (0 for success) or failure.
     */
    suspend fun synthesize(
        text: String,
        onPcmChunk: (buffer: ByteArray, length: Int) -> Boolean
    ): Result<Int> = withContext(dispatcher) {
        if (text.isBlank()) {
            return@withContext Result.success(0)
        }

        val id = engineId.get()
        if (!isInitialized.get() || id == 0L) {
            return@withContext Result.failure(PocketTtsException("PocketTTS engine is not initialized or ready"))
        }

        isSynthesizing.set(true)
        try {
            val utf8Bytes = text.toByteArray(StandardCharsets.UTF_8)
            val sink = PcmSink { buffer, length ->
                if (!isSynthesizing.get()) {
                    return@PcmSink false
                }
                if (buffer != null && length > 0) {
                    return@PcmSink onPcmChunk(buffer, length)
                }
                true
            }

            val status = bridge.speak(id, utf8Bytes, sink)
            if (status != 0) {
                val lastErr = bridge.lastError(id) ?: "status code $status"
                Log.w(TAG, "Native speak completed with status $status ($lastErr)")
            }
            Result.success(status)
        } catch (t: Throwable) {
            val err = "Native synthesis failed: ${t.message}"
            Log.e(TAG, err, t)
            Result.failure(PocketTtsException(err, t))
        } finally {
            isSynthesizing.set(false)
        }
    }

    /**
     * Immediately interrupts in-progress synthesis.
     */
    fun stop() {
        isSynthesizing.set(false)
        val id = engineId.get()
        if (id != 0L) {
            try {
                bridge.cut(id)
            } catch (t: Throwable) {
                Log.w(TAG, "Error cutting native synthesis: ${t.message}")
            }
        }
    }

    /**
     * Retrieves the last native engine error string, if any.
     */
    fun getLastError(): String? {
        val id = engineId.get()
        return if (id != 0L) bridge.lastError(id) else null
    }

    /**
     * Retrieves runtime information regarding the native engine state.
     */
    fun getEngineInfo(): String? {
        val id = engineId.get()
        return if (id != 0L) bridge.info(id) else null
    }

    /**
     * Releases native engine resources and resets the wrapper state.
     */
    @Synchronized
    override fun close() {
        stop()
        val id = engineId.getAndSet(0L)
        if (id != 0L) {
            try {
                bridge.close(id)
                Log.i(TAG, "PocketTTS native engine closed (id=$id)")
            } catch (t: Throwable) {
                Log.w(TAG, "Error closing native engine: ${t.message}")
            }
        }
        isInitialized.set(false)
    }
}
