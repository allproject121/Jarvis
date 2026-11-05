package com.jarvis.assistant.ui.spoonknife

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment

/**
 * Optional DialogFragment rendering the Spoon-Knife static HTML/CSS frontend.
 * Can be presented on top of any FragmentManager and dismissed safely.
 */
class SpoonKnifeDemoFragment : DialogFragment() {

    companion object {
        const val TAG = "SpoonKnifeDemoFragment"
        fun newInstance(): SpoonKnifeDemoFragment = SpoonKnifeDemoFragment()
    }

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Material_NoActionBar_Fullscreen)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val density = resources.displayMetrics.density

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF121212.toInt())
        }

        // Header Toolbar
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * density).toInt()
            )
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1E1E1E.toInt())
        }

        val title = TextView(requireContext()).apply {
            text = "Spoon-Knife Demo Screen"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val closeBtn = Button(requireContext()).apply {
            text = "Close"
            setTextColor(0xFF00E5FF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { dismiss() }
        }

        header.addView(title)
        header.addView(closeBtn)
        root.addView(header)

        // Web Container
        val webContainer = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        webView = WebView(requireContext()).apply {
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
        return root
    }

    fun getLoadedUrl(): String? = webView?.url

    override fun onDestroyView() {
        webView?.destroy()
        webView = null
        super.onDestroyView()
    }
}
