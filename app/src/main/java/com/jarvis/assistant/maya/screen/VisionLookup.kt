package com.jarvis.assistant.maya.screen

import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
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
 * Screenshot → Gemini vision answer (Maya `look_at_screen` / `need_vision`). Captures through the
 * accessibility screenshot API (no prompt), sends the JPEG inline to generateContent with the
 * user's Gemini key, and remembers the image size so image-space coordinates can be mapped back
 * to real device pixels.
 */
object VisionLookup {

    private const val TAG = "MayaVisionLookup"
    private val MODELS = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash")
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Shot(val jpeg: ByteArray, val width: Int, val height: Int, val takenAt: Long)

    data class Answer(val ok: Boolean, val text: String, val imageWidth: Int = 0, val imageHeight: Int = 0)

    /** Size of the last screenshot sent to the vision model (for space='image' taps). */
    @Volatile
    var lastImageSize: Pair<Int, Int>? = null
        private set

    fun hasKey(): Boolean = try { JarvisApp.instance.preferences.apiKey.isNotBlank() } catch (_: Throwable) { false }

    suspend fun capture(quality: Int = 75): Shot? {
        if (JarvisAccessibilityService.instance == null) return null
        val bytes = try { JarvisAccessibilityService.captureScreenJpeg(quality) } catch (e: Throwable) {
            Log.w(TAG, "capture failed: ${e.message}")
            null
        } ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return Shot(bytes, opts.outWidth.coerceAtLeast(1), opts.outHeight.coerceAtLeast(1), System.currentTimeMillis())
    }

    /** Real full-display size in px (what accessibility bounds and gestures use). */
    fun deviceSize(context: Context): Pair<Int, Int> {
        return try {
            val dm = DisplayMetrics()
            val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            display.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        } catch (_: Throwable) {
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    /** Maps a point read off the screenshot to device px. */
    fun imageToDevice(context: Context, x: Int, y: Int): Pair<Int, Int> {
        val (dw, dh) = deviceSize(context)
        val (iw, ih) = lastImageSize ?: run {
            // Accessibility screenshots are scaled so the long side is 640 px.
            val scale = 640f / maxOf(dw, dh).toFloat()
            (dw * scale).toInt().coerceAtLeast(1) to (dh * scale).toInt().coerceAtLeast(1)
        }
        val mx = (x.toFloat() * dw.toFloat() / iw.toFloat()).toInt().coerceIn(0, dw - 1)
        val my = (y.toFloat() * dh.toFloat() / ih.toFloat()).toInt().coerceIn(0, dh - 1)
        return mx to my
    }

    /** Takes a screenshot and asks [question] about it. */
    suspend fun look(context: Context, question: String): Answer {
        val shot = capture() ?: return Answer(false, "Screenshot nahi mila (accessibility screenshot needs Android 11+ and the Jarvis accessibility service ON).")
        return ask(context, shot, question)
    }

    suspend fun ask(context: Context, shot: Shot, question: String): Answer = withContext(Dispatchers.IO) {
        val key = try { JarvisApp.instance.preferences.apiKey.trim() } catch (_: Throwable) { "" }
        if (key.isBlank()) return@withContext Answer(false, "Gemini API key set nahi hai — vision ke liye Settings me key daalo.")
        lastImageSize = shot.width to shot.height
        val prompt = buildString {
            append("You are looking at a screenshot of an Android phone screen (")
            append(shot.width).append('x').append(shot.height)
            append(" px image). Describe briefly what is on screen: which app/screen it is, any dialog or popup on top, ")
            append("the main buttons/fields and their state. Then answer the question. When you mention where something is, ")
            append("give its centre as IMAGE pixel coordinates like (x,y) in this ").append(shot.width).append('x').append(shot.height)
            append(" image. Be concise (max ~12 lines).\n\nQUESTION: ").append(question.ifBlank { "What is on the screen?" })
        }
        val b64 = Base64.encodeToString(shot.jpeg, Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))
            }))
            put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 700))
        }.toString()
        var lastError = "vision request failed"
        for (model in MODELS) {
            try {
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
                    .post(body.toRequestBody(JSON))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val txt = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        lastError = "HTTP ${resp.code} from $model"
                        return@use
                    }
                    val parts = JSONObject(txt).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")
                    val sb = StringBuilder()
                    if (parts != null) for (i in 0 until parts.length()) {
                        val t = parts.optJSONObject(i)?.optString("text").orEmpty()
                        if (t.isNotBlank()) sb.append(t)
                    }
                    if (sb.isNotBlank()) {
                        return@withContext Answer(true, sb.toString().trim(), shot.width, shot.height)
                    }
                    lastError = "empty answer from $model"
                }
            } catch (e: Exception) {
                lastError = e.message ?: lastError
                Log.w(TAG, "vision $model failed: ${e.message}")
            }
        }
        Answer(false, "Vision se jawab nahi mila ($lastError).", shot.width, shot.height)
    }

    /** YES/NO visual check used by wait_for conditions that are not plain text. */
    suspend fun verify(context: Context, condition: String): Boolean? {
        if (!hasKey()) return null
        val shot = capture(60) ?: return null
        val a = ask(context, shot, "Answer with YES or NO on the FIRST line, then one short reason. Is this true right now: $condition")
        if (!a.ok) return null
        val first = a.text.trim().lineSequence().firstOrNull()?.trim()?.uppercase() ?: return null
        return when {
            first.startsWith("YES") -> true
            first.startsWith("NO") -> false
            else -> null
        }
    }
}
