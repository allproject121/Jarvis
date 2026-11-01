package com.jarvis.assistant.androllm.voice.asr

/**
 * Provider-agnostic speech-to-text (ported from AndroLLM core/voice).
 *
 * The recognizer records a full utterance, then returns plain text. It never
 * knows which chat engine will receive the transcript.
 *
 * Implementations are expected to be safe to construct lazily; callers invoke
 * [ensureInitialized] before the first [startSession].
 */
interface SpeechRecognizer {

    /** True once the model is loaded and cached. */
    val isInitialized: Boolean

    /** Short human label of the engine, e.g. "whisper.cpp". */
    val engineLabel: String

    /** Loads (and caches) the selected model. Returns false when none is installed. */
    suspend fun ensureInitialized(): Boolean

    /** Opens a new recognition turn. Audio is fed chunk-by-chunk via [SpeechRecognitionSession.append]. */
    suspend fun startSession(
        language: String = "auto",
        translate: Boolean = false,
        numThreads: Int = -1,
        beamSize: Int = 1,
        temperature: Float = 0.0f,
        maxSeconds: Int = 30,
        streamingEnabled: Boolean = true
    ): SpeechRecognitionSession

    /** Releases all native resources; the engine must be reinitialized after. */
    suspend fun release()
}

/** A single recognition turn: accumulate audio, then finalize to text. */
interface SpeechRecognitionSession {

    /** Milliseconds of audio captured so far. */
    val elapsedMs: Long

    /** Number of float samples captured so far. */
    val sampleCount: Int

    /** True when the engine itself detected the end of the utterance (sherpa / Vosk endpointers). */
    val engineEndpoint: Boolean get() = false

    /** Appends one ~200 ms chunk of 16 kHz mono float audio. */
    fun append(samples: FloatArray)

    /**
     * Best-effort live transcript of what has been heard so far (streaming
     * partials). Returns an empty-text result when streaming is unsupported.
     */
    suspend fun partial(): SpeechRecognitionResult

    /** Transcribes the full captured utterance and returns the final result. */
    suspend fun finish(): SpeechRecognitionResult

    /** Drops the captured audio (no transcription). */
    fun cancel()

    /** Frees session-owned buffers. */
    fun release()
}

/** Result of one transcription. */
interface SpeechRecognitionResult {
    val text: String
    val confidence: Float?
    val durationMs: Long
    val inferenceMs: Long
    val language: String?
    val engine: String
}

/** Plain value implementation of [SpeechRecognitionResult]. */
data class SimpleRecognitionResult(
    override val text: String,
    override val confidence: Float? = null,
    override val durationMs: Long = 0L,
    override val inferenceMs: Long = 0L,
    override val language: String? = null,
    override val engine: String
) : SpeechRecognitionResult
