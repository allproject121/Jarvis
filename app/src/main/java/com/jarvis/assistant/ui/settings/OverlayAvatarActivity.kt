package com.jarvis.assistant.ui.settings

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.agent.AgentTaskEngine
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.ui.agent.TaskProgressOverlay
import com.jarvis.assistant.util.CustomAvatarHelper
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.ThemeManager

class OverlayAvatarActivity : AppCompatActivity() {
    private lateinit var avatarGallery: WebView
    private lateinit var overlaySwitch: SwitchCompat
    private lateinit var modeGroup: RadioGroup
    private lateinit var edgeSpinner: Spinner
    private val preferences get() = JarvisApp.instance.preferences
    private lateinit var assetLoader: WebViewAssetLoader
    private var edgeSelectionReady = false

    // Custom 3D Avatar Views
    private lateinit var ivCustomAvatarPreview: ImageView
    private lateinit var tvCustomAvatarPlaceholder: TextView
    private lateinit var btnUploadCustomAvatar: TextView
    private lateinit var btnActivateCustomAvatar: TextView
    private lateinit var btnDeleteCustomAvatar: ImageButton

    private val pickCustomAvatarLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            handleCustomAvatarPicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay_avatar)
        ThemeManager.applyDynamicBackground(findViewById(R.id.overlayAvatarRoot))

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        overlaySwitch = findViewById(R.id.switchOverlayEnabled)
        modeGroup = findViewById(R.id.radioOverlayMode)
        edgeSpinner = findViewById(R.id.spinnerOverlayEdge)

        val hasOverlayPermission = PermissionHelper.hasOverlayPermission(this)
        val isEnabled = preferences.isTaskOverlayEnabled && hasOverlayPermission
        overlaySwitch.isChecked = isEnabled
        updateControlsState(isEnabled)

        modeGroup.check(if (preferences.taskOverlayMode == "working") R.id.rbOverlayWorking else R.id.rbOverlayAlways)
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            preferences.taskOverlayMode = if (checkedId == R.id.rbOverlayWorking) "working" else "always"
            if (preferences.isTaskOverlayEnabled && PermissionHelper.hasOverlayPermission(this)) {
                TaskProgressOverlay.showOverlay(this, forceShow = true)
            }
        }

        val edgeValues = listOf("right", "left", "top", "bottom")
        edgeSpinner.setSelection(edgeValues.indexOf(preferences.taskOverlayEdge).coerceAtLeast(0))
        edgeSpinner.post { edgeSelectionReady = true }

        edgeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (edgeSelectionReady) edgeValues.getOrNull(position)?.let { edge ->
                    preferences.taskOverlayFreePosition = false
                    preferences.taskOverlayEdge = edge
                    if (preferences.isTaskOverlayEnabled && PermissionHelper.hasOverlayPermission(this@OverlayAvatarActivity)) {
                        TaskProgressOverlay.getInstance(this@OverlayAvatarActivity).applyEdge(edge)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        overlaySwitch.setOnCheckedChangeListener { _, enabled ->
            if (enabled) {
                if (!PermissionHelper.hasOverlayPermission(this)) {
                    overlaySwitch.isChecked = false
                    updateControlsState(false)
                    Toast.makeText(this, "Please allow 'Display over other apps' to show the floating avatar", Toast.LENGTH_LONG).show()
                    PermissionHelper.requestOverlayPermission(this)
                    return@setOnCheckedChangeListener
                }
                preferences.isTaskOverlayEnabled = true
                updateControlsState(true)
                TaskProgressOverlay.showOverlay(this, forceShow = true)
            } else {
                preferences.isTaskOverlayEnabled = false
                updateControlsState(false)
                TaskProgressOverlay.hideOverlay()
            }
        }

        setupCustomAvatarCard()

        avatarGallery = findViewById(R.id.avatarGallery)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        setupGallery()
    }

    private fun setupCustomAvatarCard() {
        ivCustomAvatarPreview = findViewById(R.id.ivCustomAvatarPreview)
        tvCustomAvatarPlaceholder = findViewById(R.id.tvCustomAvatarPlaceholder)
        btnUploadCustomAvatar = findViewById(R.id.btnUploadCustomAvatar)
        btnActivateCustomAvatar = findViewById(R.id.btnActivateCustomAvatar)
        btnDeleteCustomAvatar = findViewById(R.id.btnDeleteCustomAvatar)

        btnUploadCustomAvatar.setOnClickListener {
            pickCustomAvatarLauncher.launch("*/*")
        }

        btnActivateCustomAvatar.setOnClickListener {
            if (CustomAvatarHelper.hasCustomAvatar(this)) {
                preferences.taskOverlayAvatar = "custom_3d"
                updateCustomAvatarUI()
                selectInGallery("custom_3d")
                if (preferences.isTaskOverlayEnabled && PermissionHelper.hasOverlayPermission(this)) {
                    TaskProgressOverlay.getInstance(this).forceReloadAvatar("custom_3d")
                }
                Toast.makeText(this, "Custom 3D Avatar activated on floating overlay!", Toast.LENGTH_SHORT).show()
            }
        }

        btnDeleteCustomAvatar.setOnClickListener {
            CustomAvatarHelper.deleteCustomAvatar(this)
            updateCustomAvatarUI()
            selectInGallery(preferences.taskOverlayAvatar)
            if (preferences.isTaskOverlayEnabled && PermissionHelper.hasOverlayPermission(this)) {
                TaskProgressOverlay.getInstance(this).forceReloadAvatar(preferences.taskOverlayAvatar)
            }
            Toast.makeText(this, "Custom 3D Avatar removed", Toast.LENGTH_SHORT).show()
        }

        updateCustomAvatarUI()
    }

    private fun updateCustomAvatarUI() {
        val hasCustom = CustomAvatarHelper.hasCustomAvatar(this)
        if (hasCustom) {
            val bmp = CustomAvatarHelper.getAvatarBitmap(this)
            if (bmp != null) {
                ivCustomAvatarPreview.setImageBitmap(bmp)
                ivCustomAvatarPreview.visibility = View.VISIBLE
                tvCustomAvatarPlaceholder.visibility = View.GONE
            } else {
                ivCustomAvatarPreview.visibility = View.GONE
                tvCustomAvatarPlaceholder.visibility = View.VISIBLE
            }
            btnUploadCustomAvatar.text = "🔄 Change Photo"
            btnDeleteCustomAvatar.visibility = View.VISIBLE
            btnActivateCustomAvatar.visibility = View.VISIBLE

            val isActive = preferences.taskOverlayAvatar == "custom_3d"
            if (isActive) {
                btnActivateCustomAvatar.text = "✓ Active"
                btnActivateCustomAvatar.setBackgroundResource(R.drawable.bg_jarvis_chip)
                btnActivateCustomAvatar.setTextColor(android.graphics.Color.parseColor("#38bdf8"))
            } else {
                btnActivateCustomAvatar.text = "Use As Avatar"
                btnActivateCustomAvatar.setBackgroundResource(R.drawable.bg_jarvis_row_item)
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(R.attr.themeTextPrimary, typedValue, true)
                btnActivateCustomAvatar.setTextColor(typedValue.data)
            }
        } else {
            ivCustomAvatarPreview.setImageBitmap(null)
            ivCustomAvatarPreview.visibility = View.GONE
            tvCustomAvatarPlaceholder.visibility = View.VISIBLE
            btnUploadCustomAvatar.text = "📸 Upload Image"
            btnDeleteCustomAvatar.visibility = View.GONE
            btnActivateCustomAvatar.visibility = View.GONE
        }
    }

    private fun handleCustomAvatarPicked(uri: Uri) {
        val success = CustomAvatarHelper.processAndSaveImageUri(this, uri)
        if (success) {
            preferences.taskOverlayAvatar = "custom_3d"
            updateCustomAvatarUI()
            selectInGallery("custom_3d")
            if (preferences.isTaskOverlayEnabled && PermissionHelper.hasOverlayPermission(this)) {
                TaskProgressOverlay.getInstance(this).forceReloadAvatar("custom_3d")
            }
            Toast.makeText(this, "✨ Photo converted to 3D Avatar & activated!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Unable to load image. Please choose another file.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val hasOverlayPermission = PermissionHelper.hasOverlayPermission(this)
        if (preferences.isTaskOverlayEnabled && hasOverlayPermission) {
            overlaySwitch.isChecked = true
            updateControlsState(true)
            TaskProgressOverlay.showOverlay(this, forceShow = true)
        } else if (preferences.isTaskOverlayEnabled && !hasOverlayPermission) {
            overlaySwitch.isChecked = false
            updateControlsState(false)
            TaskProgressOverlay.hideOverlay()
        }
        updateCustomAvatarUI()
    }

    override fun onStop() {
        super.onStop()
        if (preferences.isTaskOverlayEnabled && PermissionHelper.hasOverlayPermission(this)) {
            if (preferences.taskOverlayMode == "working" && AgentTaskEngine.current() == null) {
                val appForeground = JarvisApp.isAppForeground.value
                val sessionOn = JarvisConversationService.isSessionOn.value
                val standbyOn = JarvisConversationService.isStandby.value
                if (appForeground || (!sessionOn && !standbyOn)) {
                    TaskProgressOverlay.hideOverlay()
                }
            }
        }
    }

    private fun updateControlsState(enabled: Boolean) {
        modeGroup.isEnabled = enabled
        edgeSpinner.isEnabled = enabled
        for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = enabled
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupGallery() {
        avatarGallery.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        avatarGallery.settings.javaScriptEnabled = true
        avatarGallery.settings.domStorageEnabled = true
        avatarGallery.settings.allowFileAccess = false
        avatarGallery.addJavascriptInterface(AvatarPickerBridge(), "JarvisAvatarPicker")
        avatarGallery.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                selectInGallery(preferences.taskOverlayAvatar)
                avatarGallery.evaluateJavascript(
                    "window.setGalleryTheme && window.setGalleryTheme('#${themeColor(R.attr.themeTextPrimary)}', '#${themeColor(R.attr.themeAccent)}')",
                    null
                )
            }
        }
        avatarGallery.loadUrl("https://appassets.androidplatform.net/assets/overlay-toys/gallery.html")
    }

    private fun selectInGallery(name: String) {
        val hasCustom = CustomAvatarHelper.hasCustomAvatar(this)
        val base64 = if (hasCustom) CustomAvatarHelper.getBase64(this) ?: "" else ""
        avatarGallery.evaluateJavascript("window.setCustomAvatarAvailable && window.setCustomAvatarAvailable($hasCustom, '$base64')", null)
        avatarGallery.evaluateJavascript("window.setSelectedAvatar && window.setSelectedAvatar('$name')", null)
    }

    private fun themeColor(attribute: Int): String {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return String.format(java.util.Locale.US, "%06X", 0xFFFFFF and value.data)
    }

    private inner class AvatarPickerBridge {
        @JavascriptInterface
        fun pageReady() {
            runOnUiThread { selectInGallery(preferences.taskOverlayAvatar) }
        }

        @JavascriptInterface
        fun selectAvatar(name: String) {
            runOnUiThread {
                preferences.taskOverlayAvatar = name
                updateCustomAvatarUI()
                selectInGallery(preferences.taskOverlayAvatar)
                if (preferences.isTaskOverlayEnabled && PermissionHelper.hasOverlayPermission(this@OverlayAvatarActivity)) {
                    TaskProgressOverlay.getInstance(this@OverlayAvatarActivity).forceReloadAvatar(name)
                }
            }
        }
    }

    override fun onDestroy() {
        if (::avatarGallery.isInitialized) {
            avatarGallery.removeJavascriptInterface("JarvisAvatarPicker")
            avatarGallery.stopLoading()
            avatarGallery.destroy()
        }
        super.onDestroy()
    }
}
