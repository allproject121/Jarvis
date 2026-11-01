package com.jarvis.assistant.androllm.voice.whisper

import android.content.Context
import android.util.Log
import com.jarvis.assistant.androllm.voice.VoiceSettingsStore
import com.jarvis.assistant.androllm.voice.asr.SimpleRecognitionResult
import com.jarvis.assistant.androllm.voice.asr.SpeechRecognitionResult
import com.jarvis.assistant.androllm.voice.asr.SpeechRecognitionSession
import com.jarvis.assistant.androllm.voice.asr.SpeechRecognizer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "WhisperSTT"
private const val SAMPLE_RATE = 16000

/** Sliding window length (ms) used for live partial transcripts. */
private const val PARTIAL_WINDOW_MS = 3000L
/** Minimum audio before a partial is attempted. */
private const val PARTIAL_MIN_MS = 600L

/**
 * whisper.cpp speech recognizer (ported from AndroLLM; Hilt/Timber removed).
 *
 * Records the full utterance, then transcribes it offline. When streaming is
 * enabled, live partials re-transcribe a sliding window of the captured audio (the
 * official `whisper-stream` approach). The loaded model context is cached and
 * reused across turns; a changed model selection reloads it.
 *
 * Requires `libwhisper.so` (app/src/main/cpp, see the gradle snippet in the merge
 * report); without it [ensureInitialized] returns false and the assistant reports it.
 */
