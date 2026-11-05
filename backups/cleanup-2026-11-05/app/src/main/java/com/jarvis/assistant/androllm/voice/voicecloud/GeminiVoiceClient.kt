package com.jarvis.assistant.androllm.voice.voicecloud

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Thin Google Gemini REST client used ONLY by the optional AndroLLM voice
 * assistant for speech-to-text and text-to-speech (ported from AndroLLM
 * core/cloud `GeminiVoiceClient`; Hilt/Timber removed).
 *
 * This is the public `generateContent` REST endpoint - it is completely separate
 * from Jarvis's Gemini Live WebSocket session, which is never touched. The API key
 * is passed per request (read via [GeminiApiKeyProvider]); this client holds no
 * credentials.
 *
 * Jarvis fix vs. upstream: AndroLLM sent raw PCM labelled `audio/wav`; the PCM is
 * now wrapped in a real 44-byte RIFF/WAV header so the declared MIME type matches.
 */
class GeminiVoiceClient {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        // Live STT/TTS requests are short and bounded; a hard cap keeps a hung
        // network from starving the assistant's main loop.
        .callTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Result of a [transcribe] call. */
    data class TranscriptResult(val text: String, val confidence: Float = 1.0f)

    /** Result of a [synthesize] call. */
    data class SynthesisResult(val samples: FloatArray, val sampleRate: Int) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is SynthesisResult &&
                other.sampleRate == sampleRate &&
                other.samples.contentEquals(samples))

        override fun hashCode(): Int = 31 * sampleRate + samples.contentHashCode()
    }

    /**
     * Transcribes a mono 16 kHz float buffer to text via Gemini's multimodal
     * `generateContent` endpoint. Throws [GeminiVoiceException] on failure.
     */
    suspend fun transcribe(
        apiKey: String,
        model: String = DEFAULT_STT_MODEL,
        language: String = "en",
        samples: FloatArray,
        sampleRate: Int = 16_000
    ): TranscriptResult = withContext(Dispatchers.IO) {
        require(samples.isNotEmpty()) { "no audio to transcribe" }
        require(apiKey.isNotBlank()) { "Gemini API key is missing" }

        val wav = wrapWav(floatToPcm16Le(samples), sampleRate)
        val audioBase64 = Base64.encodeToString(wav, Base64.NO_WRAP)
        val prompt = if (language.isBlank() || language == "auto") TRANSCRIBE_PROMPT
        else "$TRANSCRIBE_PROMPT The expected language is '$language'."

        val body = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(JsonObject(mapOf("text" to JsonPrimitive(prompt))))
                })
            })
            put("contents", buildJsonArray {
                add(JsonObject(mapOf(
                    "role" to JsonPrimitive("user"),
                    "parts" to buildJsonArray {
                        add(JsonObject(mapOf(
                            "inline_data" to JsonObject(mapOf(
                                "mime_type" to JsonPrimitive("audio/wav"),
                                "data" to JsonPrimitive(audioBase64)
                            ))
                        )))
                    }
                )))
            })
            put("generationConfig", buildJsonObject {
                put("temperature", JsonPrimitive(0))
                put("maxOutputTokens", JsonPrimitive(256))
            })
        }

        val request = Request.Builder()
            .url("$BASE_URL/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "STT ${response.code}: ${responseBody.take(400)}")
                throw GeminiVoiceException("STT HTTP ${response.code}: ${responseBody.take(200)}")
            }
            TranscriptResult(text = (extractText(responseBody) ?: "").trim())
        }
    }

    /**
     * Synthesizes [text] into float PCM using Gemini TTS (24 kHz mono PCM16 by
     * default, decoded from the response's `inlineData`).
     */
    suspend fun synthesize(
        apiKey: String,
        model: String = DEFAULT_TTS_MODEL,
        voiceName: String = DEFAULT_TTS_VOICE,
        text: String
    ): SynthesisResult = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "nothing to synthesize" }
        require(apiKey.isNotBlank()) { "Gemini API key is missing" }

        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(JsonObject(mapOf(
                    "role" to JsonPrimitive("user"),
                    "parts" to buildJsonArray {
                        add(JsonObject(mapOf("text" to JsonPrimitive(text))))
                    }
                )))
            })
            put("generationConfig", buildJsonObject {
                put("response_modalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                put("speech_config", buildJsonObject {
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject {
                            put("voiceName", JsonPrimitive(voiceName))
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url("$BASE_URL/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "TTS ${response.code}: ${responseBody.take(400)}")
                throw GeminiVoiceException("TTS HTTP ${response.code}: ${responseBody.take(200)}")
            }
            val (base64Pcm, sampleRate) = extractAudio(responseBody)
                ?: throw GeminiVoiceException("TTS response had no audio data")
            val pcm = Base64.decode(base64Pcm, Base64.DEFAULT)
            SynthesisResult(samples = pcm16LeToFloat(pcm), sampleRate = sampleRate)
        }
    }

    private fun extractText(jsonBody: String): String? {
        val obj = runCatching { json.parseToJsonElement(jsonBody).jsonObject }.getOrNull() ?: return null
        val candidates = obj["candidates"]?.jsonArray ?: return null
        for (candidate in candidates) {
            val content = candidate.jsonObject["content"]?.jsonObject ?: continue
            val parts = content["parts"]?.jsonArray ?: continue
            for (part in parts) {
                val text = part.jsonObject["text"]?.jsonPrimitive?.content
                if (!text.isNullOrBlank()) return text
            }
        }
        return null
    }

    private fun extractAudio(jsonBody: String): Pair<String, Int>? {
        val obj = runCatching { json.parseToJsonElement(jsonBody).jsonObject }.getOrNull() ?: return null
        val candidates = obj["candidates"]?.jsonArray ?: return null
        for (candidate in candidates) {
            val content = candidate.jsonObject["content"]?.jsonObject ?: continue
            val parts = content["parts"]?.jsonArray ?: continue
            for (part in parts) {
                val inline = part.jsonObject["inlineData"]?.jsonObject
                    ?: part.jsonObject["inline_data"]?.jsonObject
                    ?: continue
                val data = inline["data"]?.jsonPrimitive?.content ?: continue
                val mime = inline["mimeType"]?.jsonPrimitive?.content
                    ?: inline["mime_type"]?.jsonPrimitive?.content
                    ?: ""
                val sampleRate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull()
                    ?: DEFAULT_TTS_SAMPLE_RATE
                return data to sampleRate
            }
        }
        return null
    }

    private fun floatToPcm16Le(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var j = 0
        for (sample in samples) {
            val v = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
            out[j] = (v and 0xff).toByte()
            out[j + 1] = ((v ushr 8) and 0xff).toByte()
            j += 2
        }
        return out
    }

    private fun pcm16LeToFloat(pcm: ByteArray): FloatArray {
        val out = FloatArray(pcm.size / 2)
        var i = 0
        for (j in out.indices) {
            val lo = pcm[i].toInt() and 0xff
            val hi = pcm[i + 1].toInt()
            val v = (hi shl 8) or lo
            val signed = if (v >= 0x8000) v - 0x10000 else v
            out[j] = signed / 32767f
            i += 2
        }
        return out
    }

    /** Prepends a canonical 44-byte PCM16 mono RIFF/WAVE header. */
    private fun wrapWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)            // PCM fmt chunk size
        header.putShort(1)           // audio format = PCM
        header.putShort(1)           // channels = mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // byte rate
        header.putShort(2)           // block align
        header.putShort(16)          // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return header.array() + pcm
    }

    companion object {
        private const val TAG = "GeminiVoice"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_STT_MODEL = "gemini-2.5-flash"
        const val DEFAULT_TTS_MODEL = "gemini-2.5-flash-preview-tts"
        const val DEFAULT_TTS_VOICE = "Kore"
        private const val DEFAULT_TTS_SAMPLE_RATE = 24_000
        private const val TRANSCRIBE_PROMPT =
            "You are a speech-to-text transcriber. Transcribe the user's audio " +
                "exactly as spoken. Do not translate, summarize, or answer — " +
                "return only the literal transcript text."
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Shared instance (the client is stateless apart from its OkHttp pool). */
        val shared: GeminiVoiceClient by lazy { GeminiVoiceClient() }
    }
}

class GeminiVoiceException(message: String) : RuntimeException(message)
