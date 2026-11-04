package com.jarvis.assistant.maya.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.util.Jarvis3DModelBridge
import com.jarvis.assistant.util.JarvisWebViewClient
import com.jarvis.assistant.util.ThemeManager

/**
 * Drives `assets/maya_character.html` inside a [WebView].
 *
 * The page exposes the same bridge as `mora_character.html`
 * (`window.MoraCompanion` / `window.JarvisOrb` / `window.MayaStage`):
 * `setState`, `setExternalAudio`, `triggerReaction`, `interrupt`, `setTheme`, plus
 * Maya extras `react`, `setCharacter`, `setLowPower`, `setFraming`, `setPaused`.
 * It calls back through the `MayaStageHost` JS interface installed here.
 */
class MayaStageController(
    private val webView: WebView,
    private val listener: Listener? = null
) {

    interface Listener {
        fun onStageReady() {}
        fun onStageError(message: String) {}
        fun onPoke(zone: String) {}
    }

    data class Options(
        val framing: String = FRAMING_HALF,
        val background: String = BG_FULL,
        val character: String = "maya",
        val lowPower: Boolean = false,
        val light: Boolean = false,
        val themeKey: String? = null,
        val themeHex: String? = null,
        val displayName: String = "Maya",
        val customModel: Boolean = false
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pageLoaded = false
    private var destroyed = false
    private var lastAudioPushAt = 0L
    private var lastState = "idle"
    private var lastCharacter: String? = null

    /** True once the 3D model finished loading (MayaStageHost.onStageReady). */
    var isReady: Boolean = false
        private set

    /** Minimum gap between audio-level pushes (ms). ~30 Hz keeps lip-sync smooth and cheap. */
    var audioPushIntervalMs: Long = 33L

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface")
    fun load(options: Options) {
        if (destroyed) return
        val context = webView.context
        pageLoaded = false
        isReady = false
        lastCharacter = options.character
        webView.apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_DEFAULT
                loadsImagesAutomatically = true
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                    if (cm?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        Log.w(TAG, "${cm.message()} @${cm.lineNumber()}")
                    }
                    return true
                }
            }
            // Plain asset serving: JarvisWebViewClient rewrites every non-mora *.glb to the active
            // vault model, which would replace models/maya.glb, so the stage uses its own loader.
            webViewClient = StageClient(context.applicationContext) {
                pageLoaded = true
                setState(lastState)
            }
            removeJavascriptInterface(HOST_INTERFACE)
            addJavascriptInterface(HostBridge(), HOST_INTERFACE)
            removeJavascriptInterface("Jarvis3DModelBridge")
            addJavascriptInterface(Jarvis3DModelBridge(context.applicationContext), "Jarvis3DModelBridge")
            loadUrl(buildUrl(options))
        }
    }

    fun setState(state: String) {
        lastState = state
        eval("window.MayaStage && window.MayaStage.setState('${safe(state)}')")
    }

    fun react(reaction: String) {
        eval("window.MayaStage && window.MayaStage.react('${safe(reaction)}')")
    }

    fun poke() {
        eval("window.MayaStage && window.MayaStage.triggerTouchReaction('body')")
    }

    fun interrupt() {
        eval("window.MayaStage && window.MayaStage.interrupt()")
    }

    /** Push the live audio level (0..1). Throttled to [audioPushIntervalMs]. */
    fun pushAudio(level: Float) {
        val now = System.currentTimeMillis()
        if (now - lastAudioPushAt < audioPushIntervalMs) return
        lastAudioPushAt = now
        val v = level.coerceIn(0f, 1f)
        eval("window.MayaStage && window.MayaStage.setExternalAudio(${String.format(java.util.Locale.US, "%.3f", v)})")
    }

    fun setTheme(themeKey: String, hex: String?) {
        eval("window.MayaStage && window.MayaStage.setTheme('${safe(themeKey)}', '${safe(hex ?: "")}')")
    }

    fun setCharacter(id: String) {
        if (id == lastCharacter) return
        lastCharacter = id
        eval("window.MayaStage && window.MayaStage.setCharacter('${safe(id)}')")
    }

    fun setLowPower(on: Boolean) {
        eval("window.MayaStage && window.MayaStage.setLowPower($on)")
    }

    fun setPaused(paused: Boolean) {
        eval("window.MayaStage && window.MayaStage.setPaused($paused)")
    }

    fun onResume() {
        if (destroyed) return
        webView.onResume()
        setPaused(false)
    }

    fun onPause() {
        if (destroyed) return
        setPaused(true)
        webView.onPause()
    }

    /** Release the page (keeps the WebView object for reuse). */
    fun unload() {
        if (destroyed) return
        pageLoaded = false
        isReady = false
        webView.loadUrl("about:blank")
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        try {
            webView.removeJavascriptInterface(HOST_INTERFACE)
            webView.removeJavascriptInterface("Jarvis3DModelBridge")
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        } catch (_: Throwable) {
        }
    }

    private fun eval(js: String) {
        if (!pageLoaded || destroyed) return
        try {
            webView.evaluateJavascript(js, null)
        } catch (_: Throwable) {
        }
    }

    /** Serves https://appassets.androidplatform.net/assets/... straight from the APK assets. */
    private class StageClient(context: Context, private val onLoaded: () -> Unit) : WebViewClientCompat() {
        private val loader = WebViewAssetLoader.Builder()
            .setDomain(JarvisWebViewClient.ASSET_DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            loader.shouldInterceptRequest(request.url)

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            onLoaded()
        }
    }

    private inner class HostBridge {
        @JavascriptInterface
        fun onStageReady() {
            mainHandler.post {
                if (destroyed) return@post
                isReady = true
                setState(lastState)
                listener?.onStageReady()
            }
        }

        @JavascriptInterface
        fun onStageError(message: String?) {
            mainHandler.post { if (!destroyed) listener?.onStageError(message ?: "unknown") }
        }

        @JavascriptInterface
        fun onPoke(zone: String?) {
            mainHandler.post { if (!destroyed) listener?.onPoke(zone ?: "body") }
        }
    }

    companion object {
        private const val TAG = "MayaStage"
        const val HOST_INTERFACE = "MayaStageHost"
        const val PAGE = "maya_character.html"
        const val FRAMING_HALF = "half"
        const val FRAMING_PORTRAIT = "portrait"
        const val FRAMING_FULL = "full"
        const val BG_FULL = "full"
        const val BG_GLOW = "glow"
        const val BG_NONE = "none"

        /** Maps the active character name (preferences.jarvisActiveCharacter) to the stage id. */
        fun characterIdFor(prefName: String?): String = MayaCharacters.byPrefName(prefName).id

        fun buildUrl(options: Options): String {
            val prefs = try { JarvisApp.instance.preferences } catch (_: Throwable) { null }
            val theme = options.themeKey ?: prefs?.appTheme ?: ThemeManager.THEME_MAYA
            val hex = options.themeHex ?: prefs?.customThemeColor ?: ""
            val b = Uri.Builder()
                .scheme("https")
                .authority(JarvisWebViewClient.ASSET_DOMAIN)
                .path("/assets/$PAGE")
                .appendQueryParameter("theme", theme)
                .appendQueryParameter("hex", hex)
                .appendQueryParameter("character", options.character)
                .appendQueryParameter("framing", options.framing)
                .appendQueryParameter("bg", options.background)
                .appendQueryParameter("name", options.displayName)
            if (options.lowPower) b.appendQueryParameter("lowpower", "1")
            if (options.light) b.appendQueryParameter("light", "1")
            if (options.customModel) b.appendQueryParameter("model", "custom")
            return b.build().toString()
        }

        private fun safe(s: String): String = s.replace("\\", "").replace("'", "").replace("\n", " ")

        /** Convenience: options for the current preferences. */
        fun optionsFromPrefs(
            context: Context,
            framing: String = FRAMING_HALF,
            background: String = BG_FULL
        ): Options {
            val prefs = try { JarvisApp.instance.preferences } catch (_: Throwable) { null }
            val character = MayaCharacters.byPrefName(prefs?.jarvisActiveCharacter)
            return Options(
                framing = framing,
                background = background,
                character = character.id,
                lowPower = MayaUiPrefs.lowPowerStage(context),
                light = ThemeManager.isLightTheme(),
                displayName = character.title
            )
        }
    }
}
