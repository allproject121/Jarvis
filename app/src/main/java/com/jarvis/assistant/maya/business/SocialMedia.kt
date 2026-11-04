package com.jarvis.assistant.maya.business

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream

/** Media helpers for post_story / post_photo_to_social / post_to_social. */
object SocialMedia {

    /** Newest image or video path from MediaStore (null if none / no permission). */
    fun newestMedia(context: Context, video: Boolean): String? {
        val uri = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DATA), null, null,
                MediaStore.MediaColumns.DATE_ADDED + " DESC"
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) { null }
    }

    /** Resolves a file name / partial name against the newest gallery items. */
    fun findMedia(context: Context, query: String, video: Boolean): String? {
        if (query.isBlank()) return newestMedia(context, video)
        if (query.startsWith("/") || query.startsWith("content://")) return query
        val uri = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DATA), MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                arrayOf("%$query%"), MediaStore.MediaColumns.DATE_ADDED + " DESC"
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) { null }
    }

    /** Renders a 1080x1920 gradient quote poster (text drawn by the phone, always spelt right). */
    fun renderStoryPoster(context: Context, text: String, footer: String): String {
        val w = 1080
        val h = 1920
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(Color.parseColor("#141E30"), Color.parseColor("#243B55"), Color.parseColor("#0F2027")), null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = if (text.length > 160) 54f else if (text.length > 80) 66f else 82f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val margin = 110
        @Suppress("DEPRECATION")
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, w - 2 * margin)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.15f).build()
        canvas.save()
        canvas.translate(margin.toFloat(), (h - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
        if (footer.isNotBlank()) {
            val fp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CCFFFFFF"); textSize = 40f; textAlign = Paint.Align.CENTER }
            canvas.drawText(footer.take(60), w / 2f, h - 160f, fp)
        }
        val dir = File(context.filesDir, "maya_posts").apply { mkdirs() }
        val f = File(dir, "story_${System.currentTimeMillis()}.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f.absolutePath
    }
}
