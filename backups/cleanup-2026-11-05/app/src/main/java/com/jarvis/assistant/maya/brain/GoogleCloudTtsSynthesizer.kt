package com.jarvis.assistant.maya.brain

import android.content.Context
import android.util.Base64
import android.util.Log
import com.jarvis.assistant.androllm.voice.tts.SpeechSynthesizer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Google Cloud Text-to-Speech (`texttospeech.googleapis.com/v1/text:synthesize`, LINEAR16).
 * Returns float PCM at [sampleRate] (24 kHz requested; the WAV header is parsed so the
 * actual rate wins). Key from BrainSettings.googleCloudTtsKey (API key, `?key=`).
 */
class GoogleCloudTtsSynthesizer(
    private val context: Context,
    private val keyProvider: () -> String = { BrainSettings.get(context).googleCloudTtsKey },
    private val voiceProvider: () -> String = { BrainSettings.get(context).ttsVoice.ifBlank { DEFAULT_VOICE } }
) : SpeechSynthesizer {

    companion object {
        private const val TAG = "GoogleCloudTTS"
        const val DEFAULT_VOICE = "en-IN-Neural2-A"
        private const val URL = "https://texttospeech.googleapis.com/v1/text:synthesize"
        private const val REQUESTED_RATE = 24_000
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Language code from a Cloud voice name like `hi-IN-Wavenet-D` → `hi-IN`. */
        fun languageCodeOf(voice: String): String {
            val parts = voice.split('-')
            return if (parts.size >= 2) "${parts[0]}-${parts[1]}" else "en-IN"
        }

        /**
         * Strips a RIFF/WAVE header if present and returns (pcm16 bytes, sample rate).
         * LINEAR16 from Cloud TTS is a WAV container.
         */
        fun unwrapWav(bytes: ByteArray, fallbackRate: Int): Pair<ByteArray, Int> {
            if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return bytes to fallbackRate
            var rate = fallbackRate
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = le32(bytes, pos + 4)
                if (id == "fmt " && pos + 16 <= bytes.size) rate = le32(bytes, pos + 12)
                if (id == "data") {
                    val start = pos + 8
                    val end = (start + size).coerceAtMost(bytes.size)
                    return bytes.copyOfRange(start, end) to rate
                }
                pos += 8 + size + (size and 1)
            }
            return bytes.copyOfRange(44, bytes.size) to rate
        }

        private fun le32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
    }

    @Volatile private var lastRate = REQUESTED_RATE

    @Volatile var lastError: String? = null
        private set

    override val isInitialized: Boolean get() = true
    override val sampleRate: Int get() = lastRate

    override fun ensureInitialized(): Boolean = keyProvider().isNotBlank()

    override fun synthesize(text: String, speed: Float): FloatArray? {
        if (text.isBlank()) return null
        val key = keyProvider()
        if (key.isBlank()) {
            lastError = "Google Cloud TTS ke liye API key nahi hai — Settings → Voice engine → Voice me key daalo."
            return null
        }
        val voice = voiceProvider().ifBlank { DEFAULT_VOICE }
        val body = JSONObject().apply {
            put("input", JSONObject().put("text", text))
            put("voice", JSONObject().put("languageCode", languageCodeOf(voice)).put("name", voice))
            put("audioConfig", JSONObject().apply {
                put("audioEncoding", "LINEAR16")
                put("sampleRateHertz", REQUESTED_RATE)
                put("speakingRate", speed.coerceIn(0.25f, 4.0f).toDouble())
            })
        }
        val req = Request.Builder()
            .url("$URL?key=$key")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return try {
            BrainHttpClient.http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    lastError = when (resp.code) {
                        400 -> "Google Cloud TTS ne voice '$voice' reject ki — Settings → Voice engine → Voice me sahi voice name daalo."
                        401, 403 -> "Google Cloud TTS ki key reject ho gayi (Cloud TTS API enable hai?) — Settings → Voice engine → Voice me check karo."
                        429 -> "Google Cloud TTS ka quota khatam — thodi der baad, ya doosri voice engine chuno."
                        else -> "Google Cloud TTS HTTP ${resp.code}: ${BrainJson.extractErrorMessage(raw).take(120)}"
                    }
                    Log.w(TAG, "TTS ${resp.code}: ${raw.take(300)}")
                    return null
                }
                val b64 = JSONObject(raw).optString("audioContent", "")
                if (b64.isBlank()) {
                    lastError = "Google Cloud TTS ne audio nahi bheja."
                    return null
                }
                val (pcm, rate) = unwrapWav(Base64.decode(b64, Base64.DEFAULT), REQUESTED_RATE)
                lastRate = rate
                lastError = null
                GeminiTtsSynthesizer.pcm16ToFloat(pcm)
            }
        } catch (e: Exception) {
            lastError = "Google Cloud TTS fail: ${e.message}"
            Log.w(TAG, "TTS error: ${e.message}")
            null
        }
    }

    override fun release() {
        // Stateless.
    }
}
