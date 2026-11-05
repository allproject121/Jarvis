package com.jarvis.assistant.androllm.voice.asr

import android.util.Log
import com.jarvis.assistant.androllm.voice.model.VoiceModels
import com.jarvis.assistant.androllm.voice.voicecloud.GeminiApiKeyProvider
import com.jarvis.assistant.androllm.voice.voicecloud.GeminiVoiceClient
import kotlinx.coroutines.runBlocking

/**
 * OPTIONAL cloud speech recognizer backed by the Gemini REST `generateContent`
 * endpoint (ported from AndroLLM; Hilt/Timber removed).
 *
 * Not Gemini Live: Jarvis's Live WebSocket session is never used or modified. The
 * user's existing Gemini key is read (read-only) through [GeminiApiKeyProvider].
 *
 * Buffers audio for the current utterance and sends it at the endpoint (silence-based
 * turn end decided by the caller's VAD). No streaming partials. [finalText] blocks on
 * the network, so call it off the main thread (the session adapter uses Dispatchers.IO).
 */
class GeminiStreamingRecognizer(
    private val gemini: GeminiVoiceClient = GeminiVoiceClient.shared,
    private val language: String = "en"
) : StreamingSpeechRecognizer {

    private var buffer: FloatArray = FloatArray(0)
    private var lastPartial: String = ""
    private var finalText: String = ""

    override val isInitialized: Boolean get() = true

    override fun ensureInitialized(): Boolean = GeminiApiKeyProvider.isConfigured()

    override fun updateSilenceTimeout(seconds: Float) {
        // Endpointing is driven by the voice service's VAD, not the recognizer.
    }

    override fun startSession() {
        buffer = FloatArray(0)
        lastPartial = ""
        finalText = ""
    }

    override fun feed(samples: FloatArray): String {
        val merged = FloatArray(buffer.size + samples.size)
        System.arraycopy(buffer, 0, merged, 0, buffer.size)
        System.arraycopy(samples, 0, merged, buffer.size, samples.size)
        buffer = merged
        return lastPartial
    }

    override fun isEndpoint(): Boolean = false

    override fun finalText(): String {
        val apiKey = GeminiApiKeyProvider.get()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Gemini STT skipped - no API key configured")
            lastPartial = ""
            return ""
        }
        if (buffer.isEmpty()) return ""
        return try {
            val result = runBlocking {
                gemini.transcribe(apiKey = apiKey, language = language, samples = buffer, sampleRate = SAMPLE_RATE)
            }
            finalText = result.text
            lastPartial = result.text
            finalText
        } catch (e: Exception) {
            Log.w(TAG, "Gemini STT failed: ${e.message}", e)
            ""
        } finally {
            buffer = FloatArray(0)
        }
    }

    override fun reset() {
        buffer = FloatArray(0)
        lastPartial = ""
        finalText = ""
    }

    override fun release() {
        reset()
    }

    private companion object {
        const val TAG = "GeminiSTT"
        const val SAMPLE_RATE: Int = VoiceModels.SAMPLE_RATE
    }
}
