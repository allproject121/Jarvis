package com.jarvis.assistant.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.ActivityApiConnectionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ApiConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApiConnectionBinding
    private val preferences by lazy { JarvisApp.instance.preferences }
    private val testHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityApiConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        setupUI()
        loadCurrentPreferences()
        setupListeners()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadCurrentPreferences() {
        binding.etApiKey.setText(preferences.apiKey)
        binding.etCustomEndpoint.setText(preferences.customEndpoint)
        binding.etLocalHostModel.setText(preferences.localHostModel)

        if (!preferences.canStoreApiKeySecurely) {
            binding.tvLiveStatus.text = "SECURE STORAGE UNAVAILABLE • API KEY DISABLED"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
        }

        when (preferences.aiModel) {
            GeminiConstants.MODEL_3_8_LIVE -> binding.rbModelFlash20.isChecked = true
            GeminiConstants.MODEL_3_1_FLASH_AGENT -> binding.rbModelFlash15.isChecked = true
            else -> binding.rbModelFlashThinking.isChecked = true
        }

        if (!preferences.canStoreApiKeySecurely) {
            // Keep the secure-storage warning set above.
        } else if (preferences.apiKey.isNotBlank()) {
            binding.tvLiveStatus.text = "KEY CONFIGURED • READY"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
        } else {
            binding.tvLiveStatus.text = "NO API KEY • ENTER KEY BELOW"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EAB308"))
        }

        if (preferences.customEndpoint.isNotBlank()) {
            binding.tvEndpointSummary.text = "Active Endpoint: ${preferences.customEndpoint}"
        }

        // Load God's Eye 3D Sensors & Feeds Preferences
        binding.etGodsEyeGoogleKey.setText(preferences.godsEyeGoogleApiKey)
        binding.etGodsEyeCesiumToken.setText(preferences.godsEyeCesiumIonToken)
        binding.etGodsEyeFirmsKey.setText(preferences.godsEyeNasaFirmsKey)
        binding.etGodsEyeAisStreamKey.setText(preferences.godsEyeAisStreamKey)
        binding.etGodsEyeTomTomKey.setText(preferences.godsEyeTomTomKey)
        binding.etGodsEyeMapillaryToken.setText(preferences.godsEyeMapillaryToken)
        binding.etGodsEyeOpenSkyClientId.setText(preferences.godsEyeOpenSkyClientId)
        binding.etGodsEyeOpenSkyClientSecret.setText(preferences.godsEyeOpenSkyClientSecret)

        binding.etHomeAssistantUrl.setText(preferences.homeAssistantUrl)
        binding.etHomeAssistantToken.setText(preferences.homeAssistantToken)
        binding.etSmartHomeWebhookUrl.setText(preferences.smartHomeWebhookUrl)
    }

    private fun setupListeners() {
        binding.btnPasteKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = clipboard.primaryClip?.getItemAt(0)
            val pasted = item?.text?.toString()?.trim() ?: ""
            if (pasted.isNotBlank()) {
                binding.etApiKey.setText(pasted)
                Toast.makeText(this, "API Key pasted from clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearKey.setOnClickListener {
            binding.etApiKey.setText("")
        }

        binding.btnTestConnection.setOnClickListener {
            val enteredKey = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (enteredKey.isBlank()) {
                Snackbar.make(binding.root, "Please enter an API key first", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            testConnection(enteredKey)
        }

        // God's Eye Registration Portal Links
        binding.btnGetGoogleKey.setOnClickListener {
            openWebUrl("https://console.cloud.google.com/google/maps-apis/credentials")
        }
        binding.btnGetCesiumToken.setOnClickListener {
            openWebUrl("https://ion.cesium.com/tokens")
        }
        binding.btnGetFirmsKey.setOnClickListener {
            openWebUrl("https://firms.modaps.eosdis.nasa.gov/api/map_key/")
        }
        binding.btnGetAisStreamKey.setOnClickListener {
            openWebUrl("https://aisstream.io/")
        }
        binding.btnGetTomTomKey.setOnClickListener {
            openWebUrl("https://developer.tomtom.com/")
        }
        binding.btnGetMapillaryToken.setOnClickListener {
            openWebUrl("https://www.mapillary.com/dashboard/developers")
        }
        binding.btnGetOpenSkyKey.setOnClickListener {
            openWebUrl("https://opensky-network.org/")
        }

        binding.btnFillWifi.setOnClickListener {
            binding.etCustomEndpoint.setText("http://192.168.0.101:11434")
            Toast.makeText(this, "Set to Home Wi-Fi PC (192.168.0.101)", Toast.LENGTH_SHORT).show()
        }

        binding.btnFillAdb.setOnClickListener {
            binding.etCustomEndpoint.setText("http://127.0.0.1:11434")
            Toast.makeText(this, "Set to USB ADB Reverse (127.0.0.1)", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestLocalHost.setOnClickListener {
            val endpoint = binding.etCustomEndpoint.text?.toString()?.trim() ?: "http://192.168.0.101:11434"
            testLocalHost(endpoint)
        }

        binding.btnTestSmartHomeApi.setOnClickListener {
            val haUrl = binding.etHomeAssistantUrl.text?.toString()?.trim() ?: ""
            val token = binding.etHomeAssistantToken.text?.toString()?.trim() ?: ""
            val webhook = binding.etSmartHomeWebhookUrl.text?.toString()?.trim() ?: ""
            preferences.homeAssistantUrl = haUrl
            preferences.homeAssistantToken = token
            preferences.smartHomeWebhookUrl = webhook

            binding.tvSmartHomeApiStatus.text = "Testing connection..."
            binding.btnTestSmartHomeApi.isEnabled = false

            lifecycleScope.launch {
                val app = application as? JarvisApp
                val smartHome = app?.smartHomeManager ?: com.jarvis.assistant.automation.smarthome.SmartHomeManager(this@ApiConnectionActivity)
                val (_, msg) = smartHome.testConnection()
                withContext(Dispatchers.Main) {
                    binding.btnTestSmartHomeApi.isEnabled = true
                    binding.tvSmartHomeApiStatus.text = msg
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                }
            }
        }

        binding.btnSaveConnection.setOnClickListener {
            saveAndApply()
        }
    }

    private fun testLocalHost(endpoint: String) {
        binding.btnTestLocalHost.isEnabled = false
        binding.tvLocalHostStatus.text = "Testing connection to $endpoint..."
        binding.tvLocalHostStatus.setTextColor(Color.parseColor("#38BDF8"))

        lifecycleScope.launch {
            val client = com.jarvis.assistant.network.LocalHostLlmClient()
            val info = client.pingServer(endpoint)
            withContext(Dispatchers.Main) {
                binding.btnTestLocalHost.isEnabled = true
                if (info.isOnline) {
                    val modelsStr = if (info.availableModels.isNotEmpty()) info.availableModels.joinToString(", ") else "None"
                    binding.tvLocalHostStatus.text = "✅ CONNECTED (${info.pingMs}ms)\nActive: ${info.activeModel}\nAvailable: $modelsStr"
                    binding.tvLocalHostStatus.setTextColor(Color.parseColor("#22C55E"))
                    if (binding.etLocalHostModel.text.isNullOrBlank() && info.activeModel.isNotBlank()) {
                        binding.etLocalHostModel.setText(info.activeModel)
                    }
                    Snackbar.make(binding.root, "Local Host AI is Online! Found ${info.availableModels.size} model(s).", Snackbar.LENGTH_LONG).show()
                } else {
                    binding.tvLocalHostStatus.text = "❌ CONNECTION FAILED (${info.pingMs}ms)\n${info.error ?: "Check PC Wi-Fi or firewall"}"
                    binding.tvLocalHostStatus.setTextColor(Color.parseColor("#EF4444"))
                    Snackbar.make(binding.root, "Could not reach Ollama at $endpoint.", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun testConnection(apiKey: String) {
        binding.btnTestConnection.isEnabled = false
        binding.tvPingResult.text = "Testing ping..."
        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3B82F6"))

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                    val request = Request.Builder().url(url).build()
                    testHttpClient.newCall(request).execute().use { response ->
                        val duration = System.currentTimeMillis() - startTime
                        if (response.isSuccessful) {
                            Pair(true, duration)
                        } else {
                            Pair(false, response.code)
                        }
                    }
                } catch (e: Exception) {
                    Pair(false, -1)
                }
            }

            binding.btnTestConnection.isEnabled = true
            if (result.first) {
                val ping = result.second
                binding.tvPingResult.text = "Ping: ${ping}ms • Valid"
                binding.tvLiveStatus.text = "CONNECTED • HANDSHAKE OK"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
                Snackbar.make(binding.root, "Connection verified! Ping: ${ping}ms", Snackbar.LENGTH_LONG).show()
            } else {
                val code = result.second
                binding.tvPingResult.text = if (code == -1) "Network Timeout" else "HTTP $code Error"
                binding.tvLiveStatus.text = "FAILED • INVALID KEY OR NETWORK"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                Snackbar.make(binding.root, "Connection failed. Please verify API key.", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun saveAndApply() {
        val key = binding.etApiKey.text?.toString()?.trim() ?: ""
        val customEp = binding.etCustomEndpoint.text?.toString()?.trim() ?: ""
        val localModel = binding.etLocalHostModel.text?.toString()?.trim() ?: ""

        val selectedModel = when (binding.rgModelGroup.checkedRadioButtonId) {
            binding.rbModelFlash20.id -> GeminiConstants.MODEL_3_8_LIVE
            binding.rbModelFlash15.id -> GeminiConstants.MODEL_3_1_FLASH_AGENT
            else -> GeminiConstants.MODEL_3_1_FLASH
        }

        try {
            preferences.apiKey = key
        } catch (e: IllegalStateException) {
            Snackbar.make(binding.root, e.message ?: "Secure key storage is unavailable.", Snackbar.LENGTH_LONG).show()
            return
        }
        preferences.aiModel = selectedModel
        preferences.customEndpoint = customEp
        preferences.localHostModel = localModel

        // Save God's Eye 3D Sensors & Feeds Preferences
        preferences.godsEyeGoogleApiKey = binding.etGodsEyeGoogleKey.text?.toString()?.trim() ?: ""
        preferences.godsEyeCesiumIonToken = binding.etGodsEyeCesiumToken.text?.toString()?.trim() ?: ""
        preferences.godsEyeNasaFirmsKey = binding.etGodsEyeFirmsKey.text?.toString()?.trim() ?: ""
        preferences.godsEyeAisStreamKey = binding.etGodsEyeAisStreamKey.text?.toString()?.trim() ?: ""
        preferences.godsEyeTomTomKey = binding.etGodsEyeTomTomKey.text?.toString()?.trim() ?: ""
        preferences.godsEyeMapillaryToken = binding.etGodsEyeMapillaryToken.text?.toString()?.trim() ?: ""
        preferences.godsEyeOpenSkyClientId = binding.etGodsEyeOpenSkyClientId.text?.toString()?.trim() ?: ""
        preferences.godsEyeOpenSkyClientSecret = binding.etGodsEyeOpenSkyClientSecret.text?.toString()?.trim() ?: ""

        preferences.homeAssistantUrl = binding.etHomeAssistantUrl.text?.toString()?.trim() ?: ""
        preferences.homeAssistantToken = binding.etHomeAssistantToken.text?.toString()?.trim() ?: ""
        preferences.smartHomeWebhookUrl = binding.etSmartHomeWebhookUrl.text?.toString()?.trim() ?: ""

        com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
        com.jarvis.assistant.background.JarvisConversationService.restartSession()

        Toast.makeText(this, "API & Connection settings saved!", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun openWebUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
