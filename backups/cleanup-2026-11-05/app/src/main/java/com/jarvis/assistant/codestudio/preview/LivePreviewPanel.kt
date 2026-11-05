package com.jarvis.assistant.codestudio.preview

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarvis.assistant.ui.compose.theme.ledger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

private enum class PreviewDevice(val label: String, val width: Dp?) {
    FIT("Fit", null),
    PHONE("Phone 360", 360.dp),
    TABLET("Tablet 768", 768.dp),
    DESKTOP("Desktop 1280", 1280.dp)
}

/**
 * Live preview of a Code Studio web project: a ref-counted local HTTP server ([PreviewServer])
 * on 127.0.0.1 plus a WebView. Console messages and JS errors are forwarded to [onConsoleLine]
 * as "[LEVEL] message (source:line)".
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LivePreviewPanel(
    projectDir: File,
    modifier: Modifier = Modifier,
    onConsoleLine: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val ledger = MaterialTheme.ledger
    val consoleSink by rememberUpdatedState(onConsoleLine)

    var port by remember { mutableIntStateOf(-1) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var device by remember { mutableStateOf(PreviewDevice.FIT) }
    var autoReload by remember { mutableStateOf(true) }

    // Ref-counted server lifetime tied to this panel + project.
    LaunchedEffect(projectDir.absolutePath) {
        var acquired = false
        try {
            val p = withContext(Dispatchers.IO) { PreviewServer.acquire(projectDir).also { acquired = true } }
            port = p
            serverError = null
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            serverError = e.message ?: e.javaClass.simpleName
        } finally {
            port = -1
            if (acquired) Thread { PreviewServer.release() }.start()
        }
    }

    val baseUrl = if (port > 0) "http://127.0.0.1:$port/" else null

    // Load / reload when the server becomes ready (or switches port/project).
    LaunchedEffect(baseUrl, webView, projectDir.absolutePath) {
        val wv = webView ?: return@LaunchedEffect
        if (baseUrl != null) wv.loadUrl(baseUrl)
    }

    // Auto-reload: poll the project tree for changes (cheap; capped walk).
    LaunchedEffect(baseUrl, autoReload, projectDir.absolutePath) {
        if (baseUrl == null || !autoReload) return@LaunchedEffect
        var last = withContext(Dispatchers.IO) { fingerprint(projectDir) }
        while (isActive) {
            delay(1200)
            val now = withContext(Dispatchers.IO) { fingerprint(projectDir) }
            if (now != last) {
                last = now
                webView?.reload()
            }
        }
    }

    Surface(modifier = modifier, color = ledger.deskNight, contentColor = ledger.deskPaper) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(ledger.deskWalnut).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { webView?.let { if (it.canGoBack()) it.goBack() } }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ledger.deskPaperDim)
                }
                IconButton(onClick = { webView?.reload() ?: Unit }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload", tint = ledger.deskPaperDim)
                }
                Text(
                    text = currentUrl.ifBlank { baseUrl ?: "starting server…" },
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = ledger.deskInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { autoReload = !autoReload }) {
                    Icon(
                        if (autoReload) Icons.Filled.Autorenew else Icons.Filled.SyncDisabled,
                        contentDescription = if (autoReload) "Auto-reload on" else "Auto-reload off",
                        tint = if (autoReload) ledger.lampAmber else ledger.deskInk
                    )
                }
                IconButton(onClick = {
                    device = PreviewDevice.entries[(device.ordinal + 1) % PreviewDevice.entries.size]
                    Toast.makeText(context, device.label, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        when (device) {
                            PreviewDevice.FIT -> Icons.Filled.Fullscreen
                            PreviewDevice.PHONE -> Icons.Filled.PhoneAndroid
                            PreviewDevice.TABLET -> Icons.Filled.TabletAndroid
                            PreviewDevice.DESKTOP -> Icons.Filled.Computer
                        },
                        contentDescription = "Device size: ${device.label}",
                        tint = ledger.deskPaperDim
                    )
                }
                IconButton(onClick = {
                    val url = currentUrl.ifBlank { baseUrl ?: return@IconButton }
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in browser", tint = ledger.deskPaperDim)
                }
            }
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = ledger.lampAmber,
                    trackColor = ledger.deskHairline
                )
            } else {
                HorizontalDivider(color = ledger.deskHairline)
            }

            Box(Modifier.weight(1f).fillMaxWidth().background(ledger.deskNightRaised), contentAlignment = Alignment.TopCenter) {
                when {
                    serverError != null -> Text(
                        "Preview server failed: $serverError",
                        Modifier.padding(16.dp), color = ledger.emberRed
                    )
                    baseUrl == null -> CircularProgressIndicator(Modifier.padding(24.dp).size(28.dp), color = ledger.lampAmber)
                }
                val frameModifier = if (device.width == null) Modifier.fillMaxSize()
                else Modifier.fillMaxHeight().horizontalScroll(rememberScrollState())
                Box(frameModifier, contentAlignment = Alignment.TopCenter) {
                    val inner = device.width?.let { Modifier.requiredWidth(it).fillMaxHeight() } ?: Modifier.fillMaxSize()
                    AndroidView(
                        modifier = inner,
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                setBackgroundColor(android.graphics.Color.WHITE)
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                                        val src = msg.sourceId()?.substringAfterLast('/')?.ifBlank { null }
                                        val where = if (src != null) " ($src:${msg.lineNumber()})" else ""
                                        consoleSink("[${msg.messageLevel().name}] ${msg.message()}$where")
                                        return true
                                    }

                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        val uri = request.url
                                        val local = uri.host == "127.0.0.1" || uri.host == "localhost"
                                        if (local || uri.scheme == "about" || uri.scheme == "data" || uri.scheme == "blob") return false
                                        return try {
                                            view.context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                            true
                                        } catch (_: Exception) { false }
                                    }

                                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                        currentUrl = url
                                        // Surface uncaught errors & promise rejections with stack info.
                                        view.evaluateJavascript(ERROR_HOOK_JS, null)
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        currentUrl = url
                                        progress = 100
                                        view.evaluateJavascript(ERROR_HOOK_JS, null)
                                    }

                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                        consoleSink("[NETWORK] ${error.description} — ${request.url}")
                                    }

                                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                                        consoleSink("[HTTP ${errorResponse.statusCode}] ${request.url.path ?: request.url}")
                                    }
                                }
                                webView = this
                            }
                        },
                        onRelease = { wv ->
                            if (webView === wv) webView = null
                            wv.stopLoading()
                            wv.destroy()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Installs window.onerror / unhandledrejection hooks (once per page) that log through console.error,
 * which WebChromeClient.onConsoleMessage then forwards. Uncaught errors are also reported by
 * Chromium itself as "Uncaught ..." console errors, so this mostly adds stack + promise rejections.
 */
private const val ERROR_HOOK_JS = """
(function(){
  if (window.__jcsHooked) return; window.__jcsHooked = true;
  window.addEventListener('error', function(e){
    if (e && e.target && e.target !== window && (e.target.src || e.target.href)) {
      console.error('Failed to load resource: ' + (e.target.src || e.target.href));
    }
  }, true);
  window.addEventListener('unhandledrejection', function(e){
    var r = e && e.reason; console.error('Unhandled promise rejection: ' + (r && (r.stack || r.message) || r));
  });
})();
"""

/** Cheap change fingerprint: count + max mtime + total size of up to 2000 files. */
private fun fingerprint(dir: File): Long {
    var count = 0L
    var maxM = 0L
    var size = 0L
    dir.walkTopDown()
        .onEnter { it.name != "node_modules" && it.name != ".git" }
        .take(2000)
        .forEach { f ->
            if (f.isFile) {
                count++
                size += f.length()
                if (f.lastModified() > maxM) maxM = f.lastModified()
            }
        }
    return maxM * 31 + size * 17 + count
}