class WhisperSpeechRecognizer(
    private val context: Context,
    private val modelManager: WhisperModelManager = WhisperModelManager.get(context),
    private val settingsStore: VoiceSettingsStore = VoiceSettingsStore.get(context)
) : SpeechRecognizer {

    @Volatile private var loadedContext: WhisperContext? = null
    @Volatile private var loadedModelPath: String? = null
    private val loadMutex = Mutex()

    override val engineLabel: String get() = "whisper.cpp"

    override val isInitialized: Boolean get() = loadedContext?.isReady == true

    /** The whisper model file currently loaded (for debug UI). */
    val activeModelFile: File? get() = loadedModelPath?.let { File(it) }

    override suspend fun ensureInitialized(): Boolean = loadMutex.withLock {
        val settings = settingsStore.current()
        val file = resolveModelFile(settings.whisperModel)
        if (file == null) {
            Log.w(TAG, "no whisper model installed")
            return@withLock false
        }
        loadedContext?.let { ctx ->
            if (ctx.isReady && loadedModelPath == file.absolutePath) return@withLock true
            // Model selection changed: unload the previous context first.
            runCatching { ctx.release() }
            loadedContext = null
            loadedModelPath = null
        }
        if (!WhisperNative.loaded) {
            Log.w(TAG, "libwhisper.so is not packaged in this build")
            return@withLock false
        }
        try {
            val ctx = withContext(Dispatchers.IO) { WhisperContext.fromFile(file.absolutePath) }
            loadedContext = ctx
            loadedModelPath = file.absolutePath
            Log.i(TAG, "loaded ${file.name} (${file.length() / (1024 * 1024)} MB)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "model init failed", t)
            false
        }
    }

    /** Picks the requested model if installed, else any installed model, else null. */
    private fun resolveModelFile(selectedId: String): File? {
        WhisperModels.byId(selectedId)?.let { if (modelManager.isInstalled(it)) return modelManager.modelFile(it) }
        return modelManager.installedModels().firstOrNull()?.let { modelManager.modelFile(it) }
    }

    override suspend fun startSession(
        language: String,
        translate: Boolean,
        numThreads: Int,
        beamSize: Int,
        temperature: Float,
        maxSeconds: Int,
        streamingEnabled: Boolean
    ): SpeechRecognitionSession {
        val ctx = loadedContext ?: throw IllegalStateException("Whisper not initialized")
        val threads = if (numThreads > 0) numThreads else WhisperCpuConfig.preferredThreadCount
        return WhisperSession(
            context = ctx,
            params = WhisperParams(
                language = language,
                translate = translate,
                numThreads = threads,
                beamSize = beamSize,
                temperature = temperature
            ),
            maxSeconds = maxSeconds,
            streamingEnabled = streamingEnabled
        )
    }

    override suspend fun release() {
        loadMutex.withLock {
            loadedContext?.let { runCatching { it.release() } }
            loadedContext = null
            loadedModelPath = null
        }
    }

    /**
     * One recording turn. Audio chunks are appended on the caller thread while
     * transcription (final or partial) runs on whisper's single dispatcher.
     */
    private class WhisperSession(
        private val context: WhisperContext,
        private val params: WhisperParams,
        private val maxSeconds: Int,
        private val streamingEnabled: Boolean
    ) : SpeechRecognitionSession {

        private val lock = Any()
        private var samples = FloatArray(SAMPLE_RATE * 4)
        private var size = 0
        private val startedAt = System.nanoTime()
        @Volatile private var cancelled = false

        /** True while a sliding-window partial transcription is running. */
        @Volatile private var partialRunning: Boolean = false

        private val maxSamples = SAMPLE_RATE * maxSeconds.coerceAtLeast(1)

        override val elapsedMs: Long get() = (System.nanoTime() - startedAt) / 1_000_000
        override val sampleCount: Int get() = synchronized(lock) { size }

        override fun append(samples: FloatArray) {
            if (cancelled) return
            synchronized(lock) {
                val room = maxSamples - size
                if (room <= 0) return
                val n = minOf(room, samples.size)
                if (size + n > this.samples.size) {
                    this.samples = this.samples.copyOf(maxOf(size + n, this.samples.size * 2).coerceAtMost(maxSamples))
                }
                System.arraycopy(samples, 0, this.samples, size, n)
                size += n
            }
        }

        override fun cancel() {
            cancelled = true
            synchronized(lock) { size = 0 }
        }

        override fun release() {
            cancel()
        }

        override suspend fun partial(): SpeechRecognitionResult {
            if (!streamingEnabled || partialRunning) return emptyResult()
            val snapshot = snapshotTail(PARTIAL_WINDOW_MS)
            if (snapshot.size < (SAMPLE_RATE * PARTIAL_MIN_MS / 1000).toInt()) return emptyResult()
            partialRunning = true
            try {
                val t0 = System.nanoTime()
                val transcription = context.transcribe(snapshot, params.copy(noContext = true))
                return SimpleRecognitionResult(
                    text = transcription.text,
                    durationMs = (snapshot.size * 1000L) / SAMPLE_RATE,
                    inferenceMs = (System.nanoTime() - t0) / 1_000_000,
                    engine = "whisper.cpp"
                )
            } finally {
                partialRunning = false
            }
        }

        override suspend fun finish(): SpeechRecognitionResult {
            val full = synchronized(lock) { samples.copyOf(size) }
            val trimmed = trimSilence(full)
            if (trimmed.isEmpty()) return emptyResult()
            val t0 = System.nanoTime()
            val transcription = context.transcribe(trimmed, params)
            val inferenceMs = (System.nanoTime() - t0) / 1_000_000
            val durationMs = (trimmed.size * 1000L) / SAMPLE_RATE
            Log.i(
                TAG,
                "transcribed ${durationMs / 1000f}s (${full.size - trimmed.size} trimmed) in ${inferenceMs}ms -> '${transcription.text.take(80)}'"
            )
            return SimpleRecognitionResult(
                text = transcription.text,
                durationMs = durationMs,
                inferenceMs = inferenceMs,
                engine = "whisper.cpp"
            )
        }

        /**
         * Drops leading/trailing near-silence so whisper decodes the actual utterance
         * (silence padding slows inference and invites hallucinations).
         */
        private fun trimSilence(data: FloatArray): FloatArray {
            val threshold = 0.008f
            var start = 0
            while (start < data.size && kotlin.math.abs(data[start]) < threshold) start++
            var end = data.size
            while (end > start && kotlin.math.abs(data[end - 1]) < threshold) end--
            if (end <= start) return FloatArray(0)
            // Keep a small speech margin at both edges (80 ms).
            start = (start - SAMPLE_RATE / 12).coerceAtLeast(0)
            end = (end + SAMPLE_RATE / 12).coerceAtMost(data.size)
            return data.copyOfRange(start, end)
        }

        private fun emptyResult(): SpeechRecognitionResult = SimpleRecognitionResult(text = "", engine = "whisper.cpp")

        /** Last [windowMs] of audio (for sliding-window partials). */
        private fun snapshotTail(windowMs: Long): FloatArray {
            val count = (windowMs * SAMPLE_RATE / 1000).toInt()
            synchronized(lock) {
                val start = (size - count).coerceAtLeast(0)
                return samples.copyOfRange(start, size)
            }
        }
    }
}
