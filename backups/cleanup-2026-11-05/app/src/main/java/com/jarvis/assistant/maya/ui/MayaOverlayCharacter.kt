package com.jarvis.assistant.maya.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.voice.overlay.OverlayPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * "Overlay character on home screen": a small draggable window
 * (TYPE_APPLICATION_OVERLAY) showing the Maya stage in portrait framing with a
 * transparent glow background, driven by the live conversation state and audio.
 *
 * Gestures: drag to move · tap to poke · double-tap to open Jarvis · long-press to hide.
 * It hides itself while Jarvis is in the foreground (the Home stage already shows her).
 */
object MayaOverlayCharacter {

    private var window: OverlayWindow? = null
    private val main = Handler(Looper.getMainLooper())

    fun isShowing(): Boolean = window != null

    /** Turn the overlay on (asks for "Display over other apps" first when missing). */
    fun enable(context: Context): Boolean {
        MayaUiPrefs.setOverlayEnabled(context, true)
        if (!OverlayPermission.isGranted(context)) {
            Toast.makeText(context, "Allow \"Display over other apps\" so the character can stay on your home screen", Toast.LENGTH_LONG).show()
            OverlayPermission.openSettings(context)
            return false
        }
        show(context)
        Toast.makeText(context, "Character is on your home screen • long-press her to hide", Toast.LENGTH_SHORT).show()
        return true
    }

    fun disable(context: Context) {
        MayaUiPrefs.setOverlayEnabled(context, false)
        hide()
    }

    /** Re-create the overlay after a restart / permission grant when the user had it on. */
    fun restoreIfEnabled(context: Context) {
        if (window == null && MayaUiPrefs.isOverlayEnabled(context) && OverlayPermission.isGranted(context)) show(context)
    }

    fun show(context: Context) {
        val app = context.applicationContext
        main.post {
            if (window != null || !OverlayPermission.isGranted(app)) return@post
            val w = OverlayWindow(app)
            if (w.attach()) window = w
        }
    }

    fun hide() {
        main.post {
            window?.detach()
            window = null
        }
    }

    /** Apply a new `character_size_dp`. */
    fun refreshSize() {
        main.post { window?.applySize() }
    }

    /** Re-tint after the active character changed. */
    fun refreshCharacter() {
        main.post { window?.refreshCharacter() }
    }

    private class OverlayWindow(private val context: Context) : MayaStageController.Listener {
        private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val density = context.resources.displayMetrics.density
        private val root = FrameLayout(context)
        private val webView = WebView(context)
        private val stage = MayaStageController(webView, this)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val handler = Handler(Looper.getMainLooper())
        private var attached = false

        private val params = WindowManager.LayoutParams(
            0, 0,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        private fun dp(v: Int) = (v * density + 0.5f).toInt()

        fun attach(): Boolean {
            return try {
                root.setBackgroundColor(Color.TRANSPARENT)
                root.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                applySize(update = false)
                val (sx, sy) = MayaUiPrefs.overlayPosition(context)
                val dm = context.resources.displayMetrics
                params.x = if (sx >= 0) sx else dm.widthPixels - params.width - dp(12)
                params.y = if (sy >= 0) sy else (dm.heightPixels * 0.55f).toInt()
                clamp()
                setupTouch()
                wm.addView(root, params)
                attached = true
                stage.audioPushIntervalMs = 50L
                stage.load(
                    MayaStageController.optionsFromPrefs(
                        context,
                        framing = MayaStageController.FRAMING_PORTRAIT,
                        background = MayaStageController.BG_GLOW
                    )
                )
                observe()
                true
            } catch (_: Throwable) {
                detach()
                false
            }
        }

        fun applySize(update: Boolean = true) {
            val size = MayaUiPrefs.characterSizeDp(context)
            params.width = dp(size)
            params.height = dp((size * 1.25f).toInt())
            if (update && attached) {
                clamp()
                try { wm.updateViewLayout(root, params) } catch (_: Throwable) {}
            }
        }

        fun refreshCharacter() {
            val prefs = try { JarvisApp.instance.preferences } catch (_: Throwable) { null } ?: return
            stage.setCharacter(MayaCharacters.byPrefName(prefs.jarvisActiveCharacter).id)
        }

        private fun observe() {
            scope.launch {
                JarvisConversationService.conversationState.collectLatest { s -> stage.setState(s.orbKey) }
            }
            scope.launch {
                JarvisConversationService.audioLevel.collectLatest { level ->
                    if (JarvisConversationService.conversationState.value == com.jarvis.assistant.data.model.ConversationState.SPEAKING) {
                        stage.pushAudio(level)
                    }
                }
            }
            scope.launch {
                JarvisConversationService.avatarAction.collect { action -> stage.react(action) }
            }
            scope.launch {
                JarvisApp.isAppForeground.collectLatest { fg ->
                    root.visibility = if (fg) View.GONE else View.VISIBLE
                    if (fg) stage.onPause() else stage.onResume()
                }
            }
        }

        // ── Gestures ────────────────────────────────────────────────────────
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private var lastTapAt = 0L
        private val longPress = Runnable {
            longPressed = true
            root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            MayaUiPrefs.setOverlayEnabled(context, false)
            Toast.makeText(context, "Character hidden — turn her back on in Settings › Overlay character", Toast.LENGTH_LONG).show()
            MayaOverlayCharacter.hide()
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun setupTouch() {
            val listener = View.OnTouchListener { _, e -> handleTouch(e) }
            webView.setOnTouchListener(listener)
            root.setOnTouchListener(listener)
        }

        private fun handleTouch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false; longPressed = false
                    handler.postDelayed(longPress, 650L)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        clamp()
                        try { wm.updateViewLayout(root, params) } catch (_: Throwable) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (longPressed) return true
                    if (dragging) {
                        MayaUiPrefs.setOverlayPosition(context, params.x, params.y)
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastTapAt < 320L) {
                            lastTapAt = 0L
                            openJarvis()
                        } else {
                            lastTapAt = now
                            root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            stage.poke()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            return true
        }

        private fun openJarvis() {
            try {
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                if (launch != null) context.startActivity(launch)
            } catch (_: Throwable) {
            }
        }

        private fun clamp() {
            val dm = context.resources.displayMetrics
            params.x = params.x.coerceIn(0, (dm.widthPixels - params.width).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (dm.heightPixels - params.height).coerceAtLeast(0))
        }

        fun detach() {
            handler.removeCallbacks(longPress)
            scope.cancel()
            stage.destroy()
            if (attached) {
                try { wm.removeView(root) } catch (_: Throwable) {}
            }
            attached = false
        }

        override fun onStageError(message: String) {
            // Keep the overlay light: if WebGL is unavailable there is nothing useful to float.
            MayaOverlayCharacter.hide()
        }
    }
}
