package com.jarvis.assistant.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class DirectAiImageGenerator(private val context: Context) {
    companion object {
        private const val TAG = "DirectAiImageGenerator"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun generateImage(prompt: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val encodedPrompt = URLEncoder.encode(prompt.trim(), "UTF-8")
            val url = "https://image.pollinations.ai/prompt/$encodedPrompt?width=1024&height=1024&nologo=true&model=flux"
            Log.i(TAG, "Requesting AI Image from: $url")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Image generation failed with HTTP ${response.code}")
                    return@withContext null
                }
                val stream = response.body?.byteStream() ?: return@withContext null
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap != null) {
                    saveToGallery(bitmap, prompt)
                }
                return@withContext bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating AI image: ${e.message}", e)
            null
        }
    }

    private fun saveToGallery(bitmap: Bitmap, prompt: String) {
        try {
            val filename = "JARVIS_AI_${System.currentTimeMillis()}.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/JARVIS")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    Log.i(TAG, "Saved AI Image to MediaStore: $uri")
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "JARVIS")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, filename)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                Log.i(TAG, "Saved AI Image to: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not save AI image to gallery: ${e.message}")
        }
    }
}
