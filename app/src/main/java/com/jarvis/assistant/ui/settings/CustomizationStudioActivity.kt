package com.jarvis.assistant.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
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
    private var hasUserChangedEntityInStudio = false
    /** Home tab "3D character" switch: flipped by the user this session (wins on Save). */
    private var homeCharacterTouched = false
    /** True while code (not the user) sets the "3D character" switch. */
    private var syncingHomeCharacter = false
    /** True while "Show everything" flips many switches (one tap sound, not sixteen). */
    private var bulkHomeUpdate = false

    // ---- Dark Bento state styling (same look as Media Studio: accent pill when selected) ----
    private fun stylePill(view: TextView, selected: Boolean) {
        view.setBackgroundResource(if (selected) R.drawable.bg_bento_studio_chip_on else R.drawable.bg_bento_studio_chip_off)
        view.setTextColor(
            if (selected) ThemeManager.onAccentColor(this)
            else ThemeManager.attrColor(this, R.attr.themeTextPrimary, Color.WHITE)
        )
    }

    private fun stylePlate(view: View, selected: Boolean) {
        view.setBackgroundResource(if (selected) R.drawable.bg_bento_studio_plate_on else R.drawable.bg_jarvis_row_item)
    }

    /** Puts the Home tab "3D character" switch in line with an explicitly picked entity. */
    private fun syncHomeCharacterSwitch(entity: String) {
        homeCharacterTouched = false
        syncingHomeCharacter = true
        binding.switchCharacter3d.isChecked = entity != "orb" && entity != "arc_reactor"
        syncingHomeCharacter = false
    }

    private val pickStudioMediaLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val (is3D, detectedExt) = com.jarvis.assistant.util.CustomAvatarHelper.is3DModelFile(this, uri)
            if (is3D) {
                val (ok, desc) = com.jarvis.assistant.util.CustomAvatarHelper.processAndSave3DModelUri(this, uri)
                val newItem = com.jarvis.assistant.util.ModelVaultManager.importModelFromUri(this, uri)
                if (ok || newItem != null) {
                    selectedEntityType = "custom_3d"
                    hasUserChangedEntityInStudio = true
                    syncHomeCharacterSwitch("custom_3d")
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
            stylePlate(cardView, isActive)
            tvActive.visibility = if (isActive) View.VISIBLE else View.GONE

            if (!item.isBuiltin) {
                btnDelete.visibility = View.VISIBLE
                btnDelete.setOnClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Delete 3D Model?")
                        .setMessage("Delete '${item.title}' (${item.getFormattedSize()}) from your vault? This cannot be undone.")
                        .setPositiveButton("Delete") { _, _ ->
                            HudSoundManager.playTapSound()
                            com.jarvis.assistant.util.ModelVaultManager.deleteVaultItem(this, item.id)
                            // If device has mora_cyber for home, default Studio to "orb" so user can see & customize the live Core Orb!
        selectedEntityType = if (preferences.orbEntityType == "mora_cyber") "orb" else preferences.orbEntityType
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
                hasUserChangedEntityInStudio = true
                com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this, item.id)
                selectedEntityType = item.category
                syncHomeCharacterSwitch(selectedEntityType)
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
            setupScreenStyleOptions()

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
            setupHomeTabControls()
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
        // If device has mora_cyber for home, default Studio to "orb" so user can see & customize the live Core Orb!
        selectedEntityType = if (preferences.orbEntityType == "mora_cyber") "orb" else preferences.orbEntityType
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
        // 4. Home screen tab (moved here from the old "Home screen" sheet). Same prefs
        //    HomeFragment.applyCustomizationPreferences reads on resume; written on Save.
        binding.switchHideAllCards.isChecked = preferences.hideAllHomeCards
        syncHomeCharacterSwitch(preferences.orbEntityType)
        binding.switchStatusIndicatorWidget.isChecked = preferences.showStatusIndicatorWidget
        binding.switchWeatherWidget.isChecked = preferences.showWeatherWidget
        binding.switchTelemetryWidget.isChecked = preferences.showTelemetryWidget
        binding.swHomeAiDashboard.isChecked = com.jarvis.assistant.androllm.HomeDashboardPrefs.isEnabled(this)
        binding.switchQuickActionsWidget.isChecked = preferences.showQuickActionsWidget
        binding.switchScreenShare.isChecked = preferences.showScreenShareButton
        binding.switchMemories.isChecked = preferences.showMemoriesButton
        binding.switchLanguage.isChecked = preferences.showLanguageButton
        binding.switchProtocolsWidget.isChecked = preferences.showProtocolsWidget
        binding.switchPocketTtsCard.isChecked = preferences.showPocketTtsCard
        binding.switchMatrixCard.isChecked = preferences.showMatrixCard
        binding.switchUpdatesWidget.isChecked = preferences.showUpdatesWidget
        binding.switchUpdateAgent.isChecked = preferences.showUpdateAgentCard
        binding.switchUpdateVoice.isChecked = preferences.showUpdateVoiceCard
        binding.switchUpdateGodsEye.isChecked = preferences.showUpdateGodsEyeCard
        binding.switchBlurEffectStudio.isChecked = preferences.isBlurEffectEnabled

        // 5. Audio & Vosk
        binding.switchHudSounds.isChecked = preferences.isHudSoundEnabled
        binding.switchHapticFeedback.isChecked = preferences.isHapticFeedbackEnabled
        val volProg = (preferences.hudSoundVolume * 100).roundToInt().coerceIn(0, 100)
        binding.sliderHudVolume.progress = volProg
        binding.tvHudVolumeVal.text = "$volProg%"

        // Show what the wake-word engines actually use, so this slider and the
        // Voice section can never disagree.
        val liveSensitivity = com.jarvis.assistant.androllm.voice.VoiceSettingsStore
            .get(applicationContext).state.value.sensitivity
        val voskProg = ((liveSensitivity - 0.30f) / 0.05f).roundToInt().coerceIn(0, 13)
        binding.sliderVoskSensitivity.progress = voskProg
        binding.tvVoskVal.text = String.format("%.2f", getCurrentVosk())

        // 6. Resonance & Audio Reactive
        selectedResonance = preferences.arcCorePulseMode
        binding.switchAudioReactiveOrb.isChecked = preferences.isAudioReactiveOrb

        // 7. Voice Persona & Assistant Name
        // "" = keep whatever persona is set elsewhere (Maria, Pari, Assistant...) unless a card is tapped.
        selectedPersona = when {
            preferences.personality.equals(com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_ROSHAN, ignoreCase = true) -> "jarvis"
            preferences.personality.equals(com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_GIRLFRIEND, ignoreCase = true) && preferences.voice.equals("Aoede", ignoreCase = true) -> "friday"
            preferences.personality.equals(com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_ASSISTANT, ignoreCase = true) && preferences.voice.equals("Charon", ignoreCase = true) -> "edith"
            else -> ""
        }

        binding.etStudioAssistantName.setText(preferences.assistantName)

        updatePresetUI(selectedPreset)
        updateThemeUI(selectedTheme)
        updateResonanceUI(selectedResonance)
        updatePersonaUI(selectedPersona)
        updatePromptTemplateUI("sarcastic_jarvis")
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

                try {
                    android.webkit.WebView.setWebContentsDebuggingEnabled(com.jarvis.assistant.BuildConfig.DEBUG)
                } catch (_: Throwable) {}
                try {
                    resumeTimers()
                    onResume()
                } catch (_: Throwable) {}

                addJavascriptInterface(com.jarvis.assistant.util.Jarvis3DModelBridge(this@CustomizationStudioActivity), "Jarvis3DModelBridge")
                loadUrl(JarvisWebViewClient.ORB_URL)
                com.jarvis.assistant.util.HudAnimationHelper.startBreathingHalo(binding.previewHaloRing)
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
            val autoRotation = if (selectedEntityType == "custom_3d" || selectedEntityType == "ai_girl" || selectedEntityType == "hinata_anime") false else true

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
            Triple(binding.tabChipWidgets, binding.containerTabWidgets, "HOME_SCREEN"),
            Triple(binding.tabChipAudio, binding.containerTabAudio, "AUDIO")
        )

        fun selectTab(selectedIdx: Int, playAudio: Boolean = true) {
            if (playAudio) HudSoundManager.playTapSound()
            tabs.forEachIndexed { index, (chip, container, _) ->
                stylePill(chip, index == selectedIdx)
                container.visibility = if (index == selectedIdx) View.VISIBLE else View.GONE
            }

            // Browsing a tab only switches the live preview; the entity is written on Save.
            // (setActiveVaultItemId writes orbEntityType at once, which used to flip the Home
            // 3D character to the orb just by opening the Studio.)
            when (selectedIdx) {
                0 -> {
                    selectedEntityType = "orb"
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

        val openHomeTab = intent?.getStringExtra(EXTRA_INITIAL_TAB) == TAB_HOME_SCREEN
        val initTabIdx = if (openHomeTab) 4 else when (selectedEntityType) {
            "hinata_anime", "ai_girl", "custom_3d" -> 1
            "arc_reactor" -> 2
            else -> 0
        }
        selectTab(initTabIdx, playAudio = false)
        if (openHomeTab) {
            // Opened from Home / Stark menu "Customize": bring the Home screen tab into view.
            binding.scrollStudio.post { binding.scrollStudio.smoothScrollTo(0, binding.scrollStudioTabs.top) }
        }
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
                selectedTheme = when (presetKey) {
                    "crimson" -> "red"
                    "white" -> "pearl_white"
                    "light" -> "pearl_white"
                    "violet" -> "amethyst"
                    else -> presetKey
                }
                customColorHex = if (presetKey == "crimson") "#FF4D5E" else if (presetKey == "white") "#FFFFFF" else ""
                updatePresetUI(presetKey)
                updateThemeUI(selectedTheme)
                applyPreviewOrbParams()
            }
        }

        // Custom Hex Color & Swatches
        binding.btnApplyCustomColor.setOnClickListener {
            HudSoundManager.playTapSound()
            val hex = binding.etCustomColorHex.text?.toString()?.trim() ?: ""
            if (hex.matches(Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$"))) {
                customColorHex = hex
                val parsed = Color.parseColor(hex)
                val hsv = FloatArray(3)
                Color.colorToHSV(parsed, hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]
                selectedTheme = when {
                    s < 0.15f && v > 0.65f -> "pearl_white"
                    s < 0.15f && v <= 0.65f -> "stealth"
                    (h in 345f..360f || h in 0f..15f) || hex.equals("#FF4D5E", ignoreCase = true) || hex.equals("#FF0000", ignoreCase = true) -> "red"
                    hex.equals("#F59E0B", ignoreCase = true) -> "gold"
                    hex.equals("#10B981", ignoreCase = true) || hex.equals("#84CC16", ignoreCase = true) -> "emerald"
                    hex.equals("#EC4899", ignoreCase = true) -> "pink"
                    hex.equals("#FF7700", ignoreCase = true) -> "orange"
                    hex.equals("#6366F1", ignoreCase = true) -> "indigo"
                    hex.equals("#C084FC", ignoreCase = true) -> "amethyst"
                    h in 16f..45f -> "orange"
                    h in 46f..65f -> "gold"
                    h in 66f..165f -> "emerald"
                    h in 166f..205f -> "cyan"
                    h in 206f..255f -> "indigo"
                    h in 256f..295f -> "amethyst"
                    h in 296f..344f -> "pink"
                    else -> "red"
                }
                selectedPreset = if (selectedTheme == "red") "crimson" else if (selectedTheme == "pearl_white") "white" else "custom"
                updatePresetUI(selectedPreset)
                updateThemeUI(selectedTheme)
                applyPreviewOrbParams()
                binding.customizationStudioRoot.setBackgroundColor(ThemeManager.getThemeBgColor(selectedTheme, customColorHex))
                Toast.makeText(this, "Theme color $hex applied!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Enter valid hex format (e.g. #00FFEE or #FF4D5E)", Toast.LENGTH_SHORT).show()
            }
        }

        val swatches = mapOf(
            binding.swatchRed to Pair("#FF4D5E", "red"),
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
                selectedPreset = if (pair.second == "red") "crimson" else "custom"
                updatePresetUI(selectedPreset)
                updateThemeUI(selectedTheme)
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
            hasUserChangedEntityInStudio = true
            selectedEntityType = "orb"
            com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this, com.jarvis.assistant.util.ModelVaultManager.BUILTIN_ORB.id)
            syncHomeCharacterSwitch("orb")
            updateEntityTypeUI("orb")
            populateVaultCardsUI()
            applyPreviewOrbParams()
        }
        binding.btnEntityAiGirl.setOnClickListener {
            HudSoundManager.playTapSound()
            hasUserChangedEntityInStudio = true
            selectedEntityType = "ai_girl"
            com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this, com.jarvis.assistant.util.ModelVaultManager.BUILTIN_LIVE_COMPANION.id)
            syncHomeCharacterSwitch("ai_girl")
            updateEntityTypeUI("ai_girl")
            populateVaultCardsUI()
            applyPreviewOrbParams()
        }
        binding.btnEntityArcReactor.setOnClickListener {
            HudSoundManager.playTapSound()
            hasUserChangedEntityInStudio = true
            selectedEntityType = "arc_reactor"
            com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this, com.jarvis.assistant.util.ModelVaultManager.BUILTIN_ARC_REACTOR.id)
            syncHomeCharacterSwitch("arc_reactor")
            updateEntityTypeUI("arc_reactor")
            populateVaultCardsUI()
            applyPreviewOrbParams()
        }
        binding.btnEntityCustom3d.setOnClickListener {
            HudSoundManager.playTapSound()
            hasUserChangedEntityInStudio = true
            selectedEntityType = "custom_3d"
            val custom = com.jarvis.assistant.util.ModelVaultManager.getVaultItems(this).firstOrNull { !it.isBuiltin }
            if (custom != null) {
                com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this, custom.id)
            }
            syncHomeCharacterSwitch("custom_3d")
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
        entities.forEach { (view, key) -> stylePill(view, key == entity) }
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
        chips.forEach { (view, key) -> stylePill(view, key == mode) }
    }

    private fun updatePresetUI(activeKey: String) {
        val lowerKey = activeKey.lowercase()
        val presetViews = listOf(
            binding.presetCyan to listOf("cyan"),
            binding.presetCrimson to listOf("crimson", "red", "ruby"),
            binding.presetViolet to listOf("violet", "amethyst"),
            binding.presetEmerald to listOf("emerald"),
            binding.presetWhite to listOf("white", "pearl_white", "light"),
            binding.presetSunset to listOf("sunset"),
            binding.presetStealth to listOf("stealth"),
            binding.presetLight to listOf("light", "pearl_white")
        )

        presetViews.forEach { (view, keys) -> stylePill(view, keys.contains(lowerKey)) }

        val displayLabel = if (lowerKey == "crimson" || lowerKey == "red") "CRIMSON RED" else if (lowerKey == "white") "ARC WHITE" else activeKey.uppercase()
        binding.tvPreviewPresetName.text = "CORE: $displayLabel"
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
        styles.forEach { (btn, type) -> stylePill(btn, type == companionType) }
    }

    private fun setupThemeTabControls() {
        val themeCards = mapOf(
            binding.cardThemeNavy to "dark",
            binding.cardThemeStealth to "stealth",
            binding.cardThemeRed to "red",
            binding.cardThemeAmethyst to "amethyst",
            binding.cardThemeLight to "light",
            binding.cardThemeAndroNight to ThemeManager.THEME_ANDROLLM_NIGHT,
            binding.cardThemeAndroParchment to ThemeManager.THEME_ANDROLLM_PARCHMENT,
            binding.cardThemeMaya to ThemeManager.THEME_MAYA
        )

        themeCards.forEach { (card, themeKey) ->
            card.setOnClickListener {
                HudSoundManager.playTapSound()
                selectedTheme = themeKey
                selectedPreset = when (themeKey) {
                    "red" -> "crimson"
                    "light", "pearl_white" -> "white"
                    "dark" -> "cyan"
                    else -> themeKey
                }
                customColorHex = if (themeKey == "red") "#FF4D5E" else if (themeKey == "light") "#FFFFFF" else if (themeKey == ThemeManager.THEME_MAYA) ThemeManager.MAYA_ACCENT_HEX else ""
                updateThemeUI(themeKey)
                updatePresetUI(selectedPreset)
                applyPreviewOrbParams()
            }
        }
    }

    private fun updateThemeUI(activeKey: String) {
        val lowerKey = activeKey.lowercase()
        val cards = listOf(
            binding.cardThemeNavy to listOf("dark", "cyan"),
            binding.cardThemeStealth to listOf("stealth"),
            binding.cardThemeRed to listOf("red", "crimson", "ruby"),
            binding.cardThemeAmethyst to listOf("amethyst", "violet", "purple"),
            binding.cardThemeLight to listOf("light", "pearl_white", "white"),
            binding.cardThemeAndroNight to listOf(ThemeManager.THEME_ANDROLLM_NIGHT),
            binding.cardThemeAndroParchment to listOf(ThemeManager.THEME_ANDROLLM_PARCHMENT),
            binding.cardThemeMaya to listOf(ThemeManager.THEME_MAYA)
        )

        cards.forEach { (card, keys) -> stylePlate(card, keys.contains(lowerKey)) }
    }

    /** Every card switch on the Home screen tab (one control per pref; written on Save). */
    private fun homeCardSwitches(): List<SwitchCompat> = listOf(
        binding.switchStatusIndicatorWidget,
        binding.switchWeatherWidget,
        binding.switchTelemetryWidget,
        binding.swHomeAiDashboard,
        binding.switchQuickActionsWidget,
        binding.switchScreenShare,
        binding.switchMemories,
        binding.switchLanguage,
        binding.switchProtocolsWidget,
        binding.switchPocketTtsCard,
        binding.switchMatrixCard,
        binding.switchUpdatesWidget,
        binding.switchUpdateAgent,
        binding.switchUpdateVoice,
        binding.switchUpdateGodsEye
    )

    /** Rows tagged "toggleRow" in the layout toggle their switch when tapped anywhere. */
    private fun bindToggleRows(root: View) {
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) bindToggleRows(root.getChildAt(i))
        }
        val row = root.parent as? View ?: return
        if (root is SwitchCompat && row.tag == "toggleRow") {
            row.setOnClickListener { root.toggle() }
        }
    }

    private fun setupHomeTabControls() {
        bindToggleRows(binding.root)

        // 3D character (Home): On = Mora 3D character, Off = arc-reactor orb. Saved with the entity on Save.
        binding.switchCharacter3d.setOnCheckedChangeListener { _, isChecked ->
            if (syncingHomeCharacter) return@setOnCheckedChangeListener
            HudSoundManager.playTapSound()
            homeCharacterTouched = true
            hasUserChangedEntityInStudio = true
            selectedEntityType = if (isChecked) "mora_cyber" else "orb"
            updateEntityTypeUI(selectedEntityType)
            applyPreviewOrbParams()
        }

        (homeCardSwitches() + binding.switchHideAllCards + binding.switchBlurEffectStudio).forEach { sw ->
            sw.setOnCheckedChangeListener { _, _ -> if (!bulkHomeUpdate) HudSoundManager.playTapSound() }
        }

        binding.btnShowAllHomeCards.setOnClickListener {
            HudSoundManager.playTapSound()
            bulkHomeUpdate = true
            binding.switchHideAllCards.isChecked = false
            homeCardSwitches().forEach { it.isChecked = true }
            bulkHomeUpdate = false
            Toast.makeText(this, "Every home card is on. Tap Apply & save to keep it.", Toast.LENGTH_SHORT).show()
        }
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
        binding.btnNameChipFriday.setOnClickListener {
            HudSoundManager.playTapSound()
            binding.etStudioAssistantName.setText("FRIDAY")
            updateNameChipUI("FRIDAY")
            selectedPersona = "friday"
            updatePersonaUI("friday")
        }

        // AndroLLM Prompt Studio Templates
        binding.cardTemplateSarcasticJarvis.setOnClickListener {
            HudSoundManager.playTapSound()
            updatePromptTemplateUI("sarcastic_jarvis")
            selectedPersona = "jarvis"
            binding.etStudioAssistantName.setText("JARVIS")
            updateNameChipUI("JARVIS")
            updatePersonaUI("jarvis")
            Toast.makeText(this, "Template Applied: Sarcastic Tony Stark Jarvis", Toast.LENGTH_SHORT).show()
        }

        binding.cardTemplateMobileArchitect.setOnClickListener {
            HudSoundManager.playTapSound()
            updatePromptTemplateUI("senior_software_engineer")
            selectedPersona = "architect"
            binding.etStudioAssistantName.setText("ARCHITECT")
            Toast.makeText(this, "Template Applied: Senior Mobile Architect", Toast.LENGTH_SHORT).show()
        }

        binding.cardTemplateCodeRefactor.setOnClickListener {
            HudSoundManager.playTapSound()
            updatePromptTemplateUI("refactor_code")
            selectedPersona = "refactor"
            binding.etStudioAssistantName.setText("REFACTOR")
            Toast.makeText(this, "Template Applied: Code Refactoring Studio", Toast.LENGTH_SHORT).show()
        }

        binding.cardTemplateExecutiveSummary.setOnClickListener {
            HudSoundManager.playTapSound()
            updatePromptTemplateUI("summarize_text")
            selectedPersona = "summary"
            binding.etStudioAssistantName.setText("ANALYST")
            Toast.makeText(this, "Template Applied: Executive Summary & TL;DR", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePersonaUI(persona: String) {
        val cards = listOf(
            binding.cardPersonaJarvis to "jarvis",
            binding.cardPersonaFriday to "friday",
            binding.cardPersonaEdith to "edith"
        )
        cards.forEach { (card, key) -> stylePlate(card, key.equals(persona, ignoreCase = true)) }
    }

    private fun updatePromptTemplateUI(activeId: String) {
        val templates = listOf(
            Triple(binding.cardTemplateSarcasticJarvis, binding.badgeTemplateSarcasticJarvis, "sarcastic_jarvis"),
            Triple(binding.cardTemplateMobileArchitect, binding.badgeTemplateMobileArchitect, "senior_software_engineer"),
            Triple(binding.cardTemplateCodeRefactor, binding.badgeTemplateCodeRefactor, "refactor_code"),
            Triple(binding.cardTemplateExecutiveSummary, binding.badgeTemplateExecutiveSummary, "summarize_text")
        )
        templates.forEach { (card, badge, id) ->
            stylePlate(card, id == activeId)
            if (id == activeId) {
                badge.text = "Active"
                badge.setTextColor(ThemeManager.attrColor(this, R.attr.themeSuccess, Color.parseColor("#10B981")))
            } else {
                badge.text = "Apply"
                badge.setTextColor(ThemeManager.attrColor(this, R.attr.themeAccent, ThemeManager.getThemeAccentColor(this)))
            }
        }
    }

    private fun updateNameChipUI(name: String) {
        val upper = name.uppercase()
        val chips = listOf(
            binding.btnNameChipJarvis to "JARVIS",
            binding.btnNameChipFriday to "FRIDAY"
        )
        chips.forEach { (chip, key) -> stylePill(chip, key == upper) }
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
            binding.switchAudioReactiveOrb.isChecked = true
            binding.switchHudSounds.isChecked = true
            binding.switchHapticFeedback.isChecked = true
            binding.sliderHudVolume.progress = 80
            binding.sliderVoskSensitivity.progress = 7

            binding.tvOrbSpeedVal.text = "1.0x"
            binding.tvOrbGlowVal.text = "1.0x"
            binding.tvOrbScaleVal.text = "1.00x"
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
                // Home tab "3D character" switch flipped this session: it decides the Home entity.
                if (homeCharacterTouched) {
                    selectedEntityType = when {
                        !binding.switchCharacter3d.isChecked -> "orb"
                        selectedEntityType != "orb" && selectedEntityType != "arc_reactor" -> selectedEntityType
                        else -> "mora_cyber"
                    }
                    hasUserChangedEntityInStudio = true
                }
                // Save Orb Settings & Entity Type
                if (hasUserChangedEntityInStudio || preferences.orbEntityType != "mora_cyber") {
                    preferences.userExplicitlyChoseEntity = true
                    preferences.orbEntityType = selectedEntityType
                    val targetVaultItem = com.jarvis.assistant.util.ModelVaultManager.getVaultItems(this).find { it.category == selectedEntityType }
                    if (targetVaultItem != null) {
                        com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(this, targetVaultItem.id)
                    }
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
                // Save Home screen tab (HomeFragment re-reads these in onResume)
                preferences.hideAllHomeCards = binding.switchHideAllCards.isChecked
                preferences.showStatusIndicatorWidget = binding.switchStatusIndicatorWidget.isChecked
                preferences.showWeatherWidget = binding.switchWeatherWidget.isChecked
                preferences.showTelemetryWidget = binding.switchTelemetryWidget.isChecked
                com.jarvis.assistant.androllm.HomeDashboardPrefs.setEnabled(this, binding.swHomeAiDashboard.isChecked)
                preferences.showQuickActionsWidget = binding.switchQuickActionsWidget.isChecked
                preferences.showScreenShareButton = binding.switchScreenShare.isChecked
                preferences.showMemoriesButton = binding.switchMemories.isChecked
                preferences.showLanguageButton = binding.switchLanguage.isChecked
                preferences.showProtocolsWidget = binding.switchProtocolsWidget.isChecked
                preferences.showPocketTtsCard = binding.switchPocketTtsCard.isChecked
                preferences.showMatrixCard = binding.switchMatrixCard.isChecked
                preferences.showUpdatesWidget = binding.switchUpdatesWidget.isChecked
                preferences.showUpdateAgentCard = binding.switchUpdateAgent.isChecked
                preferences.showUpdateVoiceCard = binding.switchUpdateVoice.isChecked
                preferences.showUpdateGodsEyeCard = binding.switchUpdateGodsEye.isChecked
                preferences.isBlurEffectEnabled = binding.switchBlurEffectStudio.isChecked

                // Save Audio & Vosk
                preferences.isHudSoundEnabled = binding.switchHudSounds.isChecked
                preferences.isHapticFeedbackEnabled = binding.switchHapticFeedback.isChecked
                preferences.hudSoundVolume = getCurrentVolume()
                // The wake-word engines read VoiceSettings.sensitivity (VoiceEngines.kt),
                // so the slider has to land there or it changes nothing.
                val voskSensitivity = getCurrentVosk()
                preferences.voskWakeWordSensitivity = voskSensitivity
                lifecycleScope.launch {
                    com.jarvis.assistant.androllm.voice.VoiceSettingsStore.get(applicationContext)
                        .update { it.copy(sensitivity = voskSensitivity) }
                }

                // Save Assistant Name
                val customName = binding.etStudioAssistantName.text?.toString()?.trim() ?: ""
                if (customName.isNotEmpty()) {
                    preferences.assistantName = customName
                }

                // Save AI Voice Persona
                when (selectedPersona) {
                    "friday" -> {
                        preferences.voice = "Aoede"
                        preferences.personality = com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_GIRLFRIEND
                    }
                    "edith" -> {
                        preferences.voice = "Charon"
                        preferences.personality = com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_ASSISTANT
                    }
                    "jarvis" -> {
                        preferences.voice = "Puck"
                        preferences.personality = com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_ROSHAN
                    }
                    else -> Unit // no persona card picked: keep the current persona & voice
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
                stylePlate(this, model.isActive && selectedEntityType == "custom_3d")
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
                setTextColor(ThemeManager.attrColor(this@CustomizationStudioActivity, R.attr.themeTextPrimary, Color.WHITE))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val subtitleText = android.widget.TextView(this).apply {
                val activeBadge = if (model.isActive && selectedEntityType == "custom_3d") "● ACTIVE • " else ""
                text = "$activeBadge${model.formattedSize}"
                textSize = 10.5f
                setTextColor(
                    ThemeManager.attrColor(
                        this@CustomizationStudioActivity,
                        if (model.isActive && selectedEntityType == "custom_3d") R.attr.themeAccent else R.attr.themeTextMuted,
                        Color.GRAY
                    )
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
                    ThemeManager.attrColor(
                        this@CustomizationStudioActivity,
                        if (model.isActive && selectedEntityType == "custom_3d") R.attr.themeSuccess else R.attr.themeAccent,
                        Color.GRAY
                    )
                )
                setBackgroundResource(R.drawable.bg_bento_studio_chip_off)
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
                    hasUserChangedEntityInStudio = true
                    syncHomeCharacterSwitch("custom_3d")
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
                setTextColor(ThemeManager.attrColor(this@CustomizationStudioActivity, R.attr.themeDanger, Color.parseColor("#EF4444")))
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

    override fun onResume() {
        super.onResume()
        try {
            binding.previewOrbWebView.onResume()
            binding.previewOrbWebView.resumeTimers()
            applyPreviewOrbParams()
        } catch (_: Throwable) {}
    }

    override fun onPause() {
        super.onPause()
        try {
            binding.previewOrbWebView.onPause()
        } catch (_: Throwable) {}
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

        /** Intent extra: which tab to open on (currently only [TAB_HOME_SCREEN]). */
        const val EXTRA_INITIAL_TAB = "studio_initial_tab"
        const val TAB_HOME_SCREEN = "home_screen"

        /** Studio opened on its Home screen tab (cards, 3D character, HUD size, compact stage). */
        fun homeScreenIntent(context: Context): Intent =
            Intent(context, CustomizationStudioActivity::class.java).putExtra(EXTRA_INITIAL_TAB, TAB_HOME_SCREEN)
    }

    /** Home/Chat tab style: Jarvis classic (default) or the AndroLLM screen inside the same tab. */
    private fun setupScreenStyleOptions() {
        val style = com.jarvis.assistant.androllm.AndroLlmUiStyle
        binding.rgHomeStyle.check(
            if (style.homeStyle(this) == style.ANDROLLM) R.id.rbHomeStyleAndroLlm else R.id.rbHomeStyleJarvis
        )
        binding.rgChatStyle.check(
            if (style.chatStyle(this) == style.ANDROLLM) R.id.rbChatStyleAndroLlm else R.id.rbChatStyleJarvis
        )
        binding.rgHomeStyle.setOnCheckedChangeListener { _, id ->
            style.setHomeStyle(this, if (id == R.id.rbHomeStyleAndroLlm) style.ANDROLLM else style.JARVIS)
        }
        binding.rgChatStyle.setOnCheckedChangeListener { _, id ->
            style.setChatStyle(this, if (id == R.id.rbChatStyleAndroLlm) style.ANDROLLM else style.JARVIS)
        }
        // swHomeAiDashboard now lives on the Home screen tab (loaded in loadInitialPreferences, saved on Save).
        binding.swShowAiHubTab.isChecked = style.showAiHubTab(this)
        binding.swShowAiHubTab.setOnCheckedChangeListener { _, checked -> style.setShowAiHubTab(this, checked) }
        binding.swShowAndroSplash.isChecked = style.showSplash(this)
        binding.swShowAndroSplash.setOnCheckedChangeListener { _, checked -> style.setShowSplash(this, checked) }
    }
}
