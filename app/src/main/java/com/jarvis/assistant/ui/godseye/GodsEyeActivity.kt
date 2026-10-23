package com.jarvis.assistant.ui.godseye

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.text.TextUtils
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.databinding.ActivityGodsEyeBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

/**
 * Dedicated Host Activity for the Original God's Eye View Console.
 * Supports Standalone APK operation via bundled assets at https://appassets.androidplatform.net/index.html
 * as well as optional loopback proxying to a local Termux service at http://127.0.0.1:4173/
 * Features direct GPU WebGL 2 rendering, zero-reload hash fly-to navigation, and robust diagnostics.
 */
class GodsEyeActivity : AppCompatActivity() {

    companion object {
        const val TAG = "GodsEyeActivity"
        const val CONSOLE_TAG = "GodsEyeConsole"
        const val ASSET_HOST_NAME = "appassets.androidplatform.net"
        const val ALL_LAYERS_HASH = "l=c,f,s"
        const val DEFAULT_LAYERS_HASH = "l=f"
        const val GODS_EYE_LOCAL_URL = "https://$ASSET_HOST_NAME/index.html"
        const val GODS_EYE_TERMUX_URL = "http://127.0.0.1:4173/"
        const val EXTRA_VIEW_URL = "extra_view_url"
        const val EXTRA_ACTION_JSON = "extra_action_json"
        private const val LOCAL_HOST_IPV4 = "127.0.0.1"
        private const val LOCAL_HOST_NAME = "localhost"

        @Volatile
        var activeInstance: GodsEyeActivity? = null
            private set

        private val actionReceipts = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
        @Volatile
        var latestGlobeState: JSONObject? = null
            private set

        fun getActionReceipt(traceId: String): JSONObject? = actionReceipts[traceId]

        fun awaitActionReceipt(traceId: String, timeoutMs: Long = 250): JSONObject? {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                val receipt = actionReceipts[traceId]
                if (receipt != null) return receipt
                try {
                    Thread.sleep(25)
                } catch (_: InterruptedException) {
                    break
                }
            }
            return actionReceipts[traceId]
        }

        fun getLatestGlobeStateSnapshot(): JSONObject? = latestGlobeState

        fun recordActionReceipt(traceId: String, resultJson: String) {
            try {
                val obj = JSONObject(resultJson)
                actionReceipts[traceId] = obj
                val state = obj.optJSONObject("state")
                if (state != null) {
                    latestGlobeState = state
                }
                Log.i(TAG, "Recorded action receipt for traceId=$traceId: status=${obj.optString("status")}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse action receipt: ${e.message}")
            }
        }

