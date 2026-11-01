package com.jarvis.assistant.androllm.voice.asr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exposes any sample-by-sample [StreamingSpeechRecognizer] (sherpa, Vosk, Gemini REST)
 * through the turn-based [SpeechRecognizer] contract the voice assistant service
 * uses, so every engine plugs into the same wake → listen → finish loop as
 * whisper.cpp.
 *
 * All decoding happens on the caller's thread for [SpeechRecognitionSession.append]
 * (cheap streaming decode), and [SpeechRecognitionSession.finish] runs on
 * [Dispatchers.IO] because some engines (Gemini REST) block on the network there.
 */
class StreamingRecognizerAdapter(
    private val delegate: StreamingSpeechRecognizer,
    override val engineLabel: String
) : SpeechRecognizer {

    override val isInitialized: Boolean get() = delegate.isInitialized

    override suspend fun ensureInitialized(): Boolean =
        withContext(Dispatchers.IO) { delegate.ensureInitialized() }

    override suspend fun startSession(
        language: String,
        translate: Boolean,
        numThreads: Int,
        beamSize: Int,
        temperature: Float,
        maxSeconds: Int,
        streamingEnabled: Boolean
    ): SpeechRecognitionSession {
        check(delegate.isInitialized) { "$engineLabel not initialized" }
        delegate.startSession()
        return Session()
    }

    override suspend fun release() {
        withContext(Dispatchers.IO) { delegate.release() }
    }

    private inner class Session : SpeechRecognitionSession {
        private val lock = Any()
        private val startedAt = System.nanoTime()
        @Volatile private var samples = 0
        @Volatile private var latest = ""
        @Volatile private var cancelled = false

        override val elapsedMs: Long get() = (System.nanoTime() - startedAt) / 1_000_000
        override val sampleCount: Int get() = samples

        /** True once the engine flagged its own endpoint (sherpa / Vosk). */
        override val engineEndpoint: Boolean get() = synchronized(lock) { delegate.isEndpoint() }

        override fun append(samples: FloatArray) {
            if (cancelled) return
            synchronized(lock) {
                latest = delegate.feed(samples)
                this.samples += samples.size
            }
        }

        override suspend fun partial(): SpeechRecognitionResult =
            SimpleRecognitionResult(
                text = latest,
                durationMs = samples * 1000L / SAMPLE_RATE,
                engine = engineLabel
            )

        override suspend fun finish(): SpeechRecognitionResult {
            if (cancelled) return SimpleRecognitionResult(text = "", engine = engineLabel)
            val t0 = System.nanoTime()
            val (text, confidence) = withContext(Dispatchers.IO) {
                synchronized(lock) {
                    val finalText = delegate.finalText().ifBlank { latest }.trim()
                    val conf = delegate.estimatedConfidence().takeIf { it > 0f }
                    delegate.reset()
                    finalText to conf
                }
            }
            return SimpleRecognitionResult(
                text = text,
                confidence = confidence,
                durationMs = samples * 1000L / SAMPLE_RATE,
                inferenceMs = (System.nanoTime() - t0) / 1_000_000,
                engine = engineLabel
            )
        }

        override fun cancel() {
            cancelled = true
            synchronized(lock) { delegate.reset() }
        }

        override fun release() {
            cancelled = true
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
