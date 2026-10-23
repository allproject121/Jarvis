package com.jarvis.assistant.ui.settings

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityCustomizationStudioBinding
import com.jarvis.assistant.util.HudSoundManager
import com.jarvis.assistant.util.JarvisWebViewClient
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.roundToInt

class CustomizationStudioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomizationStudioBinding
    private val preferences by lazy { JarvisApp.instance.preferences }

    private var selectedPreset = "cyan"
    private var selectedTheme = "dark"
    private var customColorHex = "#22D3EE"
    private var selectedResonance = "quantum"
    private var selectedPersona = "jarvis"
    private var selectedEntityType = "orb"
    private var isOrbLoaded = false
    private var companionCount = 0
    private var companionType = "orb"
    private var selectedWallpaperMode = "character"

    private val pickStudioMediaLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val (is3D, detectedExt) = com.jarvis.assistant.util.CustomAvatarHelper.is3DModelFile(this, uri)
            if (is3D) {
                val (ok, desc) = com.jarvis.assistant.util.CustomAvatarHelper.processAndSave3DModelUri(this, uri)
                val newItem = com.jarvis.assistant.util.ModelVaultManager.importModelFromUri(this, uri)
                if (ok || newItem != null) {
                    selectedEntityType = "custom_3d"
                    updateEntityTypeUI("custom_3d")
                    refreshCustomMediaStatusUI()
                    renderSavedModelsList()
                    populateVaultCardsUI()
                    binding.previewOrbWebView.evaluateJavascript(
                        "if(window.JarvisOrb){ window.JarvisOrb.setEntityType('custom_3d'); window.JarvisOrb.loadCustom3DModel(); }",
                        null
                    )
                    applyPreviewOrbParams()
                    val title = newItem?.title ?: desc
                    Toast.makeText(this, "3D Model '$title' saved to library!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to load 3D model: $desc", Toast.LENGTH_SHORT).show()
                }
            } else {
                val ok = com.jarvis.assistant.util.CustomAvatarHelper.processAndSaveMediaUri(this, uri)
                if (ok) {
                    refreshCustomMediaStatusUI()
                    renderSavedModelsList()
                    populateVaultCardsUI()
                    binding.previewOrbWebView.evaluateJavascript(
                        "if(window.JarvisOrb){ window.JarvisOrb.clearCustom3DModel(); window.JarvisOrb.loadCustomPhoto(); }",
                        null
                    )
                    applyPreviewOrbParams()
                    Toast.makeText(this, "Hologram photo medallion loaded!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to load media file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun refreshCustomMediaStatusUI() {
        val activeModel = com.jarvis.assistant.util.CustomAvatarHelper.getActiveModel(this)
        val activeModelFile = com.jarvis.assistant.util.CustomAvatarHelper.getActiveModelFile(this)
            ?: com.jarvis.assistant.util.ModelVaultManager.getActiveModelFile(this)
        if (activeModelFile != null && activeModelFile.exists() && selectedEntityType == "custom_3d") {
            val mb = String.format("%.2f MB", activeModelFile.length() / (1024f * 1024f))
            val ext = activeModelFile.extension.uppercase()
            val name = activeModel?.name ?: activeModelFile.nameWithoutExtension
            binding.tvStudioCustomMediaStatus.text = "ACTIVE: $name ($ext, $mb) • 3D Render"
        } else if (com.jarvis.assistant.util.CustomAvatarHelper.getAvatarFile(this).exists() && selectedEntityType == "custom_3d") {
            binding.tvStudioCustomMediaStatus.text = "ACTIVE: Custom Holographic Photo Medallion"
        } else {
            binding.tvStudioCustomMediaStatus.text = "ACTIVE: Quantum Arc Reactor Orb (Default Wake Avatar)"
        }
    }

    private fun populateVaultCardsUI() {
        val container = binding.layoutVaultCardsContainer
        container.removeAllViews()

        val items = com.jarvis.assistant.util.ModelVaultManager.getVaultItems(this)
        val activeId = com.jarvis.assistant.util.ModelVaultManager.getActiveVaultItemId(this)
        binding.tvVaultCountBadge.text = "${items.size} SLOTS"

        val inflater = layoutInflater
        for (item in items) {
            val cardView = inflater.inflate(R.layout.item_vault_card, container, false)
            val tvFormat = cardView.findViewById<android.widget.TextView>(R.id.tvCardFormatBadge)
            val btnDelete = cardView.findViewById<android.widget.ImageButton>(R.id.btnCardDelete)
            val tvIcon = cardView.findViewById<android.widget.TextView>(R.id.tvCardIcon)
            val tvTitle = cardView.findViewById<android.widget.TextView>(R.id.tvCardTitle)
            val tvSize = cardView.findViewById<android.widget.TextView>(R.id.tvCardSize)
            val tvActive = cardView.findViewById<android.widget.TextView>(R.id.tvCardActiveBadge)

            tvFormat.text = if (item.isBuiltin) "BUILT-IN" else "${item.format.uppercase()} 3D"
            tvTitle.text = item.title
            tvSize.text = item.getFormattedSize()

            when (item.category) {
                "mora_cyber" -> tvIcon.text = "🤖"
                "hinata_anime" -> tvIcon.text = "🌸"
                "ai_girl" -> tvIcon.text = "🌪️"
                "arc_reactor" -> tvIcon.text = "⚡"
                "custom_3d" -> tvIcon.text = "🧊"
                else -> tvIcon.text = "🌌"
            }

            val isActive = item.id == activeId
            if (isActive) {
                cardView.setBackgroundResource(R.drawable.bg_jarvis_chip)
                tvActive.visibility = View.VISIBLE
            } else {
                cardView.setBackgroundResource(R.drawable.bg_jarvis_row_item)
                tvActive.visibility = View.GONE
            }

            if (!item.isBuiltin) {
                btnDelete.visibility = View.VISIBLE
                btnDelete.setOnClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Delete 3D Model?")
                        .setMessage("Delete '${item.title}' (${item.getFormattedSize()}) from your vault? This cannot be undone.")
                        .setPositiveButton("Delete") { _, _ ->
                            HudSoundManager.playTapSound()
                            com.jarvis.assistant.util.ModelVaultManager.deleteVaultItem(this, item.id)
                            selectedEntityType = preferences.orbEntityType
                            updateEntityTypeUI(selectedEntityType)
                            populateVaultCardsUI()
                            refreshCustomMediaStatusUI()
                            applyPreviewOrbParams()
                            Toast.makeText(this, "Model '${item.title}' deleted from vault", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } else {
                btnDelete.visibility = View.GONE
            }

            cardView.setOnClickListener {
                HudSoundManager.playTapSound()
                com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this, item.id)
                selectedEntityType = item.category
                updateEntityTypeUI(selectedEntityType)
                populateVaultCardsUI()
                refreshCustomMediaStatusUI()
                applyPreviewOrbParams()
                if (item.category == "mora_cyber") {
                    startActivity(android.content.Intent(this@CustomizationStudioActivity, com.jarvis.assistant.ui.character.MoraCharacterActivity::class.java))
                }
            }

            container.addView(cardView)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            ThemeManager.applyTheme(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying theme in Studio", e)
        }
        super.onCreate(savedInstanceState)

        try {
            binding = ActivityCustomizationStudioBinding.inflate(layoutInflater)
            setContentView(binding.root)
            ThemeManager.applyDynamicBackground(binding.customizationStudioRoot)

            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.customizationStudioRoot) { _, insets ->
                val statusBarInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                val density = resources.displayMetrics.density
                val topInset = statusBarInsets.top
                val safeTopPadding = if (topInset > 0) topInset + (6 * density).toInt() else (14 * density).toInt()
                binding.layoutStudioHeader.setPadding(
                    binding.layoutStudioHeader.paddingLeft,
                    safeTopPadding,
                    binding.layoutStudioHeader.paddingRight,
                    binding.layoutStudioHeader.paddingBottom
                )
                insets
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inflating studio layout", e)
            Toast.makeText(this, "Studio initialization error: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            loadInitialPreferences()
            setupPreviewOrb()
            setupTabNavigation()
            setupOrbTabControls()
            setupThemeTabControls()
            setupWidgetTabControls()
            setupAudioTabControls()
            setupActionButtons()
            lifecycleScope.launch {
                com.jarvis.assistant.util.Jarvis3DModelBridge.modelDiagnosticsFlow.collect {
                    refreshCustomMediaStatusUI()
                }
            }
            renderSavedModelsList()
            populateVaultCardsUI()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up Studio controls", e)
        }
    }

    private fun getCurrentResonance(): Int {
        return binding.sliderCoreResonance.progress.coerceIn(10, 100)
    }

    private fun getCurrentSpeed(): Float {
        return 0.2f + (binding.sliderOrbSpeed.progress * 0.1f)
    }

    private fun getCurrentGlow(): Float {
        return 0.2f + (binding.sliderOrbGlow.progress * 0.1f)
    }

    private fun getCurrentScale(): Float {
        return 0.70f + (binding.sliderOrbScale.progress * 0.05f)
    }

    private fun getCurrentVolume(): Float {
        return (binding.sliderHudVolume.progress / 100f).coerceIn(0f, 1f)
    }

    private fun getCurrentVosk(): Float {
        return (0.30f + (binding.sliderVoskSensitivity.progress * 0.05f)).coerceIn(0.3f, 0.95f)
    }

    private fun loadInitialPreferences() {
        selectedPreset = preferences.orbCorePreset
        selectedTheme = preferences.appTheme
        customColorHex = preferences.customThemeColor
        selectedEntityType = preferences.orbEntityType
        companionCount = preferences.orbCompanionCount
        companionType = preferences.orbCompanionType

        // Entity & Granular Orb switches
        updateEntityTypeUI(selectedEntityType)
        binding.switchOrbCore.isChecked = preferences.orbShowCore
        binding.switchOrbGlow.isChecked = preferences.orbShowGlow
        binding.switchOrbRings.isChecked = preferences.orbShowRings
        binding.switchOrbRing1.isChecked = preferences.orbShowRing1
        binding.switchOrbRing2.isChecked = preferences.orbShowRing2
        binding.switchOrbRing3.isChecked = preferences.orbShowRing3
        binding.switchOrbOrbitDust.isChecked = preferences.orbShowOrbitDust
        binding.switchOrbShadow.isChecked = preferences.orbShowShadow
        binding.switchOrbIdleBreathing.isChecked = preferences.orbShowIdleBreathing
        binding.switchOrbTouchDrag.isChecked = preferences.orbTouchDragEnabled
        binding.switchOrbCinematic50.isChecked = preferences.isOrbCinematic50
        binding.switchOrbAutoRotation.isChecked = preferences.orbAutoRotationEnabled

        binding.etCustomColorHex.setText(customColorHex)
        updateCompanionUI()
        refreshCustomMediaStatusUI()
        populateVaultCardsUI()

        // 0. Core Resonance: 10 to 100%
        val resProg = preferences.coreResonanceLevel.coerceIn(10, 100)
        binding.sliderCoreResonance.progress = resProg
        binding.tvResonanceVal.text = "$resProg%"

        // 1. Orb Speed: 0.2 to 2.5 (progress 0..23)
        val speedProg = ((preferences.orbSpeed - 0.2f) / 0.1f).roundToInt().coerceIn(0, 23)
        binding.sliderOrbSpeed.progress = speedProg
        binding.tvOrbSpeedVal.text = String.format("%.1fx", getCurrentSpeed())

        // 2. Orb Glow: 0.2 to 2.0 (progress 0..18)
        val glowProg = ((preferences.orbGlow - 0.2f) / 0.1f).roundToInt().coerceIn(0, 18)
        binding.sliderOrbGlow.progress = glowProg
        binding.tvOrbGlowVal.text = String.format("%.1fx", getCurrentGlow())

        // 3. Orb Scale: 0.70 to 1.30 (progress 0..12)
        val scaleProg = ((preferences.orbScale - 0.70f) / 0.05f).roundToInt().coerceIn(0, 12)
        binding.sliderOrbScale.progress = scaleProg
        binding.tvOrbScaleVal.text = String.format("%.2fx", getCurrentScale())

        // 4. Widgets
        binding.switchTelemetryWidget.isChecked = preferences.showTelemetryWidget
        binding.switchWeatherWidget.isChecked = preferences.showWeatherWidget
        binding.switchUpdatesWidget.isChecked = preferences.showUpdatesWidget
        binding.switchBlurEffectStudio.isChecked = preferences.isBlurEffectEnabled
        binding.switchHoloScanlines.isChecked = preferences.isHoloScanlinesEnabled

        // 5. Audio & Vosk
        binding.switchHudSounds.isChecked = preferences.isHudSoundEnabled
        binding.switchHapticFeedback.isChecked = preferences.isHapticFeedbackEnabled
        val volProg = (preferences.hudSoundVolume * 100).roundToInt().coerceIn(0, 100)
        binding.sliderHudVolume.progress = volProg
        binding.tvHudVolumeVal.text = "$volProg%"

        val voskProg = ((preferences.voskWakeWordSensitivity - 0.30f) / 0.05f).roundToInt().coerceIn(0, 13)
        binding.sliderVoskSensitivity.progress = voskProg
        binding.tvVoskVal.text = String.format("%.2f", getCurrentVosk())

        // 6. Resonance & Audio Reactive
        selectedResonance = preferences.arcCorePulseMode
        binding.switchAudioReactiveOrb.isChecked = preferences.isAudioReactiveOrb

        // 7. Voice Persona & Assistant Name
        selectedPersona = when {
            preferences.isJarvisCompanionMode || preferences.personality.equals(com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_COMPANION, ignoreCase = true) || preferences.assistantName.equals("JARVIS", ignoreCase = true) -> "JARVIS"
            preferences.voice.equals("Aoede", ignoreCase = true) -> "friday"
            preferences.voice.equals("Charon", ignoreCase = true) -> "edith"
            else -> "jarvis"
        }

        binding.etStudioAssistantName.setText(preferences.assistantName)
        selectedWallpaperMode = preferences.wallpaperMode
        updateWallpaperModeUI(selectedWallpaperMode)

        updatePresetUI(selectedPreset)
        updateThemeUI(selectedTheme)
        updateResonanceUI(selectedResonance)
        updatePersonaUI(selectedPersona)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupPreviewOrb() {
        try {
            binding.previewOrbWebView.apply {
                setBackgroundColor(Color.TRANSPARENT)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        Log.i("PreviewOrbWebView", "${consoleMessage?.messageLevel()}: ${consoleMessage?.message()} -- line ${consoleMessage?.lineNumber()}")
                        return true
                    }
                }

                webViewClient = JarvisWebViewClient(this@CustomizationStudioActivity) { view, url ->
                    isOrbLoaded = true
                    view?.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.resize){ window.JarvisOrb.resize(); }", null)
                    applyPreviewOrbParams()
                    view?.postDelayed({ applyPreviewOrbParams() }, 150)
                }

                addJavascriptInterface(com.jarvis.assistant.util.Jarvis3DModelBridge(this@CustomizationStudioActivity), "Jarvis3DModelBridge")
                loadUrl(JarvisWebViewClient.ORB_URL)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing preview Orb WebView", e)
        }
    }

    private fun applyPreviewOrbParams() {
        if (!isOrbLoaded) return
        try {
            val speed = getCurrentSpeed()
            val glow = getCurrentGlow()
            val scale = getCurrentScale()
            val resonance = getCurrentResonance()
            val autoRotation = if (selectedEntityType == "custom_3d" || selectedEntityType == "ai_girl" || selectedEntityType == "hinata_anime" || selectedEntityType == "hinata_anime") false else binding.switchOrbAutoRotation.isChecked

            val togglesJson = org.json.JSONObject().apply {
                put("core", binding.switchOrbCore.isChecked)
                put("glow", binding.switchOrbGlow.isChecked)
                put("rings", binding.switchOrbRings.isChecked)
                put("ring1", binding.switchOrbRing1.isChecked)
                put("ring2", binding.switchOrbRing2.isChecked)
                put("ring3", binding.switchOrbRing3.isChecked)
                put("orbitDust", binding.switchOrbOrbitDust.isChecked)
                put("shadow", binding.switchOrbShadow.isChecked)
                put("idleBreathing", binding.switchOrbIdleBreathing.isChecked)
                put("touchDrag", binding.switchOrbTouchDrag.isChecked)
                put("audioReactive", binding.switchAudioReactiveOrb.isChecked)
                put("autoRotation", autoRotation)
            }.toString()

            val activeTheme = if (selectedTheme == "custom" || selectedPreset == "custom") {
                if (customColorHex.isNotBlank()) customColorHex else "cyan"
            } else if (selectedPreset.isNotBlank() && selectedPreset != "custom") {
                selectedPreset
            } else if (selectedTheme.isNotBlank() && selectedTheme != "custom") {
                selectedTheme
            } else {
                "dark"
            }

            val customLoadScript = if (selectedEntityType == "custom_3d" || selectedEntityType == "ai_girl" || selectedEntityType == "hinata_anime") {
                "window.JarvisOrb.loadCustomPhoto(); window.JarvisOrb.loadCustom3DModel(); "
            } else {
                ""
            }

            binding.previewOrbWebView.evaluateJavascript(
                "if(window.JarvisOrb){ " +
                        "window.JarvisOrb.resize(); " +
                        "window.JarvisOrb.setEntityType('$selectedEntityType'); " +
                        customLoadScript +
                        "window.JarvisOrb.setComponentToggles($togglesJson); " +
                        "window.JarvisOrb.setAutoRotation($autoRotation); " +
                        "window.JarvisOrb.setTheme('$activeTheme', '$customColorHex'); " +
                        "window.JarvisOrb.setPreset('$activeTheme', '$customColorHex'); " +
                        "window.JarvisOrb.setCompanionFleet($companionCount, '$companionType'); " +
                        "window.JarvisOrb.setSpeed($speed); " +
                        "window.JarvisOrb.setGlow($glow); " +
                        "window.JarvisOrb.setScale($scale); " +
                        "window.JarvisOrb.setResonance($resonance); " +
                        "window.JarvisOrb.setState('idle'); }",
                null
            )

            if (selectedEntityType == "custom_3d") {
                binding.layoutCustom3dMediaControls.visibility = View.VISIBLE
                refreshCustomMediaStatusUI()
            } else {
                binding.layoutCustom3dMediaControls.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying params to Orb", e)
        }
    }

    private fun setupTabNavigation() {
        val tabs = listOf(
            Triple(binding.tabChipOrb, binding.containerTabOrb, "ORB"),
            Triple(binding.tabChipLiveAi, binding.containerTabLiveAi, "LIVE_AI"),
            Triple(binding.tabChipArc, binding.containerTabArc, "ARC"),
            Triple(binding.tabChipTheme, binding.containerTabTheme, "THEME"),
            Triple(binding.tabChipWidgets, binding.containerTabWidgets, "WIDGETS"),
            Triple(binding.tabChipAudio, binding.containerTabAudio, "AUDIO")
        )

        fun selectTab(selectedIdx: Int, playAudio: Boolean = true) {
            if (playAudio) HudSoundManager.playTapSound()
            tabs.forEachIndexed { index, (chip, container, _) ->
                if (index == selectedIdx) {
                    chip.setBackgroundResource(R.drawable.bg_jarvis_chip)
                    chip.setTextColor(ContextCompat.getColor(this, R.color.accent_sapphire))
                    container.visibility = View.VISIBLE
                } else {
                    chip.setBackgroundResource(R.drawable.bg_jarvis_row_item)
                    chip.setTextColor(Color.parseColor("#94A3B8"))
                    container.visibility = View.GONE
                }
            }

            when (selectedIdx) {
                0 -> {
                    selectedEntityType = "orb"
                    com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this@CustomizationStudioActivity, com.jarvis.assistant.util.ModelVaultManager.BUILTIN_ORB.id)
                    updateEntityTypeUI("orb")
                    applyPreviewOrbParams()
                }
                1 -> {
                    val activeItem = com.jarvis.assistant.util.ModelVaultManager.getActiveVaultItem(this@CustomizationStudioActivity)
                    selectedEntityType = if (activeItem.id == com.jarvis.assistant.util.ModelVaultManager.BUILTIN_ORB.id || activeItem.id == com.jarvis.assistant.util.ModelVaultManager.BUILTIN_ARC_REACTOR.id) {
                        "ai_girl"
                    } else {
                        activeItem.category
                    }
                    updateEntityTypeUI(selectedEntityType)
                    populateVaultCardsUI()
                    applyPreviewOrbParams()
                }
                2 -> {
                    selectedEntityType = "arc_reactor"
                    com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this@CustomizationStudioActivity, com.jarvis.assistant.util.ModelVaultManager.BUILTIN_ARC_REACTOR.id)
                    updateEntityTypeUI("arc_reactor")
                    applyPreviewOrbParams()
                }
            }
        }

        binding.tabChipOrb.setOnClickListener { selectTab(0) }
        binding.tabChipLiveAi.setOnClickListener { selectTab(1) }
        binding.tabChipArc.setOnClickListener { selectTab(2) }
        binding.tabChipTheme.setOnClickListener { selectTab(3) }
        binding.tabChipWidgets.setOnClickListener { selectTab(4) }
        binding.tabChipAudio.setOnClickListener { selectTab(5) }

        val initTabIdx = when (selectedEntityType) {
            "hinata_anime", "ai_girl", "custom_3d" -> 1
            "arc_reactor" -> 2
            else -> 0
        }
        selectTab(initTabIdx, playAudio = false)
    }

    private fun setupOrbTabControls() {
        val presets = mapOf(
            binding.presetCyan to "cyan",
            binding.presetCrimson to "crimson",
            binding.presetViolet to "violet",
            binding.presetEmerald to "emerald",
            binding.presetWhite to "white",
            binding.presetSunset to "sunset",
            binding.presetStealth to "stealth",
            binding.presetLight to "light"
        )

        presets.forEach { (view, presetKey) ->
            view.setOnClickListener {
                HudSoundManager.playTapSound()
                selectedPreset = presetKey
                selectedTheme = presetKey
                customColorHex = ""
                updatePresetUI(presetKey)
                updateThemeUI(presetKey)
                applyPreviewOrbParams()
            }
        }

        // Custom Hex Color & Swatches
        binding.btnApplyCustomColor.setOnClickListener {
            HudSoundManager.playTapSound()
            val hex = binding.etCustomColorHex.text?.toString()?.trim() ?: ""
            if (hex.matches(Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$"))) {
                customColorHex = hex
                selectedTheme = when (hex.uppercase()) {
                    "#F59E0B" -> "gold"
                    "#10B981", "#84CC16" -> "emerald"
                    "#EC4899" -> "pink"
                    "#FF7700" -> "orange"
                    "#6366F1" -> "indigo"
                    "#FF4D5E" -> "red"
                    else -> "custom"
                }
                selectedPreset = "custom"
                updatePresetUI("custom")
                applyPreviewOrbParams()
                binding.customizationStudioRoot.setBackgroundColor(ThemeManager.getThemeBgColor(selectedTheme, customColorHex))
                Toast.makeText(this, "Theme color $hex applied!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Enter valid hex format (e.g. #00FFEE)", Toast.LENGTH_SHORT).show()
            }
        }

        val swatches = mapOf(
            binding.swatchGold to Pair("#F59E0B", "gold"),
            binding.swatchPink to Pair("#EC4899", "pink"),
            binding.swatchLime to Pair("#10B981", "emerald"),
            binding.swatchOrange to Pair("#FF7700", "orange"),
            binding.swatchIndigo to Pair("#6366F1", "indigo")
        )
        swatches.forEach { (view, pair) ->
            view.setOnClickListener {
                HudSoundManager.playTapSound()
                binding.etCustomColorHex.setText(pair.first)
                customColorHex = pair.first
                selectedTheme = pair.second
                selectedPreset = "custom"
                updatePresetUI("custom")
                applyPreviewOrbParams()
                binding.customizationStudioRoot.setBackgroundColor(ThemeManager.getThemeBgColor(selectedTheme, customColorHex))
            }
        }

        // Custom 3D Avatar & Model Controls
        binding.btnStudioUpload3DModel.setOnClickListener {
            HudSoundManager.playTapSound()
            pickStudioMediaLauncher.launch("*/*")
        }
        binding.btnStudioUploadMedia.setOnClickListener {
            HudSoundManager.playTapSound()
            pickStudioMediaLauncher.launch("*/*")
        }
        binding.btnSwitchToDefaultOrb.setOnClickListener {
            HudSoundManager.playTapSound()
            com.jarvis.assistant.util.CustomAvatarHelper.switchToDefaultOrb(this)
            selectedEntityType = "orb"
            updateEntityTypeUI("orb")
            binding.previewOrbWebView.evaluateJavascript(
                "if(window.JarvisOrb){ window.JarvisOrb.switchToDefaultOrb(); }",
                null
            )
            applyPreviewOrbParams()
            refreshCustomMediaStatusUI()
            renderSavedModelsList()
            populateVaultCardsUI()
            Toast.makeText(this, "Default Quantum Arc Reactor Orb activated!", Toast.LENGTH_SHORT).show()
        }
        binding.btnStudioDeleteMedia.setOnClickListener {
            HudSoundManager.playTapSound()
            com.jarvis.assistant.util.CustomAvatarHelper.deleteCustomAvatar(this)
            selectedEntityType = "orb"
            updateEntityTypeUI("orb")
            binding.previewOrbWebView.evaluateJavascript(
                "if(window.JarvisOrb){ window.JarvisOrb.clearCustom3DModel(); window.JarvisOrb.switchToDefaultOrb(); }",
                null
            )
            applyPreviewOrbParams()
            refreshCustomMediaStatusUI()
            renderSavedModelsList()
            populateVaultCardsUI()
            Toast.makeText(this, "All custom models removed. Default Orb restored.", Toast.LENGTH_SHORT).show()
        }

        binding.btnVaultImport3D.setOnClickListener {
            HudSoundManager.playTapSound()
            pickStudioMediaLauncher.launch("*/*")
        }
        binding.btnVaultImportMedia.setOnClickListener {
            HudSoundManager.playTapSound()
            pickStudioMediaLauncher.launch("image/*")
        }

        // Companion Fleet / Multi-Orb Controls
        binding.btnCompanionMinus.setOnClickListener {
            HudSoundManager.playTapSound()
            if (companionCount > 0) {
                companionCount--
                updateCompanionUI()
                applyPreviewOrbParams()
            }
        }
        binding.btnCompanionPlus.setOnClickListener {
            HudSoundManager.playTapSound()
            if (companionCount < 4) {
                companionCount++
                updateCompanionUI()
                applyPreviewOrbParams()
            }
        }
        binding.btnCompanionOrb.setOnClickListener {
            HudSoundManager.playTapSound()
            companionType = "orb"
            updateCompanionUI()
            applyPreviewOrbParams()
        }
        binding.btnCompanionDrone.setOnClickListener {
            HudSoundManager.playTapSound()
            companionType = "drone"
            updateCompanionUI()
            applyPreviewOrbParams()
        }
        binding.btnCompanionWisp.setOnClickListener {
            HudSoundManager.playTapSound()
            companionType = "wisp"
            updateCompanionUI()
            applyPreviewOrbParams()
        }

        binding.sliderCoreResonance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvResonanceVal.text = "$progress%"
                if (fromUser) applyPreviewOrbParams()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.sliderOrbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvOrbSpeedVal.text = String.format("%.1fx", getCurrentSpeed())
                if (fromUser) applyPreviewOrbParams()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.sliderOrbGlow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvOrbGlowVal.text = String.format("%.1fx", getCurrentGlow())
                if (fromUser) applyPreviewOrbParams()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.sliderOrbScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvOrbScaleVal.text = String.format("%.2fx", getCurrentScale())
                if (fromUser) applyPreviewOrbParams()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.btnPulseQuantum.setOnClickListener {
            HudSoundManager.playTapSound()
            selectedResonance = "quantum"
            updateResonanceUI("quantum")
        }
        binding.btnPulseOverdrive.setOnClickListener {
            HudSoundManager.playTapSound()
            selectedResonance = "overdrive"
            updateResonanceUI("overdrive")
        }
        binding.btnPulseStealth.setOnClickListener {
            HudSoundManager.playTapSound()
            selectedResonance = "stealth"
            updateResonanceUI("stealth")
        }
        binding.switchAudioReactiveOrb.setOnCheckedChangeListener { _, _ ->
            HudSoundManager.playTapSound()
            applyPreviewOrbParams()
        }

        // Entity Form Click Listeners
        binding.btnEntityOrb.setOnClickListener {
            HudSoundManager.playTapSound()
            selectedEntityType = "orb"
            com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this, com.jarvis.assistant.util.ModelVaultManager.BUILTIN_ORB.id)
            updateEntityTypeUI("orb")
            populateVaultCardsUI()
            applyPreviewOrbParams()
        }
        binding.btnEntityAiGirl.setOnClickListener {
            HudSoundManager.playTapSound()
            selectedEntityType = "ai_girl"
            com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this, com.jarvis.assistant.util.ModelVaultManager.BUILTIN_LIVE_COMPANION.id)
            updateEntityTypeUI("ai_girl")
            populateVaultCardsUI()
            applyPreviewOrbParams()
        }
        binding.btnEntityArcReactor.setOnClickListener {
            HudSoundManager.playTapSound()
            selectedEntityType = "arc_reactor"
            com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this, com.jarvis.assistant.util.ModelVaultManager.BUILTIN_ARC_REACTOR.id)
            updateEntityTypeUI("arc_reactor")
            populateVaultCardsUI()
            applyPreviewOrbParams()
        }
        binding.btnEntityCustom3d.setOnClickListener {
            HudSoundManager.playTapSound()
            selectedEntityType = "custom_3d"
            val custom = com.jarvis.assistant.util.ModelVaultManager.getVaultItems(this).firstOrNull { !it.isBuiltin }
            if (custom != null) {
                com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this, custom.id)
            }
            updateEntityTypeUI("custom_3d")
            populateVaultCardsUI()
            applyPreviewOrbParams()
        }

        // Granular Component Toggles Listeners
        val toggleSwitches = listOf(
            binding.switchOrbCore,
            binding.switchOrbGlow,
            binding.switchOrbRings,
            binding.switchOrbRing1,
            binding.switchOrbRing2,
            binding.switchOrbRing3,
            binding.switchOrbOrbitDust,
            binding.switchOrbShadow,
            binding.switchOrbIdleBreathing,
            binding.switchOrbTouchDrag,
            binding.switchOrbCinematic50,
            binding.switchOrbAutoRotation
        )
        toggleSwitches.forEach { switch ->
            switch.setOnCheckedChangeListener { _, _ ->
                HudSoundManager.playTapSound()
                applyPreviewOrbParams()
            }
        }
    }

    private fun updateEntityTypeUI(entity: String) {
        val entities = listOf(
            binding.btnEntityOrb to "orb",
            binding.btnEntityAiGirl to "ai_girl",
            binding.btnEntityArcReactor to "arc_reactor",
            binding.btnEntityCustom3d to "custom_3d"
        )
        entities.forEach { (view, key) ->
            if (key == entity) {
                view.setBackgroundResource(R.drawable.bg_jarvis_chip)
                view.setTextColor(ContextCompat.getColor(this, R.color.accent_sapphire))
            } else {
                view.setBackgroundResource(R.drawable.bg_jarvis_row_item)
                view.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
        binding.tvPreviewPresetName.text = when(entity) {
            "mora_cyber" -> "ENTITY: MORA (REDMAGIC CYBER COMPANION)"
            "hinata_anime" -> "ENTITY: HINATA (ANIME COMPANION)"
            "ai_girl" -> "ENTITY: JETT (3D FALLBACK)"
            "arc_reactor" -> "ENTITY: ARC REACTOR"
            "custom_3d" -> "ENTITY: 3D MODEL & COMPANION"
            else -> "CORE: ${selectedPreset.uppercase()}"
        }
        binding.layoutCustom3dMediaControls.visibility =
            if (entity == "custom_3d") View.VISIBLE else View.GONE
    }

    private fun updateResonanceUI(mode: String) {
        val chips = listOf(
            binding.btnPulseQuantum to "quantum",
            binding.btnPulseOverdrive to "overdrive",
            binding.btnPulseStealth to "stealth"
        )
        chips.forEach { (view, key) ->
            if (key == mode) {
                view.setBackgroundResource(R.drawable.bg_jarvis_chip)
                view.setTextColor(ContextCompat.getColor(this, R.color.accent_sapphire))
            } else {
                view.setBackgroundResource(R.drawable.bg_jarvis_row_item)
                view.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    private fun updatePresetUI(activeKey: String) {
        val presetViews = listOf(
            binding.presetCyan to "cyan",
            binding.presetCrimson to "crimson",
            binding.presetViolet to "violet",
            binding.presetEmerald to "emerald",
            binding.presetWhite to "white",
            binding.presetSunset to "sunset",
            binding.presetStealth to "stealth",
            binding.presetLight to "light"
        )

        presetViews.forEach { (view, key) ->
            if (key == activeKey) {
                view.setBackgroundResource(R.drawable.bg_jarvis_chip)
            } else {
                view.setBackgroundResource(R.drawable.bg_jarvis_row_item)
            }
        }

        binding.tvPreviewPresetName.text = "CORE: ${activeKey.uppercase()}"
    }

    private fun updateCompanionUI() {
        binding.tvCompanionCount.text = "$companionCount"
        binding.tvCompanionDescription.text = when (companionCount) {
            0 -> "0 / 4 (Solo central entity)"
            1 -> "1 / 4 (Single orbiting companion)"
            2 -> "2 / 4 (Twin binary orbits)"
            3 -> "3 / 4 (Tri-core orbital triad)"
            else -> "4 / 4 (Full orbital swarm fleet)"
        }

        val styles = listOf(
            binding.btnCompanionOrb to "orb",
            binding.btnCompanionDrone to "drone",
            binding.btnCompanionWisp to "wisp"
        )
        styles.forEach { (btn, type) ->
            if (type == companionType) {
                btn.setBackgroundResource(R.drawable.bg_jarvis_chip)
                btn.setTextColor(ContextCompat.getColor(this, R.color.accent_sapphire))
            } else {
                btn.setBackgroundResource(R.drawable.bg_jarvis_row_item)
                btn.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    private fun setupThemeTabControls() {
        val themeCards = mapOf(
            binding.cardThemeNavy to "dark",
            binding.cardThemeStealth to "stealth",
            binding.cardThemeRed to "red",
            binding.cardThemeAmethyst to "amethyst",
            binding.cardThemeLight to "light"
        )

        themeCards.forEach { (card, themeKey) ->
            card.setOnClickListener {
                HudSoundManager.playTapSound()
                selectedTheme = themeKey
                selectedPreset = themeKey
                customColorHex = ""
                updateThemeUI(themeKey)
                updatePresetUI(themeKey)
                applyPreviewOrbParams()
            }
        }
    }

    private fun updateThemeUI(activeKey: String) {
        val cards = listOf(
            binding.cardThemeNavy to "dark",
            binding.cardThemeStealth to "stealth",
            binding.cardThemeRed to "red",
            binding.cardThemeAmethyst to "amethyst",
            binding.cardThemeLight to "light"
        )

        cards.forEach { (card, key) ->
            if (key == activeKey) {
                card.setBackgroundResource(R.drawable.bg_jarvis_chip)
            } else {
                card.setBackgroundResource(R.drawable.bg_jarvis_row_item)
            }
        }
    }

    private fun setupWidgetTabControls() {
        binding.switchTelemetryWidget.setOnCheckedChangeListener { _, _ -> HudSoundManager.playTapSound() }
        binding.switchWeatherWidget.setOnCheckedChangeListener { _, _ -> HudSoundManager.playTapSound() }
        binding.switchUpdatesWidget.setOnCheckedChangeListener { _, _ -> HudSoundManager.playTapSound() }
        binding.switchBlurEffectStudio.setOnCheckedChangeListener { _, _ -> HudSoundManager.playTapSound() }
        binding.switchHoloScanlines.setOnCheckedChangeListener { _, _ -> HudSoundManager.playTapSound() }
    }

    private fun setupAudioTabControls() {
        binding.switchHudSounds.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) HudSoundManager.playSuccessSound()
        }

        binding.sliderHudVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvHudVolumeVal.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.switchHapticFeedback.setOnCheckedChangeListener { _, _ ->
            HudSoundManager.triggerHapticClick()
        }

        binding.btnTestSound.setOnClickListener {
            HudSoundManager.playSuccessSound()
        }

        binding.sliderVoskSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvVoskVal.text = String.format("%.2f", getCurrentVosk())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        // AI Voice Persona Selection
        binding.cardPersonaJarvisCompanion.setOnClickListener {
            HudSoundManager.playTapSound()
            selectedPersona = "JARVIS"
            binding.etStudioAssistantName.setText("JARVIS")
            updateNameChipUI("JARVIS")
            updatePersonaUI("JARVIS")
        }
        binding.cardPersonaJarvis.setOnClickListener {
            HudSoundManager.playTapSound()
            selectedPersona = "jarvis"
            binding.etStudioAssistantName.setText("JARVIS")
            updateNameChipUI("JARVIS")
            updatePersonaUI("jarvis")
        }
        binding.cardPersonaFriday.setOnClickListener {
            HudSoundManager.playTapSound()
            selectedPersona = "friday"
            binding.etStudioAssistantName.setText("FRIDAY")
            updateNameChipUI("FRIDAY")
            updatePersonaUI("friday")
        }
        binding.cardPersonaEdith.setOnClickListener {
            HudSoundManager.playTapSound()
            selectedPersona = "edith"
            binding.etStudioAssistantName.setText("EDITH")
            updateNameChipUI("EDITH")
            updatePersonaUI("edith")
        }

        // Assistant Name Quick Preset Chips
        binding.btnNameChipJarvis.setOnClickListener {
            HudSoundManager.playTapSound()
            binding.etStudioAssistantName.setText("JARVIS")
            updateNameChipUI("JARVIS")
        }
        binding.btnNameChipJarvisCompanion.setOnClickListener {
            HudSoundManager.playTapSound()
            binding.etStudioAssistantName.setText("JARVIS")
            updateNameChipUI("JARVIS")
            selectedPersona = "JARVIS"
            updatePersonaUI("JARVIS")
        }
        binding.btnNameChipFriday.setOnClickListener {
            HudSoundManager.playTapSound()
            binding.etStudioAssistantName.setText("FRIDAY")
            updateNameChipUI("FRIDAY")
            selectedPersona = "friday"
            updatePersonaUI("friday")
        }

        // Live Wallpaper Mode Selection
        binding.cardWallpaperCharacter.setOnClickListener {
            HudSoundManager.playTapSound()
            selectedWallpaperMode = "character"
            updateWallpaperModeUI("character")
        }
        binding.cardWallpaperArcReactor.setOnClickListener {
            HudSoundManager.playTapSound()
            selectedWallpaperMode = "arc_reactor"
            updateWallpaperModeUI("arc_reactor")
        }
        binding.btnStudioApplyLiveWallpaper.setOnClickListener {
            HudSoundManager.playSuccessSound()
            preferences.wallpaperMode = selectedWallpaperMode
            launchLiveWallpaperPicker()
        }
    }

    private fun updatePersonaUI(persona: String) {
        val cards = listOf(
            binding.cardPersonaJarvisCompanion to "JARVIS",
            binding.cardPersonaJarvis to "jarvis",
            binding.cardPersonaFriday to "friday",
            binding.cardPersonaEdith to "edith"
        )
        cards.forEach { (card, key) ->
            if (key.equals(persona, ignoreCase = true)) {
                card.setBackgroundResource(R.drawable.bg_jarvis_chip)
            } else {
                card.setBackgroundResource(R.drawable.bg_jarvis_row_item)
            }
        }
    }

    private fun updateWallpaperModeUI(mode: String) {
        if (mode.equals("arc_reactor", ignoreCase = true)) {
            binding.cardWallpaperArcReactor.setBackgroundResource(R.drawable.bg_jarvis_chip)
            binding.cardWallpaperCharacter.setBackgroundResource(R.drawable.bg_jarvis_row_item)
        } else {
            binding.cardWallpaperCharacter.setBackgroundResource(R.drawable.bg_jarvis_chip)
            binding.cardWallpaperArcReactor.setBackgroundResource(R.drawable.bg_jarvis_row_item)
        }
    }

    private fun updateNameChipUI(name: String) {
        val upper = name.uppercase()
        val chips = listOf(
            binding.btnNameChipJarvis to "JARVIS",
            binding.btnNameChipJarvisCompanion to "JARVIS",
            binding.btnNameChipFriday to "FRIDAY"
        )
        chips.forEach { (chip, key) ->
            if (key == upper) {
                chip.setBackgroundResource(R.drawable.bg_jarvis_chip)
                chip.setTextColor(ThemeManager.getThemeAccentColor(this))
            } else {
                chip.setBackgroundResource(R.drawable.bg_jarvis_row_item)
                chip.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }
        }
    }

    private fun launchLiveWallpaperPicker() {
        try {
            val intent = android.content.Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    android.content.ComponentName(this@CustomizationStudioActivity, com.jarvis.assistant.wallpaper.JarvisWallpaperService::class.java)
                )
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val chooserIntent = android.content.Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                startActivity(chooserIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Wallpaper chooser not available: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupActionButtons() {
        binding.btnBack.setOnClickListener {
            HudSoundManager.playTapSound()
            finish()
        }

        binding.btnResetDefaults.setOnClickListener {
            HudSoundManager.playTapSound()
            selectedPreset = "cyan"
            selectedTheme = "dark"
            selectedResonance = "quantum"
            selectedPersona = "jarvis"
            selectedEntityType = "orb"
            com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this, com.jarvis.assistant.util.ModelVaultManager.BUILTIN_ORB.id)
            updateEntityTypeUI("orb")
            populateVaultCardsUI()

            binding.switchOrbCore.isChecked = true
            binding.switchOrbGlow.isChecked = true
            binding.switchOrbRings.isChecked = true
            binding.switchOrbRing1.isChecked = true
            binding.switchOrbRing2.isChecked = true
            binding.switchOrbRing3.isChecked = true
            binding.switchOrbOrbitDust.isChecked = true
            binding.switchOrbShadow.isChecked = true
            binding.switchOrbIdleBreathing.isChecked = true
            binding.switchOrbTouchDrag.isChecked = true
            binding.switchOrbCinematic50.isChecked = true

            binding.sliderOrbSpeed.progress = 8 // 1.0x
            binding.sliderOrbGlow.progress = 8  // 1.0x
            binding.sliderOrbScale.progress = 6 // 1.0x
            binding.sliderCoreResonance.progress = 84
            binding.tvResonanceVal.text = "84%"
            binding.switchTelemetryWidget.isChecked = true
            binding.switchWeatherWidget.isChecked = true
            binding.switchUpdatesWidget.isChecked = true
            binding.switchBlurEffectStudio.isChecked = true
            binding.switchHoloScanlines.isChecked = true
            binding.switchAudioReactiveOrb.isChecked = true
            binding.switchHudSounds.isChecked = true
            binding.switchHapticFeedback.isChecked = true
            binding.sliderHudVolume.progress = 80
            binding.sliderVoskSensitivity.progress = 7

            binding.tvOrbSpeedVal.text = "1.0x"
            binding.tvOrbGlowVal.text = "1.0x"
            binding.tvOrbScaleVal.text = "1.0x"
            binding.tvHudVolumeVal.text = "80%"
            binding.tvVoskVal.text = "0.65"

            updatePresetUI("cyan")
            updateThemeUI("dark")
            updateResonanceUI("quantum")
            updatePersonaUI("jarvis")
            com.jarvis.assistant.util.CustomAvatarHelper.switchToDefaultOrb(this)
            refreshCustomMediaStatusUI()
            renderSavedModelsList()
            applyPreviewOrbParams()

            Toast.makeText(this, "Configuration reset to defaults", Toast.LENGTH_SHORT).show()
        }

        binding.btnApplyAndSave.setOnClickListener {
            try {
                // Save Orb Settings & Entity Type
                preferences.orbEntityType = selectedEntityType
                val targetVaultItem = com.jarvis.assistant.util.ModelVaultManager.getVaultItems(this).find { it.category == selectedEntityType }
                if (targetVaultItem != null) {
                    com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this, targetVaultItem.id)
                }
                preferences.orbShowCore = binding.switchOrbCore.isChecked
                preferences.orbShowGlow = binding.switchOrbGlow.isChecked
                preferences.orbShowRings = binding.switchOrbRings.isChecked
                preferences.orbShowRing1 = binding.switchOrbRing1.isChecked
                preferences.orbShowRing2 = binding.switchOrbRing2.isChecked
                preferences.orbShowRing3 = binding.switchOrbRing3.isChecked
                preferences.orbShowOrbitDust = binding.switchOrbOrbitDust.isChecked
                preferences.orbShowShadow = binding.switchOrbShadow.isChecked
                preferences.orbShowIdleBreathing = binding.switchOrbIdleBreathing.isChecked
                preferences.orbTouchDragEnabled = binding.switchOrbTouchDrag.isChecked
                preferences.isOrbCinematic50 = binding.switchOrbCinematic50.isChecked
                preferences.orbAutoRotationEnabled = binding.switchOrbAutoRotation.isChecked
                preferences.orbCompanionCount = companionCount
                preferences.orbCompanionType = companionType
                preferences.customThemeColor = customColorHex

                preferences.orbCorePreset = selectedPreset
                preferences.coreResonanceLevel = getCurrentResonance()
                preferences.orbSpeed = getCurrentSpeed()
                preferences.orbGlow = getCurrentGlow()
                preferences.orbScale = getCurrentScale()
                preferences.arcCorePulseMode = selectedResonance
                preferences.isAudioReactiveOrb = binding.switchAudioReactiveOrb.isChecked

                // Save Theme
                preferences.appTheme = selectedTheme

                // Save Widgets & HUD Overlays
                preferences.showTelemetryWidget = binding.switchTelemetryWidget.isChecked
                preferences.showWeatherWidget = binding.switchWeatherWidget.isChecked
                preferences.showUpdatesWidget = binding.switchUpdatesWidget.isChecked
                preferences.isBlurEffectEnabled = binding.switchBlurEffectStudio.isChecked
                preferences.isHoloScanlinesEnabled = binding.switchHoloScanlines.isChecked

                // Save Audio & Vosk
                preferences.isHudSoundEnabled = binding.switchHudSounds.isChecked
                preferences.isHapticFeedbackEnabled = binding.switchHapticFeedback.isChecked
                preferences.hudSoundVolume = getCurrentVolume()
                preferences.voskWakeWordSensitivity = getCurrentVosk()

                // Save Assistant Name & Live Wallpaper Mode
                val customName = binding.etStudioAssistantName.text?.toString()?.trim() ?: ""
                if (customName.isNotEmpty()) {
                    preferences.assistantName = customName
                }
                preferences.wallpaperMode = selectedWallpaperMode

                // Save AI Voice Persona
                when (selectedPersona) {
                    "JARVIS" -> {
                        preferences.voice = "Aoede"
                        preferences.personality = com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_COMPANION
                        if (customName.isEmpty()) {
                            preferences.assistantName = "JARVIS"
                        }
                    }
                    "friday" -> {
                        preferences.voice = "Aoede"
                        preferences.personality = com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_GIRLFRIEND
                    }
                    "edith" -> {
                        preferences.voice = "Charon"
                        preferences.personality = com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_ASSISTANT
                    }
                    else -> {
                        preferences.voice = "Puck"
                        preferences.personality = com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_ROSHAN
                    }
                }

                ThemeManager.applyTheme(this)
                HudSoundManager.playSuccessSound()
                Toast.makeText(this, "Stark HUD Configuration Applied!", Toast.LENGTH_SHORT).show()

                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving customization settings", e)
                Toast.makeText(this, "Save error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderSavedModelsList() {
        val models = com.jarvis.assistant.util.CustomAvatarHelper.getSaved3DModels(this)
        binding.layoutSavedModelsList.removeAllViews()

        if (models.isEmpty()) {
            binding.tvNoSavedModels.visibility = View.VISIBLE
            binding.layoutSavedModelsList.addView(binding.tvNoSavedModels)
            return
        }
        binding.tvNoSavedModels.visibility = View.GONE

        models.forEach { model ->
            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * resources.displayMetrics.density).toInt()
                    bottomMargin = (4 * resources.displayMetrics.density).toInt()
                }
                setBackgroundResource(
                    if (model.isActive && selectedEntityType == "custom_3d")
                        R.drawable.bg_jarvis_chip
                    else
                        R.drawable.bg_jarvis_row_item
                )
                setPadding(
                    (12 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt()
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // Info container
            val infoLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }

            val titleText = android.widget.TextView(this).apply {
                text = "${model.name} [${model.format.uppercase()}]"
                textSize = 12f
                setTextColor(
                    if (model.isActive && selectedEntityType == "custom_3d")
                        ContextCompat.getColor(this@CustomizationStudioActivity, R.color.accent_sapphire)
                    else
                        Color.WHITE
                )
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val subtitleText = android.widget.TextView(this).apply {
                val activeBadge = if (model.isActive && selectedEntityType == "custom_3d") "● ACTIVE • " else ""
                text = "$activeBadge${model.formattedSize}"
                textSize = 10.5f
                setTextColor(
                    if (model.isActive && selectedEntityType == "custom_3d")
                        Color.parseColor("#38BDF8")
                    else
                        Color.parseColor("#94A3B8")
                )
            }

            infoLayout.addView(titleText)
            infoLayout.addView(subtitleText)
            card.addView(infoLayout)

            // "USE" Button
            val btnUse = android.widget.TextView(this).apply {
                text = if (model.isActive && selectedEntityType == "custom_3d") "ACTIVE" else "USE"
                textSize = 10.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(
                    if (model.isActive && selectedEntityType == "custom_3d")
                        Color.parseColor("#10B981")
                    else
                        ContextCompat.getColor(this@CustomizationStudioActivity, R.color.accent_sapphire)
                )
                setBackgroundResource(R.drawable.bg_jarvis_chip)
                setPadding(
                    (12 * resources.displayMetrics.density).toInt(),
                    (6 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    (6 * resources.displayMetrics.density).toInt()
                )
                setOnClickListener {
                    HudSoundManager.playTapSound()
                    com.jarvis.assistant.util.CustomAvatarHelper.setActiveModel(this@CustomizationStudioActivity, model.id)
                    selectedEntityType = "custom_3d"
                    updateEntityTypeUI("custom_3d")
                    binding.previewOrbWebView.evaluateJavascript(
                        "if(window.JarvisOrb){ window.JarvisOrb.setEntityType('custom_3d'); window.JarvisOrb.loadCustom3DModel(); }",
                        null
                    )
                    applyPreviewOrbParams()
                    refreshCustomMediaStatusUI()
                    renderSavedModelsList()
                    Toast.makeText(this@CustomizationStudioActivity, "${model.name} activated!", Toast.LENGTH_SHORT).show()
                }
            }
            card.addView(btnUse)

            // "DELETE" Button
            val btnDelete = android.widget.TextView(this).apply {
                text = "✕"
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#EF4444"))
                setPadding(
                    (12 * resources.displayMetrics.density).toInt(),
                    (6 * resources.displayMetrics.density).toInt(),
                    (6 * resources.displayMetrics.density).toInt(),
                    (6 * resources.displayMetrics.density).toInt()
                )
                setOnClickListener {
                    HudSoundManager.playTapSound()
                    com.jarvis.assistant.util.CustomAvatarHelper.deleteModel(this@CustomizationStudioActivity, model.id)
                    if (!com.jarvis.assistant.util.CustomAvatarHelper.hasCustom3dModel(this@CustomizationStudioActivity)) {
                        selectedEntityType = "orb"
                        updateEntityTypeUI("orb")
                        binding.previewOrbWebView.evaluateJavascript(
                            "if(window.JarvisOrb){ window.JarvisOrb.switchToDefaultOrb(); }",
                            null
                        )
                    } else {
                        binding.previewOrbWebView.evaluateJavascript(
                            "if(window.JarvisOrb){ window.JarvisOrb.loadCustom3DModel(); }",
                            null
                        )
                    }
                    applyPreviewOrbParams()
                    refreshCustomMediaStatusUI()
                    renderSavedModelsList()
                    Toast.makeText(this@CustomizationStudioActivity, "Model removed", Toast.LENGTH_SHORT).show()
                }
            }
            card.addView(btnDelete)

            binding.layoutSavedModelsList.addView(card)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            binding.previewOrbWebView.stopLoading()
            (binding.previewOrbWebView.parent as? android.view.ViewGroup)?.removeView(binding.previewOrbWebView)
            binding.previewOrbWebView.destroy()
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "CustomizationStudio"
    }
}
