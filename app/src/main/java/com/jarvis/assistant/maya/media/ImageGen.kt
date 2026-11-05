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
import java.util.concurrent.TimeUnit

/**
 * Maya-style image generation engine with four providers:
 *  - pollinations  (free, no key; optional token)
 *  - gemini_image  (Gemini generateContent with responseModalities IMAGE+TEXT, "Nano Banana")
 *  - imagen        (Imagen :predict; paid tier, falls back to gemini_image then pollinations)
 *  - openai        (gpt-image-1 via /v1/images/generations, b64_json)
 *
 * Every successful generation is saved to Pictures/Jarvis via MediaStore and remembered in
 * [MediaPrefs.recentImages].
 */
object ImageGen {

    private const val TAG = "MayaImageGen"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    data class Result(
        val bitmap: Bitmap,
        val saved: MediaFiles.SavedMedia?,
        val provider: String,
        val width: Int,
        val height: Int,
        val note: String = ""
    )

    class ImageGenException(message: String) : Exception(message)

    fun geminiKey(): String = runCatching { JarvisApp.instance.preferences.apiKey }.getOrDefault("")

    fun openAiKey(context: Context): String {
        val own = MediaPrefs.get(context).openAiImageKey
        if (own.isNotBlank()) return own
        return runCatching { JarvisApp.instance.preferences.openAiApiKey }.getOrDefault("")
    }

    /** True when the user picked something other than the default Pollinations provider. */
    fun isCustomProviderConfigured(context: Context): Boolean =
        MediaPrefs.get(context).imageProvider != MediaPrefs.PROVIDER_POLLINATIONS

    /** Human-readable setup summary for get_image_setup. */
    fun setupSummary(context: Context): JSONObject {
        val p = MediaPrefs.get(context)
        val gemini = geminiKey().isNotBlank()
        val openai = openAiKey(context).isNotBlank()
        return JSONObject().apply {
            put("provider", p.imageProvider)
            put("default_size", p.imageSize)
            put("providers", JSONArray().apply {
                put(JSONObject().put("id", MediaPrefs.PROVIDER_POLLINATIONS).put("label", "Pollinations (free, no key)").put("ready", true)
                    .put("token_set", p.pollinationsToken.isNotBlank()))
                put(JSONObject().put("id", MediaPrefs.PROVIDER_GEMINI_IMAGE).put("label", "Google Gemini — Nano Banana (${p.geminiImageModel})").put("ready", gemini))
                put(JSONObject().put("id", MediaPrefs.PROVIDER_IMAGEN).put("label", "Imagen (${p.imagenModel}) — same Gemini key, paid tier").put("ready", gemini))
                put(JSONObject().put("id", MediaPrefs.PROVIDER_OPENAI).put("label", "OpenAI ${p.openAiImageModel}").put("ready", openai))
            })
            put("gemini_key_present", gemini)
            put("openai_key_present", openai)
            put("recent_images", JSONArray(p.recentImages().take(5)))
            put("settings_screen", "Media Studio (com.jarvis.assistant.maya.media.MediaStudioActivity)")
        }
    }