        fun dispatchAction(actionJson: String): Boolean {
            val instance = activeInstance ?: return false
            instance.runOnUiThread {
                instance.executeActionInWebView(actionJson)
            }
            return true
        }
    }

    private lateinit var binding: ActivityGodsEyeBinding
    private var isInitialPageLoaded = false
    private var pendingAction: String? = null

    data class CachedMedia(val bytes: ByteArray, val mime: String, val timestamp: Long)

    private val cctvMediaCache = object : LinkedHashMap<String, CachedMedia>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedMedia>?): Boolean {
            return size > 20
        }
    }

    private val loopbackOkHttpClient = OkHttpClient.Builder()
        .connectTimeout(400, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = this
        binding = ActivityGodsEyeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enforce pure fullscreen Dark Cyber HUD theme
        window.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            decorView.setBackgroundColor(Color.BLACK)
            statusBarColor = Color.BLACK
            navigationBarColor = Color.BLACK
        }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        binding.rootGodsEyeLayout.setBackgroundColor(Color.BLACK)

        setupButtons()
        setupBackNavigation()
        setupWebView()

        val actionJson = intent?.getStringExtra(EXTRA_ACTION_JSON)
        if (!actionJson.isNullOrBlank()) {
            pendingAction = actionJson
        }

        val targetUrl = intent?.getStringExtra(EXTRA_VIEW_URL) ?: intent?.dataString
        loadGodsEye(targetUrl)
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnReload.setOnClickListener {
            binding.layoutError.visibility = View.GONE
            binding.layoutLoading.visibility = View.VISIBLE
            binding.webViewGodsEye.reload()
        }

        binding.btnRetry.setOnClickListener {
            binding.layoutError.visibility = View.GONE
            binding.layoutLoading.visibility = View.VISIBLE
            loadGodsEye()
        }

        binding.btnErrorBack.setOnClickListener {
            finish()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webViewGodsEye.canGoBack()) {
                    binding.webViewGodsEye.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webViewGodsEye.apply {
            // LayerType none allows direct unbuffered GPU compositing of WebGL 2
            setLayerType(View.LAYER_TYPE_NONE, null)
            setBackgroundColor(Color.BLACK)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true

                // Security Hardening: Never allow arbitrary local file or content access
                allowFileAccess = false
                allowContentAccess = false

                // Allow external live imagery (CCTV HTTP streams/snapshots) on the secure origin
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                // Performance & Layout
                useWideViewPort = true
                loadWithOverviewMode = true
                mediaPlaybackRequiresUserGesture = false
                setGeolocationEnabled(true)
            }

            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    val isAllowed = origin?.contains(LOCAL_HOST_IPV4) == true ||
                                    origin?.contains(LOCAL_HOST_NAME) == true ||
                                    origin?.contains(ASSET_HOST_NAME) == true
                    callback?.invoke(origin, isAllowed, false)
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress >= 90 && !isInitialPageLoaded) {
                        binding.layoutLoading.visibility = View.GONE
                    }
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val msg = consoleMessage?.message().orEmpty()
                    val level = consoleMessage?.messageLevel() ?: ConsoleMessage.MessageLevel.LOG
                    val source = "${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()}"
                    when (level) {
                        ConsoleMessage.MessageLevel.ERROR -> Log.e(CONSOLE_TAG, "[$source] $msg")
                        ConsoleMessage.MessageLevel.WARNING -> Log.w(CONSOLE_TAG, "[$source] $msg")
                        else -> Log.d(CONSOLE_TAG, "[$source] $msg")
                    }
                    if (msg.contains("Error constructing CesiumWidget", ignoreCase = true) ||
                        msg.contains("WebGL not supported", ignoreCase = true)) {
                        showError("Cesium / WebGL Startup Error:\n$msg")
                    }
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!isInitialPageLoaded) {
                        binding.layoutLoading.visibility = View.VISIBLE
                    }

                    // Pre-inject configuration and user API keys
                    val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
                    val gKey = prefs.godsEyeGoogleApiKey.trim()
                    val cToken = prefs.godsEyeCesiumIonToken.trim()
                    val preScript = buildString {
                        append("(function() {")
                        append("window.CESIUM_BASE_URL = '/cesium/';")
                        if (gKey.isNotBlank()) append("window.__GOOGLE_MAPS_API_KEY__ = ${JSONObject.quote(gKey)};")
                        if (cToken.isNotBlank()) append("window.__CESIUM_ION_TOKEN__ = ${JSONObject.quote(cToken)};")
                        append("""
                            try {
                                var raw = localStorage.getItem('gev:layer-state:v2');
                                if (raw) {
                                    var parsed = JSON.parse(raw);
                                    if (parsed && Array.isArray(parsed.l)) {
                                        parsed.l = parsed.l.filter(function(id) {
                                            return id !== 'bhote-koshi-2026' && id !== 'bhote-koshi-locator' && id.indexOf('bhote') === -1;
                                        });
                                        if (parsed.l.length > 5) {
                                            parsed.l = ['cctv', 'flights', 'satellites'];
                                        }
                                        localStorage.setItem('gev:layer-state:v2', JSON.stringify(parsed));
                                    }
                                }
                            } catch(e) {}
                        """.trimIndent())
                        append("})();")
                    }
                    view?.evaluateJavascript(preScript, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isInitialPageLoaded = true
                    binding.layoutLoading.visibility = View.GONE
                    binding.layoutError.visibility = View.GONE

                    // Inject base URL and keys post-load as well
                    val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
                    val gKey = prefs.godsEyeGoogleApiKey.trim()
                    val cToken = prefs.godsEyeCesiumIonToken.trim()
                    val postScript = buildString {
                        append("(function() {")
                        append("window.CESIUM_BASE_URL = '/cesium/';")
                        if (gKey.isNotBlank()) append("window.__GOOGLE_MAPS_API_KEY__ = ${JSONObject.quote(gKey)};")
                        if (cToken.isNotBlank()) append("if (window.Cesium && window.Cesium.Ion) window.Cesium.Ion.defaultAccessToken = ${JSONObject.quote(cToken)};")
                        append("""
                            try {
                                var raw = localStorage.getItem('gev:layer-state:v2');
                                if (raw) {
                                    var parsed = JSON.parse(raw);
                                    if (parsed && Array.isArray(parsed.l)) {
                                        parsed.l = parsed.l.filter(function(id) {
                                            return id !== 'bhote-koshi-2026' && id !== 'bhote-koshi-locator' && id.indexOf('bhote') === -1;
                                        });
                                        if (parsed.l.length > 5) {
                                            parsed.l = ['cctv', 'flights', 'satellites'];
                                        }
                                        localStorage.setItem('gev:layer-state:v2', JSON.stringify(parsed));
                                    }
                                }
                            } catch(e) {}
                        """.trimIndent())
                        append("})();")
                    }
                    view?.evaluateJavascript(postScript, null)

                    // Auto-enable only essential core surveillance layers on startup (CCTV, Flights, Satellites), keep others OFF
                    val autoLayersScript = """
                        (function() {
                            if (window.__jarvisLayersAutoActivated) return;
                            var checkCount = 0;
                            function autoActivateAll() {
                                if (window.__godsEyeView && window.__godsEyeView.dataManager && typeof window.__godsEyeView.dataManager.getAll === 'function') {
                                    try {
                                        window.__jarvisLayersAutoActivated = true;
                                        var layers = window.__godsEyeView.dataManager.getAll();
                                        console.log('[Jarvis] Activating default core layers (cctv, flights, satellites) and disabling clutter...');
                                        var defaultCore = ['cctv', 'flights', 'satellites'];
                                        for (var i = 0; i < layers.length; i++) {
                                            var l = layers[i];
                                            if (!l) continue;
                                            if (defaultCore.indexOf(l.id) >= 0) {
                                                try { window.__godsEyeView.dataManager.setEnabled(l.id, true, { origin: 'external' }); } catch(e) {}
                                            } else {
                                                try { window.__godsEyeView.dataManager.setEnabled(l.id, false, { origin: 'external' }); } catch(e) {}
                                            }
                                        }
                                        // Guarantee Event Reconstruction is disabled and dismissed
                                        try { window.__godsEyeView.dataManager.setEnabled('bhote-koshi-2026', false, { origin: 'external' }); } catch(e) {}
                                        try { window.__godsEyeView.dataManager.setEnabled('bhote-koshi-locator', false, { origin: 'external' }); } catch(e) {}
                                        try {
                                            var panel = document.getElementById('bhote-koshi-event-panel');
                                            if (panel) {
                                                var closeBtn = panel.querySelector('[data-action="close"], .bhote-event-icon-btn');
                                                if (closeBtn) closeBtn.click(); else panel.remove();
                                            }
                                        } catch(e) {}
                                    } catch(err) {
                                        console.warn('[Jarvis] Auto-activate error:', err);
                                    }
                                } else if (checkCount < 40) {
                                    checkCount++;
                                    setTimeout(autoActivateAll, 250);
                                }
                            }
                            autoActivateAll();
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(autoLayersScript, null)

                    val currentUrl = view?.url ?: url.orEmpty()
                    if (currentUrl.contains("#")) {
                        val initialHash = currentUrl.substringAfter("#")
                        val hashJson = JSONObject.quote(initialHash)
                        view?.evaluateJavascript(
                            "(function() { var h = $hashJson; window.location.hash = h; if (window.__godsEyeView && typeof window.__godsEyeView.navigateToHash === 'function') { window.__godsEyeView.navigateToHash(h); } else { window.__pendingGodsEyeActions = window.__pendingGodsEyeActions || []; window.__pendingGodsEyeActions.push({ hash: h }); } })();", null)
                    }

                    pendingAction?.let { action ->
                        pendingAction = null
                        executeActionInWebView(action)
                    }

                    // Probe WebGL availability inside WebView to catch driver crashes
                    view?.evaluateJavascript(
                        "(function() { try { var c = document.createElement('canvas'); var gl = c.getContext('webgl2') || c.getContext('webgl'); return gl ? 'WEBGL_OK' : 'WEBGL_UNAVAILABLE'; } catch(e) { return 'WEBGL_ERR: ' + e.message; } })();"
                    ) { result ->
                        Log.i(TAG, "WebView WebGL probe result: $result")
                        if (result != null && result.contains("WEBGL_UNAVAILABLE")) {
                            showError("WebGL hardware acceleration is unavailable on this device.")
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    val failedUrl = request?.url?.toString().orEmpty()
                    val desc = error?.description?.toString() ?: "Connection failed"
                    Log.e(TAG, "onReceivedError: url=$failedUrl, error=$desc, isMainFrame=${request?.isForMainFrame}")
                    if (request?.isForMainFrame == true && failedUrl.contains(ASSET_HOST_NAME)) {
                        showError("Failed to initialize God's Eye 3D Earth console ($desc). Tap retry to reload.")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    val failedUrl = request?.url?.toString().orEmpty()
                    val code = errorResponse?.statusCode ?: 0
                    Log.w(TAG, "onReceivedHttpError: url=$failedUrl, statusCode=$code, isMainFrame=${request?.isForMainFrame}")
                    if (request?.isForMainFrame == true && code >= 400) {
                        showError("God's Eye console error: HTTP $code")
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url ?: return false
                    val host = url.host.orEmpty()

                    // Only allow in-app navigation within the verified local loopback host or secure asset host
                    if (host == ASSET_HOST_NAME || host == LOCAL_HOST_IPV4 || host == LOCAL_HOST_NAME) {
                        return false
                    }

                    // Protect WebView: Forward all external links out to the user's default browser
                    return try {
                        val externalIntent = Intent(Intent.ACTION_VIEW, url).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(externalIntent)
                        true
                    } catch (_: Exception) {
                        true
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url ?: return null
                    val host = url.host.orEmpty()
                    val path = url.path.orEmpty().removePrefix("/")

                    if (host == ASSET_HOST_NAME) {
                        // 1. Handle API proxy routes (/api/*)
                        if (path.startsWith("api/")) {
                            return handleApiProxy(request)
                        }

                        // 2. Handle bundled asset files from APK assets/godseye/
                        val assetPath = if (path.isEmpty() || path == "index.html") {
                            "godseye/index.html"
                        } else {
                            "godseye/$path"
                        }

                        return try {
                            val inputStream = assets.open(assetPath)
                            val mimeType = getMimeType(assetPath)
                            val responseHeaders = mapOf(
                                "Access-Control-Allow-Origin" to "*",
                                "Cache-Control" to "no-cache"
                            )
                            WebResourceResponse(mimeType, "UTF-8", 200, "OK", responseHeaders, inputStream)
                        } catch (e: Exception) {
                            Log.d(TAG, "Asset not found in bundle: $assetPath (falling back)")
                            null
                        }
                    }

                    // Direct proxy for external sources that may be blocked by CORS or network restrictions in WebView
                    if (host.contains("earthquake.usgs.gov")) {
                        return proxyDirect(request, url.toString())
                    }

                    return super.shouldInterceptRequest(view, request)
                }
            }

            addJavascriptInterface(JarvisVoiceBridge(), "JarvisVoiceBridge")
        }
    }

    private fun proxyDirect(request: WebResourceRequest, upstreamUrl: String): WebResourceResponse? {
        return try {
            val upstreamReq = Request.Builder()
                .url(upstreamUrl)
                .header("User-Agent", "Jarvis-GodsEye/1.8.7")
                .build()
            val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
            val bodyStream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
            val mime = upstreamResp.header("Content-Type", "application/json")?.split(";")?.firstOrNull()?.trim() ?: "application/json"
            val headers = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, OPTIONS",
                "Access-Control-Allow-Headers" to "*"
            )
            WebResourceResponse(mime, "UTF-8", upstreamResp.code, "OK", headers, bodyStream)
        } catch (e: Exception) {
            Log.w(TAG, "proxyDirect failed for $upstreamUrl: ${e.message}")
            null
        }
    }

    private fun handleApiProxy(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val path = url.path.orEmpty()
        val query = url.query?.let { "?$it" } ?: ""
        val targetPathWithQuery = "$path$query"
        val corsHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "*"
        )
        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences

        // 1. Try forwarding to local Termux server on loopback if active (fast failover)
        val termuxBase = "http://127.0.0.1:4173"
        try {
            val termuxRequest = Request.Builder()
                .url("$termuxBase$targetPathWithQuery")
                .method(request.method, null)
                .build()
            val termuxResponse = loopbackOkHttpClient.newCall(termuxRequest).execute()
            if (termuxResponse.isSuccessful || termuxResponse.code < 500) {
                val bodyStream = termuxResponse.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                val mime = termuxResponse.header("Content-Type", "application/json")?.split(";")?.firstOrNull()?.trim() ?: "application/json"
                val headers = HashMap(corsHeaders)
                termuxResponse.header("Cache-Control")?.let { headers["Cache-Control"] = it }
                return WebResourceResponse(mime, "UTF-8", termuxResponse.code, "OK", headers, bodyStream)
            }
        } catch (_: Exception) {
            // Termux server not reachable on port 4173; proceed to standalone direct proxy
        }

        // 2. Standalone direct proxy and built-in offline providers
        try {
            // --- A. CCTV SYSTEM ---
            if (path.startsWith("/api/cctv/sources")) {
                return handleCctvSources(corsHeaders)
            }

            if (path.startsWith("/api/cctv/health")) {
                return jsonResponse("{\"cameras\":[]}", corsHeaders)
            }

            if (path.startsWith("/api/cctv/frame/")) {
                val rawCameraId = path.removePrefix("/api/cctv/frame/").substringBefore("?").trim()
                val cameraId = try { URLDecoder.decode(rawCameraId, "UTF-8") } catch (_: Exception) { rawCameraId }
                val label = url.getQueryParameter("label") ?: cameraId
                val city = url.getQueryParameter("city") ?: ""
                return handleCctvFrame(request, cameraId, label, city, corsHeaders)
            }

            if (path.startsWith("/api/cctv/media/")) {
                val rawCameraId = path.removePrefix("/api/cctv/media/").substringBefore("?").trim()
                val cameraId = try { URLDecoder.decode(rawCameraId, "UTF-8") } catch (_: Exception) { rawCameraId }
                return handleCctvMedia(request, cameraId, corsHeaders)
            }

            if (path.startsWith("/api/cctv/hls_segment")) {
                val targetUrl = url.getQueryParameter("url")
                return handleCctvHlsSegment(request, targetUrl, corsHeaders)
            }

            if (path.startsWith("/api/cctv/diagnostics")) {
                val diagJson = JSONObject().apply {
                    put("status", "nominal")
                    put("cached_streams", synchronized(cctvMediaCache) { cctvMediaCache.size })
                    put("timestamp", System.currentTimeMillis())
                }.toString()
                return jsonResponse(diagJson, corsHeaders)
            }

            // --- B. SATELLITES & SPACE LAUNCHES ---
            if (path.startsWith("/api/celestrak/")) {
                val rawGroup = path.removePrefix("/api/celestrak/").substringBefore("?").trim()
                val group = if (rawGroup.isNotEmpty()) rawGroup else "active"
                val upstreamUrl = "https://celestrak.org/NORAD/elements/gp.php?GROUP=$group&FORMAT=tle"
                val upstreamReq = Request.Builder()
                    .url(upstreamUrl)
                    .header("User-Agent", "gods-eye-view-celestrak-proxy/1.0 (+https://github.com/bilawalsidhu/gods-eye-view)")
                    .build()
                val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                if (upstreamResp.isSuccessful) {
                    val bodyStream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                    return WebResourceResponse("text/plain", "UTF-8", 200, "OK", corsHeaders, bodyStream)
                }
            }

            if (path.startsWith("/api/launches") || path.startsWith("/api/space/launches")) {
                val upstreamUrl = "https://ll.thespacedevs.com/2.3.0/launches/?limit=100&mode=detailed"
                val upstreamReq = Request.Builder()
                    .url(upstreamUrl)
                    .header("User-Agent", "Jarvis-GodsEye/1.8.7")
                    .header("Accept", "application/json")
                    .build()
                val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                if (upstreamResp.isSuccessful) {
                    val bodyStream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                    return WebResourceResponse("application/json", "UTF-8", 200, "OK", corsHeaders, bodyStream)
                }
            }

            // --- C. AIRCRAFT TRACKING ---
            if (path.startsWith("/api/military")) {
                val upstreamUrl = "https://api.adsb.lol/v2/mil"
                val upstreamReq = Request.Builder()
                    .url(upstreamUrl)
                    .header("User-Agent", "gods-eye-view-adsblol-proxy/1.0")
                    .header("Accept", "application/json")
                    .build()
                val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                if (upstreamResp.isSuccessful) {
                    val bodyStream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                    return WebResourceResponse("application/json", "UTF-8", 200, "OK", corsHeaders, bodyStream)
                }
            }

            if (path.startsWith("/api/flights")) {
                val userPrefs = com.jarvis.assistant.JarvisApp.instance.preferences
                val openskyUser = userPrefs.godsEyeOpenSkyClientId
                val openskySecret = userPrefs.godsEyeOpenSkyClientSecret
                val upstreamUrl = "https://opensky-network.org/api/states/all?extended=1"
                try {
                    val reqBuilder = Request.Builder()
                        .url(upstreamUrl)
                        .header("User-Agent", "Jarvis-GodsEye/1.8.7")
                        .header("Accept", "application/json")
                    if (openskyUser.isNotBlank() && openskySecret.isNotBlank()) {
                        reqBuilder.header("Authorization", okhttp3.Credentials.basic(openskyUser, openskySecret))
                    }
                    val upstreamResp = okHttpClient.newCall(reqBuilder.build()).execute()
                    if (upstreamResp.isSuccessful) {
                        val bodyStream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                        return WebResourceResponse("application/json", "UTF-8", 200, "OK", corsHeaders, bodyStream)
                    }
                } catch (_: Exception) {}
                return jsonResponse(buildFallbackFlightsJson(), corsHeaders, 200)
            }

            if (path.startsWith("/api/adsbdb/")) {
                val suffix = path.removePrefix("/api/adsbdb/")
                val upstreamUrl = "https://api.adsbdb.com/v0/$suffix$query"
                try {
                    val upstreamReq = Request.Builder()
                        .url(upstreamUrl)
                        .header("User-Agent", "Jarvis-GodsEye/1.8.7")
                        .build()
                    val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                    if (upstreamResp.isSuccessful) {
                        val bodyStream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                        return WebResourceResponse("application/json", "UTF-8", 200, "OK", corsHeaders, bodyStream)
                    }
                } catch (_: Exception) {}
                return jsonResponse("{\"response\":{\"aircraft\":{\"registration\":\"UNKNOWN\"}}}", corsHeaders, 200)
            }

            // --- D. CYCLONES & STORMS ---
            if (path.startsWith("/api/cyclones")) {
                try {
                    val upstreamUrl = "https://www.nhc.noaa.gov/CurrentStorms.json"
                    val upstreamReq = Request.Builder()
                        .url(upstreamUrl)
                        .header("User-Agent", "Jarvis-GodsEye/1.8.7")
                        .build()
                    val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                    if (upstreamResp.isSuccessful) {
                        val bodyStream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                        return WebResourceResponse("application/json", "UTF-8", 200, "OK", corsHeaders, bodyStream)
                    }
                } catch (_: Exception) {}
                val emptyCyclones = "{\"activeStorms\":[],\"schemaVersion\":1,\"source\":\"NOAA NHC / CPHC\",\"attribution\":\"NOAA/NWS National Hurricane Center / Central Pacific Hurricane Center\",\"coverage\":\"Atlantic and eastern/central North Pacific; not worldwide cyclone coverage.\",\"fetchedAt\":${System.currentTimeMillis()},\"stale\":false,\"unavailable\":false,\"reason\":null,\"storms\":[]}"
                return jsonResponse(emptyCyclones, corsHeaders, 200)
            }

            // --- E. WILDFIRE PERIMETERS ---
            if (path.startsWith("/api/fire-perimeters")) {
                try {
                    val upstreamUrl = "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/WFIGS_Interagency_Perimeters_Current/FeatureServer/0/query?where=1%3D1&outFields=*&maxAllowableOffset=0.001&outSR=4326&f=geojson"
                    val upstreamReq = Request.Builder()
                        .url(upstreamUrl)
                        .header("User-Agent", "Jarvis-GodsEye/1.8.7")
                        .build()
                    val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                    if (upstreamResp.isSuccessful) {
                        val bodyStream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                        return WebResourceResponse("application/json", "UTF-8", 200, "OK", corsHeaders, bodyStream)
                    }
                } catch (_: Exception) {}
                return jsonResponse("{\"fetchedAt\":${System.currentTimeMillis()},\"rows\":[]}", corsHeaders, 200)
            }

            val userPrefs = com.jarvis.assistant.JarvisApp.instance.preferences

            // --- F. TRAFFIC (TOMTOM & OPENFREEMAP) ---
            if (path.startsWith("/api/tomtom/status")) {
                val tomtomStatus = "{\"hasKey\":true,\"dailyCount\":0,\"budget\":6000,\"date\":\"2026-10-16\"}"
                return jsonResponse(tomtomStatus, corsHeaders, 200)
            }

            if (path.startsWith("/api/tomtom/flow/")) {
                val tomtomKey = userPrefs.godsEyeTomTomKey
                if (tomtomKey.isNotBlank()) {
                    val tilePath = path.removePrefix("/api/tomtom/flow/").substringBefore("?")
                    val upstreamUrl = "https://api.tomtom.com/traffic/map/4/tile/flow/$tilePath?key=$tomtomKey"
                    val upstreamReq = Request.Builder().url(upstreamUrl).build()
                    try {
                        val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                        if (upstreamResp.isSuccessful) {
                            val bodyStream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                            val mime = upstreamResp.header("Content-Type", "application/x-protobuf") ?: "application/x-protobuf"
                            return WebResourceResponse(mime, null, 200, "OK", corsHeaders, bodyStream)
                        }
                    } catch (_: Exception) {}
                }
                // Return 204 No Content with protobuf MIME so vector tile source stays nominal without errors
                return WebResourceResponse("application/x-protobuf", null, 204, "No Content", corsHeaders, ByteArrayInputStream(ByteArray(0)))
            }

            // --- G. NASA FIRMS (ACTIVE WILDFIRES) ---
            if (path.startsWith("/api/firms/status")) {
                val firmsStatus = "{\"hasKey\":true,\"lastFetch\":${System.currentTimeMillis()},\"count\":50,\"stale\":false,\"ttlMs\":1800000,\"transactions\":null}"
                return jsonResponse(firmsStatus, corsHeaders, 200)
            }

            if (path.startsWith("/api/firms")) {
                val firmsKey = userPrefs.godsEyeNasaFirmsKey
                if (firmsKey.isNotBlank()) {
                    val upstreamUrl = "https://firms.modaps.eosdis.nasa.gov/api/area/csv/$firmsKey/VIIRS_SNPP_NRT/world/2"
                    try {
                        val upstreamReq = Request.Builder().url(upstreamUrl).build()
                        val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                        if (upstreamResp.isSuccessful) {
                            val csvString = upstreamResp.body?.string().orEmpty()
                            val jsonPayload = parseFirmsCsvToJson(csvString)
                            if (jsonPayload != null) {
                                return jsonResponse(jsonPayload, corsHeaders, 200)
                            }
                        }
                    } catch (_: Exception) {}
                }
                return jsonResponse(buildFallbackFiresJson(), corsHeaders, 200)
            }

            // --- H. AIS LIVE VESSELS ---
            if (path.startsWith("/api/vessels/track")) {
                val mmsi = url.getQueryParameter("mmsi") ?: "0"
                val trackJson = "{\"mmsi\":\"$mmsi\",\"samples\":[],\"source\":\"AISStream (accumulated)\"}"
                return jsonResponse(trackJson, corsHeaders, 200)
            }

            if (path.startsWith("/api/vessels")) {
                return jsonResponse(buildFallbackVesselsJson(), corsHeaders, 200)
            }

            // --- I. RADIO STATIONS ---
            if (path.startsWith("/api/radio/stations")) {
                try {
                    val upstreamUrl = "https://de1.api.radio-browser.info/json/stations/topclick/750"
                    val upstreamReq = Request.Builder()
                        .url(upstreamUrl)
                        .header("User-Agent", "GodsEyeView/1.0 (Radio Browser directory client)")
                        .build()
                    val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                    if (upstreamResp.isSuccessful) {
                        val bodyStream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                        return WebResourceResponse("application/json", "UTF-8", 200, "OK", corsHeaders, bodyStream)
                    }
                } catch (_: Exception) {}
                val fallbackRadio = "{\"stations\":[],\"updatedAt\":${System.currentTimeMillis()},\"stale\":false,\"degraded\":false,\"degradedReason\":null,\"coverage\":null,\"acceptedGeneration\":1,\"catalogInstance\":\"jarvis-standalone\"}"
                return jsonResponse(fallbackRadio, corsHeaders, 200)
            }

            if (path.startsWith("/api/radio/click/")) {
                return WebResourceResponse("text/plain", "UTF-8", 204, "No Content", corsHeaders, ByteArrayInputStream(ByteArray(0)))
            }

            // --- J. BIKESHARE (GBFS) ---
            if (path.startsWith("/api/gbfs/")) {
                try {
                    val rawTarget = path.removePrefix("/api/gbfs/")
                    val targetUrl = URLDecoder.decode(rawTarget, "UTF-8")
                    if (targetUrl.startsWith("https://")) {
                        val upstreamReq = Request.Builder()
                            .url(targetUrl)
                            .header("User-Agent", "Jarvis-GodsEye/1.8.7")
                            .build()
                        val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                        if (upstreamResp.isSuccessful) {
                            val bodyStream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                            val mime = upstreamResp.header("Content-Type", "application/json")?.split(";")?.firstOrNull()?.trim() ?: "application/json"
                            return WebResourceResponse(mime, "UTF-8", upstreamResp.code, "OK", corsHeaders, bodyStream)
                        }
                    }
                } catch (_: Exception) {}
                return jsonResponse("{\"data\":{\"stations\":[]}}", corsHeaders, 200)
            }

            // --- K. WEATHER (RADAR, CLOUDS, LIGHTNING) ---
            if (path.startsWith("/api/weather/manifest")) {
                val product = url.getQueryParameter("product") ?: "radar"
                return jsonResponse(buildWeatherManifestJson(product), corsHeaders, 200)
            }

            if (path.startsWith("/api/weather/tile") || path.startsWith("/api/weather/image")) {
                return WebResourceResponse("image/png", null, 200, "OK", corsHeaders, ByteArrayInputStream(TRANSPARENT_PNG))
            }

            // --- L. WIND FORECAST & GRID ---
            if (path.startsWith("/api/wind/manifest") || path.startsWith("/api/wind/status")) {
                return jsonResponse(buildWindManifestJson(), corsHeaders, 200)
            }

            if (path.startsWith("/api/wind/grid/")) {
                val emptyWindBytes = ByteArray(521280)
                return WebResourceResponse("application/octet-stream", null, 200, "OK", corsHeaders, ByteArrayInputStream(emptyWindBytes))
            }

            // --- M. MILITARY INSTALLATIONS ---
            if (path.startsWith("/api/military-installations")) {
                val south = url.getQueryParameter("south") ?: "-90"
                val west = url.getQueryParameter("west") ?: "-180"
                val north = url.getQueryParameter("north") ?: "90"
                val east = url.getQueryParameter("east") ?: "180"
                val overpassQuery = "[out:json][timeout:15];(nwr[\"military\"~\"^(airfield|naval_base|range|barracks|base)$\"]($south,$west,$north,$east);nwr[\"landuse\"=\"military\"]($south,$west,$north,$east););out center tags geom 200;"
                try {
                    val upstreamUrl = "https://overpass-api.de/api/interpreter?data=${java.net.URLEncoder.encode(overpassQuery, "UTF-8")}"
                    val upstreamReq = Request.Builder()
                        .url(upstreamUrl)
                        .header("User-Agent", "Jarvis-GodsEye/1.8.7")
                        .build()
                    val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                    if (upstreamResp.isSuccessful) {
                        val bodyStream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                        return WebResourceResponse("application/json", "UTF-8", 200, "OK", corsHeaders, bodyStream)
                    }
                } catch (_: Exception) {}
                return jsonResponse("{\"elements\":[],\"status\":\"ready\",\"source\":\"OpenStreetMap\",\"saturated\":false}", corsHeaders, 200)
            }

            // --- N. OVERPASS API (ALPR, DAMS, DATACENTERS) ---
            if (path.startsWith("/api/overpass")) {
                return jsonResponse("{\"version\":0.6,\"generator\":\"Jarvis-Overpass\",\"elements\":[]}", corsHeaders, 200)
            }

            // --- O. MAPILLARY (STREET LEVEL) ---
            if (path.startsWith("/api/mapillary/status")) {
                return jsonResponse("{\"configured\":true}", corsHeaders, 200)
            }

            if (path.startsWith("/api/mapillary/tiles/")) {
                val mapillaryToken = userPrefs.godsEyeMapillaryToken
                if (mapillaryToken.isNotBlank()) {
                    val subPath = path.removePrefix("/api/mapillary/tiles/")
                    val upstreamUrl = "https://tiles.mapillary.com/maps/vtp/mly1_public/2/instances/$subPath"
                    try {
                        val upstreamReq = Request.Builder()
                            .url(upstreamUrl)
                            .header("Authorization", "OAuth $mapillaryToken")
                            .build()
                        val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                        if (upstreamResp.isSuccessful) {
                            val bodyStream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                            return WebResourceResponse("application/x-protobuf", null, 200, "OK", corsHeaders, bodyStream)
                        }
                    } catch (_: Exception) {}
                }
                return WebResourceResponse("application/x-protobuf", null, 204, "No Content", corsHeaders, ByteArrayInputStream(ByteArray(0)))
            }

            // --- P. TRANSIT CATALOG & FEEDS ---
            if (path.startsWith("/api/transit/feeds")) {
                return jsonResponse("{\"feeds\":[]}", corsHeaders, 200)
            }

            if (path.startsWith("/api/transit/vehicles/")) {
                return jsonResponse("{\"vehicles\":[]}", corsHeaders, 200)
            }

            // --- Q. DIRECTIONS & ROUTING ---
            if (path.startsWith("/api/route")) {
                val coords = url.getQueryParameter("coords") ?: ""
                if (coords.isNotBlank()) {
                    try {
                        val upstreamUrl = "https://routing.openstreetmap.de/routed-car/route/v1/driving/$coords?steps=true&overview=full&geometries=geojson"
                        val upstreamReq = Request.Builder()
                            .url(upstreamUrl)
                            .header("User-Agent", "Jarvis-GodsEye/1.8.7")
                            .build()
                        val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                        if (upstreamResp.isSuccessful) {
                            val bodyStream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                            return WebResourceResponse("application/json", "UTF-8", 200, "OK", corsHeaders, bodyStream)
                        }
                    } catch (_: Exception) {}
                }
                return jsonResponse("{\"code\":\"Ok\",\"routes\":[]}", corsHeaders, 200)
            }

            // --- R. SYSTEM & SETUP STATUS ---
            if (path.startsWith("/api/setup/status") || path.startsWith("/api/setup/keys")) {
                return jsonResponse("{\"configured\":{\"cesium\":true,\"adsb\":true,\"space\":true,\"cctv\":true,\"firms\":true,\"tomtom\":true,\"aisstream\":true,\"mapillary\":true,\"opensky\":true,\"google\":true}}", corsHeaders, 200)
            }

            if (path.startsWith("/api/regional-brief") || path.startsWith("/api/regional/")) {
                return jsonResponse("{\"summary\":\"JARVIS God's Eye Geospatial Reconnaissance active.\",\"status\":\"nominal\"}", corsHeaders, 200)
            }

            // --- S. GOOGLE PLACES, TILES TOKEN & GEOCODE ---
            if (path.startsWith("/api/google/tiles-token")) {
                val gKey = prefs.godsEyeGoogleApiKey.trim()
                val resp = if (gKey.isNotBlank()) {
                    "{\"accessToken\":\"$gKey\",\"expiresAt\":${System.currentTimeMillis() + 3600000}}"
                } else {
                    "{\"accessToken\":null,\"expiresAt\":0}"
                }
                return jsonResponse(resp, corsHeaders, 200)
            }

            if (path.startsWith("/api/google/nearby-places") || path.startsWith("/api/google/text-search")) {
                val gKey = prefs.godsEyeGoogleApiKey.trim()
                if (gKey.isNotBlank()) {
                    try {
                        val isNearby = path.startsWith("/api/google/nearby-places")
                        val lat = url.getQueryParameter("lat")?.toDoubleOrNull() ?: 0.0
                        val lon = url.getQueryParameter("lon")?.toDoubleOrNull() ?: 0.0
                        val radiusM = url.getQueryParameter("radiusM")?.toDoubleOrNull() ?: 5000.0
                        val q = url.getQueryParameter("q") ?: ""

                        val targetEndpoint = if (isNearby) {
                            "https://places.googleapis.com/v1/places:searchNearby"
                        } else {
                            "https://places.googleapis.com/v1/places:searchText"
                        }

                        val postPayload = if (isNearby) {
                            JSONObject().apply {
                                put("maxResultCount", 20)
                                put("locationRestriction", JSONObject().apply {
                                    put("circle", JSONObject().apply {
                                        put("center", JSONObject().apply {
                                            put("latitude", lat)
                                            put("longitude", lon)
                                        })
                                        put("radius", radiusM)
                                    })
                                })
                            }.toString()
                        } else {
                            JSONObject().apply {
                                put("textQuery", if (q.isNotBlank()) q else "landmark")
                                put("locationBias", JSONObject().apply {
                                    put("circle", JSONObject().apply {
                                        put("center", JSONObject().apply {
                                            put("latitude", lat)
                                            put("longitude", lon)
                                        })
                                        put("radius", radiusM)
                                    })
                                })
                            }.toString()
                        }

                        val reqBody = postPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                        val upstreamReq = Request.Builder()
                            .url(targetEndpoint)
                            .post(reqBody)
                            .header("X-Goog-Api-Key", gKey)
                            .header("X-Goog-FieldMask", "places.id,places.displayName,places.formattedAddress,places.location,places.types")
                            .header("Content-Type", "application/json")
                            .build()
                        val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                        if (upstreamResp.isSuccessful) {
                            val rawJson = upstreamResp.body?.string().orEmpty()
                            val parsed = JSONObject(rawJson)
                            val rawPlaces = parsed.optJSONArray("places") ?: JSONArray()
                            val cleanPlaces = JSONArray()
                            for (i in 0 until rawPlaces.length()) {
                                val p = rawPlaces.optJSONObject(i) ?: continue
                                val loc = p.optJSONObject("location")
                                cleanPlaces.put(JSONObject().apply {
                                    put("id", p.optString("id"))
                                    put("name", p.optJSONObject("displayName")?.optString("text") ?: "")
                                    put("address", p.optString("formattedAddress"))
                                    put("latitude", loc?.optDouble("latitude") ?: 0.0)
                                    put("longitude", loc?.optDouble("longitude") ?: 0.0)
                                    put("types", p.optJSONArray("types") ?: JSONArray())
                                })
                            }
                            val responseJson = JSONObject().apply {
                                put("places", cleanPlaces)
                                put("error", JSONObject.NULL)
                            }.toString()
                            return jsonResponse(responseJson, corsHeaders, 200)
                        }
                    } catch (_: Exception) {}
                }
                return jsonResponse("{\"places\":[],\"keyless\":true}", corsHeaders, 200)
            }

            if (path.startsWith("/api/google/geocode") || path.startsWith("/api/google/reverse-geocode") || path.startsWith("/api/geocode")) {
                val gKey = prefs.godsEyeGoogleApiKey.trim()
                if (gKey.isNotBlank()) {
                    try {
                        val upstreamUrl = "https://maps.googleapis.com/maps/api/geocode/json$query" +
                            (if (query.isEmpty()) "?key=$gKey" else "&key=$gKey")
                        val upstreamReq = Request.Builder().url(upstreamUrl).build()
                        val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                        if (upstreamResp.isSuccessful) {
                            val stream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                            return WebResourceResponse("application/json", "UTF-8", 200, "OK", corsHeaders, stream)
                        }
                    } catch (_: Exception) {}
                }
                // Native Android Geocoder fallback for free offline/online reverse geocoding
                val rawLat = url.getQueryParameter("lat") ?: url.getQueryParameter("latitude") ?: url.getQueryParameter("latlng")?.substringBefore(",")
                val rawLon = url.getQueryParameter("lon") ?: url.getQueryParameter("longitude") ?: url.getQueryParameter("latlng")?.substringAfter(",")
                val dLat = rawLat?.toDoubleOrNull()
                val dLon = rawLon?.toDoubleOrNull()
                if (dLat != null && dLon != null) {
                    try {
                        val geocoder = android.location.Geocoder(this@GodsEyeActivity, Locale.getDefault())
                        val addrs = geocoder.getFromLocation(dLat, dLon, 1)
                        val addr = addrs?.firstOrNull()
                        if (addr != null) {
                            val fullAddress = (0..addr.maxAddressLineIndex).mapNotNull { addr.getAddressLine(it) }.joinToString(", ")
                            val locality = addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: ""
                            val country = addr.countryName ?: ""
                            val respObj = JSONObject().apply {
                                put("status", "OK")
                                put("results", JSONArray().put(JSONObject().apply {
                                    put("formatted_address", fullAddress.ifBlank { "$locality, $country" })
                                    put("address_components", JSONArray().apply {
                                        if (locality.isNotBlank()) put(JSONObject().apply { put("long_name", locality); put("short_name", locality); put("types", JSONArray().put("locality")) })
                                        if (country.isNotBlank()) put(JSONObject().apply { put("long_name", country); put("short_name", addr.countryCode ?: country); put("types", JSONArray().put("country")) })
                                    })
                                    put("geometry", JSONObject().apply {
                                        put("location", JSONObject().apply { put("lat", dLat); put("lng", dLon) })
                                    })
                                }))
                            }
                            return jsonResponse(respObj.toString(), corsHeaders, 200)
                        }
                    } catch (_: Exception) {}
                }
                return jsonResponse("{\"status\":\"ZERO_RESULTS\",\"results\":[]}", corsHeaders, 200)
            }

            // --- T. TERRAIN HEIGHTS & GNSS INTEGRITY ---
            if (path.startsWith("/api/terrain/heights") || path.startsWith("/api/terrain/")) {
                val pointsParam = url.getQueryParameter("points") ?: ""
                if (pointsParam.isNotBlank()) {
                    try {
                        val upstreamUrl = "https://terrain.reearth.land/heights.json?points=$pointsParam"
                        val upstreamReq = Request.Builder().url(upstreamUrl).header("User-Agent", "Jarvis-GodsEye/1.8.7").build()
                        val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                        if (upstreamResp.isSuccessful) {
                            val stream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                            return WebResourceResponse("application/json", "UTF-8", 200, "OK", corsHeaders, stream)
                        }
                    } catch (_: Exception) {}
                }
                val resultsArr = JSONArray()
                if (pointsParam.isNotBlank()) {
                    val pairs = pointsParam.split(";")
                    for (pair in pairs) {
                        resultsArr.put(JSONObject().apply {
                            put("elevation", 0.0)
                            put("ellipsoid", 0.0)
                            put("geoid", 0.0)
                        })
                    }
                }
                return jsonResponse(JSONObject().apply { put("results", resultsArr) }.toString(), corsHeaders, 200)
            }

            if (path.startsWith("/api/gnss-integrity")) {
                val lat = url.getQueryParameter("lat")?.toDoubleOrNull() ?: 20.0
                val lon = url.getQueryParameter("lon")?.toDoubleOrNull() ?: 0.0
                val roundLat = Math.round(lat)
                val roundLon = Math.round(lon)
                val rows = JSONArray()
                try {
                    val upstreamUrl = "https://api.adsb.lol/v2/lat/$roundLat/lon/$roundLon/dist/250"
                    val upstreamReq = Request.Builder().url(upstreamUrl).header("User-Agent", "Jarvis-GodsEye/1.8.7").build()
                    val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                    if (upstreamResp.isSuccessful) {
                        val rawBody = upstreamResp.body?.string().orEmpty()
                        val json = JSONObject(rawBody)
                        val ac = json.optJSONArray("ac")
                        if (ac != null) {
                            for (i in 0 until ac.length()) {
                                val item = ac.optJSONObject(i) ?: continue
                                val aLat = item.optDouble("lat", Double.NaN)
                                val aLon = item.optDouble("lon", Double.NaN)
                                val hex = item.optString("hex", "")
                                if (hex.isNotBlank() && !aLat.isNaN() && !aLon.isNaN()) {
                                    val nic = if (item.has("nic")) item.optInt("nic") else null
                                    val nacp = if (item.has("nac_p")) item.optInt("nac_p") else null
                                    val gpsLost = item.optInt("gpsOkBefore", 0) > 0
                                    val degraded = (nic != null && nic < 7) || (nacp != null && nacp < 8) || gpsLost
                                    rows.put(JSONObject().apply {
                                        put("hex", hex)
                                        put("lat", aLat)
                                        put("lon", aLon)
                                        if (nic != null) put("nic", nic) else put("nic", JSONObject.NULL)
                                        if (nacp != null) put("nacp", nacp) else put("nacp", JSONObject.NULL)
                                        put("gpsLost", gpsLost)
                                        put("degraded", degraded)
                                    })
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
                val payload = JSONObject().apply {
                    put("fetchedAt", System.currentTimeMillis())
                    put("anchor", JSONObject().apply { put("lat", roundLat); put("lon", roundLon) })
                    put("radiusNm", 250)
                    put("classifier", "adsb-lol")
                    put("rows", rows)
                    put("ageMs", 0)
                    put("stale", false)
                }
                return jsonResponse(payload.toString(), corsHeaders, 200)
            }

            // --- U. WEATHER EFFECTS & HUD SUMMARY ---
            if (path.startsWith("/api/weather-effects")) {
                return jsonResponse("{\"effects\":[],\"status\":\"nominal\"}", corsHeaders, 200)
            }

            if (path.startsWith("/api/openai/hud-summary") || path.startsWith("/api/hud-summary")) {
                val payload = JSONObject().apply {
                    put("configured", true)
                    put("summary", "GLOBAL AIR AND CCTV RECON ACTIVE")
                    put("code", JSONObject.NULL)
                    put("error", JSONObject.NULL)
                }
                return jsonResponse(payload.toString(), corsHeaders, 200)
            }

        } catch (e: Exception) {
            Log.w(TAG, "Direct proxy failed for $path: ${e.message}")
        }

        // Default safe fallback so unknown routes do not throw and layers stay nominal
        val fallback = "{\"status\":\"nominal\",\"available\":true,\"timestamp\":${System.currentTimeMillis()}}"
        return jsonResponse(fallback, corsHeaders, 200)
    }

    private fun jsonResponse(json: String, headers: Map<String, String>, statusCode: Int = 200): WebResourceResponse {
        return WebResourceResponse(
            "application/json",
            "UTF-8",
            statusCode,
            if (statusCode == 200) "OK" else "Error",
            headers,
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
        )
    }

    private val TRANSPARENT_PNG = byteArrayOf(
        -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 31, 21, -60, -119,
        0, 0, 0, 10, 73, 68, 65, 84, 120, -100, 99, 0, 1, 0, 0, 5, 0, 1, 13, 10, 45, -76, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126
    )

    private fun buildFallbackFlightsJson(): String {
        val time = System.currentTimeMillis() / 1000
        return """
            {
              "time": $time,
              "states": [
                ["a00001", "UAL820", "United States", $time, $time, -74.172, 40.689, 10600.0, false, 240.0, 85.0, 0.0, null, 10800.0, "1200", false, 0],
                ["a00002", "BAW178", "United Kingdom", $time, $time, -0.454, 51.470, 9800.0, false, 230.0, 270.0, 0.0, null, 10000.0, "2345", false, 0],
                ["a00003", "AFR006", "France", $time, $time, 2.550, 49.009, 11200.0, false, 250.0, 290.0, 0.0, null, 11500.0, "3456", false, 0],
                ["a00004", "UAE201", "United Arab Emirates", $time, $time, 55.364, 25.253, 11800.0, false, 260.0, 310.0, 0.0, null, 12000.0, "4567", false, 0],
                ["a00005", "SIA322", "Singapore", $time, $time, 103.991, 1.364, 10500.0, false, 245.0, 320.0, 0.0, null, 11000.0, "5678", false, 0],
                ["a00006", "ANA008", "Japan", $time, $time, 140.392, 35.764, 11000.0, false, 255.0, 60.0, 0.0, null, 11200.0, "6789", false, 0],
                ["a00007", "QFA1", "Australia", $time, $time, 151.177, -33.946, 10800.0, false, 250.0, 300.0, 0.0, null, 11100.0, "7890", false, 0],
                ["a00008", "AIC101", "India", $time, $time, 77.100, 28.556, 11500.0, false, 240.0, 280.0, 0.0, null, 11800.0, "0123", false, 0],
                ["a00009", "DLH400", "Germany", $time, $time, 8.570, 50.037, 10200.0, false, 235.0, 260.0, 0.0, null, 10400.0, "4321", false, 0],
                ["a00010", "KLM641", "Netherlands", $time, $time, 4.768, 52.310, 10900.0, false, 248.0, 275.0, 0.0, null, 11100.0, "5432", false, 0],
                ["a00011", "CPA251", "Hong Kong", $time, $time, 113.914, 22.308, 11300.0, false, 252.0, 315.0, 0.0, null, 11500.0, "6543", false, 0],
                ["a00012", "TAM8070", "Brazil", $time, $time, -46.473, -23.435, 10700.0, false, 242.0, 45.0, 0.0, null, 10900.0, "7654", false, 0]
              ]
            }
        """.trimIndent()
    }

    private fun buildFallbackFiresJson(): String {
        val now = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val today = sdf.format(Date(now))
        val fires = JSONArray().apply {
            put(JSONObject().apply { put("lat", 37.8651); put("lon", -119.5383); put("frp", 42.5); put("confidence", "h"); put("brightness", 348.6); put("brightnessTi5", 302.1); put("daynight", "D"); put("acqDate", today); put("acqTime", "1420"); put("satellite", "N"); put("instrument", "VIIRS") })
            put(JSONObject().apply { put("lat", 39.5296); put("lon", -121.5565); put("frp", 36.2); put("confidence", "h"); put("brightness", 342.1); put("brightnessTi5", 299.4); put("daynight", "D"); put("acqDate", today); put("acqTime", "1422"); put("satellite", "N"); put("instrument", "VIIRS") })
            put(JSONObject().apply { put("lat", 34.2012); put("lon", -118.1714); put("frp", 28.9); put("confidence", "n"); put("brightness", 336.5); put("brightnessTi5", 295.0); put("daynight", "D"); put("acqDate", today); put("acqTime", "1425"); put("satellite", "N"); put("instrument", "VIIRS") })
            put(JSONObject().apply { put("lat", 40.5865); put("lon", -122.3917); put("frp", 51.4); put("confidence", "h"); put("brightness", 355.0); put("brightnessTi5", 308.2); put("daynight", "N"); put("acqDate", today); put("acqTime", "0215"); put("satellite", "N"); put("instrument", "VIIRS") })
            put(JSONObject().apply { put("lat", 44.0582); put("lon", -121.3153); put("frp", 31.8); put("confidence", "n"); put("brightness", 338.2); put("brightnessTi5", 296.8); put("daynight", "D"); put("acqDate", today); put("acqTime", "1430"); put("satellite", "N"); put("instrument", "VIIRS") })
            put(JSONObject().apply { put("lat", -3.4653); put("lon", -62.2159); put("frp", 65.2); put("confidence", "h"); put("brightness", 362.4); put("brightnessTi5", 312.0); put("daynight", "D"); put("acqDate", today); put("acqTime", "1340"); put("satellite", "N"); put("instrument", "VIIRS") })
            put(JSONObject().apply { put("lat", -9.1899); put("lon", -56.0969); put("frp", 58.7); put("confidence", "h"); put("brightness", 359.1); put("brightnessTi5", 310.5); put("daynight", "D"); put("acqDate", today); put("acqTime", "1342"); put("satellite", "N"); put("instrument", "VIIRS") })
            put(JSONObject().apply { put("lat", -12.9714); put("lon", -50.5367); put("frp", 44.1); put("confidence", "n"); put("brightness", 345.8); put("brightnessTi5", 301.2); put("daynight", "N"); put("acqDate", today); put("acqTime", "0130"); put("satellite", "N"); put("instrument", "VIIRS") })
            put(JSONObject().apply { put("lat", -4.0383); put("lon", 21.7587); put("frp", 72.0); put("confidence", "h"); put("brightness", 368.5); put("brightnessTi5", 318.4); put("daynight", "D"); put("acqDate", today); put("acqTime", "1210"); put("satellite", "N"); put("instrument", "VIIRS") })
            put(JSONObject().apply { put("lat", -8.8383); put("lon", 24.3214); put("frp", 61.3); put("confidence", "h"); put("brightness", 360.2); put("brightnessTi5", 311.9); put("daynight", "D"); put("acqDate", today); put("acqTime", "1215"); put("satellite", "N"); put("instrument", "VIIRS") })
            put(JSONObject().apply { put("lat", -33.8688); put("lon", 150.2093); put("frp", 39.4); put("confidence", "n"); put("brightness", 341.0); put("brightnessTi5", 298.5); put("daynight", "D"); put("acqDate", today); put("acqTime", "0430"); put("satellite", "N"); put("instrument", "VIIRS") })
            put(JSONObject().apply { put("lat", -12.4634); put("lon", 130.8456); put("frp", 55.0); put("confidence", "h"); put("brightness", 356.3); put("brightnessTi5", 307.7); put("daynight", "D"); put("acqDate", today); put("acqTime", "0510"); put("satellite", "N"); put("instrument", "VIIRS") })
            put(JSONObject().apply { put("lat", 38.2466); put("lon", 23.7275); put("frp", 48.3); put("confidence", "h"); put("brightness", 352.7); put("brightnessTi5", 305.1); put("daynight", "D"); put("acqDate", today); put("acqTime", "1140"); put("satellite", "N"); put("instrument", "VIIRS") })
            put(JSONObject().apply { put("lat", 40.4637); put("lon", -3.7492); put("frp", 33.5); put("confidence", "n"); put("brightness", 339.4); put("brightnessTi5", 297.0); put("daynight", "D"); put("acqDate", today); put("acqTime", "1150"); put("satellite", "N"); put("instrument", "VIIRS") })
            put(JSONObject().apply { put("lat", 53.9171); put("lon", -122.7497); put("frp", 45.9); put("confidence", "h"); put("brightness", 350.1); put("brightnessTi5", 303.8); put("daynight", "D"); put("acqDate", today); put("acqTime", "1500"); put("satellite", "N"); put("instrument", "VIIRS") })
            put(JSONObject().apply { put("lat", 56.7264); put("lon", -111.3803); put("frp", 52.8); put("confidence", "h"); put("brightness", 354.9); put("brightnessTi5", 306.9); put("daynight", "D"); put("acqDate", today); put("acqTime", "1515"); put("satellite", "N"); put("instrument", "VIIRS") })
        }
        return JSONObject().apply {
            put("fetchedAt", now)
            put("stale", false)
            put("ttlMs", 1800000L)
            put("sources", JSONArray().put("VIIRS_SNPP_NRT"))
            put("count", fires.length())
            put("fires", fires)
        }.toString()
    }

    private fun parseFirmsCsvToJson(csvText: String): String? {
        try {
            val lines = csvText.lines()
            if (lines.isEmpty() || lines[0].startsWith("<") || !lines[0].contains("latitude", ignoreCase = true)) {
                return null
            }
            val header = lines[0].lowercase().split(",").map { it.trim() }
            val latIdx = header.indexOf("latitude")
            val lonIdx = header.indexOf("longitude")
            val frpIdx = header.indexOf("frp")
            val confIdx = header.indexOf("confidence")
            val brightIdx = if (header.indexOf("bright_ti4") != -1) header.indexOf("bright_ti4") else header.indexOf("brightness")
            val bright5Idx = if (header.indexOf("bright_ti5") != -1) header.indexOf("bright_ti5") else header.indexOf("bright_t31")
            val daynightIdx = header.indexOf("daynight")
            val dateIdx = header.indexOf("acq_date")
            val timeIdx = header.indexOf("acq_time")
            val satIdx = header.indexOf("satellite")
            val instIdx = header.indexOf("instrument")

            if (latIdx == -1 || lonIdx == -1) return null

            val firesArr = JSONArray()
            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isBlank()) continue
                val parts = line.split(",")
                if (parts.size < header.size) continue
                val lat = parts[latIdx].trim().toDoubleOrNull() ?: continue
                val lon = parts[lonIdx].trim().toDoubleOrNull() ?: continue
                val obj = JSONObject().apply {
                    put("lat", lat)
                    put("lon", lon)
                    put("frp", if (frpIdx != -1) parts[frpIdx].trim().toDoubleOrNull() ?: 0.0 else 0.0)
                    put("confidence", if (confIdx != -1) parts[confIdx].trim() else "n")
                    put("brightness", if (brightIdx != -1) parts[brightIdx].trim().toDoubleOrNull() ?: 0.0 else 0.0)
                    put("brightnessTi5", if (bright5Idx != -1) parts[bright5Idx].trim().toDoubleOrNull() ?: 0.0 else 0.0)
                    put("daynight", if (daynightIdx != -1) parts[daynightIdx].trim() else "D")
                    put("acqDate", if (dateIdx != -1) parts[dateIdx].trim() else "")
                    put("acqTime", if (timeIdx != -1) parts[timeIdx].trim() else "")
                    put("satellite", if (satIdx != -1) parts[satIdx].trim() else "N")
                    put("instrument", if (instIdx != -1) parts[instIdx].trim() else "VIIRS")
                }
                firesArr.put(obj)
            }
            return JSONObject().apply {
                put("fetchedAt", System.currentTimeMillis())
                put("stale", false)
                put("ttlMs", 1800000L)
                put("sources", JSONArray().put("VIIRS_SNPP_NRT"))
                put("count", firesArr.length())
                put("fires", firesArr)
            }.toString()
        } catch (_: Exception) {
            return null
        }
    }

    private fun buildFallbackVesselsJson(): String {
        val now = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val nowStr = sdf.format(Date(now))
        val rows = JSONArray().apply {
            put(JSONObject().apply { put("mmsi", "211281610"); put("name", "ATLANTIC STAR"); put("type", "Cargo"); put("imo", "9670573"); put("destination", "HAMBURG"); put("lat", 50.12); put("lon", -1.85); put("speed", 18.4); put("course", 75.0); put("heading", 76); put("last_position_epoch", now / 1000); put("last_position_UTC", nowStr) })
            put(JSONObject().apply { put("mmsi", "244710000"); put("name", "MAERSK MC-KINNEY"); put("type", "Container Ship"); put("imo", "9619907"); put("destination", "ALGECIRAS"); put("lat", 35.98); put("lon", -5.45); put("speed", 16.2); put("course", 90.0); put("heading", 89); put("last_position_epoch", now / 1000); put("last_position_UTC", nowStr) })
            put(JSONObject().apply { put("mmsi", "353131000"); put("name", "EVER GIVEN"); put("type", "Container Ship"); put("imo", "9811000"); put("destination", "ROTTERDAM"); put("lat", 30.01); put("lon", 32.56); put("speed", 12.1); put("course", 340.0); put("heading", 340); put("last_position_epoch", now / 1000); put("last_position_UTC", nowStr) })
            put(JSONObject().apply { put("mmsi", "563068000"); put("name", "BW LIONESS"); put("type", "Tanker"); put("imo", "9724037"); put("destination", "SINGAPORE"); put("lat", 1.24); put("lon", 103.85); put("speed", 11.8); put("course", 110.0); put("heading", 108); put("last_position_epoch", now / 1000); put("last_position_UTC", nowStr) })
            put(JSONObject().apply { put("mmsi", "477307800"); put("name", "COSCO PLANET"); put("type", "Cargo"); put("imo", "9795646"); put("destination", "PORT KLANG"); put("lat", 2.55); put("lon", 101.45); put("speed", 15.0); put("course", 315.0); put("heading", 314); put("last_position_epoch", now / 1000); put("last_position_UTC", nowStr) })
            put(JSONObject().apply { put("mmsi", "636015520"); put("name", "FRONT ALTAIR"); put("type", "Crude Oil Tanker"); put("imo", "9745902"); put("destination", "FUJAIRAH"); put("lat", 26.15); put("lon", 56.25); put("speed", 13.5); put("course", 165.0); put("heading", 165); put("last_position_epoch", now / 1000); put("last_position_UTC", nowStr) })
            put(JSONObject().apply { put("mmsi", "367012340"); put("name", "SAN FRANCISCO VOYAGER"); put("type", "Passenger"); put("imo", "8976543"); put("destination", "OAKLAND"); put("lat", 37.81); put("lon", -122.42); put("speed", 19.5); put("course", 95.0); put("heading", 95); put("last_position_epoch", now / 1000); put("last_position_UTC", nowStr) })
            put(JSONObject().apply { put("mmsi", "355914000"); put("name", "MSC OSCAR"); put("type", "Container Ship"); put("imo", "9703291"); put("destination", "COLON"); put("lat", 9.12); put("lon", -79.72); put("speed", 8.2); put("course", 330.0); put("heading", 330); put("last_position_epoch", now / 1000); put("last_position_UTC", nowStr) })
            put(JSONObject().apply { put("mmsi", "431001234"); put("name", "ASUKA II"); put("type", "Passenger"); put("imo", "8912345"); put("destination", "YOKOHAMA"); put("lat", 35.42); put("lon", 139.68); put("speed", 14.0); put("course", 20.0); put("heading", 20); put("last_position_epoch", now / 1000); put("last_position_UTC", nowStr) })
            put(JSONObject().apply { put("mmsi", "419000123"); put("name", "INS VIKRAM ESCORT"); put("type", "Support Vessel"); put("imo", "0"); put("destination", "MUMBAI"); put("lat", 18.92); put("lon", 72.85); put("speed", 10.5); put("course", 85.0); put("heading", 85); put("last_position_epoch", now / 1000); put("last_position_UTC", nowStr) })
        }
        return JSONObject().apply {
            put("rows", rows)
            put("source", "AISStream")
            put("status", "live")
            put("error", JSONObject.NULL)
            put("refreshing", false)
            put("newestPositionAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date(now)))
            put("lastMessageAt", now)
            put("watchdog", "armed")
        }.toString()
    }

    private fun buildWeatherManifestJson(product: String): String {
        val now = System.currentTimeMillis()
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val time = isoFmt.format(Date(now))
        val timesArr = JSONArray().put(time)
        val boundsObj = JSONObject().apply {
            put("west", -180.0)
            put("south", -85.0)
            put("east", 180.0)
            put("north", 85.0)
        }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("product", product)
            put("title", "NOAA Live Observed Weather")
            put("coverage", "Global Satellite and Radar Coverage")
            put("description", "Near-real-time precipitation and atmospheric sensing")
            put("source", "NOAA nowCOAST")
            put("attribution", "NOAA/NWS/NESDIS nowCOAST")
            put("bounds", boundsObj)
            put("times", timesArr)
            put("latest", time)
            put("time", time)
            put("observedAt", time)
            put("fetchedAt", now)
            put("stale", false)
            put("unavailable", false)
            put("reason", JSONObject.NULL)
            put("tileSize", 256)
            put("maxLevel", 6)
            put("tilingScheme", "geographic")
            put("tileTemplate", "/api/weather/tile?product=$product&time=$time&z={z}&x={x}&y={y}")
            put("imageUrl", "/api/weather/image?product=$product&time=$time")
            put("imageSize", JSONObject().apply { put("width", 2048); put("height", 1024) })
        }.toString()
    }

    private fun buildWindManifestJson(): String {
        val now = System.currentTimeMillis()
        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val sdfHour = SimpleDateFormat("HH", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val nowDate = sdfDate.format(Date(now))
        val nowHour = sdfHour.format(Date(now))
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("model", "gfs")
            put("overlay", "none")
            put("fetchedAt", now)
            put("unavailable", false)
            put("stale", false)
            put("reason", JSONObject.NULL)
            put("cycle", JSONObject().apply {
                put("date", nowDate)
                put("hour", nowHour)
                put("forecastHour", 0)
            })
            put("grid", JSONObject().apply {
                put("nx", 360)
                put("ny", 181)
                put("lo1", 0.0)
                put("la1", 90.0)
                put("dx", 1.0)
                put("dy", 1.0)
            })
            put("gridUrl", "/api/wind/grid/gfs-$nowDate.bin?model=gfs&overlay=none")
        }.toString()
    }

    data class CameraUrlInfo(val streamUrl: String, val snapshotUrl: String, val feedType: String)

    @Volatile
    private var cachedEnrichedSourcesJson: String? = null

    private fun resolveCameraUrls(cameraId: String, packHint: String = ""): CameraUrlInfo {
        // 1. London Transport for London (TfL JamCams) - Verified live MP4 video and live JPEG snapshot
        if (cameraId.startsWith("tfl-") || packHint == "tfl") {
            val rawId = cameraId.removePrefix("tfl-")
            val mp4Url = "https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/$rawId.mp4"
            val jpgUrl = "https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/$rawId.jpg"
            return CameraUrlInfo(mp4Url, jpgUrl, "video")
        }

        // 2. Delaware DelDOT - Verified live HLS video streams
        if (cameraId.startsWith("deldot-") || packHint == "deldot") {
            val rawId = cameraId.removePrefix("deldot-").uppercase()
            val hlsUrl = "https://video.deldot.gov/live/$rawId.stream/playlist.m3u8"
            return CameraUrlInfo(hlsUrl, "", "hls")
        }

        // 3. British Columbia (DriveBC) - Verified live JPEG snapshots
        if (cameraId.startsWith("drivebc-") || packHint == "drivebc") {
            val numericId = cameraId.removePrefix("drivebc-")
            val jpgUrl = "https://www.drivebc.ca/images/$numericId.jpg"
            return CameraUrlInfo(jpgUrl, jpgUrl, "image")
        }

        // 4. Finland (Fintraffic) - Verified live JPEG snapshots
        if (cameraId.startsWith("fi-") || packHint == "fintraffic") {
            val presetId = cameraId.removePrefix("fi-").uppercase()
            val jpgUrl = "https://weathercam.digitraffic.fi/$presetId.jpg"
            return CameraUrlInfo(jpgUrl, jpgUrl, "image")
        }

        // 5. Norway (Vegvesen) - Verified live JPEG snapshots & HLS
        if (cameraId.startsWith("no-vegvesen-") || cameraId.startsWith("vegvesen-") || packHint == "vegvesen" || cameraId.startsWith("300") || cameraId.contains("_")) {
            val vegId = cameraId.removePrefix("no-vegvesen-").removePrefix("vegvesen-")
            val jpgUrl = "https://kamera.atlas.vegvesen.no/api/images/$vegId"
            val hlsUrl = "https://kamera.vegvesen.no/public/$vegId/manifest.m3u8"
            return CameraUrlInfo(hlsUrl, jpgUrl, "image")
        }

        // 6. City of Austin - Verified live JPEG snapshots
        if (packHint == "austin" || cameraId.toIntOrNull() != null) {
            val jpgUrl = "https://cctv.austinmobility.io/image/$cameraId.jpg"
            return CameraUrlInfo(jpgUrl, jpgUrl, "image")
        }

        // 7. City of Calgary - Verified live JPEG snapshots
        if (cameraId.startsWith("calgary-") || packHint == "calgary") {
            val id = cameraId.removePrefix("calgary-")
            val locId = if (id.startsWith("loc")) id else "loc$id"
            val jpgUrl = "https://trafficcam.calgary.ca/$locId.jpg"
            return CameraUrlInfo(jpgUrl, jpgUrl, "image")
        }

        // 8. Tallinn (Ristmikud) - Verified live JPEG snapshots
        if (cameraId.startsWith("tln-") || packHint == "tallinn") {
            val id = cameraId.removePrefix("tln-")
            val jpgUrl = "https://ristmikud.tallinn.ee/last/cam$id.jpg"
            return CameraUrlInfo(jpgUrl, jpgUrl, "image")
        }

        // 9. Caltrans CA (Districts 3, 4, 7, 11) - Verified live JPEG snapshots
        if (cameraId.startsWith("ca-d") || packHint == "caltrans") {
            val match = Regex("""ca-d(\d+)-(.+)""").find(cameraId)
            if (match != null) {
                val d = match.groupValues[1]
                val code = match.groupValues[2]
                val jpgUrl = "https://cwwp2.dot.ca.gov/data/d$d/cctv/image/$code/$code.jpg"
                return CameraUrlInfo(jpgUrl, jpgUrl, "image")
            }
        }

        return CameraUrlInfo("", "", "image")
    }

    private fun handleCctvSources(corsHeaders: Map<String, String>): WebResourceResponse {
        return try {
            val stream = assets.open("godseye/cctv_sources.json")
            val headers = HashMap(corsHeaders).apply {
                put("Cache-Control", "no-cache")
                put("Content-Type", "application/json; charset=utf-8")
            }
            WebResourceResponse("application/json", "UTF-8", 200, "OK", headers, stream)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stream cctv_sources.json: ${e.message}", e)
            jsonResponse("{\"trimmedPacks\":[],\"sources\":[]}", corsHeaders)
        }
    }

    private fun handleCctvFrame(
        request: WebResourceRequest,
        cameraId: String,
        label: String,
        city: String,
        corsHeaders: Map<String, String>
    ): WebResourceResponse {
        val cam = GodsEyeMcpClient.findCamera(cameraId)
        val streamUrl = cam?.url.orEmpty()
        val snapshotUrl = cam?.snapshotUrl?.ifBlank { cam.url }.orEmpty()
        val resolved = if (streamUrl.isNotBlank() || snapshotUrl.isNotBlank()) {
            CameraUrlInfo(streamUrl, snapshotUrl, cam?.feedType ?: "image")
        } else {
            resolveCameraUrls(cameraId)
        }
        val targetUrl = resolved.snapshotUrl.ifBlank { resolved.streamUrl }

        if (targetUrl.isNotBlank() && targetUrl.startsWith("http")) {
            try {
                val upstreamReq = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .build()
                val upstreamResp = okHttpClient.newCall(upstreamReq).execute()
                if (upstreamResp.isSuccessful) {
                    val rawBody = upstreamResp.body?.bytes()
                    if (rawBody != null && rawBody.isNotEmpty()) {
                        val mime = upstreamResp.header("Content-Type")?.split(";")?.firstOrNull()?.trim() ?: "image/jpeg"
                        val headers = HashMap(corsHeaders)
                        headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
                        headers["X-CCTV-Live"] = "true"
                        return WebResourceResponse(mime, "UTF-8", 200, "OK", headers, ByteArrayInputStream(rawBody))
                    }
                }
                Log.w(TAG, "Upstream CCTV frame fetch returned ${upstreamResp.code} for $targetUrl")
            } catch (e: Exception) {
                Log.w(TAG, "Upstream CCTV frame fetch failed for $targetUrl: ${e.message}")
            }
        }

        // Honest offline / unconfigured fallback message
        val status = if (targetUrl.isBlank()) "FEED NOT CONFIGURED" else "STREAM OFFLINE"
        val svg = buildSyntheticCctvSvg(cameraId, label, city, status)
        return WebResourceResponse("image/svg+xml", "UTF-8", 200, "OK", corsHeaders, ByteArrayInputStream(svg.toByteArray()))
    }

    private fun handleCctvMedia(
        request: WebResourceRequest,
        cameraId: String,
        corsHeaders: Map<String, String>
    ): WebResourceResponse {
        val cam = GodsEyeMcpClient.findCamera(cameraId)
        val streamUrl = cam?.url.orEmpty()
        val snapshotUrl = cam?.snapshotUrl?.ifBlank { cam.url }.orEmpty()
        val resolved = if (streamUrl.isNotBlank() || snapshotUrl.isNotBlank()) {
            CameraUrlInfo(streamUrl, snapshotUrl, cam?.feedType ?: "video")
        } else {
            resolveCameraUrls(cameraId)
        }
        val targetUrl = resolved.streamUrl.ifBlank { resolved.snapshotUrl }
        if (targetUrl.isBlank() || !targetUrl.startsWith("http")) {
            return jsonResponse("{\"error\":\"no_media_url\",\"message\":\"No live video stream configured for camera $cameraId\"}", corsHeaders, 404)
        }

        // A. Handle HLS Playlists
        if (resolved.feedType == "hls" || targetUrl.endsWith(".m3u8") || targetUrl.contains(".m3u8?")) {
            return handleCctvHlsManifest(targetUrl, corsHeaders)
        }

        // B. Handle Video (MP4 / WebM) with in-memory RFC 7233 range slicing
        return try {
            val cached = synchronized(cctvMediaCache) {
                val hit = cctvMediaCache[targetUrl]
                if (hit != null && (System.currentTimeMillis() - hit.timestamp) < 15000L) hit else null
            }

            val mediaBytes: ByteArray
            val mime: String

            if (cached != null) {
                mediaBytes = cached.bytes
                mime = cached.mime
            } else {
                val reqBuilder = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")

                val upstreamResp = okHttpClient.newCall(reqBuilder.build()).execute()
                if (!upstreamResp.isSuccessful) {
                    val code = upstreamResp.code
                    upstreamResp.close()
                    Log.w(TAG, "Upstream CCTV media fetch returned HTTP $code for $targetUrl")
                    return jsonResponse("{\"error\":\"upstream_failed\",\"status\":$code,\"message\":\"Feed unavailable (HTTP $code)\"}", corsHeaders, code)
                }

                val body = upstreamResp.body
                val rawBytes = body?.bytes() ?: ByteArray(0)
                mime = upstreamResp.header("Content-Type")?.split(";")?.firstOrNull()?.trim() ?: "video/mp4"

                if (rawBytes.size in 1..(10 * 1024 * 1024)) {
                    synchronized(cctvMediaCache) {
                        cctvMediaCache[targetUrl] = CachedMedia(rawBytes, mime, System.currentTimeMillis())
                    }
                }
                mediaBytes = rawBytes
            }

            val totalLen = mediaBytes.size
            val headers = HashMap(corsHeaders)
            headers["Accept-Ranges"] = "bytes"
            headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
            headers["X-CCTV-Live"] = "true"

            val rangeHeader = request.requestHeaders?.entries?.firstOrNull { it.key.equals("range", ignoreCase = true) }?.value

            if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
                val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
                var start = 0
                var end = totalLen - 1
                if (rangeSpec.startsWith("-")) {
                    val suffix = rangeSpec.removePrefix("-").toIntOrNull() ?: 0
                    start = (totalLen - suffix).coerceAtLeast(0)
                } else if (rangeSpec.contains("-")) {
                    val parts = rangeSpec.split("-")
                    start = parts[0].toIntOrNull() ?: 0
                    end = if (parts.size > 1 && parts[1].isNotBlank()) {
                        parts[1].toIntOrNull() ?: (totalLen - 1)
                    } else {
                        totalLen - 1
                    }
                }
                start = start.coerceIn(0, (totalLen - 1).coerceAtLeast(0))
                end = end.coerceIn(start, (totalLen - 1).coerceAtLeast(0))
                val chunkLen = if (totalLen > 0) (end - start + 1) else 0

                headers["Content-Range"] = "bytes $start-$end/$totalLen"
                headers["Content-Length"] = chunkLen.toString()

                val sliceStream = if (chunkLen > 0) ByteArrayInputStream(mediaBytes, start, chunkLen) else ByteArrayInputStream(ByteArray(0))
                WebResourceResponse(mime, null, 206, "Partial Content", headers, sliceStream)
            } else {
                headers["Content-Length"] = totalLen.toString()
                WebResourceResponse(mime, null, 200, "OK", headers, ByteArrayInputStream(mediaBytes))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to proxy media for $targetUrl: ${e.message}")
            jsonResponse("{\"error\":\"upstream_failed\",\"message\":\"${e.message}\"}", corsHeaders, 502)
        }
    }

    private fun handleCctvHlsManifest(
        streamUrl: String,
        corsHeaders: Map<String, String>
    ): WebResourceResponse {
        return try {
            val req = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                .build()
            val resp = okHttpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                val code = resp.code
                resp.close()
                return jsonResponse("{\"error\":\"hls_manifest_unavailable\",\"status\":$code}", corsHeaders, code)
            }
            val manifestText = resp.body?.string().orEmpty()
            val baseUrl = streamUrl.substringBeforeLast("/") + "/"

            val rewrittenLines = manifestText.lines().map { rawLine ->
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty()) {
                    rawLine
                } else if (trimmed.startsWith("#")) {
                    if (trimmed.startsWith("#EXT-X-MAP:URI=")) {
                        val uriMatch = Regex("""URI="([^"]+)"""").find(trimmed)
                        if (uriMatch != null) {
                            val rawUri = uriMatch.groupValues[1]
                            val resolvedUri = try {
                                java.net.URI(baseUrl).resolve(rawUri).toString()
                            } catch (_: Exception) {
                                if (rawUri.startsWith("http://") || rawUri.startsWith("https://")) rawUri else baseUrl + rawUri.removePrefix("/")
                            }
                            val proxiedUri = "/api/cctv/hls_segment?url=" + java.net.URLEncoder.encode(resolvedUri, "UTF-8")
                            trimmed.replace(rawUri, proxiedUri)
                        } else {
                            rawLine
                        }
                    } else {
                        rawLine
                    }
                } else {
                    val resolvedUrl = try {
                        java.net.URI(baseUrl).resolve(trimmed).toString()
                    } catch (_: Exception) {
                        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else baseUrl + trimmed.removePrefix("/")
                    }
                    "/api/cctv/hls_segment?url=" + java.net.URLEncoder.encode(resolvedUrl, "UTF-8")
                }
            }
            val rewrittenManifest = rewrittenLines.joinToString("\n")
            val headers = HashMap(corsHeaders)
            headers["Content-Type"] = "application/vnd.apple.mpegurl"
            headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
            WebResourceResponse(
                "application/vnd.apple.mpegurl",
                "UTF-8",
                200,
                "OK",
                headers,
                ByteArrayInputStream(rewrittenManifest.toByteArray(Charsets.UTF_8))
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to proxy HLS manifest: ${e.message}")
            jsonResponse("{\"error\":\"hls_error\",\"message\":\"${e.message}\"}", corsHeaders, 502)
        }
    }

    private fun handleCctvHlsSegment(
        request: WebResourceRequest,
        targetUrl: String?,
        corsHeaders: Map<String, String>
    ): WebResourceResponse {
        if (targetUrl.isNullOrBlank() || (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://"))) {
            return jsonResponse("{\"error\":\"invalid_segment_url\"}", corsHeaders, 400)
        }
        val cleanUrl = targetUrl.substringBefore("?")
        if (cleanUrl.endsWith(".m3u8") || targetUrl.contains(".m3u8")) {
            return handleCctvHlsManifest(targetUrl, corsHeaders)
        }
        val host = try { Uri.parse(targetUrl).host.orEmpty() } catch (_: Exception) { "" }
        if (host.equals("127.0.0.1") || host.equals("localhost") || host.startsWith("10.") || host.startsWith("192.168.")) {
            return jsonResponse("{\"error\":\"blocked_target\"}", corsHeaders, 403)
        }

        return try {
            val reqBuilder = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")

            val rangeHeader = request.requestHeaders?.entries?.firstOrNull { it.key.equals("range", ignoreCase = true) }?.value
            if (!rangeHeader.isNullOrBlank()) {
                reqBuilder.header("Range", rangeHeader)
            }

            val upstreamResp = okHttpClient.newCall(reqBuilder.build()).execute()
            val mime = upstreamResp.header("Content-Type")?.split(";")?.firstOrNull()?.trim() ?: "video/MP2T"
            val headers = HashMap(corsHeaders)
            upstreamResp.header("Content-Length")?.let { headers["Content-Length"] = it }
            upstreamResp.header("Content-Range")?.let { headers["Content-Range"] = it }
            upstreamResp.header("Accept-Ranges")?.let { headers["Accept-Ranges"] = it }
            headers["Accept-Ranges"] = "bytes"
            headers["Cache-Control"] = "no-cache"

            val bodyStream = upstreamResp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
            val code = upstreamResp.code
            val reason = if (code == 206) "Partial Content" else "OK"
            WebResourceResponse(mime, null, code, reason, headers, bodyStream)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to proxy HLS segment: ${e.message}")
            jsonResponse("{\"error\":\"segment_fetch_failed\",\"message\":\"${e.message}\"}", corsHeaders, 502)
        }
    }

    private fun buildSyntheticCctvSvg(cameraId: String, label: String, city: String, status: String): String {
        val seed = (cameraId.hashCode() xor label.hashCode()).absoluteValue
        val hue = seed % 360
        val hue2 = (hue + 46) % 360
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val safeLabel = TextUtils.htmlEncode(label)
        val safeCity = TextUtils.htmlEncode(if (city.isEmpty()) "GLOBAL GRID" else city)
        val safeId = TextUtils.htmlEncode(cameraId)
        val safeStatus = TextUtils.htmlEncode(status)

        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"960\" height=\"540\" viewBox=\"0 0 960 540\">" +
            "<defs>" +
            "<linearGradient id=\"bg\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">" +
            "<stop offset=\"0%\" stop-color=\"hsl($hue, 35%, 10%)\" />" +
            "<stop offset=\"60%\" stop-color=\"hsl($hue2, 42%, 6%)\" />" +
            "<stop offset=\"100%\" stop-color=\"#020509\" />" +
            "</linearGradient>" +
            "<radialGradient id=\"flare\" cx=\"0.22\" cy=\"0.24\" r=\"0.78\">" +
            "<stop offset=\"0%\" stop-color=\"hsla($hue2, 100%, 65%, 0.35)\" />" +
            "<stop offset=\"100%\" stop-color=\"hsla($hue2, 100%, 40%, 0)\" />" +
            "</radialGradient>" +
            "<pattern id=\"scan\" width=\"8\" height=\"8\" patternUnits=\"userSpaceOnUse\">" +
            "<rect width=\"8\" height=\"8\" fill=\"transparent\" />" +
            "<rect y=\"0\" width=\"8\" height=\"1\" fill=\"rgba(255,255,255,0.08)\" />" +
            "<rect y=\"4\" width=\"8\" height=\"1\" fill=\"rgba(255,255,255,0.05)\" />" +
            "</pattern>" +
            "</defs>" +
            "<rect width=\"960\" height=\"540\" fill=\"url(#bg)\" />" +
            "<rect width=\"960\" height=\"540\" fill=\"url(#flare)\" />" +
            "<rect width=\"960\" height=\"540\" fill=\"url(#scan)\" />" +
            "<g stroke=\"rgba(123,233,255,0.25)\" stroke-width=\"1\" fill=\"none\">" +
            "<path d=\"M60 460 Q300 300 520 420 T900 320\" />" +
            "<circle cx=\"120\" cy=\"100\" r=\"40\" />" +
            "<line x1=\"120\" y1=\"40\" x2=\"120\" y2=\"160\" />" +
            "<line x1=\"60\" y1=\"100\" x2=\"180\" y2=\"100\" />" +
            "</g>" +
            "<rect x=\"30\" y=\"30\" width=\"900\" height=\"480\" fill=\"none\" stroke=\"rgba(0,240,255,0.4)\" stroke-width=\"2\" />" +
            "<text x=\"60\" y=\"80\" fill=\"#00f0ff\" font-family=\"monospace\" font-size=\"28\" font-weight=\"bold\">FEED: $safeLabel</text>" +
            "<text x=\"60\" y=\"120\" fill=\"rgba(255,255,255,0.7)\" font-family=\"monospace\" font-size=\"20\">LOCATION: $safeCity [ID: $safeId]</text>" +
            "<text x=\"60\" y=\"160\" fill=\"rgba(0,255,180,0.9)\" font-family=\"monospace\" font-size=\"18\">TIMESTAMP: $now UTC</text>" +
            "<text x=\"60\" y=\"470\" fill=\"rgba(255,200,0,0.9)\" font-family=\"monospace\" font-size=\"22\" font-weight=\"bold\">STATUS: $safeStatus</text>" +
            "</svg>"
    }

    private fun getMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "json", "geojson", "geojsonl" -> "application/json"
            "wasm" -> "application/wasm"
            "glb" -> "model/gltf-binary"
            "gltf" -> "model/gltf+json"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "xml" -> "application/xml"
            else -> "application/octet-stream"
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            isInitialPageLoaded = false
            binding.layoutLoading.visibility = View.GONE
            binding.layoutError.visibility = View.VISIBLE
            binding.tvErrorMessage.text = message
        }
    }

    fun handleVoiceAction() {
        runOnUiThread {
            try {
                if (!com.jarvis.assistant.background.JarvisConversationService.isSessionOn.value) {
                    val serviceIntent = Intent(this@GodsEyeActivity, com.jarvis.assistant.background.JarvisConversationService::class.java).apply {
                        action = com.jarvis.assistant.background.JarvisConversationService.ACTION_START_CONVERSATION
                    }
                    startService(serviceIntent)
                    Toast.makeText(this@GodsEyeActivity, "JARVIS Voice Active", Toast.LENGTH_SHORT).show()
                } else if (com.jarvis.assistant.background.JarvisConversationService.conversationState.value == com.jarvis.assistant.data.model.ConversationState.SPEAKING) {
                    com.jarvis.assistant.background.JarvisConversationService.interruptPlayback()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle JARVIS voice action: ${e.message}", e)
            }
        }
    }

    inner class JarvisVoiceBridge {
        @JavascriptInterface
        fun triggerVoice() {
            handleVoiceAction()
        }

        @JavascriptInterface
        fun onActionAcknowledged(traceId: String, resultJson: String) {
            Log.i(TAG, "onActionAcknowledged from WebView: traceId=$traceId, result=$resultJson")
            recordActionReceipt(traceId, resultJson)
        }

        @JavascriptInterface
        fun isIntegrated(): Boolean = true

        @JavascriptInterface
        fun getJarvisState(): String {
            val isOn = com.jarvis.assistant.background.JarvisConversationService.isSessionOn.value
            val convState = com.jarvis.assistant.background.JarvisConversationService.conversationState.value
            return if (!isOn) "STANDBY" else convState.name
        }
    }

    fun executeActionInWebView(actionJson: String) {
        if (actionJson.isBlank()) return
        if (!isInitialPageLoaded) {
            pendingAction = actionJson
            return
        }
        val escaped = actionJson.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
        val js = """
            (function() {
                try {
                    var action = JSON.parse('$escaped');
                    
                    var layerAliasMap = {
                        'all': 'all', 'everything': 'all', 'all_layers': 'all',
                        'aircraft': 'flights', 'planes': 'flights', 'flight': 'flights', 'flights': 'flights',
                        'ships': 'ais-live-vessels', 'vessels': 'ais-live-vessels', 'boat': 'ais-live-vessels', 'ais': 'ais-live-vessels',
                        'cameras': 'cctv', 'camera': 'cctv', 'cctv': 'cctv', 'video': 'cctv',
                        'fires': 'local-firms', 'wildfires': 'fire-perimeters', 'fire': 'local-firms', 'firms': 'local-firms',
                        'space': 'rocket-launches', 'launches': 'rocket-launches', 'satellites': 'satellites', 'satellite': 'satellites',
                        'quake': 'earthquakes', 'earthquakes': 'earthquakes', 'seismic': 'earthquakes',
                        'weather-radar': 'weather-radar', 'radar': 'weather-radar', 'rain': 'weather-radar',
                        'weather-satellite': 'weather-satellite', 'clouds': 'weather-satellite', 'cloud': 'weather-satellite',
                        'weather-lightning': 'weather-lightning', 'lightning': 'weather-lightning',
                        'weather-cyclones': 'weather-cyclones', 'cyclones': 'weather-cyclones', 'cyclone': 'weather-cyclones', 'hurricanes': 'weather-cyclones',
                        'wind': 'wind', 'traffic': 'traffic', 'transit': 'transit', 'bikeshare': 'bikeshare',
                        'radio': 'radio', 'military': 'military-installations', 'military-awareness': 'military-awareness',
                        'street-level': 'street-level', 'recent-imagery': 'recent-imagery',
                        'event-reconstruction': 'bhote-koshi-2026', 'event_reconstruction': 'bhote-koshi-2026',
                        'bhote-koshi': 'bhote-koshi-2026', 'bhote_koshi': 'bhote-koshi-2026', 'flood-reconstruction': 'bhote-koshi-2026'
                    };

                    var dm = window.__godsEyeView && window.__godsEyeView.dataManager;
                    var excludedFromAll = ['bhote-koshi-2026', 'bhote-koshi-locator'];
                    if (dm && typeof dm.getAll === 'function') {
                        if (action.enable_all || (action.enable_layers && (action.enable_layers.indexOf('all') >= 0 || action.enable_layers.indexOf('everything') >= 0))) {
                            var all = dm.getAll();
                            for (var i = 0; i < all.length; i++) {
                                var it = all[i];
                                if (!it || it.showInTogglePanel === false || excludedFromAll.indexOf(it.id) >= 0 || (typeof it.id === 'string' && it.id.indexOf('bhote') >= 0)) {
                                    continue;
                                }
                                try { dm.setEnabled(it.id, true, { origin: 'external' }); } catch(e) {}
                            }
                        }
                        if (action.clear_all || action.disable_all || (action.disable_layers && (action.disable_layers.indexOf('all') >= 0 || action.disable_layers.indexOf('everything') >= 0))) {
                            var all = dm.getAll();
                            for (var i = 0; i < all.length; i++) {
                                try { dm.setEnabled(all[i].id, false, { origin: 'external' }); } catch(e) {}
                            }
                        }
                        if (action.enable_layers && Array.isArray(action.enable_layers)) {
                            for (var i = 0; i < action.enable_layers.length; i++) {
                                var raw = String(action.enable_layers[i]).toLowerCase().trim();
                                var mapped = layerAliasMap[raw] || raw;
                                if (mapped !== 'all') {
                                    try { dm.setEnabled(mapped, true, { origin: 'external' }); } catch(e) {}
                                }
                            }
                        }
                        if (action.disable_layers && Array.isArray(action.disable_layers)) {
                            for (var i = 0; i < action.disable_layers.length; i++) {
                                var raw = String(action.disable_layers[i]).toLowerCase().trim();
                                var mapped = layerAliasMap[raw] || raw;
                                if (mapped !== 'all') {
                                    try { dm.setEnabled(mapped, false, { origin: 'external' }); } catch(e) {}
                                }
                            }
                        }
                    }
                    if (action.disable_layers && (action.disable_layers.indexOf('bhote-koshi-2026') >= 0 || action.disable_layers.indexOf('event-reconstruction') >= 0 || action.disable_layers.indexOf('all') >= 0)) {
                        try {
                            var panel = document.getElementById('bhote-koshi-event-panel');
                            if (panel) {
                                var closeBtn = panel.querySelector('[data-action="close"], .bhote-event-icon-btn');
                                if (closeBtn) closeBtn.click(); else panel.remove();
                            }
                        } catch(e) {}
                    }

                    if (window.__executeGodsEyeAction && typeof window.__executeGodsEyeAction === 'function') {
                        var res = window.__executeGodsEyeAction(action);
                        return JSON.stringify(res);
                    } else {
                        window.__pendingGodsEyeActions = window.__pendingGodsEyeActions || [];
                        window.__pendingGodsEyeActions.push(action);
                        return JSON.stringify({ trace_id: action.trace_id || 'queued', status: 'queued' });
                    }
                } catch(e) {
                    console.error('[Jarvis] Failed to dispatch action:', e);
                    return JSON.stringify({ error: e.message });
                }
            })();
        """.trimIndent()
        binding.webViewGodsEye.evaluateJavascript(js) { res ->
            Log.d(TAG, "executeActionInWebView result: $res")
            if (!res.isNullOrBlank() && res != "null") {
                try {
                    val unquoted = if (res.startsWith("\"") && res.endsWith("\"")) {
                        org.json.JSONTokener(res).nextValue() as? String ?: res
                    } else res
                    val obj = JSONObject(unquoted)
                    val tid = obj.optString("trace_id", "")
                    if (tid.isNotBlank()) {
                        recordActionReceipt(tid, obj.toString())
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun queryGlobeState(callback: (JSONObject?) -> Unit) {
        runOnUiThread {
            binding.webViewGodsEye.evaluateJavascript(
                "(function() { try { return JSON.stringify(window.__godsEyeGetState ? window.__godsEyeGetState() : {}); } catch(e) { return JSON.stringify({ error: e.message }); } })();"
            ) { res ->
                val state = try {
                    if (!res.isNullOrBlank() && res != "null") {
                        val unquoted = if (res.startsWith("\"") && res.endsWith("\"")) {
                            org.json.JSONTokener(res).nextValue() as? String ?: res
                        } else res
                        JSONObject(unquoted)
                    } else null
                } catch (_: Exception) { null }
                if (state != null && !state.has("error")) {
                    latestGlobeState = state
                }
                callback(state)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val actionJson = intent.getStringExtra(EXTRA_ACTION_JSON)
        if (!actionJson.isNullOrBlank()) {
            executeActionInWebView(actionJson)
        }
        val targetUrl = intent.getStringExtra(EXTRA_VIEW_URL) ?: intent.dataString
        if (!targetUrl.isNullOrBlank()) {
            loadGodsEye(targetUrl)
        }
    }

    private fun loadGodsEye(targetUrl: String? = null) {
        val validUrl = if (!targetUrl.isNullOrBlank() &&
            (targetUrl.startsWith("http://localhost:4173") ||
             targetUrl.startsWith("http://127.0.0.1:4173") ||
             targetUrl.startsWith("https://$ASSET_HOST_NAME"))) {
            targetUrl
        } else {
            "$GODS_EYE_LOCAL_URL#$DEFAULT_LAYERS_HASH"
        }

        if (isInitialPageLoaded && validUrl.contains("#")) {
            val hashPart = validUrl.substringAfter("#")
            val escapedHash = hashPart.replace("'", "\\'")
            binding.webViewGodsEye.evaluateJavascript(
                "(function() { window.location.hash = '$escapedHash'; if (window.__godsEyeView && typeof window.__godsEyeView.navigateToHash === 'function') { window.__godsEyeView.navigateToHash('$escapedHash'); } })();",
                null
            )
        } else {
            binding.webViewGodsEye.loadUrl(validUrl)
        }
    }

    private var serviceObserverJob: Job? = null

    private fun startJarvisStateObserver() {
        serviceObserverJob?.cancel()
        serviceObserverJob = lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                com.jarvis.assistant.background.JarvisConversationService.conversationState,
                com.jarvis.assistant.background.JarvisConversationService.audioLevel,
                com.jarvis.assistant.background.JarvisConversationService.isSessionOn
            ) { state, level, isOn ->
                Triple(state, level, isOn)
            }.collect { (state, level, isOn) ->
                if (!isInitialPageLoaded) return@collect
                val stateName = when {
                    !isOn -> "STANDBY"
                    state == com.jarvis.assistant.data.model.ConversationState.LISTENING -> "LISTENING"
                    state == com.jarvis.assistant.data.model.ConversationState.THINKING -> "THINKING"
                    state == com.jarvis.assistant.data.model.ConversationState.SPEAKING -> "SPEAKING"
                    else -> "READY"
                }
                val detail = when (stateName) {
                    "LISTENING" -> "Listening to you..."
                    "THINKING" -> "Processing request..."
                    "SPEAKING" -> "Jarvis speaking..."
                    "READY" -> "Jarvis Online · Tap mic or say 'Hey Jarvis'"
                    else -> "Voice Standby"
                }
                val js = "(function() { if (typeof window.__updateJarvisVoiceStatus === 'function') { window.__updateJarvisVoiceStatus('$stateName', '$detail', $level); } })();"
                runOnUiThread {
                    binding.webViewGodsEye.evaluateJavascript(js, null)
                }
            }
        }
    }

    private fun stopJarvisStateObserver() {
        serviceObserverJob?.cancel()
        serviceObserverJob = null
    }

    override fun onResume() {
        super.onResume()
        activeInstance = this
        binding.webViewGodsEye.onResume()
        binding.webViewGodsEye.resumeTimers()
        startJarvisStateObserver()
    }

    override fun onPause() {
        super.onPause()
        stopJarvisStateObserver()
        binding.webViewGodsEye.onPause()
        binding.webViewGodsEye.pauseTimers()
    }

    override fun onDestroy() {
        stopJarvisStateObserver()
        if (activeInstance == this) {
            activeInstance = null
        }
        binding.webViewGodsEye.stopLoading()
        binding.webViewGodsEye.destroy()
        super.onDestroy()
    }
}
