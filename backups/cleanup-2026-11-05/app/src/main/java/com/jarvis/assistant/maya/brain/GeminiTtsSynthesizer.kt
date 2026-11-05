package com.jarvis.assistant.maya.brain

import android.content.Context
import android.util.Base64
import android.util.Log
import com.jarvis.assistant.androllm.voice.tts.SpeechSynthesizer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini TTS over REST `generateContent` (model `gemini-2.5-flash-preview-tts`,
 * `responseModalities: ["AUDIO"]`, `prebuiltVoiceConfig.voiceName`). Returns 24 kHz float PCM.
 * Key: BrainSettings.resolvedGeminiTtsKey() (own TTS key → brain Gemini key → Jarvis key).
 * Blocking; call off the main thread.
 */
class GeminiTtsSynthesizer(
    private val context: Context,
    private val keyProvider: () -> String = { BrainSettings.get(context).resolvedGeminiTtsKey() },
    private val voiceProvider: () -> String = { BrainSettings.get(context).ttsVoice.ifBlank { DEFAULT_VOICE } },
    private val modelProvider: () -> String = { BrainSettings.get(context).geminiTtsModel }
) : SpeechSynthesizer {

    companion object {
        private const val TAG = "GeminiTTS"
        const val DEFAULT_MODEL = "gemini-2.5-flash-preview-tts"
        const val DEFAULT_VOICE = "Kore"
        val VOICES = listOf(
            "Kore", "Puck", "Charon", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr", "Achird", "Algenib",
            "Algieba", "Alnilam", "Autonoe", "Callirrhoe", "Despina", "Enceladus", "Erinome", "Gacrux",
            "Iapetus", "Laomedeia", "Pulcherrima", "Rasalgethi", "Sadachbia", "Sadaltager", "Schedar",
            "Sulafat", "Umbriel", "Vindemiatrix", "Zubenelgenubi"
        )
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** `audio/L16;codec=pcm;rate=24000` → 24000 */
        fun sampleRateFromMime(mime: String?): Int =
            Regex("rate=(\\d+)").find(mime.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 24_000

        fun pcm16ToFloat(bytes: ByteArray): FloatArray {
            val n = bytes.size / 2
            val out = FloatArray(n)
            var j = 0
            for (i in 0 until n) {
                val lo = bytes[j].toInt() and 0xFF
                val hi = bytes[j + 1].toInt()
                out[i] = ((hi shl 8) or lo).toShort() / 32768f
                j += 2
            }
            return out
        }
    }

    @Volatile private var lastRate = 24_000

    /** Last failure explanation (Maya-style). */
    @Volatile var lastError: String? = null
        private set

    override val isInitialized: Boolean get() = true
    override val sampleRate: Int get() = lastRate

    override fun ensureInitialized(): Boolean = keyProvider().isNotBlank()

    override fun synthesize(text: String, speed: Float): FloatArray? {
        if (text.isBlank()) return null
        val key = keyProvider()
        if (key.isBlank()) {
            lastError = "Gemini TTS ke liye API key nahi hai — Settings → Voice engine → Voice me key daalo."
            return null
        }
        val model = modelProvider().ifBlank { DEFAULT_MODEL }
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            }))
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig",
                    JSONObject().put("voiceName", voiceProvider().ifBlank { DEFAULT_VOICE }))))
            })
        }
        val req = Request.Builder()
            .url("$BASE/models/$model:generateContent")
            .addHeader("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON))
            .build()
        return try {
            BrainHttpClient.http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    lastError = when (resp.code) {
                        400, 404 -> "Gemini TTS ka ye model nahi chala — Settings → Voice engine → Voice me doosra model chuno."
                        401, 403 -> "Gemini TTS ki key reject ho gayi — Settings → Voice engine → Voice me sahi key daalo."
                        429 -> "Gemini TTS ka quota khatam — thodi der baad, ya doosri voice engine chuno."
                        else -> "Gemini TTS HTTP ${resp.code}: ${BrainJson.extractErrorMessage(raw).take(120)}"
                    }
                    Log.w(TAG, "TTS ${resp.code}: ${raw.take(300)}")
                    return null
                }
                val (b64, mime) = extractAudio(JSONObject(raw)) ?: run {
                    lastError = "Gemini TTS ne audio nahi bheja."
                    return null
                }
                lastRate = sampleRateFromMime(mime)
                lastError = null
                pcm16ToFloat(Base64.decode(b64, Base64.DEFAULT))
            }
        } catch (e: Exception) {
            lastError = "Gemini TTS fail: ${e.message}"
            Log.w(TAG, "TTS error: ${e.message}")
            null
        }
    }

    private fun extractAudio(root: JSONObject): Pair<String, String>? {
        val candidates = root.optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val parts = candidates.optJSONObject(i)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val inline = parts.optJSONObject(j)?.let { it.optJSONObject("inlineData") ?: it.optJSONObject("inline_data") } ?: continue
                val data = inline.optString("data", "")
                if (data.isNotBlank()) return data to inline.optString("mimeType", inline.optString("mime_type", ""))
            }
        }
        return null
    }

    override fun release() {
        // Stateless.
    }
}
