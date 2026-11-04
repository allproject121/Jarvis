package com.jarvis.assistant.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.util.CustomAvatarHelper
import kotlin.math.cos
import kotlin.math.sin

/**
 * Interactive JARVIS Live Wallpaper Service.
 *
 * Supports two distinct futuristic modes:
 * 1. "character": Live interactive 3D/holographic humanoid AI companion with natural breathing,
 *    blinking eyes, gaze tracking towards user touch, holographic concentric ground stand rings,
 *    and real-time voice state reactivity (Idle, Listening, Thinking, Speaking).
 * 2. "arc_reactor": Stark Arc Reactor holographic HUD with rotating concentric energy rings and
 *    breathing pulse reactor core.
 *
 * Both modes are 100% battery-efficient: rendering pauses completely whenever the home screen is
 * hidden, phone is locked, or another app is opened.
 */
class JarvisWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return JarvisWallpaperEngine()
    }

    private inner class JarvisWallpaperEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private var isVisible = false
        private var width = 1080
        private var height = 2400

        // Animation timing & rotation angles
        private var ringAngle1 = 0f
        private var ringAngle2 = 0f
        private var ringAngle3 = 0f
        private var standAngle1 = 0f
        private var standAngle2 = 0f
        private var pulseTime = 0f

        // Character Gaze & Blinking state
        private var targetLookX = 540f
        private var targetLookY = 1200f
        private var currentLookX = 540f
        private var currentLookY = 1200f
        private var blinkTimer = 0f
        private var isBlinking = false

        // Custom avatar bitmap caching
        private var cachedAvatarBitmap: Bitmap? = null
        private var lastAvatarCheckTime = 0L

        // Touch shockwave state
        private var shockwaveRadius = 0f
        private var shockwaveMaxRadius = 0f
        private var shockwaveAlpha = 0
        private var shockwaveX = 0f
        private var shockwaveY = 0f

        // Reusable geometries
        private val standOval = RectF()
        private val avatarSrcRect = Rect()
        private val avatarDstRect = RectF()
        private val auraOval = RectF()

        // Paints
        private val bgPaint = Paint().apply {
            color = Color.parseColor("#030712")
            style = Paint.Style.FILL
        }

        private val gridPaint = Paint().apply {
            color = Color.parseColor("#10223A")
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val outerRingPaint = Paint().apply {
            color = Color.parseColor("#00E5FF")
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val midRingPaint = Paint().apply {
            color = Color.parseColor("#00B0FF")
            strokeWidth = 2f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val corePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        private val shockwavePaint = Paint().apply {
            color = Color.parseColor("#00E5FF")
            strokeWidth = 4f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val textPaint = Paint().apply {
            color = Color.parseColor("#80D8FF")
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
            letterSpacing = 0.2f
        }

        private val statusSubTextPaint = Paint().apply {
            color = Color.parseColor("#4FC3F7")
            textSize = 20f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            letterSpacing = 0.15f
        }

        private val characterPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

        private val standOuterPaint = Paint().apply {
            color = Color.parseColor("#00E5FF")
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val standInnerPaint = Paint().apply {
            color = Color.parseColor("#00B0FF")
            strokeWidth = 1.8f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val standFillPaint = Paint().apply {
            color = Color.parseColor("#1500E5FF")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val armorPaint = Paint().apply {
            color = Color.parseColor("#1E293B")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val armorOutlinePaint = Paint().apply {
            color = Color.parseColor("#38BDF8")
            strokeWidth = 2f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val visorPaint = Paint().apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val soundwavePaint = Paint().apply {
            color = Color.parseColor("#00E5FF")
            strokeWidth = 2.5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val drawRunnable = object : Runnable {
            override fun run() {
                drawFrame()
                if (isVisible) {
                    handler.postDelayed(this, 16) // ~60 FPS
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            isVisible = visible
            if (visible) {
                targetLookX = width / 2f
                targetLookY = height * 0.45f
                handler.post(drawRunnable)
            } else {
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            shockwaveMaxRadius = (w.coerceAtLeast(h) * 0.8f)
            targetLookX = w / 2f
            targetLookY = h * 0.45f
            currentLookX = targetLookX
            currentLookY = targetLookY
            cachedAvatarBitmap = CustomAvatarHelper.getAvatarBitmap(this@JarvisWallpaperService)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            super.onSurfaceDestroyed(holder)
            isVisible = false
            handler.removeCallbacks(drawRunnable)
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)
            event ?: return
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    targetLookX = event.x
                    targetLookY = event.y
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        shockwaveX = event.x
                        shockwaveY = event.y
                        shockwaveRadius = 15f
                        shockwaveAlpha = 250
                    }
                }
                MotionEvent.ACTION_UP -> {
                    targetLookX = width / 2f
                    targetLookY = height * 0.45f
                }
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    val prefs = JarvisApp.instance.preferences
                    val mode = prefs.wallpaperMode
                    if (mode.equals("arc_reactor", ignoreCase = true)) {
                        renderJarvisHud(canvas)
                    } else {
                        renderCharacterMode(canvas)
                    }
                }
            } catch (ignored: Exception) {
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (ignored: Exception) {}
                }
            }
        }

        /**
         * Renders the Live 3D/Holographic Character Mode:
         * - Concentric rotating energy rings beneath feet (the base stand).
         * - Holographic humanoid character/avatar with natural breathing.
         * - Gaze tracking towards user touch gestures on home screen.
         * - Voice state machine reactivity (Idle, Listening, Thinking, Speaking).
         */
        private fun renderCharacterMode(canvas: Canvas) {
            val cx = width / 2f
            val groundY = height * 0.75f
            pulseTime += 0.04f

            // Smooth gaze lerp
            currentLookX += (targetLookX - currentLookX) * 0.12f
            currentLookY += (targetLookY - currentLookY) * 0.12f
            val lookDeltaX = (currentLookX - cx) / (width * 0.5f) * 16f
            val lookDeltaY = (currentLookY - (height * 0.45f)) / (height * 0.5f) * 10f

            // Check avatar cache every 4 seconds
            val now = SystemClock.elapsedRealtime()
            if (now - lastAvatarCheckTime > 4000) {
                lastAvatarCheckTime = now
                cachedAvatarBitmap = CustomAvatarHelper.getAvatarBitmap(this@JarvisWallpaperService)
            }

            // Blinking timer (~3.5 sec interval, 160ms blink duration)
            blinkTimer += 0.04f
            if (blinkTimer > 3.5f) {
                isBlinking = true
                if (blinkTimer > 3.66f) {
                    isBlinking = false
                    blinkTimer = 0f
                }
            }

            // Sync with Jarvis Live Conversation State
            val convState = try {
                JarvisConversationService.conversationState.value
            } catch (_: Throwable) {
                ConversationState.IDLE
            }
            val audioLevel = try {
                JarvisConversationService.audioLevel.value
            } catch (_: Throwable) {
                0f
            }

            // 1. Deep Space Dark Background with subtle ambient starfield
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            // 2. Subtle Grid
            val gridSpacing = 80f
            var gy = 0f
            while (gy < height) {
                canvas.drawLine(0f, gy, width.toFloat(), gy, gridPaint)
                gy += gridSpacing
            }

            // 3. Ground Concentric Energy Ring Stand (Beneath Feet)
            standAngle1 = (standAngle1 + (if (convState == ConversationState.LISTENING) 1.5f else 0.5f)) % 360f
            standAngle2 = (standAngle2 - 0.8f) % 360f

            val standRadiusX = width * 0.38f
            val standRadiusY = standRadiusX * 0.28f

            // Stand filled platform
            standOval.set(cx - standRadiusX, groundY - standRadiusY, cx + standRadiusX, groundY + standRadiusY)
            canvas.drawOval(standOval, standFillPaint)

            // Outer Ground Ring
            canvas.drawOval(standOval, standOuterPaint)

            // Middle Ground Ring with tick marks
            canvas.save()
            val midStandRadiusX = standRadiusX * 0.75f
            val midStandRadiusY = standRadiusY * 0.75f
            standOval.set(cx - midStandRadiusX, groundY - midStandRadiusY, cx + midStandRadiusX, groundY + midStandRadiusY)
            canvas.drawOval(standOval, standInnerPaint)

            // Ground Stand Ticks
            for (i in 0 until 16) {
                val angleRad = Math.toRadians((i * 22.5 + standAngle1).toDouble())
                val x1 = (cx + midStandRadiusX * cos(angleRad)).toFloat()
                val y1 = (groundY + midStandRadiusY * sin(angleRad)).toFloat()
                val x2 = (cx + (midStandRadiusX + 10f) * cos(angleRad)).toFloat()
                val y2 = (groundY + (midStandRadiusY + 3f) * sin(angleRad)).toFloat()
                canvas.drawLine(x1, y1, x2, y2, standInnerPaint)
            }

            // Inner Core Stand Ring
            val innerStandRadiusX = standRadiusX * 0.45f
            val innerStandRadiusY = standRadiusY * 0.45f
            standOval.set(cx - innerStandRadiusX, groundY - innerStandRadiusY, cx + innerStandRadiusX, groundY + innerStandRadiusY)
            canvas.drawOval(standOval, standOuterPaint)
            canvas.restore()

            // 4. Character Rendering (Breathing Bounce & Holographic Aura)
            val breathingOffset = sin(pulseTime.toDouble() * 1.8).toFloat() * 7f
            val charHeight = height * 0.42f
            val charWidth = charHeight * 0.55f
            val charTop = groundY - charHeight + breathingOffset

            // Ambient Holographic Aura behind Character
            auraOval.set(cx - charWidth * 0.7f, charTop, cx + charWidth * 0.7f, groundY)
            val auraColor = when (convState) {
                ConversationState.SPEAKING -> Color.parseColor("#3000E5FF")
                ConversationState.LISTENING -> Color.parseColor("#3500E676")
                ConversationState.THINKING -> Color.parseColor("#35FFD600")
                else -> Color.parseColor("#2000B0FF")
            }
            val auraPaint = Paint().apply {
                isAntiAlias = true
                shader = RadialGradient(
                    cx, charTop + charHeight * 0.4f, charWidth * 0.9f,
                    intArrayOf(auraColor, Color.TRANSPARENT),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawOval(auraOval, auraPaint)

            val avatarBmp = cachedAvatarBitmap
            if (avatarBmp != null && !avatarBmp.isRecycled) {
                // Draw custom user/Jett 3D model texture
                avatarSrcRect.set(0, 0, avatarBmp.width, avatarBmp.height)
                val drawLeft = cx - charWidth / 2f + lookDeltaX * 0.5f
                val drawTop = charTop + lookDeltaY * 0.3f
                avatarDstRect.set(drawLeft, drawTop, drawLeft + charWidth, drawTop + charHeight)
                canvas.drawBitmap(avatarBmp, avatarSrcRect, avatarDstRect, characterPaint)
            } else {
                // Procedural Cyberpunk Humanoid Agent Silhouette
                drawProceduralHumanoid(canvas, cx, charTop, charWidth, charHeight, groundY, lookDeltaX, lookDeltaY)
            }

            // 5. Reactive Voice State Visuals
            when (convState) {
                ConversationState.SPEAKING -> {
                    // Expanding voice ripples
                    val waveAmp = (audioLevel * 40f).coerceIn(10f, 60f)
                    val mouthY = charTop + charHeight * 0.22f + lookDeltaY
                    for (w in 1..3) {
                        val r = waveAmp * w * 0.6f + (pulseTime * 15f % 30f)
                        soundwavePaint.alpha = (200 - (w * 50)).coerceIn(20, 255)
                        canvas.drawArc(
                            cx + lookDeltaX - r, mouthY - r * 0.6f,
                            cx + lookDeltaX + r, mouthY + r * 0.6f,
                            -45f, 90f, false, soundwavePaint
                        )
                    }
                }
                ConversationState.LISTENING -> {
                    // Attentive listening halo above head
                    val headY = charTop + charHeight * 0.12f
                    soundwavePaint.color = Color.parseColor("#00E676")
                    val pulseR = 35f + sin(pulseTime.toDouble() * 5.0).toFloat() * 10f
                    canvas.drawCircle(cx + lookDeltaX, headY, pulseR, soundwavePaint)
                    soundwavePaint.color = Color.parseColor("#00E5FF")
                }
                ConversationState.THINKING -> {
                    // Orbiting cyber thought particles
                    val headY = charTop + charHeight * 0.12f
                    for (p in 0..2) {
                        val angle = pulseTime * 3f + (p * 2.094f)
                        val px = cx + lookDeltaX + cos(angle.toDouble()).toFloat() * 45f
                        val py = headY + sin(angle.toDouble()).toFloat() * 20f
                        canvas.drawCircle(px, py, 4f, outerRingPaint)
                    }
                }
                ConversationState.IDLE -> {}
            }

            // 6. Holographic Status Badge & Pedestal HUD
            val statusLabel = when (convState) {
                ConversationState.SPEAKING -> "JARVIS · SPEAKING"
                ConversationState.LISTENING -> "JARVIS · LISTENING..."
                ConversationState.THINKING -> "JARVIS · PROCESSING..."
                else -> "JARVIS 3D · STANDBY"
            }
            canvas.drawText(statusLabel, cx, groundY + standRadiusY + 50f, textPaint)
            canvas.drawText("HOME SCREEN AGENT ACTIVE", cx, groundY + standRadiusY + 80f, statusSubTextPaint)

            // 7. Interactive Touch Shockwave
            if (shockwaveAlpha > 0) {
                shockwavePaint.alpha = shockwaveAlpha
                canvas.drawCircle(shockwaveX, shockwaveY, shockwaveRadius, shockwavePaint)
                shockwaveRadius += 16f
                shockwaveAlpha = (shockwaveAlpha - 7).coerceAtLeast(0)
            }
        }

        /**
         * Procedural Humanoid Drawing:
         * Sleek cybernetic armored silhouette standing upright on the pedestal:
         * - Sleek cybernetic legs anchored firmly to the ground ring stand.
         * - Armored torso with glowing chest Arc Reactor.
         * - Arms resting naturally at sides.
         * - Cyber helm with animated glowing visor and gaze-tracking eyes.
         */
        private fun drawProceduralHumanoid(
            canvas: Canvas,
            cx: Float,
            top: Float,
            w: Float,
            h: Float,
            groundY: Float,
            lookX: Float,
            lookY: Float
        ) {
            val headRadius = w * 0.22f
            val headCenterY = top + headRadius + 10f

            // Torso
            val torsoTop = headCenterY + headRadius + 12f
            val torsoBottom = top + h * 0.58f
            val torsoLeft = cx - w * 0.35f
            val torsoRight = cx + w * 0.35f
            val torsoRect = RectF(torsoLeft, torsoTop, torsoRight, torsoBottom)
            canvas.drawRoundRect(torsoRect, 18f, 18f, armorPaint)
            canvas.drawRoundRect(torsoRect, 18f, 18f, armorOutlinePaint)

            // Stark Arc Reactor Core at Center Chest
            val coreY = torsoTop + (torsoBottom - torsoTop) * 0.32f
            corePaint.color = Color.parseColor("#00E5FF")
            canvas.drawCircle(cx, coreY, 14f, corePaint)
            canvas.drawCircle(cx, coreY, 20f, armorOutlinePaint)

            // Left & Right Arms resting naturally at sides
            val armWidth = w * 0.16f
            val leftArm = RectF(torsoLeft - armWidth - 6f, torsoTop + 6f, torsoLeft - 6f, torsoBottom + 20f)
            val rightArm = RectF(torsoRight + 6f, torsoTop + 6f, torsoRight + armWidth + 6f, torsoBottom + 20f)
            canvas.drawRoundRect(leftArm, 10f, 10f, armorPaint)
            canvas.drawRoundRect(leftArm, 10f, 10f, armorOutlinePaint)
            canvas.drawRoundRect(rightArm, 10f, 10f, armorPaint)
            canvas.drawRoundRect(rightArm, 10f, 10f, armorOutlinePaint)

            // Left & Right Legs anchored firmly to ground
            val legWidth = w * 0.22f
            val legLeft = RectF(cx - legWidth - 6f, torsoBottom + 4f, cx - 6f, groundY - 4f)
            val legRight = RectF(cx + 6f, torsoBottom + 4f, cx + legWidth + 6f, groundY - 4f)
            canvas.drawRoundRect(legLeft, 12f, 12f, armorPaint)
            canvas.drawRoundRect(legLeft, 12f, 12f, armorOutlinePaint)
            canvas.drawRoundRect(legRight, 12f, 12f, armorPaint)
            canvas.drawRoundRect(legRight, 12f, 12f, armorOutlinePaint)

            // Head & Cyber Helm
            val headX = cx + lookX * 0.4f
            val headY = headCenterY + lookY * 0.2f
            canvas.drawCircle(headX, headY, headRadius, armorPaint)
            canvas.drawCircle(headX, headY, headRadius, armorOutlinePaint)

            // Cyber Visor / Eyes (with blinking & gaze tracking)
            if (!isBlinking) {
                val eyeWidth = headRadius * 0.7f
                val eyeHeight = headRadius * 0.28f
                val eyeRect = RectF(
                    headX - eyeWidth / 2f + lookX * 0.5f,
                    headY - eyeHeight / 2f + lookY * 0.4f,
                    headX + eyeWidth / 2f + lookX * 0.5f,
                    headY + eyeHeight / 2f + lookY * 0.4f
                )
                canvas.drawRoundRect(eyeRect, 6f, 6f, visorPaint)
            } else {
                // Narrow slit while blinking
                canvas.drawLine(
                    headX - headRadius * 0.35f, headY,
                    headX + headRadius * 0.35f, headY,
                    armorOutlinePaint
                )
            }
        }

        /**
         * Renders the Classic Stark Arc Reactor HUD Mode:
         * Concentric rotating HUD rings, tick marks, rotating gyro arcs, and pulsing core.
         */
        private fun renderJarvisHud(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val baseRadius = width * 0.32f

            // 1. Background
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            // 2. Subtle Stark Radar Crosshair Grid
            canvas.drawLine(cx - baseRadius * 1.6f, cy, cx + baseRadius * 1.6f, cy, gridPaint)
            canvas.drawLine(cx, cy - baseRadius * 1.6f, cx, cy + baseRadius * 1.6f, gridPaint)
            canvas.drawCircle(cx, cy, baseRadius * 1.35f, gridPaint)

            // 3. Update rotation angles
            ringAngle1 = (ringAngle1 + 0.6f) % 360f
            ringAngle2 = (ringAngle2 - 0.4f) % 360f
            ringAngle3 = (ringAngle3 + 1.2f) % 360f
            pulseTime += 0.04f

            // 4. Outer Rotating Segmented HUD Ring
            canvas.save()
            canvas.rotate(ringAngle1, cx, cy)
            val outerRadius = baseRadius * 1.15f
            for (i in 0 until 12) {
                val startAngle = i * 30f + 4f
                val sweepAngle = 22f
                canvas.drawArc(
                    cx - outerRadius, cy - outerRadius,
                    cx + outerRadius, cy + outerRadius,
                    startAngle, sweepAngle, false, outerRingPaint
                )
            }
            canvas.restore()

            // 5. Middle Ring with Tick Marks
            canvas.save()
            canvas.rotate(ringAngle2, cx, cy)
            val midRadius = baseRadius * 0.9f
            canvas.drawCircle(cx, cy, midRadius, midRingPaint)
            for (i in 0 until 36) {
                val rad = Math.toRadians((i * 10).toDouble())
                val innerR = if (i % 3 == 0) midRadius - 16f else midRadius - 8f
                val x1 = (cx + innerR * cos(rad)).toFloat()
                val y1 = (cy + innerR * sin(rad)).toFloat()
                val x2 = (cx + midRadius * cos(rad)).toFloat()
                val y2 = (cy + midRadius * sin(rad)).toFloat()
                canvas.drawLine(x1, y1, x2, y2, midRingPaint)
            }
            canvas.restore()

            // 6. Inner Fast Rotating Gyro Ring
            canvas.save()
            canvas.rotate(ringAngle3, cx, cy)
            val innerRadius = baseRadius * 0.65f
            for (i in 0 until 6) {
                val startAngle = i * 60f + 8f
                val sweepAngle = 44f
                canvas.drawArc(
                    cx - innerRadius, cy - innerRadius,
                    cx + innerRadius, cy + innerRadius,
                    startAngle, sweepAngle, false, outerRingPaint
                )
            }
            canvas.restore()

            // 7. Glowing Arc Reactor Core (Breathing Pulse)
            val pulseScale = 1.0f + 0.08f * sin(pulseTime.toDouble()).toFloat()
            val coreRadius = baseRadius * 0.38f * pulseScale
            corePaint.shader = RadialGradient(
                cx, cy, coreRadius,
                intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#00E5FF"), Color.parseColor("#002171"), Color.TRANSPARENT),
                floatArrayOf(0.0f, 0.45f, 0.85f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, coreRadius, corePaint)

            // 8. JARVIS Holographic Status Text
            canvas.drawText("JARVIS · SYSTEM ACTIVE", cx, cy + baseRadius * 1.55f, textPaint)

            // 9. Interactive Touch Shockwave
            if (shockwaveAlpha > 0) {
                shockwavePaint.alpha = shockwaveAlpha
                canvas.drawCircle(shockwaveX, shockwaveY, shockwaveRadius, shockwavePaint)
                shockwaveRadius += 16f
                shockwaveAlpha = (shockwaveAlpha - 7).coerceAtLeast(0)
            }
        }
    }
}
