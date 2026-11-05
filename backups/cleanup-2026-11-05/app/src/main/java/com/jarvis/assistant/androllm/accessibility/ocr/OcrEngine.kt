package com.jarvis.assistant.androllm.accessibility.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Ported from AndroLLM (core/accessibility/ocr/OcrEngine).
 *
 * On-device OCR used by the screen analyzer as a vision fallback when the
 * accessibility tree is missing or unhelpful (custom-drawn UIs, games,
 * images). AndroLLM shipped a no-op engine; Jarvis binds the real ML Kit
 * Latin text recognizer (com.google.mlkit:text-recognition, bundled model).
 */
interface OcrEngine {
    /** True when a real model is available. */
    val isAvailable: Boolean

    /** Recognized text lines, top to bottom. */
    suspend fun recognize(bitmap: Bitmap): List<String>
}

/** ML Kit implementation. The recognizer is created lazily and reused. */
object MlKitOcrEngine : OcrEngine {

    private const val TAG = "JarvisOcr"

    private val recognizer: TextRecognizer? by lazy {
        runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
            .onFailure { Log.w(TAG, "ML Kit text recognizer unavailable: ${it.message}") }
            .getOrNull()
    }

    override val isAvailable: Boolean get() = recognizer != null

    override suspend fun recognize(bitmap: Bitmap): List<String> {
        val client = recognizer ?: return emptyList()
        // Accessibility screenshots are HARDWARE bitmaps; ML Kit needs a
        // software copy it can read pixels from.
        val soft = withContext(Dispatchers.Default) { toSoftwareBitmap(bitmap) } ?: return emptyList()
        val image = runCatching { InputImage.fromBitmap(soft, 0) }.getOrNull() ?: return emptyList()
        return suspendCancellableCoroutine { cont ->
            client.process(image)
                .addOnSuccessListener { text ->
                    val lines = text.textBlocks
                        .sortedBy { it.boundingBox?.top ?: 0 }
                        .flatMap { block -> block.lines.map { it.text.trim() } }
                        .filter { it.isNotBlank() }
                    if (cont.isActive) cont.resume(lines)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "OCR failed: ${e.message}")
                    if (cont.isActive) cont.resume(emptyList())
                }
        }
    }

    /** Returns a readable ARGB_8888 copy when [bitmap] is a hardware bitmap. */
    fun toSoftwareBitmap(bitmap: Bitmap): Bitmap? = runCatching {
        if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    }.getOrNull()
}
