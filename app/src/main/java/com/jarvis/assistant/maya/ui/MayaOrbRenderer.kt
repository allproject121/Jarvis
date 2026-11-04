package com.jarvis.assistant.maya.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.jarvis.assistant.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Canvas renderer for the Maya orb: the neon stage background (radial electric-blue
 * glow, circuit traces like the launcher icon, travelling pulses), rotating neon
 * rings, a breathing audio-reactive core, floating particles and state effects
 * (listening ripples, thinking motes, speaking waveform).
 *
 * Used by [MayaOrbView] (battery-saver / low-RAM fallback on the Home stage) and by
 * the live wallpaper's `maya_character` mode. No allocations in the steady state.
 */
class MayaOrbRenderer(context: Context?) {

    var accent: Int = MayaPalette.PRIMARY
        set(value) { if (field != value) { field = value; bgDirty = true; circuitsDirty = true } }
    var secondary: Int = MayaPalette.LIGHT_BLUE
    var glow: Int = MayaPalette.CYAN_GLOW
    var light: Boolean = false
        set(value) { if (field != value) { field = value; bgDirty = true; circuitsDirty = true } }
    var lowPower: Boolean = false
        set(value) { if (field != value) { field = value; seedParticles() } }

    /** Vertical position of the orb centre as a fraction of the height. */
    var centerYFraction: Float = 0.42f

    private val density: Float = context?.resources?.displayMetrics?.density ?: 2.75f
    private val typeface: Typeface = try {
        context?.let { ResourcesCompat.getFont(it, R.font.inter_semibold) } ?: Typeface.DEFAULT_BOLD
    } catch (_: Throwable) {
        Typeface.DEFAULT_BOLD
    }

    private var w = 0
    private var h = 0
    private var bgDirty = true
    private var circuitsDirty = true

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circuitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val circuitGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val oval = RectF()
    private val wavePath = Path()
    private val circuitPath = Path()
    private val padPoints = ArrayList<FloatArray>()
    private val traces = ArrayList<FloatArray>() // flattened polylines x0,y0,x1,y1,...
    private val traceLengths = ArrayList<Float>()

    private val coreMatrix = Matrix()
    private val haloMatrix = Matrix()
    private var coreShader: RadialGradient? = null
    private var coreShaderColor = 0
    private var haloShader: RadialGradient? = null
    private var haloShaderColor = 0

    // particles
    private val maxParticles = 64
    private val px = FloatArray(maxParticles)
    private val py = FloatArray(maxParticles)
    private val pSpeed = FloatArray(maxParticles)
    private val pPhase = FloatArray(maxParticles)
    private val pSize = FloatArray(maxParticles)
    private var particleCount = 0

    // pulses travelling along circuits
    private val pulseTrace = IntArray(6)
    private val pulseU = FloatArray(6)
    private val pulseSpeed = FloatArray(6)

    // smoothed values
    private var level = 0f
    private var stateColor = MayaPalette.PRIMARY
    private var ringAngle1 = 0f
    private var ringAngle2 = 0f
    private var ringAngle3 = 0f

    // touch shockwave
    private var shockX = 0f
    private var shockY = 0f
    private var shockR = 0f
    private var shockA = 0f

    private val rnd = java.util.Random(4242)

    fun setSize(width: Int, height: Int) {
        if (width == w && height == h) return
        w = max(1, width)
        h = max(1, height)
        bgDirty = true
        circuitsDirty = true
        seedParticles()
    }

    fun touch(x: Float, y: Float) {
        shockX = x
        shockY = y
        shockR = 12f * density
        shockA = 1f
    }

    private fun dp(v: Float) = v * density

    private fun seedParticles() {
        particleCount = if (lowPower) 24 else maxParticles
        val r = java.util.Random(99)
        for (i in 0 until maxParticles) {
            px[i] = r.nextFloat() * max(1, w)
            py[i] = r.nextFloat() * max(1, h)
            pSpeed[i] = dp(8f + r.nextFloat() * 22f)
            pPhase[i] = r.nextFloat() * 6.283f
            pSize[i] = dp(0.8f + r.nextFloat() * 1.8f)
        }
    }

    private fun rebuildBackground() {
        bgDirty = false
        val cx = w / 2f
        val cy = h * centerYFraction
        val radius = max(w, h) * 0.8f
        val colors = if (light) intArrayOf(
            MayaPalette.withAlpha(accent, 0.28f), MayaPalette.TINT_SOFTER, 0xFFD6E4F5.toInt()
        ) else intArrayOf(
            MayaPalette.withAlpha(accent, 0.55f), MayaPalette.withAlpha(accent, 0.18f),
            MayaPalette.withAlpha(MayaPalette.BG, 0.94f), MayaPalette.BG_EDGE
        )
        val stops = if (light) floatArrayOf(0f, 0.55f, 1f) else floatArrayOf(0f, 0.3f, 0.62f, 1f)
        bgPaint.shader = RadialGradient(cx, cy, radius, colors, stops, Shader.TileMode.CLAMP)
    }