    /**
     * Generates an image with the configured provider (or [providerOverride]). Falls back along
     * imagen -> gemini_image -> pollinations and gemini_image/openai -> pollinations on failure.
     */
    suspend fun generate(
        context: Context,
        prompt: String,
        style: String? = null,
        size: ImageSize? = null,
        providerOverride: String? = null,
        save: Boolean = true,
        baseName: String = "JARVIS_AI"
    ): Result = withContext(Dispatchers.IO) {
        val prefs = MediaPrefs.get(context)
        val fullPrompt = PollinationsUrl.promptWithStyle(prompt, style)
        val dims = size ?: ImageSize.parse(prefs.imageSize)
        val provider = (providerOverride ?: prefs.imageProvider).lowercase()
        val attempts = mutableListOf<String>()
        val order = when (provider) {
            MediaPrefs.PROVIDER_IMAGEN -> listOf(MediaPrefs.PROVIDER_IMAGEN, MediaPrefs.PROVIDER_GEMINI_IMAGE, MediaPrefs.PROVIDER_POLLINATIONS)
            MediaPrefs.PROVIDER_GEMINI_IMAGE -> listOf(MediaPrefs.PROVIDER_GEMINI_IMAGE, MediaPrefs.PROVIDER_POLLINATIONS)
            MediaPrefs.PROVIDER_OPENAI -> listOf(MediaPrefs.PROVIDER_OPENAI, MediaPrefs.PROVIDER_POLLINATIONS)
            else -> listOf(MediaPrefs.PROVIDER_POLLINATIONS)
        }
        var lastError: String? = null
        for (p in order) {
            try {
                val raw = when (p) {
                    MediaPrefs.PROVIDER_IMAGEN -> viaImagen(context, fullPrompt, dims)
                    MediaPrefs.PROVIDER_GEMINI_IMAGE -> viaGeminiImage(context, fullPrompt, dims)
                    MediaPrefs.PROVIDER_OPENAI -> viaOpenAi(context, fullPrompt, dims)
                    else -> viaPollinations(context, fullPrompt, dims)
                }
                // Providers only approximate the aspect ratio (Gemini/OpenAI have fixed sizes), so the
                // chosen Media Studio size is enforced here: centre-crop to the ratio, then scale.
                val bmp = fitToSize(raw, dims)
                val saved = if (save) MediaFiles.saveImage(context, bmp, baseName) else null
                saved?.let { prefs.addRecentImage(it.ref) }
                val note = if (attempts.isEmpty()) "" else "Fallback used after: ${attempts.joinToString("; ")}"
                return@withContext Result(bmp, saved, p, bmp.width, bmp.height, note)
            } catch (e: Exception) {
                lastError = "${p}: ${e.message}"
                attempts.add(lastError)
                Log.w(TAG, "Provider $p failed: ${e.message}")
            }
        }
        throw ImageGenException(lastError ?: "Image generation failed")
    }

    // ---------------- providers ----------------

