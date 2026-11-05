package com.jarvis.assistant.maya.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Small "look at this picture" helper: sends an inline JPEG to Gemini generateContent
 * (falls back to OpenAI gpt-4o-mini when only an OpenAI key is configured) and returns the
 * model's text answer. Used by viewer_describe, look_through_camera, look_at_file and the
 * notification-free parts of commentary.
 */
object VisionDescribe {

    private const val TAG = "VisionDescribe"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    const val DEFAULT_QUESTION = "Describe what is on the screen right now. Be specific, concise and helpful."

    fun jpegBytes(bitmap: Bitmap, maxDim: Int = 1024, quality: Int = 80): ByteArray {
        val scale = (maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== bitmap) scaled.recycle()
        return out.toByteArray()
    }

    suspend fun describe(context: Context, jpeg: ByteArray, question: String?, systemHint: String? = null): String =
        withContext(Dispatchers.IO) {
            val q = question?.trim().orEmpty().ifBlank { DEFAULT_QUESTION }
            val geminiKey = runCatching { JarvisApp.instance.preferences.apiKey }.getOrDefault("")
            if (geminiKey.isNotBlank()) {
                try {
                    return@withContext viaGemini(geminiKey, jpeg, q, systemHint)
                } catch (e: Exception) {
                    Log.w(TAG, "Gemini vision failed: ${e.message}")
                }
            }
            val openAiKey = runCatching { JarvisApp.instance.preferences.openAiApiKey }.getOrDefault("")
                .ifBlank { MediaPrefs.get(context).openAiImageKey }
            if (openAiKey.isNotBlank()) {
                try {
                    return@withContext viaOpenAi(openAiKey, jpeg, q, systemHint)
                } catch (e: Exception) {
                    Log.w(TAG, "OpenAI vision failed: ${e.message}")
                }
            }
            throw IllegalStateException("No vision-capable API key configured (add a Gemini key in Settings → API connection).")
        }

    suspend fun describeBitmap(context: Context, bitmap: Bitmap, question: String?, systemHint: String? = null): String =
        describe(context, jpegBytes(bitmap), question, systemHint)

    suspend fun describeFile(context: Context, path: String, question: String?): String {
        val bmp = BitmapFactory.decodeFile(path) ?: throw IllegalArgumentException("Not a readable image: $path")
        return try { describeBitmap(context, bmp, question) } finally { bmp.recycle() }
    }

    private fun viaGemini(key: String, jpeg: ByteArray, question: String, systemHint: String?): String {
        val models = com.jarvis.assistant.ai.GeminiTextModels.candidates(key, null)
        var last: Exception? = null
        for (model in models) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
                val body = JSONObject().apply {
                    if (!systemHint.isNullOrBlank()) {
                        put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemHint))))
                    }
                    put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().apply {
                        put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
                        put(JSONObject().put("text", question))
                    })))
                    put("generationConfig", JSONObject().put("temperature", 0.4).put("maxOutputTokens", 700))
                }
                val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(160)}")
                    val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        ?: throw IllegalStateException("No candidates")
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text", "").orEmpty())
                    val answer = sb.toString().trim()
                    if (answer.isBlank()) throw IllegalStateException("Empty answer")
                    return answer
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Gemini vision failed")
    }

    private fun viaOpenAi(key: String, jpeg: ByteArray, question: String, systemHint: String?): String {
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val messages = JSONArray()
        if (!systemHint.isNullOrBlank()) messages.put(JSONObject().put("role", "system").put("content", systemHint))
        messages.put(JSONObject().put("role", "user").put("content", JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", question))
            put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl)))
        }))
        val body = JSONObject().put("model", "gpt-4o-mini").put("messages", messages).put("max_tokens", 600)
        val req = Request.Builder().url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $key").post(body.toString().toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(160)}")
            return JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")
                ?.trim().orEmpty().ifBlank { throw IllegalStateException("Empty answer") }
        }
    }
}
