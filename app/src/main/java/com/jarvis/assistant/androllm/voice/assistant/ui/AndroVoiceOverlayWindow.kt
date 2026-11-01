package com.jarvis.assistant.androllm.voice.assistant.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.jarvis.assistant.ui.compose.feature.settings.ComposeUiPrefs
import com.jarvis.assistant.ui.compose.feature.voice.AndroVoiceOverlay
import com.jarvis.assistant.ui.compose.theme.AndroLLMTheme
import com.jarvis.assistant.voice.overlay.OverlayPermission

/**
 * Hosts the AndroLLM Gemini Live-style overlay in a full-screen system overlay
 * window (`TYPE_APPLICATION_OVERLAY`) while an AndroLLM voice-mode turn is in
 * flight (ported from AndroLLM `VoiceOverlayWindow`).
 *
 * Distinct from Jarvis's own [com.jarvis.assistant.voice.overlay.VoiceOverlayWindow]
 * (the orb card used by Jarvis's voice session): this one is only shown by the
 * optional [com.jarvis.assistant.androllm.voice.assistant.service.VoiceAssistantService].
 *
 * Requires "Display over other apps" ([OverlayPermission]); without it [show] no-ops
 * and the assistant keeps running via its notification. A plain overlay view has no
 * LifecycleOwner, so [OverlayWindowOwner] is installed on the view tree for Compose.
 */
class AndroVoiceOverlayWindow(private val context: Context) {

    private var view: ComposeView? = null
    private var owner: OverlayWindowOwner? = null
    @Volatile private var showPending = false
    private val mainHandler = Handler(Looper.getMainLooper())

    val isShowing: Boolean get() = view != null

    /**
     * Adds the overlay window (posted to the main thread). Returns true when the
     * overlay was (or is being) shown, false when the permission is missing.
     * Idempotent: repeated calls while added or queued are no-ops.
     */
    fun show(): Boolean {
        if (view != null || showPending) return true
        if (!OverlayPermission.isGranted(context)) return false

        showPending = true
        mainHandler.post {
            if (view == null) {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val windowOwner = OverlayWindowOwner()
                val themeMode = ComposeUiPrefs.themeMode(context)
                val composeView = ComposeView(context).apply {
                    setViewTreeLifecycleOwner(windowOwner)
                    setViewTreeViewModelStoreOwner(windowOwner)
                    setViewTreeSavedStateRegistryOwner(windowOwner)
                    setContent {
                        AndroLLMTheme(themeMode = themeMode) {
                            AndroVoiceOverlay()
                        }
                    }
                }
                windowOwner.onCreate()

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP
                    y = 0
                }

                if (runCatching { wm.addView(composeView, params) }.isSuccess) {
                    view = composeView
                    owner = windowOwner
                    windowOwner.onResume()
                } else {
                    windowOwner.destroy()
                }
            }
            showPending = false
        }
        return true
    }

    fun hide() {
        mainHandler.post {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            view?.let { runCatching { wm.removeView(it) } }
            view = null
            owner?.destroy()
            owner = null
        }
    }
}

/**
 * Minimal [LifecycleOwner] + [ViewModelStoreOwner] + [SavedStateRegistryOwner] for a
 * floating Compose overlay (what a ComponentActivity installs on its window tree).
 */
internal class OverlayWindowOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(Bundle())
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    /** collectAsStateWithLifecycle needs STARTED to deliver values. */
    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        savedStateController.performSave(Bundle())
        viewModelStore.clear()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
