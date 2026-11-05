package com.jarvis.assistant.maya.brain

import org.json.JSONObject

/** One parsed Deepgram streaming message. */
data class DeepgramMessage(
    val type: String,
    val transcript: String,
    val isFinal: Boolean,
    val speechFinal: Boolean,
    val confidence: Double,
    /** Error/metadata text for non-result messages. */
    val detail: String = ""
) {
    val isResult: Boolean get() = type == "Results"
    val isUtteranceEnd: Boolean get() = type == "UtteranceEnd"
    val isError: Boolean get() = type == "Error"
}

/**
 * Pure parser for Deepgram `wss://api.deepgram.com/v1/listen` JSON frames
 * (no Android imports so unit tests run on the JVM).
 */
object DeepgramJson {

    fun parse(raw: String): DeepgramMessage? {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val type = obj.optString("type", "")
        return when (type) {
            "Results" -> {
                val alt = obj.optJSONObject("channel")
                    ?.optJSONArray("alternatives")
                    ?.optJSONObject(0)
                DeepgramMessage(
                    type = type,
                    transcript = alt?.optString("transcript", "").orEmpty().trim(),
                    isFinal = obj.optBoolean("is_final", false),
                    speechFinal = obj.optBoolean("speech_final", false),
                    confidence = alt?.optDouble("confidence", 0.0) ?: 0.0
                )
            }
            "UtteranceEnd" -> DeepgramMessage(type, "", isFinal = true, speechFinal = true, confidence = 0.0)
            "Error" -> DeepgramMessage(
                type, "", isFinal = false, speechFinal = false, confidence = 0.0,
                detail = obj.optString("description", obj.optString("message", obj.optString("error", raw.take(200))))
            )
            else -> DeepgramMessage(type.ifBlank { "Unknown" }, "", isFinal = false, speechFinal = false, confidence = 0.0, detail = raw.take(200))
        }
    }

    /** Builds the listen URL exactly like Maya (nova-3, multi, linear16 @16k, smart_format, interim, endpointing). */
    fun listenUrl(endpointingMs: Int = 300, model: String = "nova-3", language: String = "multi"): String =
        "wss://api.deepgram.com/v1/listen?model=$model&language=$language&encoding=linear16&sample_rate=16000" +
            "&smart_format=true&interim_results=true&endpointing=$endpointingMs&vad_events=true&utterance_end_ms=1000"

    /** Float [-1,1] mono samples → little-endian PCM16 bytes. */
    fun floatToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var j = 0
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            out[j++] = (v and 0xFF).toByte()
            out[j++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
