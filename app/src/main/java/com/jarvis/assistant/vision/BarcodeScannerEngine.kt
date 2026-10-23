package com.jarvis.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * On-device Barcode & QR Code Scanner Engine.
 *
 * Scans and decodes barcodes and QR codes from screen captures or camera bitmaps using
 * pure on-device offline recognition.
 */
class BarcodeScannerEngine(private val context: Context) {

    companion object {
        private const val TAG = "BarcodeScannerEngine"

        data class ScannedCode(
            val rawValue: String,
            val format: String,
            val timestamp: Long = System.currentTimeMillis()
        )
    }

    /**
     * Scans a Bitmap image for QR codes or Barcodes and returns detected items.
     */
    fun scanBitmap(bitmap: Bitmap): List<ScannedCode> {
        val results = mutableListOf<ScannedCode>()
        if (bitmap.isRecycled) return results

        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader()
            val hints = mapOf(
                com.google.zxing.DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE,
                com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(
                    com.google.zxing.BarcodeFormat.QR_CODE,
                    com.google.zxing.BarcodeFormat.DATA_MATRIX,
                    com.google.zxing.BarcodeFormat.UPC_A,
                    com.google.zxing.BarcodeFormat.UPC_E,
                    com.google.zxing.BarcodeFormat.EAN_8,
                    com.google.zxing.BarcodeFormat.EAN_13,
                    com.google.zxing.BarcodeFormat.CODE_39,
                    com.google.zxing.BarcodeFormat.CODE_93,
                    com.google.zxing.BarcodeFormat.CODE_128,
                    com.google.zxing.BarcodeFormat.ITF,
                    com.google.zxing.BarcodeFormat.PDF_417
                )
            )
            reader.setHints(hints)
            val result = reader.decodeWithState(binaryBitmap)

            if (result != null && !result.text.isNullOrBlank()) {
                val formatName = result.barcodeFormat?.name ?: "QR_CODE"
                Log.i(TAG, "Barcode/QR detected: ${result.text} ($formatName)")
                results.add(ScannedCode(rawValue = result.text, format = formatName))
            }
        } catch (e: Exception) {
            // ZXing throws NotFoundException when no barcode is visible in frame, which is expected
            Log.d(TAG, "Scan completed: ${e.message}")
        }
        return results
    }
}
