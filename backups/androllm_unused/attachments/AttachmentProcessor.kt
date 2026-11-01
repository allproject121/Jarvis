package com.jarvis.assistant.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Processes multimodal attachments (documents, code files, images)
 * for injection into Gemini Multimodal, GPT-4o Vision, or Local AI contexts.
 */
class AttachmentProcessor private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AttachmentProcessor"
        private const val ATTACHMENT_CACHE_DIR = "chat_attachments"
        private const val MAX_IMAGE_DIMENSION = 1024

        @Volatile
        private var instance: AttachmentProcessor? = null

        fun getInstance(context: Context): AttachmentProcessor {
            return instance ?: synchronized(this) {
                instance ?: AttachmentProcessor(context.applicationContext).also { instance = it }
            }
        }
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, ATTACHMENT_CACHE_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    suspend fun processUri(uri: Uri): ChatAttachment? = withContext(Dispatchers.IO) {
        try {
            val name = resolveDisplayName(uri)
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val id = UUID.randomUUID().toString()
            val targetFile = File(cacheDir, "$id-$name")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null

            val isImg = mime.startsWith("image/") || name.endsWith(".jpg", true) ||
                    name.endsWith(".jpeg", true) || name.endsWith(".png", true) || name.endsWith(".webp", true)

            var extractedText = ""
            if (!isImg && targetFile.length() < 2 * 1024 * 1024) { // Under 2MB text
                extractedText = runCatching { targetFile.readText(Charsets.UTF_8).take(8000) }.getOrDefault("")
            }

            ChatAttachment(
                id = id,
                name = name,
                mimeType = mime,
                fileUri = uri.toString(),
                filePath = targetFile.absolutePath,
                sizeBytes = targetFile.length(),
                extractedText = extractedText,
                isImage = isImg
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed processing attachment: ${e.message}", e)
            null
        }
    }

    /**
     * Converts an image attachment into an optimized, base64-encoded JPEG string
     * suitable for OpenAI Vision or Gemini Multimodal API requests.
     */
    fun encodeImageToBase64(attachment: ChatAttachment): String? {
        if (!attachment.isImage) return null
        return try {
            val file = File(attachment.filePath)
            if (!file.exists()) return null

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)

            var sampleSize = 1
            while (options.outWidth / sampleSize > MAX_IMAGE_DIMENSION || options.outHeight / sampleSize > MAX_IMAGE_DIMENSION) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val bytes = baos.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Error encoding image to base64: ${e.message}")
            null
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        var name = "attachment"
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        name = cursor.getString(idx) ?: "attachment"
                    }
                }
            }
        } else if (uri.scheme == "file") {
            name = uri.lastPathSegment ?: "attachment"
        }
        return name
    }
}
