package com.jarvis.assistant.maya.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import java.util.Locale

/**
 * Renders quote / text posters with Canvas so the words are always spelt correctly.
 * Background is a gradient by default; optionally an AI-generated backdrop (then darkened).
 */
object QuoteImageRenderer {

    private const val TAG = "QuoteImageRenderer"

    private val GRADIENTS: Map<String, IntArray> = mapOf(
        "sunset" to intArrayOf(0xFFFF512F.toInt(), 0xFFDD2476.toInt()),
        "ocean" to intArrayOf(0xFF2193B0.toInt(), 0xFF6DD5ED.toInt()),
        "night" to intArrayOf(0xFF0F2027.toInt(), 0xFF203A43.toInt(), 0xFF2C5364.toInt()),
        "forest" to intArrayOf(0xFF134E5E.toInt(), 0xFF71B280.toInt()),
        "royal" to intArrayOf(0xFF141E30.toInt(), 0xFF243B55.toInt()),
        "peach" to intArrayOf(0xFFED4264.toInt(), 0xFFFFEDBC.toInt()),
        "lavender" to intArrayOf(0xFF4568DC.toInt(), 0xFFB06AB3.toInt()),
        "fire" to intArrayOf(0xFFF12711.toInt(), 0xFFF5AF19.toInt()),
        "mono" to intArrayOf(0xFF232526.toInt(), 0xFF414345.toInt()),
        "gold" to intArrayOf(0xFF3A1C71.toInt(), 0xFFD76D77.toInt(), 0xFFFFAF7B.toInt())
    )

    fun styleNames(): List<String> = GRADIENTS.keys.toList()

    private fun pickGradient(style: String?): IntArray {
        val s = style?.lowercase(Locale.ROOT).orEmpty()
        GRADIENTS.entries.firstOrNull { s.contains(it.key) }?.let { return it.value }
        return when {
            s.contains("dark") || s.contains("black") -> GRADIENTS.getValue("night")
            s.contains("love") || s.contains("pink") || s.contains("romantic") -> GRADIENTS.getValue("peach")
            s.contains("motivat") || s.contains("energy") -> GRADIENTS.getValue("fire")
            s.contains("calm") || s.contains("blue") -> GRADIENTS.getValue("ocean")
            s.contains("nature") || s.contains("green") -> GRADIENTS.getValue("forest")
            s.contains("festival") || s.contains("diwali") || s.contains("birthday") -> GRADIENTS.getValue("gold")
            else -> GRADIENTS.values.elementAt((System.currentTimeMillis() / 1000 % GRADIENTS.size).toInt())
        }
    }

    /**
     * @param background optional AI-generated backdrop; it is scaled/cropped to [size] and darkened.
     */
    fun render(
        quote: String,
        author: String? = null,
        style: String? = null,
        size: ImageSize = ImageSize.STORY,
        background: Bitmap? = null,
        brand: String? = null
    ): Bitmap {
        val w = size.width
        val h = size.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // 1. Background
        if (background != null && !background.isRecycled) {
            drawCoverScaled(canvas, background, w, h)
            canvas.drawColor(Color.argb(140, 0, 0, 0))
        } else {
            val colors = pickGradient(style)
            val paint = Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), colors, null, Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            // soft vignette
            val vignette = Paint().apply {
                shader = android.graphics.RadialGradient(
                    w / 2f, h / 2f, maxOf(w, h) * 0.75f,
                    intArrayOf(Color.TRANSPARENT, Color.argb(110, 0, 0, 0)), null, Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vignette)
        }

        // 2. Quote text (auto-fit)
        val margin = (w * 0.10f)
        val maxTextWidth = (w - 2 * margin).toInt()
        val serif = style?.lowercase(Locale.ROOT)?.let { it.contains("serif") || it.contains("classic") || it.contains("shayari") } == true
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = if (serif) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(10f, 0f, 4f, Color.argb(160, 0, 0, 0))
        }
        val cleanQuote = quote.trim()
        val quoted = if (cleanQuote.startsWith("\"") || cleanQuote.startsWith("“")) cleanQuote else "“$cleanQuote”"
        var textSize = (w * 0.085f).coerceAtLeast(28f)
        val maxTextHeight = h * 0.62f
        textPaint.textSize = textSize
        var layout: StaticLayout = staticLayout(quoted, textPaint, maxTextWidth)
        while (layout.height > maxTextHeight && textSize > 24f) {
            textSize *= 0.92f
            textPaint.textSize = textSize
            layout = staticLayout(quoted, textPaint, maxTextWidth)
        }

