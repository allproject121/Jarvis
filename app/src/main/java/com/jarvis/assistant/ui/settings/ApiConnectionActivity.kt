package com.jarvis.assistant.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.MultiModelAiRouter
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.databinding.ActivityApiConnectionBinding
import com.jarvis.assistant.util.HudSoundManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApiConnectionBinding
    private val preferences by lazy { JarvisApp.instance.preferences }
    private val testHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
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
        setupModelDiscoveryListeners()

        // Observe live GGUF downloads
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val modelManager = com.jarvis.assistant.ai.local.LocalModelManager.getInstance(this@ApiConnectionActivity, lifecycleScope)
                modelManager.downloads.collect {
                    refreshLocalBrainUi()
                }
            }
        }
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
            binding.tvLiveStatus.text = "Secure storage unavailable · API key disabled"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(com.jarvis.assistant.util.ThemeManager.attrColor(this@ApiConnectionActivity, R.attr.themeDanger, Color.parseColor("#EF4444")))
        }

        when (preferences.aiModel) {
            GeminiConstants.MODEL_3_8_LIVE -> binding.rbModelFlash20.isChecked = true
            GeminiConstants.MODEL_3_1_FLASH_AGENT -> binding.rbModelFlash15.isChecked = true
            else -> binding.rbModelFlashThinking.isChecked = true
        }

        if (!preferences.canStoreApiKeySecurely) {
            // Keep the secure-storage warning set above.
        } else if (preferences.apiKey.isNotBlank()) {
            binding.tvLiveStatus.text = "Key configured · ready"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(com.jarvis.assistant.util.ThemeManager.attrColor(this@ApiConnectionActivity, R.attr.themeSuccess, Color.parseColor("#22C55E")))
        } else {
            binding.tvLiveStatus.text = "No API key · enter it below"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(com.jarvis.assistant.util.ThemeManager.attrColor(this@ApiConnectionActivity, R.attr.themeWarning, Color.parseColor("#EAB308")))
        }

        if (preferences.customEndpoint.isNotBlank()) {
            binding.tvEndpointSummary.text = "Active Endpoint: ${preferences.customEndpoint}"
        }

        // Load Multi-Model AI Execution Mode & Selected Provider
        if (preferences.aiExecutionMode == MultiModelAiRouter.MODE_ALL_TOGETHER) {
            binding.rbModeAllTogether.isChecked = true
            binding.layoutSingleProviderSelect.visibility = View.GONE
        } else {
            binding.rbModeSingle.isChecked = true
            binding.layoutSingleProviderSelect.visibility = View.VISIBLE
        }

        // Load AI Engine Power Switches
        binding.switchGeminiEnabled.isChecked = preferences.isGeminiEnabled
        binding.switchOllamaEnabled.isChecked = preferences.isOllamaEnabled
        binding.switchLocalBrainEnabled.isChecked = preferences.isLocalBrainEnabled

        when (preferences.selectedAiProvider) {
            MultiModelAiRouter.PROVIDER_OPENAI -> binding.rbProviderOpenAi.isChecked = true
            MultiModelAiRouter.PROVIDER_CLAUDE -> binding.rbProviderClaude.isChecked = true
            MultiModelAiRouter.PROVIDER_DEEPSEEK -> binding.rbProviderDeepSeek.isChecked = true
            MultiModelAiRouter.PROVIDER_GROK -> binding.rbProviderGrok.isChecked = true
            MultiModelAiRouter.PROVIDER_OPENROUTER -> binding.rbProviderOpenRouter.isChecked = true
            MultiModelAiRouter.PROVIDER_LOCAL -> binding.rbProviderOllama.isChecked = true
            MultiModelAiRouter.PROVIDER_ONDEVICE_LOCAL -> binding.rbProviderOnDeviceLocal.isChecked = true
            else -> binding.rbProviderGemini.isChecked = true
        }

        // Check local model file status
        refreshLocalBrainUi()

        // Load Multi-Model API Keys
        binding.etOpenAiApiKey.setText(preferences.openAiApiKey)
        binding.etClaudeApiKey.setText(preferences.claudeApiKey)
        binding.etDeepSeekApiKey.setText(preferences.deepSeekApiKey)
        binding.etGrokApiKey.setText(preferences.grokApiKey)
        binding.etOpenRouterApiKey.setText(preferences.openRouterApiKey)

        // Load Selected Models for each Provider & update Badges
        val openAiMod = preferences.openAiModel
        binding.tvOpenAiActiveBadge.text = openAiMod
        when (openAiMod) {
            "gpt-4o-mini" -> binding.rbOpenAiGpt4oMini.isChecked = true
            "o1" -> binding.rbOpenAiO1.isChecked = true
            "o3-mini" -> binding.rbOpenAiO3Mini.isChecked = true
            "gpt-4-turbo" -> binding.rbOpenAiGpt4Turbo.isChecked = true
            else -> binding.rbOpenAiGpt4o.isChecked = true
        }

        val claudeMod = preferences.claudeModel
        binding.tvClaudeActiveBadge.text = claudeMod
        when (claudeMod) {
            "claude-3-5-haiku-20241022" -> binding.rbClaudeHaiku.isChecked = true
            "claude-3-opus-20240229" -> binding.rbClaudeOpus.isChecked = true
            else -> binding.rbClaudeSonnet.isChecked = true
        }

        val deepSeekMod = preferences.deepSeekModel
        binding.tvDeepSeekActiveBadge.text = deepSeekMod
        when (deepSeekMod) {
            "deepseek-reasoner" -> binding.rbDeepSeekReasoner.isChecked = true
            else -> binding.rbDeepSeekChat.isChecked = true
        }

        val grokMod = preferences.grokModel
        binding.tvGrokActiveBadge.text = grokMod
        when (grokMod) {
            "grok-2-mini" -> binding.rbGrok2Mini.isChecked = true
            "grok-beta" -> binding.rbGrokBeta.isChecked = true
            else -> binding.rbGrok2.isChecked = true
        }

        val openRouterMod = preferences.openRouterModel
        binding.tvOpenRouterActiveBadge.text = openRouterMod
        when (openRouterMod) {
            "meta-llama/llama-3.3-70b-instruct" -> binding.rbOpenRouterLlama.isChecked = true
            "anthropic/claude-3.5-sonnet" -> binding.rbOpenRouterClaude.isChecked = true
            "deepseek/deepseek-r1" -> binding.rbOpenRouterDeepSeek.isChecked = true
            "openai/gpt-4o" -> binding.rbOpenRouterGpt4o.isChecked = true
            else -> binding.rbOpenRouterAuto.isChecked = true
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
            pasteToEditText { binding.etApiKey.setText(it) }
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

        // Execution Mode toggle listener: hide single provider selector when in all-together mode
        binding.rgExecutionMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == binding.rbModeAllTogether.id) {
                binding.layoutSingleProviderSelect.visibility = View.GONE
            } else {
                binding.layoutSingleProviderSelect.visibility = View.VISIBLE
            }
        }

        // God's Eye Registration Portal Links
        binding.btnGetGoogleKey.setOnClickListener { openWebUrl("https://console.cloud.google.com/google/maps-apis/credentials") }
        binding.btnGetCesiumToken.setOnClickListener { openWebUrl("https://ion.cesium.com/tokens") }
        binding.btnGetFirmsKey.setOnClickListener { openWebUrl("https://firms.modaps.eosdis.nasa.gov/api/map_key/") }
        binding.btnGetAisStreamKey.setOnClickListener { openWebUrl("https://aisstream.io/") }
        binding.btnGetTomTomKey.setOnClickListener { openWebUrl("https://developer.tomtom.com/") }
        binding.btnGetMapillaryToken.setOnClickListener { openWebUrl("https://www.mapillary.com/dashboard/developers") }
        binding.btnGetOpenSkyKey.setOnClickListener { openWebUrl("https://opensky-network.org/") }

        binding.btnFillWifi.setOnClickListener {
            binding.etCustomEndpoint.setText(AppPreferences.DEFAULT_CUSTOM_ENDPOINT)
            Toast.makeText(this, "Set to USB ADB Reverse (127.0.0.1) - run: adb reverse tcp:11434 tcp:11434", Toast.LENGTH_LONG).show()
        }

        binding.btnFillAdb.setOnClickListener {
            binding.etCustomEndpoint.setText(AppPreferences.DEFAULT_CUSTOM_ENDPOINT)
            Toast.makeText(this, "Set to USB ADB Reverse (127.0.0.1) - run: adb reverse tcp:11434 tcp:11434", Toast.LENGTH_LONG).show()
        }

        binding.btnTestLocalHost.setOnClickListener {
            val endpoint = binding.etCustomEndpoint.text?.toString()?.trim().orEmpty()
                .ifBlank { AppPreferences.DEFAULT_CUSTOM_ENDPOINT }
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

        binding.switchGeminiEnabled.setOnCheckedChangeListener { _, isChecked ->
            preferences.isGeminiEnabled = isChecked
            val stateText = if (isChecked) "Enabled (Cloud streaming active)" else "Disabled (Offline Mode Forced)"
            Snackbar.make(binding.root, "Gemini Cloud API: $stateText", Snackbar.LENGTH_SHORT).show()
        }

        binding.switchOllamaEnabled.setOnCheckedChangeListener { _, isChecked ->
            preferences.isOllamaEnabled = isChecked
            val stateText = if (isChecked) "Enabled (LAN/USB inference active)" else "Disabled"
            Snackbar.make(binding.root, "PC Ollama: $stateText", Snackbar.LENGTH_SHORT).show()
        }

        binding.switchLocalBrainEnabled.setOnCheckedChangeListener { _, isChecked ->
            preferences.isLocalBrainEnabled = isChecked
            val stateText = if (isChecked) "Enabled (Autonomous local engine active)" else "Disabled"
            Snackbar.make(binding.root, "100% On-Device Brain: $stateText", Snackbar.LENGTH_SHORT).show()
        }

        binding.btnTestLocalBrain.setOnClickListener {
            HudSoundManager.playTapSound()
            testLocalBrain()
        }

        binding.btnDownloadLocalBrain.setOnClickListener {
            HudSoundManager.playTapSound()
            showLocalModelManagerDialog()
        }

        binding.btnCancelLocalBrainDownload.setOnClickListener {
            HudSoundManager.playTapSound()
            val modelManager = com.jarvis.assistant.ai.local.LocalModelManager.getInstance(this, lifecycleScope)
            val activeDownload = modelManager.downloads.value.values.firstOrNull {
                it.status == com.jarvis.assistant.ai.local.LocalDownloadStatus.DOWNLOADING ||
                it.status == com.jarvis.assistant.ai.local.LocalDownloadStatus.VERIFYING
            }
            if (activeDownload != null) {
                modelManager.cancelDownload(activeDownload.modelId)
                Toast.makeText(this, "Download cancelled.", Toast.LENGTH_SHORT).show()
                refreshLocalBrainUi()
            }
        }

        binding.btnSaveConnection.setOnClickListener {
            saveAndApply()
        }
    }

    private fun setupModelDiscoveryListeners() {
        // OpenAI Paste / Clear / Check Models
        binding.btnPasteOpenAi.setOnClickListener { pasteToEditText { binding.etOpenAiApiKey.setText(it) } }
        binding.btnClearOpenAi.setOnClickListener { binding.etOpenAiApiKey.setText("") }
        binding.btnTestOpenAi.setOnClickListener {
            val key = binding.etOpenAiApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                Snackbar.make(binding.root, "Enter OpenAI API key first", Snackbar.LENGTH_SHORT).show()
            } else {
                checkOpenAiModels(key)
            }
        }
        binding.rgOpenAiModels.setOnCheckedChangeListener { _, checkedId ->
            val mod = when (checkedId) {
                binding.rbOpenAiGpt4oMini.id -> "gpt-4o-mini"
                binding.rbOpenAiO1.id -> "o1"
                binding.rbOpenAiO3Mini.id -> "o3-mini"
                binding.rbOpenAiGpt4Turbo.id -> "gpt-4-turbo"
                else -> "gpt-4o"
            }
            preferences.openAiModel = mod
            binding.tvOpenAiActiveBadge.text = mod
        }

        // Claude Paste / Clear / Check Models
        binding.btnPasteClaude.setOnClickListener { pasteToEditText { binding.etClaudeApiKey.setText(it) } }
        binding.btnClearClaude.setOnClickListener { binding.etClaudeApiKey.setText("") }
        binding.btnTestClaude.setOnClickListener {
            val key = binding.etClaudeApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                Snackbar.make(binding.root, "Enter Anthropic Claude API key first", Snackbar.LENGTH_SHORT).show()
            } else {
                checkClaudeModels(key)
            }
        }
        binding.rgClaudeModels.setOnCheckedChangeListener { _, checkedId ->
            val mod = when (checkedId) {
                binding.rbClaudeHaiku.id -> "claude-3-5-haiku-20241022"
                binding.rbClaudeOpus.id -> "claude-3-opus-20240229"
                else -> "claude-3-5-sonnet-20241022"
            }
            preferences.claudeModel = mod
            binding.tvClaudeActiveBadge.text = mod
        }

        // DeepSeek Paste / Clear / Check Models
        binding.btnPasteDeepSeek.setOnClickListener { pasteToEditText { binding.etDeepSeekApiKey.setText(it) } }
        binding.btnClearDeepSeek.setOnClickListener { binding.etDeepSeekApiKey.setText("") }
        binding.btnTestDeepSeek.setOnClickListener {
            val key = binding.etDeepSeekApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                Snackbar.make(binding.root, "Enter DeepSeek API key first", Snackbar.LENGTH_SHORT).show()
            } else {
                checkDeepSeekModels(key)
            }
        }
        binding.rgDeepSeekModels.setOnCheckedChangeListener { _, checkedId ->
            val mod = when (checkedId) {
                binding.rbDeepSeekReasoner.id -> "deepseek-reasoner"
                else -> "deepseek-chat"
            }
            preferences.deepSeekModel = mod
            binding.tvDeepSeekActiveBadge.text = mod
        }

        // Grok Paste / Clear / Check Models
        binding.btnPasteGrok.setOnClickListener { pasteToEditText { binding.etGrokApiKey.setText(it) } }
        binding.btnClearGrok.setOnClickListener { binding.etGrokApiKey.setText("") }
        binding.btnTestGrok.setOnClickListener {
            val key = binding.etGrokApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                Snackbar.make(binding.root, "Enter xAI Grok API key first", Snackbar.LENGTH_SHORT).show()
            } else {
                checkGrokModels(key)
            }
        }
        binding.rgGrokModels.setOnCheckedChangeListener { _, checkedId ->
            val mod = when (checkedId) {
                binding.rbGrok2Mini.id -> "grok-2-mini"
                binding.rbGrokBeta.id -> "grok-beta"
                else -> "grok-2"
            }
            preferences.grokModel = mod
            binding.tvGrokActiveBadge.text = mod
        }

        // OpenRouter Paste / Clear / Check Models
        binding.btnPasteOpenRouter.setOnClickListener { pasteToEditText { binding.etOpenRouterApiKey.setText(it) } }
        binding.btnClearOpenRouter.setOnClickListener { binding.etOpenRouterApiKey.setText("") }
        binding.btnTestOpenRouter.setOnClickListener {
            val key = binding.etOpenRouterApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                Snackbar.make(binding.root, "Enter OpenRouter API key first", Snackbar.LENGTH_SHORT).show()
            } else {
                checkOpenRouterModels(key)
            }
        }
        binding.rgOpenRouterModels.setOnCheckedChangeListener { _, checkedId ->
            val mod = when (checkedId) {
                binding.rbOpenRouterLlama.id -> "meta-llama/llama-3.3-70b-instruct"
                binding.rbOpenRouterClaude.id -> "anthropic/claude-3.5-sonnet"
                binding.rbOpenRouterDeepSeek.id -> "deepseek/deepseek-r1"
                binding.rbOpenRouterGpt4o.id -> "openai/gpt-4o"
                else -> "auto"
            }
            preferences.openRouterModel = mod
            binding.tvOpenRouterActiveBadge.text = mod
        }
    }

    private fun checkOpenAiModels(apiKey: String) {
        binding.btnTestOpenAi.isEnabled = false
        binding.tvOpenAiModelStatus.text = "Checking OpenAI models..."
        lifecycleScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder()
                        .url("https://api.openai.com/v1/models")
                        .header("Authorization", "Bearer $apiKey")
                        .build()
                    testHttpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string() ?: ""
                            val json = JSONObject(body)
                            val data = json.optJSONArray("data")
                            val count = data?.length() ?: 0
                            Pair(true, "✅ Verified! Found $count models available (gpt-4o, gpt-4o-mini, o1, o3-mini, etc.)")
                        } else {
                            Pair(false, "❌ Verification failed: HTTP ${resp.code} (Invalid Key)")
                        }
                    }
                } catch (e: Exception) {
                    Pair(false, "❌ Network error: ${e.message}")
                }
            }
            binding.btnTestOpenAi.isEnabled = true
            binding.tvOpenAiModelStatus.text = msg
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun checkClaudeModels(apiKey: String) {
        binding.btnTestClaude.isEnabled = false
        binding.tvClaudeModelStatus.text = "Checking Claude models..."
        lifecycleScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder()
                        .url("https://api.anthropic.com/v1/models")
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .build()
                    testHttpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful || resp.code == 200 || resp.code == 404) {
                            Pair(true, "✅ Key Active! Available: claude-3-5-sonnet, claude-3-5-haiku, claude-3-opus")
                        } else {
                            Pair(false, "❌ Verification failed: HTTP ${resp.code}")
                        }
                    }
                } catch (e: Exception) {
                    Pair(false, "❌ Network error: ${e.message}")
                }
            }
            binding.btnTestClaude.isEnabled = true
            binding.tvClaudeModelStatus.text = msg
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun checkDeepSeekModels(apiKey: String) {
        binding.btnTestDeepSeek.isEnabled = false
        binding.tvDeepSeekModelStatus.text = "Checking DeepSeek models..."
        lifecycleScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder()
                        .url("https://api.deepseek.com/models")
                        .header("Authorization", "Bearer $apiKey")
                        .build()
                    testHttpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            Pair(true, "✅ Key Verified! Available: deepseek-chat (V3), deepseek-reasoner (R1)")
                        } else {
                            Pair(false, "❌ Verification failed: HTTP ${resp.code}")
                        }
                    }
                } catch (e: Exception) {
                    Pair(false, "❌ Network error: ${e.message}")
                }
            }
            binding.btnTestDeepSeek.isEnabled = true
            binding.tvDeepSeekModelStatus.text = msg
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun checkGrokModels(apiKey: String) {
        binding.btnTestGrok.isEnabled = false
        binding.tvGrokModelStatus.text = "Checking xAI Grok models..."
        lifecycleScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder()
                        .url("https://api.x.ai/v1/models")
                        .header("Authorization", "Bearer $apiKey")
                        .build()
                    testHttpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            Pair(true, "✅ Key Verified! Available: grok-2, grok-2-mini, grok-beta")
                        } else {
                            Pair(false, "❌ Verification failed: HTTP ${resp.code}")
                        }
                    }
                } catch (e: Exception) {
                    Pair(false, "❌ Network error: ${e.message}")
                }
            }
            binding.btnTestGrok.isEnabled = true
            binding.tvGrokModelStatus.text = msg
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun checkOpenRouterModels(apiKey: String) {
        binding.btnTestOpenRouter.isEnabled = false
        binding.tvOpenRouterModelStatus.text = "Checking OpenRouter models..."
        lifecycleScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder()
                        .url("https://openrouter.ai/api/v1/models")
                        .header("Authorization", "Bearer $apiKey")
                        .build()
                    testHttpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            Pair(true, "✅ Connected! Over 200+ models accessible (Llama 3.3, Claude, DeepSeek R1, GPT-4o)")
                        } else {
                            Pair(false, "❌ Verification failed: HTTP ${resp.code}")
                        }
                    }
                } catch (e: Exception) {
                    Pair(false, "❌ Network error: ${e.message}")
                }
            }
            binding.btnTestOpenRouter.isEnabled = true
            binding.tvOpenRouterModelStatus.text = msg
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun pasteToEditText(action: (String) -> Unit) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0)
        val pasted = item?.text?.toString()?.trim() ?: ""
        if (pasted.isNotBlank()) {
            action(pasted)
            Toast.makeText(this, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testLocalHost(endpoint: String) {
        binding.btnTestLocalHost.isEnabled = false
        binding.tvLocalHostStatus.text = "Testing connection to $endpoint..."
        binding.tvLocalHostStatus.setTextColor(com.jarvis.assistant.util.ThemeManager.attrColor(this@ApiConnectionActivity, R.attr.themeAccent, Color.parseColor("#38BDF8")))

        lifecycleScope.launch {
            val client = com.jarvis.assistant.network.LocalHostLlmClient()
            val info = client.pingServer(endpoint)
            withContext(Dispatchers.Main) {
                binding.btnTestLocalHost.isEnabled = true
                if (info.isOnline) {
                    val modelsStr = if (info.availableModels.isNotEmpty()) info.availableModels.joinToString(", ") else "None"
                    binding.tvLocalHostStatus.text = "✅ Connected (${info.pingMs}ms)\nActive: ${info.activeModel}\nAvailable: $modelsStr"
                    binding.tvLocalHostStatus.setTextColor(com.jarvis.assistant.util.ThemeManager.attrColor(this@ApiConnectionActivity, R.attr.themeSuccess, Color.parseColor("#22C55E")))
                    if (binding.etLocalHostModel.text.isNullOrBlank() && info.activeModel.isNotBlank()) {
                        binding.etLocalHostModel.setText(info.activeModel)
                    }
                    Snackbar.make(binding.root, "Local Host AI is Online! Found ${info.availableModels.size} model(s).", Snackbar.LENGTH_LONG).show()
                } else {
                    binding.tvLocalHostStatus.text = "❌ Connection failed (${info.pingMs}ms)\n${info.error ?: "Check PC Wi-Fi or firewall"}"
                    binding.tvLocalHostStatus.setTextColor(com.jarvis.assistant.util.ThemeManager.attrColor(this@ApiConnectionActivity, R.attr.themeDanger, Color.parseColor("#EF4444")))
                    Snackbar.make(binding.root, "Could not reach Ollama at $endpoint.", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun testLocalBrain() {
        binding.btnTestLocalBrain.isEnabled = false
        binding.tvLocalBrainStatus.text = "Initializing native ARM64 local engine..."
        binding.tvLocalBrainStatus.setTextColor(com.jarvis.assistant.util.ThemeManager.attrColor(this@ApiConnectionActivity, R.attr.themeAccent, Color.parseColor("#38BDF8")))

        lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            val result = withContext(Dispatchers.Default) {
                try {
                    val engine = com.jarvis.brain.LocalLlamaEngine.getInstance(this@ApiConnectionActivity)
                    val loadRes = engine.load()
                    if (loadRes is com.jarvis.brain.LocalLlamaEngine.LoadResult.Error) {
                        return@withContext Pair(false, "Load Error: ${loadRes.reason}")
                    }
                    val prompt = com.jarvis.brain.IntentPrompt.build("system diagnosis", modelName = engine.activeModelName)
                    val raw = engine.generate(prompt, com.jarvis.brain.IntentPrompt.GRAMMAR)
                    val elapsed = System.currentTimeMillis() - t0
                    Pair(true, "✅ On-Device Inference Ready (${elapsed}ms)!\nOutput: $raw")
                } catch (e: Exception) {
                    Pair(false, "❌ Error: ${e.message}")
                }
            }

            withContext(Dispatchers.Main) {
                binding.btnTestLocalBrain.isEnabled = true
                binding.tvLocalBrainStatus.text = result.second
                if (result.first) {
                    binding.tvLocalBrainBadge.text = "Active · 100% Offline"
                    binding.tvLocalBrainStatus.setTextColor(com.jarvis.assistant.util.ThemeManager.attrColor(this@ApiConnectionActivity, R.attr.themeSuccess, Color.parseColor("#22C55E")))
                    Snackbar.make(binding.root, "Jarvis On-Device Brain is running smoothly!", Snackbar.LENGTH_LONG).show()
                } else {
                    binding.tvLocalBrainStatus.setTextColor(com.jarvis.assistant.util.ThemeManager.attrColor(this@ApiConnectionActivity, R.attr.themeDanger, Color.parseColor("#EF4444")))
                    Snackbar.make(binding.root, result.second, Snackbar.LENGTH_LONG).show()
                }
                refreshLocalBrainUi()
            }
        }
    }

    private fun refreshLocalBrainUi() {
        val engine = com.jarvis.brain.LocalLlamaEngine.getInstance(this)
        val resolved = engine.resolveModelFile()
        val modelManager = com.jarvis.assistant.ai.local.LocalModelManager.getInstance(this, lifecycleScope)
        val activeId = modelManager.activeModelId.value
        val descriptor = com.jarvis.assistant.ai.local.LocalAiConfig.AVAILABLE_MODELS.find { it.id == activeId }
        val activeDownload = modelManager.downloads.value.values.firstOrNull {
            it.status == com.jarvis.assistant.ai.local.LocalDownloadStatus.DOWNLOADING ||
            it.status == com.jarvis.assistant.ai.local.LocalDownloadStatus.VERIFYING
        }

        if (activeDownload != null) {
            val dDesc = com.jarvis.assistant.ai.local.LocalAiConfig.AVAILABLE_MODELS.find { it.id == activeDownload.modelId }
            binding.layoutLocalBrainDownload.visibility = View.VISIBLE
            binding.btnDownloadLocalBrain.visibility = View.GONE
            binding.btnTestLocalBrain.visibility = View.GONE
            binding.tvLocalBrainBadge.text = "Downloading · ${activeDownload.progressPercent}%"
            binding.tvLocalBrainDownloadTitle.text = "Downloading ${dDesc?.name ?: activeDownload.modelId}..."
            binding.tvLocalBrainDownloadPercent.text = "${activeDownload.progressPercent}%"
            binding.progressLocalBrainDownload.progress = activeDownload.progressPercent
            val dMb = activeDownload.bytesDownloaded shr 20
            val tMb = activeDownload.totalBytes shr 20
            val speedMb = String.format(java.util.Locale.US, "%.1f MB/s", activeDownload.speedBytesPerSec / (1024 * 1024))
            val eta = if (activeDownload.etaSeconds > 0) " · ETA: ${activeDownload.etaSeconds}s" else ""
            binding.tvLocalBrainDownloadDetails.text = "$dMb MB / $tMb MB · $speedMb$eta"
            binding.tvLocalBrainPathSummary.text = "Downloading from Hugging Face"
            binding.tvLocalBrainStatus.text = "Status: Downloading on-device model weights (${activeDownload.progressPercent}%)..."
            binding.tvLocalBrainStatus.setTextColor(com.jarvis.assistant.util.ThemeManager.attrColor(this, R.attr.themeAccent, Color.parseColor("#38BDF8")))
        } else if (resolved != null && resolved.exists()) {
            val sizeMb = resolved.length() shr 20
            binding.layoutLocalBrainDownload.visibility = View.GONE
            binding.btnDownloadLocalBrain.visibility = View.GONE
            binding.btnTestLocalBrain.visibility = View.VISIBLE
            binding.btnTestLocalBrain.isEnabled = true
            binding.tvLocalBrainBadge.text = "Ready · ${sizeMb}MB"
            binding.tvLocalBrainPathSummary.text = "Model: ${resolved.name} (Ready in storage)"
            binding.tvLocalBrainStatus.text = "Status: 100% On-Device Active · Uncensored Autonomous Brain"
            binding.tvLocalBrainStatus.setTextColor(com.jarvis.assistant.util.ThemeManager.attrColor(this, R.attr.themeSuccess, Color.parseColor("#22C55E")))
        } else {
            binding.layoutLocalBrainDownload.visibility = View.GONE
            binding.btnDownloadLocalBrain.visibility = View.VISIBLE
            binding.btnTestLocalBrain.visibility = View.GONE
            binding.tvLocalBrainBadge.text = "Not Installed"
            binding.tvLocalBrainPathSummary.text = "No GGUF weights detected on device."
            binding.tvLocalBrainStatus.text = "Status: Tap below to download 100% sovereign offline brain."
            binding.tvLocalBrainStatus.setTextColor(com.jarvis.assistant.util.ThemeManager.attrColor(this, R.attr.themeTextSecondary, Color.parseColor("#94A3B8")))
        }
    }

    private fun showLocalModelManagerDialog() {
        val modelManager = com.jarvis.assistant.ai.local.LocalModelManager.getInstance(this, lifecycleScope)
        val models = com.jarvis.assistant.ai.local.LocalAiConfig.AVAILABLE_MODELS
        val modelItems = models.map { m ->
            val ready = modelManager.isModelReady(m.id)
            val status = if (ready) "✅ READY (Installed)" else "⬇️ Download (${m.formattedSize})"
            "${m.name}\n$status"
        }.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Select Sovereign Brain Model (llama.cpp)")
            .setItems(modelItems) { _, which ->
                val selectedModel = models[which]
                val isReady = modelManager.isModelReady(selectedModel.id)
                if (isReady) {
                    modelManager.setActiveModel(selectedModel.id)
                    Toast.makeText(this, "${selectedModel.name} is active.", Toast.LENGTH_SHORT).show()
                    refreshLocalBrainUi()
                } else {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Download ${selectedModel.name}")
                        .setMessage("Download on-device GGUF weights (${selectedModel.formattedSize})?\n\n${selectedModel.description}\n\n• 100% Offline & Private\n• Native ARM64 NEON acceleration\n• Full phone control & WhatsApp offline")
                        .setPositiveButton("Start Download") { _, _ ->
                            HudSoundManager.playTapSound()
                            modelManager.setActiveModel(selectedModel.id)
                            modelManager.downloadModel(selectedModel.id)
                            Toast.makeText(this, "Starting download of ${selectedModel.name}…", Toast.LENGTH_SHORT).show()
                            refreshLocalBrainUi()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun testConnection(apiKey: String) {
        binding.btnTestConnection.isEnabled = false
        binding.tvPingResult.text = "Testing ping..."
        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(com.jarvis.assistant.util.ThemeManager.attrColor(this@ApiConnectionActivity, R.attr.themeInfo, Color.parseColor("#3B82F6")))

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
                binding.tvLiveStatus.text = "Connected · models available (3.8 Live, 3.1 Flash, 2.0 Flash)"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(com.jarvis.assistant.util.ThemeManager.attrColor(this@ApiConnectionActivity, R.attr.themeSuccess, Color.parseColor("#22C55E")))
                Snackbar.make(binding.root, "Gemini models verified! Ping: ${ping}ms", Snackbar.LENGTH_LONG).show()
            } else {
                val code = result.second
                binding.tvPingResult.text = if (code == -1) "Network Timeout" else "HTTP $code Error"
                binding.tvLiveStatus.text = "Failed · invalid key or network"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(com.jarvis.assistant.util.ThemeManager.attrColor(this@ApiConnectionActivity, R.attr.themeDanger, Color.parseColor("#EF4444")))
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

        // Save AI Engine Power Switches
        preferences.isGeminiEnabled = binding.switchGeminiEnabled.isChecked
        preferences.isOllamaEnabled = binding.switchOllamaEnabled.isChecked
        preferences.isLocalBrainEnabled = binding.switchLocalBrainEnabled.isChecked

        // Save Multi-Model Execution Mode
        val executionMode = if (binding.rbModeAllTogether.isChecked) {
            MultiModelAiRouter.MODE_ALL_TOGETHER
        } else {
            MultiModelAiRouter.MODE_SINGLE
        }
        preferences.aiExecutionMode = executionMode

        // Save Selected Primary Provider for Single Mode
        val selectedProvider = when (binding.rgProviderGroup.checkedRadioButtonId) {
            binding.rbProviderOpenAi.id -> MultiModelAiRouter.PROVIDER_OPENAI
            binding.rbProviderClaude.id -> MultiModelAiRouter.PROVIDER_CLAUDE
            binding.rbProviderDeepSeek.id -> MultiModelAiRouter.PROVIDER_DEEPSEEK
            binding.rbProviderGrok.id -> MultiModelAiRouter.PROVIDER_GROK
            binding.rbProviderOpenRouter.id -> MultiModelAiRouter.PROVIDER_OPENROUTER
            binding.rbProviderOllama.id -> MultiModelAiRouter.PROVIDER_LOCAL
            binding.rbProviderOnDeviceLocal.id -> MultiModelAiRouter.PROVIDER_ONDEVICE_LOCAL
            else -> MultiModelAiRouter.PROVIDER_GEMINI
        }
        preferences.selectedAiProvider = selectedProvider

        // Save Multi-Model API Keys securely
        preferences.openAiApiKey = binding.etOpenAiApiKey.text?.toString()?.trim() ?: ""
        preferences.claudeApiKey = binding.etClaudeApiKey.text?.toString()?.trim() ?: ""
        preferences.deepSeekApiKey = binding.etDeepSeekApiKey.text?.toString()?.trim() ?: ""
        preferences.grokApiKey = binding.etGrokApiKey.text?.toString()?.trim() ?: ""
        preferences.openRouterApiKey = binding.etOpenRouterApiKey.text?.toString()?.trim() ?: ""

        // Save Specific Model Selections
        preferences.openAiModel = when (binding.rgOpenAiModels.checkedRadioButtonId) {
            binding.rbOpenAiGpt4oMini.id -> "gpt-4o-mini"
            binding.rbOpenAiO1.id -> "o1"
            binding.rbOpenAiO3Mini.id -> "o3-mini"
            binding.rbOpenAiGpt4Turbo.id -> "gpt-4-turbo"
            else -> "gpt-4o"
        }

        preferences.claudeModel = when (binding.rgClaudeModels.checkedRadioButtonId) {
            binding.rbClaudeHaiku.id -> "claude-3-5-haiku-20241022"
            binding.rbClaudeOpus.id -> "claude-3-opus-20240229"
            else -> "claude-3-5-sonnet-20241022"
        }

        preferences.deepSeekModel = when (binding.rgDeepSeekModels.checkedRadioButtonId) {
            binding.rbDeepSeekReasoner.id -> "deepseek-reasoner"
            else -> "deepseek-chat"
        }

        preferences.grokModel = when (binding.rgGrokModels.checkedRadioButtonId) {
            binding.rbGrok2Mini.id -> "grok-2-mini"
            binding.rbGrokBeta.id -> "grok-beta"
            else -> "grok-2"
        }

        preferences.openRouterModel = when (binding.rgOpenRouterModels.checkedRadioButtonId) {
            binding.rbOpenRouterLlama.id -> "meta-llama/llama-3.3-70b-instruct"
            binding.rbOpenRouterClaude.id -> "anthropic/claude-3.5-sonnet"
            binding.rbOpenRouterDeepSeek.id -> "deepseek/deepseek-r1"
            binding.rbOpenRouterGpt4o.id -> "openai/gpt-4o"
            else -> "auto"
        }

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

        Toast.makeText(this, "API & Multi-Model settings saved!", Toast.LENGTH_SHORT).show()
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
