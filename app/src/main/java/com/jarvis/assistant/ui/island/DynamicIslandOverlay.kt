package com.jarvis.assistant.ui.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Jarvis Dynamic Island: a small black capsule at the top-centre of the screen
 * (TYPE_APPLICATION_OVERLAY, like Jarvis' other overlays) with a live audio visualizer
 * and a short status line. Tap expands it to show the live reply with Stop / Open buttons.
 *
 * Pure view code; [DynamicIslandController] decides when it is shown and what it says.
 * Must be used from the main thread.
 */
class DynamicIslandOverlay(
    private val context: Context,
    private val onStop: () -> Unit,
    private val onOpen: () -> Unit
) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    private var attached = false
    private var expanded = false
    private var mode = Mode.IDLE

    private val capsuleBg = GradientDrawable().apply {
        setColor(Color.parseColor("#F2000000"))
        cornerRadius = dp(22).toFloat()
        setStroke(max(1, dp(1)), Color.parseColor("#5500E5FF"))
    }

    private val dot = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ACCENT_IDLE)
        }
    }

    private val statusText = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(190)
    }

    private val visualizer = IslandVisualizerView(context)

    private val transcriptText = TextView(context).apply {
        setTextColor(Color.parseColor("#9CA3AF"))
        textSize = 12f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }

    private val responseText = TextView(context).apply {
        setTextColor(Color.parseColor("#E5E7EB"))
        textSize = 13f
        maxLines = 4
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }

    private val actionsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        visibility = View.GONE
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = capsuleBg
        setPadding(dp(14), dp(8), dp(14), dp(8))
        minimumWidth = dp(150)
        isClickable = true
        setOnClickListener { toggleExpanded() }
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(6)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    init {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(9) })
        header.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(visualizer, LinearLayout.LayoutParams(dp(42), dp(18)).apply { marginStart = dp(10) })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(transcriptText, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        root.addView(responseText, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        actionsRow.addView(actionChip("Open Jarvis", Color.parseColor("#E5E7EB")) {
            collapse()
            onOpen()
        })
        actionsRow.addView(actionChip("Stop", Color.parseColor("#F87171")) {
            collapse()
            onStop()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        root.addView(actionsRow, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    }

    val isShowing: Boolean get() = attached

    fun show() {
        if (attached) return
        try {
            windowManager.addView(root, params)
            attached = true
            root.scaleX = 0.6f
            root.alpha = 0f
            root.animate().scaleX(1f).alpha(1f).setDuration(260).setInterpolator(OvershootInterpolator(1.4f)).start()
            visualizer.start()
        } catch (_: Exception) {
            attached = false
        }
    }

    fun hide() {
        if (!attached) return
        visualizer.stop()
        try {
            windowManager.removeView(root)
        } catch (_: Exception) {
        }
        attached = false
        expanded = false
        applyExpanded()
    }

    fun setStatus(newMode: Mode, text: String) {
        mode = newMode
        if (statusText.text?.toString() != text) statusText.text = text
        (dot.background as? GradientDrawable)?.setColor(
            when (newMode) {
                Mode.LISTENING -> ACCENT_LISTENING
                Mode.THINKING -> ACCENT_THINKING
                Mode.SPEAKING -> ACCENT_SPEAKING
                Mode.IDLE -> ACCENT_IDLE
            }
        )
        visualizer.mode = newMode
    }

    fun setLevel(level: Float) {
        visualizer.targetLevel = level.coerceIn(0f, 1f)
    }

    fun setTranscript(text: String) {
        transcriptText.text = if (text.isBlank()) "" else "You: ${text.trim()}"
        if (expanded) transcriptText.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    fun setResponse(text: String) {
        responseText.text = text.trim()
        if (expanded) responseText.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun toggleExpanded() {
        expanded = !expanded
        applyExpanded()
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        applyExpanded()
    }

    private fun applyExpanded() {
        transcriptText.visibility = if (expanded && !transcriptText.text.isNullOrBlank()) View.VISIBLE else View.GONE
        responseText.visibility = if (expanded && !responseText.text.isNullOrBlank()) View.VISIBLE else View.GONE
        actionsRow.visibility = if (expanded) View.VISIBLE else View.GONE
        capsuleBg.cornerRadius = if (expanded) dp(24).toFloat() else dp(22).toFloat()
        root.setPadding(dp(14), if (expanded) dp(12) else dp(8), dp(14), if (expanded) dp(12) else dp(8))
        if (attached) {
            try {
                windowManager.updateViewLayout(root, params)
            } catch (_: Exception) {
            }
        }
    }

    private fun actionChip(label: String, color: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(color)
        textSize = 12f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#1F2937"))
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    /** Five rounded bars driven by the live audio level (synthetic motion while speaking/thinking). */
    private class IslandVisualizerView(context: Context) : View(context) {
        var targetLevel = 0f
        var mode = Mode.IDLE
        private var level = 0f
        private var phase = 0f
        private var running = false
        private val rect = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_LISTENING }

        fun start() {
            running = true
            postInvalidateOnAnimation()
        }

        fun stop() {
            running = false
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val synthetic = when (mode) {
                Mode.SPEAKING -> 0.45f + 0.35f * abs(sin(phase * 0.7f))
                Mode.THINKING -> 0.22f
                else -> 0f
            }
            val goal = max(targetLevel, synthetic)
            level += (goal - level) * 0.25f
            phase += 0.25f
            paint.color = when (mode) {
                Mode.LISTENING -> ACCENT_LISTENING
                Mode.THINKING -> ACCENT_THINKING
                Mode.SPEAKING -> ACCENT_SPEAKING
                Mode.IDLE -> ACCENT_IDLE
            }
            val bars = 5
            val gap = width / (bars * 2f + 1f)
            val barWidth = gap
            val h = height.toFloat()
            for (i in 0 until bars) {
                val wobble = 0.55f + 0.45f * abs(sin(phase + i * 0.9f))
                val barH = (h * (0.18f + 0.82f * level * wobble)).coerceIn(h * 0.18f, h)
                val left = gap + i * (barWidth + gap)
                val top = (h - barH) / 2f
                rect.set(left, top, left + barWidth, top + barH)
                canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
            }
            if (running && isAttachedToWindow) postInvalidateOnAnimation()
        }
    }

    private companion object {
        val ACCENT_IDLE = Color.parseColor("#6B7280")
        val ACCENT_LISTENING = Color.parseColor("#00E5FF")
        val ACCENT_THINKING = Color.parseColor("#FBBF24")
        val ACCENT_SPEAKING = Color.parseColor("#34D399")
    }
}
