package com.jarvis.assistant.voice.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating system overlay window (TYPE_APPLICATION_OVERLAY) for Jarvis Voice Assistant.
 * Renders an animated glowing holographic orb, live speech transcription, and assistant replies
 * on top of any active Android application.
 */
class VoiceOverlayWindow(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: FrameLayout? = null
    private var orbView: View? = null
    private var statusText: TextView? = null
    private var transcriptText: TextView? = null
    private var responseText: TextView? = null
    private var pulseAnimator: ValueAnimator? = null

    val isShowing: Boolean get() = overlayView != null

    fun show(initialStatus: String = "Listening...") {
        if (overlayView != null) {
            updateStatus(initialStatus)
            return
        }
        if (!OverlayPermission.isGranted(context)) return

        mainHandler.post {
            try {
                val root = FrameLayout(context).apply {
                    setBackgroundColor(Color.parseColor("#99050B14")) // Semi-transparent dark backdrop
                }

                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val shape = GradientDrawable().apply {
                        cornerRadius = 48f
                        setColor(Color.parseColor("#EE0B132B")) // Deep cyber navy
                        setStroke(3, Color.parseColor("#00E5FF")) // Glowing cyan border
                    }
                    background = shape
                    setPadding(48, 40, 48, 48)
                }

                // Glowing Jarvis Orb
                val orb = View(context).apply {
                    val orbShape = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        colors = intArrayOf(
                            Color.parseColor("#00E5FF"),
                            Color.parseColor("#1C2541"),
                            Color.parseColor("#0B132B")
                        )
                        gradientType = GradientDrawable.RADIAL_GRADIENT
                        gradientRadius = 90f
                    }
                    background = orbShape
                }
                orbView = orb
                val orbParams = LinearLayout.LayoutParams(140, 140).apply {
                    bottomMargin = 24
                }
                card.addView(orb, orbParams)

                // Pulse Animation
                pulseAnimator = ValueAnimator.ofFloat(0.85f, 1.15f).apply {
                    duration = 1100
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { anim ->
                        val scale = anim.animatedValue as Float
                        orb.scaleX = scale
                        orb.scaleY = scale
                    }
                    start()
                }

                // Status text (Listening / Thinking / Speaking)
                val status = TextView(context).apply {
                    text = initialStatus
                    textSize = 14f
                    setTextColor(Color.parseColor("#00E5FF"))
                    gravity = Gravity.CENTER
                }
                statusText = status
                card.addView(status)

                // Live user speech transcription
                val transcript = TextView(context).apply {
                    text = ""
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(0, 16, 0, 8)
                }
                transcriptText = transcript
                card.addView(transcript)

                // Assistant response preview
                val response = TextView(context).apply {
                    text = ""
                    textSize = 14f
                    setTextColor(Color.parseColor("#E0E0E0"))
                    gravity = Gravity.CENTER
                }
                responseText = response
                card.addView(response)

                val closeBtn = TextView(context).apply {
                    text = "Close"
                    textSize = 12f
                    setTextColor(Color.parseColor("#80FFFFFF"))
                    setPadding(24, 16, 24, 8)
                    setOnClickListener { hide() }
                }
                card.addView(closeBtn)

                val cardParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM
                    marginStart = 32
                    marginEnd = 32
                    bottomMargin = 48
                }
                root.addView(card, cardParams)

                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )

                windowManager.addView(root, params)
                overlayView = root
            } catch (e: Exception) {
                overlayView = null
            }
        }
    }

    fun updateStatus(status: String) {
        mainHandler.post {
            statusText?.text = status
        }
    }

    fun updateTranscript(userSpeech: String) {
        mainHandler.post {
            transcriptText?.text = userSpeech
        }
    }

    fun updateResponse(reply: String) {
        mainHandler.post {
            responseText?.text = reply
        }
    }

    fun hide() {
        mainHandler.post {
            pulseAnimator?.cancel()
            pulseAnimator = null
            overlayView?.let { view ->
                runCatching { windowManager.removeView(view) }
            }
            overlayView = null
            orbView = null
            statusText = null
            transcriptText = null
            responseText = null
        }
    }
}