        val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 255, 255)
            textSize = (textSize * 0.55f).coerceAtLeast(20f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
            setShadowLayer(8f, 0f, 3f, Color.argb(150, 0, 0, 0))
        }
        val authorText = author?.trim()?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("—") || it.startsWith("-")) it else "— $it" }
        val authorLayout = authorText?.let { staticLayout(it, authorPaint, maxTextWidth) }

        val totalHeight = layout.height + (authorLayout?.height?.plus((textSize * 0.6f).toInt()) ?: 0)
        var y = (h - totalHeight) / 2f
        canvas.save()
        canvas.translate(margin, y)
        layout.draw(canvas)
        canvas.restore()
        y += layout.height
        if (authorLayout != null) {
            y += textSize * 0.6f
            canvas.save()
            canvas.translate(margin, y)
            authorLayout.draw(canvas)
            canvas.restore()
        }

        // 3. Decorative line + brand
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(170, 255, 255, 255)
            strokeWidth = (w * 0.004f).coerceAtLeast(2f)
        }
        val lineY = (h - totalHeight) / 2f - textSize * 0.9f
        canvas.drawLine(w / 2f - w * 0.08f, lineY, w / 2f + w * 0.08f, lineY, linePaint)
        if (!brand.isNullOrBlank()) {
            val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 255, 255, 255)
                textSize = (w * 0.028f).coerceAtLeast(16f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
                letterSpacing = 0.12f
            }
            canvas.drawText(brand.uppercase(Locale.ROOT), w / 2f, h - margin * 0.7f, brandPaint)
        }
        return bmp
    }

    private fun staticLayout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(true)
            .build()

    private fun drawCoverScaled(canvas: Canvas, src: Bitmap, w: Int, h: Int) {
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = (w / scale).toInt().coerceAtMost(src.width)
        val sh = (h / scale).toInt().coerceAtMost(src.height)
        val left = (src.width - sw) / 2
        val top = (src.height - sh) / 2
        val srcRect = Rect(left, top, left + sw, top + sh)
        val dst = Rect(0, 0, w, h)
        canvas.drawBitmap(src, srcRect, dst, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /** Convenience: render + save to Pictures/Jarvis; returns the saved record. */
    suspend fun renderAndSave(
        context: Context,
        quote: String,
        author: String?,
        style: String?,
        size: ImageSize,
        aiBackground: Boolean
    ): Pair<Bitmap, MediaFiles.SavedMedia?> {
        var bg: Bitmap? = null
        if (aiBackground) {
            bg = try {
                ImageGen.generate(
                    context,
                    prompt = "abstract atmospheric background for a quote poster, soft focus, no text, no letters, ${style ?: "elegant"}",
                    size = size,
                    save = false
                ).bitmap
            } catch (e: Exception) {
                Log.w(TAG, "AI background unavailable, using gradient: ${e.message}")
                null
            }
        }
        val brand = runCatching { com.jarvis.assistant.JarvisApp.instance.preferences.assistantName }.getOrDefault("JARVIS")
        val bmp = render(quote, author, style, size, bg, brand)
        bg?.recycle()
        val saved = MediaFiles.saveImage(context, bmp, "JARVIS_QUOTE")
        saved?.let { MediaPrefs.get(context).addRecentImage(it.ref) }
        return bmp to saved
    }
}
