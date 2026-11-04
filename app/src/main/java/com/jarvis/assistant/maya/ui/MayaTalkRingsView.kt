package com.jarvis.assistant.maya.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Ripple rings around the big circular talk button. Rings expand outward while
 * listening/speaking (audio-reactive), breathe softly when idle with a live
 * session, and a one-off [burst] marks state changes.
 */
class MayaTalkRingsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var state = "idle"
    private var sessionOn = false
    private var level = 0f
    private var smoothLevel = 0f
    private var color = MayaPalette.PRIMARY
    private var targetColor = MayaPalette.PRIMARY
    private var burstT = -1f
    private var t = 0f
    private var lastNs = 0L
    private var running = false

    /** Radius of the button the rings surround, in px (rings start just outside it). */
    var buttonRadiusPx: Float = 42f * density

    fun setState(stateKey: String, isSessionOn: Boolean, accent: Int) {
        state = stateKey.lowercase()
        sessionOn = isSessionOn
        targetColor = when (state) {
            "listening" -> MayaPalette.CYAN_GLOW
            "thinking" -> MayaPalette.VIOLET_LIGHT
            "speaking" -> MayaPalette.LIGHT_BLUE
            else -> accent
        }
        updateRunning()
    }

    fun setLevel(v: Float) {
        level = v.coerceIn(0f, 1f)
    }

    fun burst() {
        burstT = 0f
        updateRunning()
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun wantsFrames(): Boolean =
        isAttachedToWindow && visibility == VISIBLE && windowVisibility == VISIBLE &&
            (sessionOn || state != "idle" || burstT >= 0f)

    private fun updateRunning() {
        val should = wantsFrames()
        if (should == running) {
            if (!should) invalidate()
            return
        }
        running = should
        if (running) {
            lastNs = System.nanoTime()
            Choreographer.getInstance().postFrameCallback(frame)
        } else {
            Choreographer.getInstance().removeFrameCallback(frame)
            invalidate()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateRunning() }
    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        super.onDetachedFromWindow()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); updateRunning() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateRunning() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else min(0.1f, (now - lastNs) / 1_000_000_000f)
        lastNs = now
        t += dt
        smoothLevel += (level - smoothLevel) * min(1f, dt * 12f)
        color = MayaPalette.mix(color, targetColor, min(1f, dt * 6f))

        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f - 2f * density
        val r0 = buttonRadiusPx + 2f * density
        val span = max(1f, maxR - r0)

        // soft glow disc right behind the button
        val glowA = when (state) { "listening", "speaking" -> 0.22f + smoothLevel * 0.25f; "thinking" -> 0.16f; else -> if (sessionOn) 0.12f else 0.07f }
        fillPaint.color = MayaPalette.withAlpha(color, glowA)
        canvas.drawCircle(cx, cy, r0 + span * 0.22f + smoothLevel * span * 0.2f, fillPaint)

        val active = state == "listening" || state == "speaking"
        if (active) {
            val speed = if (state == "speaking") 0.9f + smoothLevel * 0.8f else 0.65f
            for (k in 0 until 3) {
                val ph = ((t * speed) + k / 3f) % 1f
                val rr = r0 + ph * span
                ringPaint.strokeWidth = (2.6f - ph * 1.8f) * density * (1f + smoothLevel * 0.6f)
                ringPaint.color = MayaPalette.withAlpha(color, (0.75f + smoothLevel * 0.25f) * (1f - ph))
                canvas.drawCircle(cx, cy, rr, ringPaint)
            }
        } else if (state == "thinking") {
            val rr = r0 + span * (0.3f + 0.08f * sin(t * 2.4f))
            ringPaint.strokeWidth = 2f * density
            ringPaint.color = MayaPalette.withAlpha(color, 0.55f)
            canvas.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, (t * 220f) % 360f, 100f, false, ringPaint)
            canvas.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, (t * 220f + 180f) % 360f, 100f, false, ringPaint)
        } else {
            val breathe = 0.5f + 0.5f * sin(t * 1.8f)
            val rr = r0 + span * (0.18f + 0.06f * breathe)
            ringPaint.strokeWidth = 1.4f * density
            ringPaint.color = MayaPalette.withAlpha(color, if (sessionOn) 0.45f + 0.25f * breathe else 0.3f)
            canvas.drawCircle(cx, cy, rr, ringPaint)
        }

        if (burstT >= 0f) {
            burstT += dt
            val k = burstT / 0.6f
            if (k >= 1f) {
                burstT = -1f
                updateRunning()
            } else {
                ringPaint.strokeWidth = 3f * density * (1f - k)
                ringPaint.color = MayaPalette.withAlpha(color, 0.9f * (1f - k))
                canvas.drawCircle(cx, cy, r0 + span * k, ringPaint)
            }
        }
    }
}