    private fun rebuildCircuits() {
        circuitsDirty = false
        traces.clear(); traceLengths.clear(); padPoints.clear()
        circuitPath.reset()
        val r = java.util.Random(1337)
        for (side in intArrayOf(-1, 1)) {
            val dir = if (side < 0) 1f else -1f
            val xEdge = if (side < 0) 0f else w.toFloat()
            for (i in 0 until 7) {
                val y0 = h * (0.2f + i * 0.08f + r.nextFloat() * 0.025f)
                val x1 = xEdge + dir * w * (0.05f + r.nextFloat() * 0.11f)
                val dy = (if (r.nextBoolean()) -1f else 1f) * h * (0.012f + r.nextFloat() * 0.035f)
                val x2 = x1 + dir * abs(dy)
                var x3 = x2 + dir * w * (0.025f + r.nextFloat() * 0.06f)
                x3 = if (dir > 0) min(x3, w * 0.3f) else max(x3, w * 0.7f)
                val pts = floatArrayOf(xEdge, y0, x1, y0, x2, y0 + dy, x3, y0 + dy)
                traces.add(pts)
                circuitPath.moveTo(pts[0], pts[1])
                var len = 0f
                var k = 2
                while (k < pts.size) {
                    circuitPath.lineTo(pts[k], pts[k + 1])
                    len += hypot(pts[k] - pts[k - 2], pts[k + 1] - pts[k - 1])
                    k += 2
                }
                traceLengths.add(len)
                padPoints.add(floatArrayOf(x3, y0 + dy))
            }
        }
        for (i in pulseTrace.indices) {
            pulseTrace[i] = r.nextInt(max(1, traces.size))
            pulseU[i] = r.nextFloat()
            pulseSpeed[i] = 0.15f + r.nextFloat() * 0.22f
        }
    }

    private fun colorForState(state: String): Int = when (state) {
        "listening" -> glow
        "thinking" -> MayaPalette.VIOLET_LIGHT
        "speaking" -> secondary
        else -> accent
    }

