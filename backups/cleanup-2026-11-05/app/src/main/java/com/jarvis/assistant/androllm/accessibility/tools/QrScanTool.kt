package com.jarvis.assistant.androllm.accessibility.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.jarvis.assistant.androllm.accessibility.ocr.MlKitOcrEngine
import com.jarvis.assistant.androllm.tools.ToolOutcome
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Ported from AndroLLM (core/accessibility/tools/QrScanTool) — `scan_qr`.
 *
 * Reads a QR code that is currently ON SCREEN (accessibility screenshot,
 * Android 11+) or from an image file path. Decoding (ZXing core) runs on a
 * background dispatcher.
 */
object QrScanTool {

    suspend fun execute(args: JSONObject): ToolOutcome {
        val imagePath = args.optString("image_path", "")
            .ifBlank { args.optString("path", "") }
            .ifBlank { args.optString("image", "") }
            .trim()

        val bitmap: Bitmap = if (imagePath.isNotEmpty()) {
            if (!File(imagePath).exists()) return ToolOutcome.fail("Image file not found: $imagePath")
            withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(imagePath) }.getOrNull() }
                ?: return ToolOutcome.fail("Could not decode the image at $imagePath.")
        } else {
            if (!JarvisAccessibilityService.isRunning()) {
                return ToolOutcome.fail("Jarvis Accessibility Service is not enabled. Please enable it in Settings > Accessibility.")
            }
            JarvisAccessibilityService.captureScreenBitmap()
                ?: return ToolOutcome.fail("Could not capture the screen (needs Android 11+ and the Jarvis accessibility service).")
        }

        val text = decode(bitmap)
        val source = if (imagePath.isNotEmpty()) "image" else "screen"
        if (text == null) {
            // Not finding a code is a valid answer, not a tool failure.
            return ToolOutcome.ok("No QR code found in the $source.", JSONObject().put("found", false))
        }
        return ToolOutcome.ok(
            "QR code: $text",
            JSONObject()
                .put("found", true)
                .put("text", text)
                .put("content_type", contentType(text))
        )
    }

    suspend fun decode(bitmap: Bitmap): String? = withContext(Dispatchers.Default) {
        runCatching {
            val soft = MlKitOcrEngine.toSoftwareBitmap(bitmap) ?: return@runCatching null
            val w = soft.width
            val h = soft.height
            val pixels = IntArray(w * h)
            soft.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            val hints = mapOf(DecodeHintType.TRY_HARDER to true)
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints)?.text
        }.getOrNull()
    }

    private fun contentType(text: String): String = when {
        text.startsWith("http://") || text.startsWith("https://") -> "url"
        text.startsWith("mailto:") -> "email"
        text.startsWith("tel:") -> "phone"
        text.startsWith("WIFI:") -> "wifi"
        text.startsWith("upi:", ignoreCase = true) -> "upi"
        else -> "text"
    }
}
