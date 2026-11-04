package com.jarvis.assistant.maya.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View

/**
 * Native animated Maya orb (no WebGL). Shown on the Home stage instead of the
 * 3D character when battery saver is on, the phone has < 3 GB RAM, or the
 * character is switched off. Rendering stops whenever the view is detached or
 * not visible.
 */
class MayaOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val renderer = MayaOrbRenderer(context)
    private var running = false
    private var startNs = 0L
    private var lastNs = 0L
    private var frameSkip = false
    private var targetLevel = 0f

    var state: String = "idle"
        set(value) { field = value.lowercase() }

    var drawStageBackground: Boolean = true

    var lowPower: Boolean
        get() = renderer.lowPower
        set(value) { renderer.lowPower = value }

    var light: Boolean
        get() = renderer.light
        set(value) { renderer.light = value }

    var centerYFraction: Float
        get() = renderer.centerYFraction
        set(value) { renderer.centerYFraction = value }

    fun setAccent(primary: Int, secondary: Int = MayaPalette.LIGHT_BLUE, glow: Int = MayaPalette.CYAN_GLOW) {
        renderer.accent = primary
        renderer.secondary = secondary
        renderer.glow = glow
    }

    fun setAudioLevel(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            frameSkip = !frameSkip
            if (!(renderer.lowPower && frameSkip)) invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun updateRunning() {
        val shouldRun = isAttachedToWindow && visibility == VISIBLE && windowVisibility == VISIBLE
        if (shouldRun == running) return
        running = shouldRun
        if (running) {
            lastNs = System.nanoTime()
            if (startNs == 0L) startNs = lastNs
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRunning()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunning()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        renderer.setSize(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        if (startNs == 0L) startNs = now
        val dt = ((now - lastNs).coerceAtLeast(0L)) / 1_000_000_000f
        lastNs = now
        val t = (now - startNs) / 1_000_000_000f
        renderer.draw(canvas, t, dt, state, targetLevel, drawStageBackground)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            renderer.touch(event.x, event.y)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return super.onTouchEvent(event) || isClickable
    }

    override fun performClick(): Boolean = super.performClick()
}