    /**
     * Draw one frame.
     * @param t seconds since start, [dt] seconds since the last frame,
     * [state] idle|listening|thinking|speaking, [audioLevel] 0..1.
     */
    fun draw(
        canvas: Canvas,
        t: Float,
        dt: Float,
        state: String,
        audioLevel: Float,
        drawBackground: Boolean = true,
        title: String? = null,
        subtitle: String? = null
    ) {
        if (w <= 1 || h <= 1) return
        if (bgDirty) rebuildBackground()
        if (circuitsDirty) rebuildCircuits()
        val step = min(0.1f, max(0.001f, dt))

        level += (audioLevel.coerceIn(0f, 1f) - level) * min(1f, step * 14f)
        stateColor = MayaPalette.mix(stateColor, colorForState(state), min(1f, step * 5f))
        val speaking = state == "speaking"

        val cx = w / 2f
        val cy = h * centerYFraction
        val base = min(w, h) * 0.17f

        // 1. background + circuitry
        if (drawBackground) {
            canvas.drawColor(if (light) MayaPalette.TINT_SOFTER else Color.BLACK)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
            val breathe = 0.5f + 0.5f * sin(t * (if (state == "thinking") 2.6f else 1.15f))
            circuitGlowPaint.color = MayaPalette.withAlpha(accent, (if (light) 0.08f else 0.16f) * (0.6f + 0.4f * breathe))
            circuitGlowPaint.strokeWidth = dp(5f)
            circuitPaint.color = MayaPalette.withAlpha(accent, (if (light) 0.35f else 0.62f) * (0.55f + 0.45f * breathe))
            circuitPaint.strokeWidth = dp(1.2f)
            canvas.drawPath(circuitPath, circuitGlowPaint)
            canvas.drawPath(circuitPath, circuitPaint)
            for (p in padPoints) {
                canvas.drawCircle(p[0], p[1], dp(3.2f), circuitPaint)
                dotPaint.color = MayaPalette.withAlpha(secondary, 0.9f)
                canvas.drawCircle(p[0], p[1], dp(1.2f), dotPaint)
            }
            if (!lowPower && traces.isNotEmpty()) {
                val speedK = if (state == "thinking") 2.2f else if (speaking) 1.5f else 1f
                for (i in pulseTrace.indices) {
                    pulseU[i] += pulseSpeed[i] * step * speedK
                    if (pulseU[i] > 1f) { pulseU[i] = 0f; pulseTrace[i] = rnd.nextInt(traces.size) }
                    val idx = pulseTrace[i].coerceIn(0, traces.size - 1)
                    pointOnTrace(traces[idx], traceLengths[idx], pulseU[i])
                    dotPaint.color = MayaPalette.withAlpha(glow, 0.35f)
                    canvas.drawCircle(tmpX, tmpY, dp(4.5f), dotPaint)
                    dotPaint.color = MayaPalette.withAlpha(Color.WHITE, 0.95f)
                    canvas.drawCircle(tmpX, tmpY, dp(1.6f), dotPaint)
                }
            }
        }

        // 2. particles (slow while thinking, livelier while speaking)
        val pSpeedK = if (state == "thinking") 0.35f else if (speaking) 1f + level else 1f
        for (i in 0 until particleCount) {
            py[i] -= pSpeed[i] * step * pSpeedK
            px[i] += sin(t * 0.6f + pPhase[i]) * dp(4f) * step
            if (py[i] < -dp(4f)) { py[i] = h + dp(4f); px[i] = rnd.nextFloat() * w }
            val tw = 0.45f + 0.55f * abs(sin(t * 1.3f + pPhase[i]))
            val col = when (i % 3) { 0 -> glow; 1 -> secondary; else -> accent }
            dotPaint.color = MayaPalette.withAlpha(col, 0.18f * tw)
            canvas.drawCircle(px[i], py[i], pSize[i] * 2.6f, dotPaint)
            dotPaint.color = MayaPalette.withAlpha(col, 0.85f * tw)
            canvas.drawCircle(px[i], py[i], pSize[i], dotPaint)
        }

        // 3. halo glow behind the orb
        val haloR = base * (2.7f + level * 0.6f)
        if (haloShader == null || haloShaderColor != stateColor) {
            haloShaderColor = stateColor
            haloShader = RadialGradient(0f, 0f, 1f,
                intArrayOf(MayaPalette.withAlpha(stateColor, 0.42f), MayaPalette.withAlpha(stateColor, 0.12f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        haloMatrix.reset(); haloMatrix.setScale(haloR, haloR); haloMatrix.postTranslate(cx, cy)
        haloShader?.setLocalMatrix(haloMatrix)
        haloPaint.shader = haloShader
        canvas.drawCircle(cx, cy, haloR, haloPaint)

        // 4. rotating neon rings
        val ringSpeed = when (state) { "listening" -> 1.8f; "thinking" -> 0.6f; "speaking" -> 1.2f + level; else -> 1f }
        ringAngle1 = (ringAngle1 + 22f * ringSpeed * step) % 360f
        ringAngle2 = (ringAngle2 - 14f * ringSpeed * step) % 360f
        ringAngle3 = (ringAngle3 + 40f * ringSpeed * step) % 360f
        drawSegmentedRing(canvas, cx, cy, base * 1.55f, ringAngle1, 12, 22f, dp(2.4f), stateColor, 0.9f)
        drawSegmentedRing(canvas, cx, cy, base * 1.88f, ringAngle2, 36, 4f, dp(1.4f), secondary, 0.55f)
        drawSegmentedRing(canvas, cx, cy, base * 1.28f, ringAngle3, 3, 70f, dp(1.8f), glow, 0.7f)

        // 5. state effects
        when (state) {
            "listening" -> for (k in 0 until 3) {
                val ph = ((t * 0.7f) + k / 3f) % 1f
                val rr = base * (1.2f + ph * 1.6f)
                ringPaint.strokeWidth = dp(2f) * (1f - ph)
                ringPaint.color = MayaPalette.withAlpha(stateColor, 0.6f * (1f - ph))
                canvas.drawCircle(cx, cy, rr, ringPaint)
            }
            "thinking" -> for (k in 0 until 6) {
                val a = t * 0.9f + k * (2f * PI.toFloat() / 6f)
                val rr = base * (1.42f + 0.08f * sin(t * 2f + k))
                val mx = cx + cos(a) * rr
                val my = cy + sin(a) * rr * 0.92f
                dotPaint.color = MayaPalette.withAlpha(stateColor, 0.3f)
                canvas.drawCircle(mx, my, dp(6f), dotPaint)
                dotPaint.color = MayaPalette.withAlpha(Color.WHITE, 0.9f)
                canvas.drawCircle(mx, my, dp(2f), dotPaint)
            }
            "speaking" -> {
                wavePath.reset()
                val n = 96
                for (i in 0..n) {
                    val a = i / n.toFloat() * 2f * PI.toFloat()
                    val wob = sin(a * 6f + t * 7f) * 0.5f + sin(a * 11f - t * 9f) * 0.3f + sin(a * 3f + t * 3f) * 0.2f
                    val rr = base * (1.12f + (0.05f + level * 0.32f) * wob)
                    val x = cx + cos(a) * rr
                    val y = cy + sin(a) * rr
                    if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
                }
                wavePath.close()
                wavePaint.strokeWidth = dp(5f)
                wavePaint.color = MayaPalette.withAlpha(stateColor, 0.18f)
                canvas.drawPath(wavePath, wavePaint)
                wavePaint.strokeWidth = dp(1.8f)
                wavePaint.color = MayaPalette.withAlpha(stateColor, 0.9f)
                canvas.drawPath(wavePath, wavePaint)
            }
        }

        // 6. breathing, audio-reactive core
        val coreR = base * (0.92f + 0.06f * sin(t * 1.7f) + level * 0.28f)
        if (coreShader == null || coreShaderColor != stateColor) {
            coreShaderColor = stateColor
            coreShader = RadialGradient(0f, 0f, 1f,
                intArrayOf(Color.WHITE, MayaPalette.mix(Color.WHITE, stateColor, 0.55f), stateColor, MayaPalette.withAlpha(stateColor, 0.35f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.18f, 0.5f, 0.82f, 1f), Shader.TileMode.CLAMP)
        }
        coreMatrix.reset(); coreMatrix.setScale(coreR, coreR); coreMatrix.postTranslate(cx, cy)
        coreShader?.setLocalMatrix(coreMatrix)
        corePaint.shader = coreShader
        canvas.drawCircle(cx, cy, coreR, corePaint)
        // inner swirl arcs
        oval.set(cx - coreR * 0.62f, cy - coreR * 0.62f, cx + coreR * 0.62f, cy + coreR * 0.62f)
        ringPaint.strokeWidth = dp(1.6f)
        ringPaint.color = MayaPalette.withAlpha(Color.WHITE, 0.55f)
        canvas.drawArc(oval, ringAngle3 * 1.5f, 80f, false, ringPaint)
        canvas.drawArc(oval, ringAngle3 * 1.5f + 180f, 80f, false, ringPaint)

        // 7. touch shockwave
        if (shockA > 0f) {
            ringPaint.strokeWidth = dp(2.4f)
            ringPaint.color = MayaPalette.withAlpha(stateColor, shockA)
            canvas.drawCircle(shockX, shockY, shockR, ringPaint)
            shockR += dp(420f) * step
            shockA = max(0f, shockA - step * 1.6f)
        }

        // 8. optional labels (wallpaper)
        if (title != null) {
            titlePaint.typeface = typeface
            titlePaint.textSize = dp(17f)
            titlePaint.letterSpacing = 0.02f
            titlePaint.color = if (light) 0xFF0F172A.toInt() else MayaPalette.TEXT
            titlePaint.setShadowLayer(dp(10f), 0f, 0f, MayaPalette.withAlpha(stateColor, if (light) 0f else 0.8f))
            canvas.drawText(title, cx, cy + base * 2.35f, titlePaint)
            if (subtitle != null) {
                subPaint.typeface = typeface
                subPaint.textSize = dp(12.5f)
                subPaint.color = if (light) 0xFF475569.toInt() else MayaPalette.TEXT_SECONDARY
                canvas.drawText(subtitle, cx, cy + base * 2.35f + dp(22f), subPaint)
            }
        }
    }

    private fun drawSegmentedRing(
        canvas: Canvas, cx: Float, cy: Float, r: Float, startAngle: Float,
        segments: Int, sweep: Float, stroke: Float, color: Int, alpha: Float
    ) {
        oval.set(cx - r, cy - r, cx + r, cy + r)
        val stepAngle = 360f / segments
        glowPaint.strokeWidth = stroke * 3.2f
        glowPaint.color = MayaPalette.withAlpha(color, alpha * 0.18f)
        ringPaint.strokeWidth = stroke
        ringPaint.color = MayaPalette.withAlpha(color, alpha)
        for (i in 0 until segments) {
            val a = startAngle + i * stepAngle
            canvas.drawArc(oval, a, sweep, false, glowPaint)
            canvas.drawArc(oval, a, sweep, false, ringPaint)
        }
    }

    private var tmpX = 0f
    private var tmpY = 0f
    private fun pointOnTrace(pts: FloatArray, len: Float, u: Float) {
        var d = u * len
        var k = 2
        while (k < pts.size) {
            val seg = hypot(pts[k] - pts[k - 2], pts[k + 1] - pts[k - 1])
            if (d <= seg || k == pts.size - 2) {
                val f = if (seg > 0f) (d / seg).coerceIn(0f, 1f) else 0f
                tmpX = pts[k - 2] + (pts[k] - pts[k - 2]) * f
                tmpY = pts[k - 1] + (pts[k + 1] - pts[k - 1]) * f
                return
            }
            d -= seg
            k += 2
        }
        tmpX = pts[pts.size - 2]
        tmpY = pts[pts.size - 1]
    }
}
