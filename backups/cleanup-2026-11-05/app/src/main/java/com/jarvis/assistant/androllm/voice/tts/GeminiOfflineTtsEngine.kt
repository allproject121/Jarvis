package com.jarvis.assistant.androllm.voice.tts

import android.util.Log
import com.jarvis.assistant.androllm.voice.voicecloud.GeminiApiKeyProvider
import com.jarvis.assistant.androllm.voice.voicecloud.GeminiVoiceClient
import kotlinx.coroutines.runBlocking

/**
 * OPTIONAL cloud text-to-speech via the Gemini REST `generateContent` endpoint
 * (ported from AndroLLM; Hilt/Timber removed). Despite the upstream name it is not
 * offline - it implements the [OfflineTtsEngine]/[SpeechSynthesizer] contracts so
 * it plugs into the same sentence-by-sentence pipeline.
 *
 * Never Gemini Live: the Live WebSocket session is not used or modified. The user's
 * existing Gemini key is read (read-only) through [GeminiApiKeyProvider].
 * [synthesize] blocks on the network - call it off the main thread.
 */
class GeminiOfflineTtsEngine(
    private val gemini: GeminiVoiceClient = GeminiVoiceClient.shared,
    @Volatile var voiceName: String = GeminiVoiceClient.DEFAULT_TTS_VOICE
) : OfflineTtsEngine, SpeechSynthesizer {

    override val isInitialized: Boolean get() = true

    override fun ensureInitialized(): Boolean = GeminiApiKeyProvider.isConfigured()

    @Volatile private var lastSampleRate: Int? = null

    override val sampleRate: Int
        get() = lastSampleRate ?: 24_000

    override fun synthesize(text: String, speed: Float): FloatArray? {
        if (text.isBlank()) return null
        val apiKey = GeminiApiKeyProvider.get()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Gemini TTS skipped - no API key configured")
            return null
        }
        return runCatching {
            runBlocking {
                val result = gemini.synthesize(apiKey = apiKey, voiceName = voiceName, text = text)
                lastSampleRate = result.sampleRate
                result.samples
            }
        }.onFailure { Log.w(TAG, "Gemini TTS failed: ${it.message}", it) }
            .getOrNull()
    }

    override fun release() {
        // The Gemini client is a shared stateless instance; nothing to release.
    }

    private companion object {
        const val TAG = "GeminiTTS"
    }
}
