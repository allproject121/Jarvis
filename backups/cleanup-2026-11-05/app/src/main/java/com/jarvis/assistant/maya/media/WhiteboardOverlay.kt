package com.jarvis.assistant.maya.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.util.Locale

/**
 * Full-screen whiteboard overlay (SYSTEM_ALERT_WINDOW) the assistant can write on.
 * Content is a Maya-style board script, one element per line:
 *   # heading | ## sub heading | - point | $ equation $ | [shape: box|circle|triangle|arrow|line] | ---
 * Double-tap the board to close it.
 */
object WhiteboardOverlay {

    sealed class Element {
        data class Heading(val text: String) : Element()
        data class SubHeading(val text: String) : Element()
        data class Point(val text: String) : Element()
        data class Equation(val text: String) : Element()
        data class Shape(val kind: String, val label: String) : Element()
        object Divider : Element()
        data class Text(val text: String) : Element()
    }

    fun parse(script: String): List<Element> = script.lines().mapNotNull { raw ->
        val l = raw.trim()
        when {
            l.isBlank() -> null
            l == "---" -> Element.Divider
            l.startsWith("## ") -> Element.SubHeading(l.removePrefix("## ").trim())
            l.startsWith("# ") -> Element.Heading(l.removePrefix("# ").trim())
            l.startsWith("- ") || l.startsWith("* ") -> Element.Point(l.substring(2).trim())
            l.startsWith("$") && l.endsWith("$") && l.length > 1 -> Element.Equation(l.trim('$').trim())
            l.startsWith("[shape:", ignoreCase = true) && l.endsWith("]") -> {
                val body = l.substring(7, l.length - 1).trim()
                val kind = body.substringBefore(' ').lowercase(Locale.ROOT)
                Element.Shape(kind, body.substringAfter(' ', "").trim())
            }
            else -> Element.Text(l)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var view: BoardView? = null
    private val elements = mutableListOf<Element>()
    @Volatile var title: String = ""
        private set

    fun isOpen(): Boolean = view != null

    fun snapshot(): List<Element> = synchronized(elements) { elements.toList() }

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun open(context: Context, boardTitle: String? = null): String {
        if (!canDraw(context)) return "Whiteboard needs 'Display over other apps' permission for Jarvis (Settings → Apps → Jarvis → Display over other apps)."
        if (!boardTitle.isNullOrBlank()) title = boardTitle.trim()
        main.post { ensureView(context.applicationContext) }
        return "Whiteboard khol diya."
    }

    fun draw(context: Context, script: String, append: Boolean, boardTitle: String?): String {
        if (!canDraw(context)) return open(context)
        val parsed = parse(script)
        synchronized(elements) {
            if (!append) elements.clear()
            elements.addAll(parsed)
        }
        if (!boardTitle.isNullOrBlank()) title = boardTitle.trim()
        main.post {
            ensureView(context.applicationContext)
            view?.invalidate()
        }
        return "Drew ${parsed.size} item(s) on the whiteboard."
    }

    fun clear(): String {
        synchronized(elements) { elements.clear() }
        main.post { view?.invalidate() }
        return "Whiteboard saaf kar diya."
    }

    fun close(context: Context): String {
        main.post {
            val v = view ?: return@post
            try {
                (context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            } catch (_: Exception) {}
            view = null
        }
        return "Whiteboard band kar diya."
    }

    private fun ensureView(context: Context) {
        if (view != null) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = BoardView(context)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            wm.addView(v, lp)
            view = v
        } catch (e: Exception) {
            android.util.Log.e("WhiteboardOverlay", "addView failed: ${e.message}")
        }
    }

    private class BoardView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val bg = Paint().apply { color = Color.rgb(18, 24, 22) }
        private val chalk = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 240, 230) }
        private val accent = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 214, 102) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(240, 240, 230); style = Paint.Style.STROKE; strokeWidth = 3f * density
        }
        private var lastTap = 0L

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                val now = System.currentTimeMillis()
                if (now - lastTap < 350) WhiteboardOverlay.close(context) else lastTap = now
            }
            return true
        }

        private fun drawText(canvas: Canvas, text: String, paint: TextPaint, size: Float, x: Float, y: Float, width: Int, bold: Boolean = false): Float {
            paint.textSize = size * density
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(10))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.1f).build()
            canvas.save(); canvas.translate(x, y); layout.draw(canvas); canvas.restore()
            return layout.height.toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
            val pad = 20f * density
            val w = (width - 2 * pad).toInt()
            var y = pad + 24f * density
            if (WhiteboardOverlay.title.isNotBlank()) y += drawText(canvas, WhiteboardOverlay.title, accent, 26f, pad, y, w, bold = true) + 12f * density
            val items = WhiteboardOverlay.snapshot()
            for (e in items) {
                if (y > height - pad) break
                when (e) {
                    is Element.Heading -> y += drawText(canvas, e.text, accent, 24f, pad, y, w, true) + 10f * density
                    is Element.SubHeading -> y += drawText(canvas, e.text, chalk, 20f, pad, y, w, true) + 8f * density
                    is Element.Point -> y += drawText(canvas, "•  " + e.text, chalk, 17f, pad + 8f * density, y, w - (8f * density).toInt()) + 6f * density
                    is Element.Equation -> {
                        chalk.typeface = Typeface.MONOSPACE
                        y += drawText(canvas, e.text, accent, 20f, pad + 24f * density, y, w) + 10f * density
                    }
                    is Element.Text -> y += drawText(canvas, e.text, chalk, 16f, pad, y, w) + 6f * density
                    Element.Divider -> {
                        canvas.drawLine(pad, y + 6f * density, width - pad, y + 6f * density, stroke); y += 16f * density
                    }
                    is Element.Shape -> {
                        val s = 110f * density
                        val left = pad + 12f * density
                        val r = RectF(left, y, left + s * 1.4f, y + s)
                        when {
                            e.kind.startsWith("circle") -> canvas.drawCircle(r.centerX(), r.centerY(), s / 2, stroke)
                            e.kind.startsWith("triangle") -> {
                                val p = Path()
                                if (e.label.contains("right")) { p.moveTo(r.left, r.top); p.lineTo(r.left, r.bottom); p.lineTo(r.right, r.bottom) }
                                else { p.moveTo(r.centerX(), r.top); p.lineTo(r.left, r.bottom); p.lineTo(r.right, r.bottom) }
                                p.close(); canvas.drawPath(p, stroke)
                            }
                            e.kind.startsWith("arrow") -> {
                                canvas.drawLine(r.left, r.centerY(), r.right, r.centerY(), stroke)
                                canvas.drawLine(r.right, r.centerY(), r.right - 20 * density, r.centerY() - 14 * density, stroke)
                                canvas.drawLine(r.right, r.centerY(), r.right - 20 * density, r.centerY() + 14 * density, stroke)
                            }
                            e.kind.startsWith("line") -> canvas.drawLine(r.left, r.centerY(), r.right, r.centerY(), stroke)
                            else -> canvas.drawRect(r, stroke)
                        }
                        if (e.label.isNotBlank()) drawText(canvas, e.label, chalk, 14f, r.right + 12f * density, r.centerY() - 10f * density, (width - r.right - pad - 12f * density).toInt())
                        y += s + 14f * density
                    }
                }
            }
            accent.textSize = 12f * density
            canvas.drawText("double-tap to close", pad, height - pad / 2, accent)
        }
    }
}