    private fun viaPollinations(context: Context, prompt: String, dims: ImageSize): Bitmap {
        val token = MediaPrefs.get(context).pollinationsToken.ifBlank { null }
        val url = PollinationsUrl.build(prompt, dims.width, dims.height, seed = System.currentTimeMillis() % 100_000, token = token)
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Jarvis)").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ImageGenException("Pollinations HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: throw ImageGenException("Pollinations returned an empty body")
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw ImageGenException("Pollinations returned an undecodable image")
        }
    }

    private fun viaGeminiImage(context: Context, prompt: String, dims: ImageSize): Bitmap {
        val key = geminiKey()
        if (key.isBlank()) throw ImageGenException("Gemini API key not set (Settings → API connection)")
        val prefs = MediaPrefs.get(context)
        val models = listOf(prefs.geminiImageModel, MediaPrefs.FALLBACK_GEMINI_IMAGE_MODEL).distinct()
        var last: String? = null
        for (model in models) {
            try {
                return try {
                    geminiGenerateContentImage(key, model, "$prompt (aspect ratio ${aspectLabel(dims)})", aspectLabel(dims))
                } catch (e: ImageGenException) {
                    // Older image models reject generationConfig.imageConfig - retry without it.
                    if (e.message?.contains("HTTP 400") == true) geminiGenerateContentImage(key, model, "$prompt (aspect ratio ${aspectLabel(dims)})", null)
                    else throw e
                }
            } catch (e: Exception) {
                last = "$model: ${e.message}"
            }
        }
        throw ImageGenException(last ?: "Gemini image failed")
    }

    private fun geminiGenerateContentImage(key: String, model: String, prompt: String, aspect: String?): Bitmap {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("IMAGE").put("TEXT")).apply {
                if (aspect != null) put("imageConfig", JSONObject().put("aspectRatio", aspect))
            })
        }
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ImageGenException("Gemini HTTP ${resp.code}: ${errorMessage(text)}")
            val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: throw ImageGenException("Gemini returned no candidates")
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data") ?: continue
                val data = inline.optString("data", "")
                if (data.isNotBlank()) {
                    val bytes = Base64.decode(data, Base64.DEFAULT)
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw ImageGenException("Gemini image undecodable")
                }
            }
            throw ImageGenException("Gemini answered with text only (model may not support images)")
        }
    }

    private fun viaImagen(context: Context, prompt: String, dims: ImageSize): Bitmap {
        val key = geminiKey()
        if (key.isBlank()) throw ImageGenException("Gemini API key not set")
        val model = MediaPrefs.get(context).imagenModel
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:predict?key=$key"
        val body = JSONObject().apply {
            put("instances", JSONArray().put(JSONObject().put("prompt", prompt)))
            put("parameters", JSONObject().put("sampleCount", 1).put("aspectRatio", aspectLabel(dims)))
        }
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ImageGenException("Imagen HTTP ${resp.code}: ${errorMessage(text)} (Imagen needs the paid tier)")
            val pred = JSONObject(text).optJSONArray("predictions")?.optJSONObject(0)
                ?: throw ImageGenException("Imagen returned no predictions")
            val data = pred.optString("bytesBase64Encoded", "")
            if (data.isBlank()) throw ImageGenException("Imagen returned no image bytes")
            val bytes = Base64.decode(data, Base64.DEFAULT)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw ImageGenException("Imagen image undecodable")
        }
    }

    private fun viaOpenAi(context: Context, prompt: String, dims: ImageSize): Bitmap {
        val key = openAiKey(context)
        if (key.isBlank()) throw ImageGenException("OpenAI API key not set (Media Studio → Image generation)")
        val model = MediaPrefs.get(context).openAiImageModel
        val sizeStr = when {
            dims.width > dims.height -> "1536x1024"
            dims.height > dims.width -> "1024x1536"
            else -> "1024x1024"
        }
        val body = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("n", 1)
            put("size", sizeStr)
            if (model.startsWith("gpt-image")) put("output_format", "jpeg") else put("response_format", "b64_json")
        }
        val req = Request.Builder()
            .url("https://api.openai.com/v1/images/generations")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ImageGenException("OpenAI HTTP ${resp.code}: ${errorMessage(text)}")
            val item = JSONObject(text).optJSONArray("data")?.optJSONObject(0) ?: throw ImageGenException("OpenAI returned no data")
            val b64 = item.optString("b64_json", "")
            if (b64.isNotBlank()) {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw ImageGenException("OpenAI image undecodable")
            }
            val urlStr = item.optString("url", "")
            if (urlStr.isBlank()) throw ImageGenException("OpenAI returned neither b64_json nor url")
            client.newCall(Request.Builder().url(urlStr).build()).execute().use { dl ->
                val bytes = dl.body?.bytes() ?: throw ImageGenException("OpenAI image download failed")
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw ImageGenException("OpenAI image undecodable")
            }
        }
    }

    /** Centre-crops [src] to the aspect ratio of [dims] and scales it to exactly dims.width x dims.height. */
    private fun fitToSize(src: Bitmap, dims: ImageSize): Bitmap {
        if (src.width == dims.width && src.height == dims.height) return src
        return try {
            val targetRatio = dims.width.toFloat() / dims.height
            val srcRatio = src.width.toFloat() / src.height
            var cropW = src.width
            var cropH = src.height
            if (srcRatio > targetRatio) cropW = (src.height * targetRatio).toInt().coerceIn(1, src.width)
            else if (srcRatio < targetRatio) cropH = (src.width / targetRatio).toInt().coerceIn(1, src.height)
            val x = (src.width - cropW) / 2
            val y = (src.height - cropH) / 2
            val cropped = if (cropW != src.width || cropH != src.height) Bitmap.createBitmap(src, x, y, cropW, cropH) else src
            val out = Bitmap.createScaledBitmap(cropped, dims.width, dims.height, true)
            if (cropped !== src && cropped !== out) cropped.recycle()
            if (out !== src) src.recycle()
            out
        } catch (e: Throwable) {
            Log.w(TAG, "fitToSize failed: ${e.message}")
            src
        }
    }

    private fun aspectLabel(d: ImageSize): String = when {
        d.width == d.height -> "1:1"
        d.width * 16 == d.height * 9 -> "9:16"
        d.height * 16 == d.width * 9 -> "16:9"
        d.width > d.height -> "4:3"
        else -> "3:4"
    }

    private fun errorMessage(body: String): String = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.take(200) ?: body.take(200)
    } catch (e: Exception) {
        body.take(200)
    }
}
