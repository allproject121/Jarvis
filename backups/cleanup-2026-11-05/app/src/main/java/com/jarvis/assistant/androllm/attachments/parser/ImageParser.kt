package com.jarvis.assistant.androllm.attachments.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.jarvis.assistant.androllm.attachments.model.AttachmentType
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** OCR backend abstraction so parsers stay testable (ported from AndroLLM). */
interface AttachmentOcrEngine {
    /** OCRs one rendered PDF page; returns text or null on failure. */
    fun ocrPdfPage(file: File, pageIndex: Int): String?
}

/**
 * On-device OCR via ML Kit's bundled Latin text recognizer
 * (com.google.mlkit:text-recognition — model ships inside the AAR, no Play
 * Services needed). Used for image attachments and scanned PDF pages.
 */
class MlKitOcrEngine(private val context: Context) : AttachmentOcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** OCRs a bitmap; returns the recognized text or null when nothing was found. */
    suspend fun ocrBitmap(bitmap: Bitmap): String? = withContext(Dispatchers.Default) {
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.text.trim()
                    if (cont.isActive) cont.resume(text.ifEmpty { null })
                }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }
    }

    /** OCRs an image file (decoded at a memory-safe sample size). */
    suspend fun ocrImageFile(file: File): String? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, 2048)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalArgumentException("Could not decode image (unsupported format?)")
        try {
            ocrBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** OCRs a content URI (HEIC/webp decode handled by the system codec). */
    suspend fun ocrUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream) }
        }.getOrNull() ?: return@withContext null
        try {
            ocrBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Renders the PDF page and OCRs it. Must not be called on the main thread. */
    override fun ocrPdfPage(file: File, pageIndex: Int): String? = try {
        val bitmap = PdfParser(context, null).renderPage(file, pageIndex)
        if (bitmap == null) {
            null
        } else {
            try {
                runBlocking { ocrBitmap(bitmap) }
            } finally {
                bitmap.recycle()
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun computeSampleSize(width: Int, height: Int, maxDim: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / sample > maxDim || height / sample > maxDim) sample *= 2
        return sample
    }
}

/** Image attachment parser: OCRs the picture so text-only engines can read it. */
class ImageParser(
    private val ocr: MlKitOcrEngine
) : DocumentParser {

    override val format = AttachmentType.IMAGE
    override fun supports(file: File): Boolean =
        file.extension.lowercase() in setOf("png", "jpg", "jpeg", "heic", "heif", "webp", "bmp", "gif")

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        val text = runBlocking { ocr.ocrImageFile(file) }
            ?: throw IllegalArgumentException("No text found in image (OCR)")
        val normalized = TextParsers.normalize(text)
        if (normalized.isEmpty()) throw IllegalArgumentException("No text found in image (OCR)")
        return ParsedDocument(
            text = normalized,
            pages = listOf(normalized),
            pageCount = 1,
            fromOcr = true
        )
    }
}
