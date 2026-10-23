package com.jarvis.assistant.ui.spoonknife

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Standalone activity hosting the Spoon-Knife static HTML/CSS frontend.
 * Provides an optional demo surface without interfering with Jarvis's primary UI.
 */
class SpoonKnifeDemoActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var instance: SpoonKnifeDemoActivity? = null
            private set
    }

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        val density = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF121212.toInt())
        }

        // Header Toolbar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * density).toInt()
            )
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1E1E1E.toInt())
        }

        val title = TextView(this).apply {
            text = "Spoon-Knife Demo Screen"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val closeBtn = Button(this).apply {
            text = "Close"
            setTextColor(0xFF00E5FF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { finish() }
        }

        header.addView(title)
        header.addView(closeBtn)
        root.addView(header)

        // Web Container
        val webContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            loadUrl("file:///android_asset/spoonknife/index.html")
        }

        webContainer.addView(webView)
        root.addView(webContainer)

        setContentView(root)
    }

    fun getLoadedUrl(): String? = webView?.url

    override fun onDestroy() {
        if (instance == this) instance = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
