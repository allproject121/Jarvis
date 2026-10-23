package com.jarvis.assistant.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.databinding.FragmentHomeBinding
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.util.LocationWeatherHelper
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.DeviceTelemetryHelper
import com.jarvis.assistant.util.HudSoundManager
import com.jarvis.assistant.ui.settings.CustomizationStudioActivity
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.util.FrostedGlassEffect
import com.jarvis.assistant.util.HudAnimationHelper
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel
    private val preferences by lazy { JarvisApp.instance.preferences }

    private var isZenModeActive = false
    private var currentLoadedOrbUrl: String? = null
    private var isPageLoaded = false
    private var pendingOrbState: String? = null
    private var pendingThemeKey: String? = null
    private var pendingCustomHex: String? = null
    private var lastAudioPushTime = 0L
    private var taskFallbackPanel: android.widget.LinearLayout? = null
    private var taskFallbackText: android.widget.TextView? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            fetchLiveWeather()
        } else {
            binding.tvWeatherCondition.text = "Location Permission Needed"
            binding.tvWeatherDetails.text = "Tap here to grant location for accurate weather"
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        createTaskFallbackPanel()

        // Top Navigation (Menu & Quick Access)
        binding.btnTopChat.setOnClickListener {
            HudSoundManager.playTapSound()
            StarkTopMenuDialog.newInstance().show(childFragmentManager, StarkTopMenuDialog.TAG)
        }
        binding.btnTopChat.setOnLongClickListener {
            HudSoundManager.playTapSound()
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_CHAT)
            true
        }

        binding.btnTopMatrix.setOnClickListener {
            HudSoundManager.playTapSound()
            startActivity(Intent(requireContext(), com.jarvis.assistant.ui.matrix.JarvisMatrixActivity::class.java))
        }

        binding.btnTopCustomization.setOnClickListener {
            HudSoundManager.playTapSound()
            startActivity(Intent(requireContext(), CustomizationStudioActivity::class.java))
        }

        binding.btnTopSettings.setOnClickListener {
            HudSoundManager.playTapSound()
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_SETTINGS)
        }

        // Top Notification / Updates button (swapped with God's Eye)
        binding.btnTopUpdates.setOnClickListener {
            HudSoundManager.playTapSound()
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_UPDATES)
        }

        binding.btnToggleZenMode.setOnClickListener {
            HudSoundManager.playTapSound()
            toggleZenMode()
        }

        binding.btnExitZenMode.setOnClickListener {
            HudSoundManager.playTapSound()
            toggleZenMode()
        }

        binding.btnDismissLiveError.setOnClickListener {
            hideLiveErrorOverlay {
                viewModel.clearLiveError()
            }
        }

        binding.btnSwitchToJarvisMode.visibility = View.GONE
        binding.btnSwitchToJarvisMode.setOnClickListener {
            HudSoundManager.playTapSound()
            preferences.experienceMode = com.jarvis.assistant.data.preferences.AppPreferences.MODE_JARVIS
            android.widget.Toast.makeText(requireContext(), "💜 Switched to JARVIS Mode", android.widget.Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.switchExperienceMode(com.jarvis.assistant.data.preferences.AppPreferences.MODE_JARVIS)
        }
        // Default to 3D Mora Cyber Character on Home Screen per user specification
        if (preferences.orbEntityType == "orb") {
            preferences.orbEntityType = "mora_cyber"
            com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(requireContext(), com.jarvis.assistant.util.ModelVaultManager.BUILTIN_MORA.id)
        }
        val isMoraInit = preferences.orbEntityType == "mora_cyber"
        if (isMoraInit) {
            binding.orbHaloRing.visibility = View.GONE
            val density = resources.displayMetrics.density
            val initialHeight = if (preferences.hideAllHomeCards) (520 * density).toInt() else (360 * density).toInt()
            binding.orbContainer.layoutParams.height = initialHeight
        }

        setupOrbWebView()

        // Action Buttons
        binding.btnScreenShareQuick.setOnClickListener {
            (activity as? MainActivity)?.toggleScreenShare()
        }

        binding.btnSavedMemories.setOnClickListener {
            startActivity(Intent(requireContext(), MemoryActivity::class.java))
        }

        binding.btnConversationLanguageQuick.setOnClickListener {
            showLanguageDialog()
        }

        // Updates & Announcements Section Click Handlers
        binding.btnViewAllUpdates.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_UPDATES)
        }

        binding.cardUpdateAgent.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_UPDATES)
        }

        binding.cardUpdateVoice.setOnClickListener {
            startActivity(Intent(requireContext(), com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity::class.java))
        }

        binding.cardUpdateGodsEye.setOnClickListener {
            startActivity(Intent(requireContext(), com.jarvis.assistant.ui.godseye.GodsEyeActivity::class.java))
        }

        // Weather Card Click -> Refresh or Request Permission
        binding.cardWeather.setOnClickListener {
            if (!PermissionHelper.hasLocationPermission(requireContext())) {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            } else {
                fetchLiveWeather()
            }
        }

        // Safe Window Insets: Guarantee header sits safely below status bar / camera cutout without being pushed down too far
        ViewCompat.setOnApplyWindowInsetsListener(binding.homeScrollView) { v, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val topInset = statusBarInsets.top
            val safeTopPadding = if (topInset > 0) topInset + (6 * density).toInt() else (14 * density).toInt()
            v.setPadding(
                v.paddingLeft,
                safeTopPadding,
                v.paddingRight,
                v.paddingBottom
            )
            insets
        }

        // Apply iPhone-style hardware blur to ambient glow orbs on Android 12+
        FrostedGlassEffect.applyBlur(binding.glowOrbTopRight, 50f, 50f)
        FrostedGlassEffect.applyBlur(binding.glowOrbCenterLeft, 50f, 50f)
        FrostedGlassEffect.applyBlur(binding.glowOrbBottom, 50f, 50f)

        // Start Arc Reactor Halo Breathing Pulse Animation
        HudAnimationHelper.startBreathingHalo(binding.orbHaloRing)

        // Stark Tactical Protocols Click Handlers
        binding.cardHomeWallpaper.setOnClickListener {
            HudSoundManager.playTapSound()
            try {
                val intent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(
                        android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        android.content.ComponentName(requireContext(), com.jarvis.assistant.wallpaper.JarvisWallpaperService::class.java)
                    )
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                } catch (_: Exception) {
                    android.widget.Toast.makeText(requireContext(), "Stark Arc Reactor Wallpaper service active", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.cardHomeAppLock.setOnClickListener {
            HudSoundManager.playTapSound()
            val intent = Intent(requireContext(), com.jarvis.assistant.ui.facelock.JarvisAppLockActivity::class.java).apply {
                putExtra(com.jarvis.assistant.ui.facelock.JarvisAppLockActivity.EXTRA_PACKAGE_NAME, requireContext().packageName)
                putExtra(com.jarvis.assistant.ui.facelock.JarvisAppLockActivity.EXTRA_APP_LABEL, "JARVIS Core Console")
            }
            startActivity(intent)
        }

        binding.cardHomePocketTts.setOnClickListener {
            HudSoundManager.playTapSound()
            com.jarvis.assistant.voice.PocketTtsEngine.getInstance(requireContext())
                .speak("नमस्ते सर, जार्विस न्यूरल वॉइस एक्टिव है। All systems operational.")
            startActivity(Intent(requireContext(), com.jarvis.assistant.ui.matrix.JarvisMatrixActivity::class.java))
        }

        binding.cardHomeMatrix.setOnClickListener {
            HudSoundManager.playTapSound()
            startActivity(Intent(requireContext(), com.jarvis.assistant.ui.matrix.JarvisMatrixActivity::class.java))
        }

        // Attach Spring Touch Micro-Animations to interactive cards and buttons
        val touchableViews = listOf(
            binding.btnTopChat,
            binding.btnTopCustomization,
            binding.btnTopSettings,
            binding.btnTopUpdates,
            binding.cardWeather,
            binding.btnScreenShareQuick,
            binding.btnSavedMemories,
            binding.btnConversationLanguageQuick,
            binding.cardHomeWallpaper,
            binding.cardHomeAppLock,
            binding.cardHomePocketTts,
            binding.cardHomeMatrix,
            binding.cardUpdateAgent,
            binding.cardUpdateVoice,
            binding.cardUpdateGodsEye,
            binding.btnViewAllUpdates,
            binding.layoutStatusIndicator
        )
        touchableViews.forEach { HudAnimationHelper.attachSpringTouch(it) }

        // Staggered Iron Man HUD Boot Sequence Cascade Animation
        val cascadeViews = listOf(
            binding.layoutHeader to -25f,
            binding.orbContainer to 0f,
            binding.layoutStatusIndicator to 20f,
            binding.cardWeather to 25f,
            binding.cardTelemetry to 28f,
            binding.layoutActionGrid to 30f,
            binding.layoutUpdatesContainer to 35f
        )
        HudAnimationHelper.playCascadeEntrance(cascadeViews, baseDelay = 45L)

        updateHomeHeaderAndVoiceStatus()
        observeViewModel()
        checkLocationAndFetchWeather()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupOrbWebView() {
        binding.orbWebView.apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
            settings.apply {
                databaseEnabled = true
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = false
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
                    Log.i("OrbWebView", "${consoleMessage?.messageLevel()}: ${consoleMessage?.message()} -- line ${consoleMessage?.lineNumber()}")
                    return true
                }
            }

            webViewClient = com.jarvis.assistant.util.JarvisWebViewClient(requireContext()) { view, url ->
                isPageLoaded = true
                Log.i("OrbWebView", "onPageFinished for $url")

                // Force immediate canvas resize and layout calibration
                view?.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.resize){ window.JarvisOrb.resize(); }", null)

                // Sync Theme
                val currentTheme = pendingThemeKey ?: preferences.appTheme
                val customHex = pendingCustomHex ?: preferences.customThemeColor
                setOrbTheme(currentTheme, customHex)

                // Sync State
                val state = pendingOrbState ?: when (viewModel.conversationState.value) {
                    ConversationState.LISTENING -> "listening"
                    ConversationState.SPEAKING -> "speaking"
                    ConversationState.THINKING -> "thinking"
                    ConversationState.IDLE -> "idle"
                }
                setOrbState(state)
                applyCustomizationPreferences()

                // Additional post-render passes to guarantee layout stabilization
                view?.postDelayed({ applyCustomizationPreferences() }, 150)
                view?.postDelayed({ applyCustomizationPreferences() }, 450)
            }

            // Single tap detection triggers mic/conversation without interrupting 3D rotation drag
            val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    binding.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.triggerTouchReaction){ window.JarvisOrb.triggerTouchReaction(); }", null)
                    (activity as? MainActivity)?.handleMicOrOrbClick()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    (activity as? MainActivity)?.handleHoldMicOrOrb()
                }
            })

            setOnTouchListener { v, event ->
                gestureDetector.onTouchEvent(event)
                v.onTouchEvent(event)
            }

            addJavascriptInterface(com.jarvis.assistant.util.Jarvis3DModelBridge(requireContext()), "Jarvis3DModelBridge")
            val initialUrl = if (preferences.orbEntityType == "mora_cyber") {
                com.jarvis.assistant.util.JarvisWebViewClient.MORA_URL
            } else {
                com.jarvis.assistant.util.JarvisWebViewClient.ORB_URL
            }
            currentLoadedOrbUrl = initialUrl
            loadUrl(initialUrl)
        }
    }

    fun setOrbState(state: String) {
        pendingOrbState = state
        if (!isPageLoaded) return
        activity?.runOnUiThread {
            binding.orbWebView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setState('$state');", null)
        }
    }

    fun setOrbTheme(themeKey: String, customHex: String?) {
        pendingThemeKey = themeKey
        pendingCustomHex = customHex
        if (!isPageLoaded) return
        activity?.runOnUiThread {
            val hex = if (themeKey == "custom" && !customHex.isNullOrBlank()) customHex else customHex ?: ""
            val activeTheme = if (themeKey == "custom" && hex.isNotBlank()) hex else themeKey
            binding.orbWebView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setTheme('$activeTheme', '$hex');", null)
        }
    }

    private fun pushExternalAudio(amp: Float, bass: Float, high: Float) {
        val isAvatar = preferences.orbEntityType in setOf("custom_3d", "ai_girl", "hinata_anime")
        if (!isPageLoaded || (!preferences.isAudioReactiveOrb && !isAvatar)) return
        val now = System.currentTimeMillis()
        if (now - lastAudioPushTime < 33L) return // ~30 calls per second throttle
        lastAudioPushTime = now
        activity?.runOnUiThread {
            binding.orbWebView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setExternalAudio($amp, $bass, $high);", null)
        }
    }

    override fun onResume() {
        super.onResume()
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        binding.orbWebView.onResume()
        binding.orbWebView.resumeTimers()
        renderTaskFallback(com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.value)

        // Sync theme with settings
        setOrbTheme(preferences.appTheme, preferences.customThemeColor)
        updateHomeHeaderAndVoiceStatus()
        applyCustomizationPreferences()

        // Refresh blur on ambient glow orbs according to preference
        FrostedGlassEffect.applyBlur(binding.glowOrbTopRight, 50f, 50f)
        FrostedGlassEffect.applyBlur(binding.glowOrbCenterLeft, 50f, 50f)
        FrostedGlassEffect.applyBlur(binding.glowOrbBottom, 50f, 50f)

        if (PermissionHelper.hasLocationPermission(requireContext())) {
            fetchLiveWeather()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.orbWebView.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            binding.orbWebView.visibility = View.VISIBLE
            binding.orbWebView.onResume()
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            setOrbTheme(preferences.appTheme, preferences.customThemeColor)
            updateHomeHeaderAndVoiceStatus()
            applyCustomizationPreferences()
        } else if (hidden && _binding != null) {
            binding.orbWebView.onPause()
            binding.orbWebView.visibility = View.GONE
            binding.root.visibility = View.GONE
        }
    }

    private fun applyCustomizationPreferences() {
        val b = _binding ?: return

                // Mode Switcher Banner: Always hidden per user specification
        b.btnSwitchToJarvisMode.visibility = View.GONE

        // Master Clean Immersion Mode or Zen Mode hides all bottom cards
        val hideAll = isZenModeActive || preferences.hideAllHomeCards
        if (hideAll) {
            b.layoutHomeCardsContainer.visibility = View.GONE
        } else {
            b.layoutHomeCardsContainer.visibility = View.VISIBLE
            b.layoutStatusIndicator.visibility = if (preferences.showStatusIndicatorWidget) View.VISIBLE else View.GONE
            b.cardWeather.visibility = if (preferences.showWeatherWidget) View.VISIBLE else View.GONE
            b.cardTelemetry.visibility = if (preferences.showTelemetryWidget) View.VISIBLE else View.GONE
            b.layoutActionGrid.visibility = if (preferences.showQuickActionsWidget) View.VISIBLE else View.GONE
            b.layoutTacticalProtocols.visibility = if (preferences.showProtocolsWidget) View.VISIBLE else View.GONE
            b.layoutUpdatesSectionHeader.visibility = if (preferences.showUpdatesWidget) View.VISIBLE else View.GONE
            b.layoutUpdatesContainer.visibility = if (preferences.showUpdatesWidget) View.VISIBLE else View.GONE
        }

        // Telemetry update
        if (!hideAll && preferences.showTelemetryWidget) {
            updateTelemetryData()
        }

        val isMora = preferences.orbEntityType == "mora_cyber"
        val desiredUrl = if (isMora) {
            com.jarvis.assistant.util.JarvisWebViewClient.MORA_URL
        } else {
            com.jarvis.assistant.util.JarvisWebViewClient.ORB_URL
        }

        if (currentLoadedOrbUrl != desiredUrl) {
            currentLoadedOrbUrl = desiredUrl
            isPageLoaded = false
            b.orbWebView.loadUrl(desiredUrl)
        }

        // Hide purple orb halo ring when Mora is active to avoid circle across character
        b.orbHaloRing.visibility = if (isMora) View.GONE else View.VISIBLE

        // Dynamic Stage Height
        try {
            val screenHeight = resources.displayMetrics.heightPixels
            val density = resources.displayMetrics.density
            val targetStageHeight = if (isZenModeActive || preferences.hideAllHomeCards) {
                (520 * density).toInt().coerceAtMost((screenHeight * 0.70f).toInt())
            } else if (preferences.isOrbCinematic50) {
                (screenHeight * 0.48f).toInt()
            } else if (isMora) {
                (360 * density).toInt()
            } else {
                (280 * density).toInt()
            }
            if (b.orbContainer.layoutParams.height != targetStageHeight) {
                b.orbContainer.layoutParams.height = targetStageHeight
                b.orbContainer.requestLayout()
                b.orbWebView.postDelayed({
                    b.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.resize){ window.JarvisOrb.resize(); }", null)
                }, 200)
            }
        } catch (_: Exception) {}

        // Orb & Entity Modifiers
        if (isPageLoaded) {
            val speed = preferences.orbSpeed
            val glow = preferences.orbGlow
            val scale = preferences.orbScale
            val preset = preferences.orbCorePreset
            val resonanceLevel = preferences.coreResonanceLevel
            val resonanceBoost = when (preferences.arcCorePulseMode) {
                "overdrive" -> 1.35f
                "stealth" -> 0.70f
                else -> 1.0f
            }
            val effSpeed = speed * resonanceBoost
            val rawEntityType = preferences.orbEntityType
            val hasCustom3D = com.jarvis.assistant.util.CustomAvatarHelper.hasActiveCustom3dModel(requireContext()) ||
                        com.jarvis.assistant.util.ModelVaultManager.getActiveModelFile(requireContext()) != null ||
                        (com.jarvis.assistant.util.CustomAvatarHelper.get3DModelFile(requireContext())?.exists() == true)
            val hasBuiltin3D = rawEntityType == "hinata_anime" || rawEntityType == "ai_girl"
            val entityType = if (rawEntityType == "custom_3d" && !com.jarvis.assistant.util.CustomAvatarHelper.hasCustomAvatar(requireContext()) && !hasCustom3D) {
                "orb"
            } else {
                rawEntityType
            }

            val hasAnyVisibleToggle = preferences.orbShowCore || preferences.orbShowGlow || preferences.orbShowRings
            val showCore = if (hasAnyVisibleToggle) preferences.orbShowCore else true
            val showGlow = if (hasAnyVisibleToggle) preferences.orbShowGlow else true
            val showRings = if (hasAnyVisibleToggle) preferences.orbShowRings else true
            val showRing1 = if (hasAnyVisibleToggle) preferences.orbShowRing1 else true
            val showRing2 = if (hasAnyVisibleToggle) preferences.orbShowRing2 else true
            val showRing3 = if (hasAnyVisibleToggle) preferences.orbShowRing3 else true
            val showOrbitDust = if (hasAnyVisibleToggle) preferences.orbShowOrbitDust else true

            val is3DAvatar = entityType == "custom_3d" || entityType == "ai_girl" || entityType == "hinata_anime"
            val togglesJson = org.json.JSONObject().apply {
                put("core", showCore)
                put("glow", showGlow)
                put("rings", showRings)
                put("ring1", showRing1)
                put("ring2", showRing2)
                put("ring3", showRing3)
                put("orbitDust", showOrbitDust)
                put("shadow", preferences.orbShowShadow)
                put("idleBreathing", preferences.orbShowIdleBreathing)
                put("autoRotation", if (is3DAvatar) false else preferences.orbAutoRotationEnabled)
                put("touchDrag", preferences.orbTouchDragEnabled)
                put("audioReactive", preferences.isAudioReactiveOrb)
            }.toString()

            val autoRotation = if (is3DAvatar) false else preferences.orbAutoRotationEnabled
            val companionCount = preferences.orbCompanionCount
            val companionType = preferences.orbCompanionType

            val customHex = preferences.customThemeColor
            val activeTheme = if (preset == "custom" || preferences.appTheme == "custom") {
                if (customHex.isNotBlank()) customHex else "cyan"
            } else if (preset.isNotBlank() && preset != "custom") {
                preset
            } else if (preferences.appTheme.isNotBlank() && preferences.appTheme != "custom") {
                preferences.appTheme
            } else {
                "dark"
            }

            val customLoadScript = if (is3DAvatar) {
                "window.JarvisOrb.loadCustom3DModel(); window.JarvisOrb.loadCustomPhoto(); "
            } else {
                "window.JarvisOrb.switchToDefaultOrb(); "
            }

            b.orbWebView.evaluateJavascript(
                "if(window.JarvisOrb){ " +
                        "window.JarvisOrb.resize(); " +
                        "window.JarvisOrb.setEntityType('$entityType'); " +
                        customLoadScript +
                        "window.JarvisOrb.setComponentToggles($togglesJson); " +
                        "window.JarvisOrb.setAutoRotation($autoRotation); " +
                        "window.JarvisOrb.setCompanionFleet($companionCount, '$companionType'); " +
                        "window.JarvisOrb.setSpeed($effSpeed); " +
                        "window.JarvisOrb.setGlow($glow); " +
                        "window.JarvisOrb.setScale($scale); " +
                        "window.JarvisOrb.setTheme('$activeTheme', '$customHex'); " +
                        "window.JarvisOrb.setPreset('$activeTheme', '$customHex'); " +
                        "window.JarvisOrb.setResonance($resonanceLevel); }",
                null
            )
        }
    }

    private fun updateTelemetryData() {
        try {
            val t = DeviceTelemetryHelper.getTelemetry(requireContext())
            binding.tvTelemetryBattery.text = "${t.batteryPercent}%"
            binding.tvTelemetryBatteryState.text = t.batteryStatusText
            binding.tvTelemetryRam.text = "${t.ramUsedGb} / ${t.ramTotalGb} GB"
            binding.tvTelemetryRamPercent.text = "${t.ramPercent}% LOAD"
            binding.tvTelemetryNetwork.text = t.networkType
            binding.tvTelemetryNetworkStatus.text = if (t.isOnline) "SECURE" else "OFFLINE"
            binding.tvTelemetryUptime.text = t.uptimeFormatted
        } catch (_: Exception) {}
    }

    private fun checkLocationAndFetchWeather() {
        if (PermissionHelper.hasLocationPermission(requireContext())) {
            fetchLiveWeather()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun fetchLiveWeather() {
        binding.tvWeatherCondition.text = "Locating • Fetching weather..."
        viewLifecycleOwner.lifecycleScope.launch {
            val weather = LocationWeatherHelper.fetchWeather(requireContext())
            if (_binding != null && weather != null) {
                binding.tvTemperature.text = weather.temperature
                binding.tvWeatherCondition.text = weather.condition
                binding.tvWeatherDetails.text = weather.details
                binding.ivWeatherIcon.setImageResource(weather.iconResId)
                // Keep Jarvis's context aligned with the city shown on Home.
                preferences.userLocation = weather.cityName
            } else if (_binding != null) {
                binding.tvWeatherCondition.text = "Live Weather • Tap to retry"
            }
        }
    }

    private fun updateHomeHeaderAndVoiceStatus() {
        if (_binding == null) return
        val userName = preferences.userName.ifBlank { "Roshan" }
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeGreeting = when (hour) {
            in 4..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Welcome back"
        }
        binding.tvGreeting.text = "$timeGreeting, $userName"

        val currentLang = preferences.language.ifBlank { "Auto" }
        binding.tvQuickLanguageStatus.text = if (currentLang.contains("Auto", ignoreCase = true)) "Auto" else currentLang
    }

    private fun showLanguageDialog() {
        val languages = com.jarvis.assistant.data.model.GeminiConstants.SUPPORTED_LANGUAGES.toTypedArray()
        val currentLang = preferences.language
        val currentIndex = languages.indexOfFirst { it.equals(currentLang, ignoreCase = true) }.let { if (it >= 0) it else 0 }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Conversation Language")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val chosen = languages[which]
                preferences.language = chosen
                binding.tvQuickLanguageStatus.text = chosen
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.collect { snapshot ->
                        renderTaskFallback(snapshot)
                        if (snapshot != null) {
                            if (snapshot.currentStatus == "COMPLETED") {
                                binding.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.triggerReaction){ window.JarvisOrb.triggerReaction('success'); }", null)
                            } else if (snapshot.currentStatus in setOf("FAILED", "BLOCKED")) {
                                binding.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.triggerReaction){ window.JarvisOrb.triggerReaction('failed'); }", null)
                            }
                        }
                    }
                }
                launch {
                    com.jarvis.assistant.background.JarvisConversationService.wakeEvents.collect {
                        binding.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.triggerReaction){ window.JarvisOrb.triggerReaction('wave'); }", null)
                    }
                }
                launch {
                    com.jarvis.assistant.ai.learning.UserCorrectionTracker.getInstance(requireContext()).corrections.collect { list ->
                        if (list.isNotEmpty()) {
                            binding.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.triggerReaction){ window.JarvisOrb.triggerReaction('teach'); }", null)
                        }
                    }
                }
                launch {
                    com.jarvis.assistant.background.JarvisConversationService.liveUserTranscript.collect { transcript ->
                        if (transcript.isNotBlank()) {
                            val lower = transcript.lowercase().trim()
                            if (lower.contains("hello") || lower.contains("hi jarvis") || lower.contains("hey jarvis") || lower.contains("namaste")) {
                                binding.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.triggerReaction){ window.JarvisOrb.triggerReaction('wave'); }", null)
                            } else if (lower.contains("thank") || lower.contains("shukriya") || lower.contains("good job") || lower.contains("awesome") || lower.contains("well done")) {
                                binding.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.triggerReaction){ window.JarvisOrb.triggerReaction('joy'); }", null)
                            } else if (lower.contains("remember") || lower.contains("from now on") || lower.contains("my preference") || lower.contains("aise karna") || lower.contains("seekh lo")) {
                                binding.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.triggerReaction){ window.JarvisOrb.triggerReaction('teach'); }", null)
                            } else if (lower.contains("stop") || lower.contains("chup") || lower.contains("ruko") || lower.contains("wait")) {
                                binding.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.interrupt){ window.JarvisOrb.interrupt(); }", null)
                            } else if (lower.contains("?") || lower.startsWith("what") || lower.startsWith("how") || lower.startsWith("why") || lower.startsWith("kya") || lower.startsWith("kaise")) {
                                binding.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.triggerReaction){ window.JarvisOrb.triggerReaction('curious'); }", null)
                            }
                        }
                    }
                }
                launch {
                    val screenCapture = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance()
                    combine(screenCapture.isSharing, screenCapture.hasReceivedFrame) { sharing, hasFrame -> sharing to hasFrame }
                        .collect { (sharing, hasFrame) ->
                        binding.tvQuickScreenShareStatus.text = when {
                            !sharing -> "Share screen"
                            !hasFrame -> "Starting…"
                            else -> "Stop sharing"
                        }
                        binding.tvQuickScreenShareStatus.setTextColor(
                            if (sharing && hasFrame) Color.parseColor("#10B981")
                            else ContextCompat.getColor(requireContext(), R.color.text_subtle)
                        )
                    }
                }

                launch {
                    combine(
                        viewModel.isSessionOn,
                        viewModel.isMicMuted,
                        viewModel.conversationState,
                        com.jarvis.assistant.background.JarvisConversationService.isStandby
                    ) { isOn, isMuted, state, isStandby ->
                        HomeStatusState(isOn, isMuted, state, isStandby)
                    }.collect { status ->
                        if (!status.isOn) {
                            setOrbState("idle")
                            binding.tvListeningStatus.text = "JARVIS OFF • TAP TO START"
                            binding.tvListeningStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_subtle))
                            binding.tvHoldHint.text = "Tap mic or orb to power on"
                            binding.tvHoldHint.visibility = View.VISIBLE
                            binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                Color.parseColor("#64748B")
                            )
                        } else if (status.isMuted) {
                            setOrbState("idle")
                            binding.tvListeningStatus.text = "MIC MUTED (OFF) • TAP TO TALK"
                            binding.tvListeningStatus.setTextColor(Color.parseColor("#EF4444"))
                            binding.tvHoldHint.text = "Microphone is muted • Tap mic to unmute"
                            binding.tvHoldHint.visibility = View.VISIBLE
                            binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                Color.parseColor("#EF4444")
                            )
                        } else if (status.isStandby) {
                            setOrbState("idle")
                            binding.tvListeningStatus.text = "STANDBY • SAY 'HEY JARVIS'"
                            binding.tvListeningStatus.setTextColor(Color.parseColor("#3B82F6"))
                            binding.tvHoldHint.text = "Listening for wake word • Hold mic to power off"
                            binding.tvHoldHint.visibility = View.VISIBLE
                            binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                Color.parseColor("#3B82F6")
                            )
                        } else {
                            binding.tvHoldHint.visibility = View.VISIBLE
                            when (status.state) {
                                ConversationState.LISTENING -> {
                                    setOrbState("listening")
                                    binding.tvListeningStatus.text = "MIC ON • LISTENING..."
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#10B981"))
                                    binding.tvHoldHint.text = "Speak now • Tap to mute • Hold to power off"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#10B981")
                                    )
                                }
                                ConversationState.SPEAKING -> {
                                    setOrbState("speaking")
                                    binding.tvListeningStatus.text = "JARVIS SPEAKING..."
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#06B6D4"))
                                    binding.tvHoldHint.text = "Tap mic to interrupt"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#06B6D4")
                                    )
                                }
                                ConversationState.THINKING -> {
                                    setOrbState("thinking")
                                    binding.tvListeningStatus.text = "THINKING..."
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#F59E0B"))
                                    binding.tvHoldHint.text = "Processing request..."
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#F59E0B")
                                    )
                                }
                                ConversationState.IDLE -> {
                                    setOrbState("idle")
                                    binding.tvListeningStatus.text = "MIC ON • READY"
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#10B981"))
                                    binding.tvHoldHint.text = "Tap to mute • Hold to power off"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#10B981")
                                    )
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.audioFrequency.collect { freq ->
                        val amp = freq.amplitude
                        val bass = freq.bass
                        val high = freq.high
                        pushExternalAudio(amp, bass, high)
                    }
                }

                launch {
                    viewModel.liveError.collect { error ->
                        if (error == null) {
                            hideLiveErrorOverlay()
                        } else {
                            showLiveErrorOverlay()
                            binding.tvLiveErrorTitle.text = error.title
                            binding.tvLiveErrorMessage.text = error.message
                            binding.btnFixLiveError.text = error.actionText

                            when (error.errorCode) {
                                "API_KEY_INVALID", "API_KEY_MISSING" -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_warning)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#EF4444"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        startActivity(Intent(requireContext(), com.jarvis.assistant.ui.settings.ApiConnectionActivity::class.java))
                                    }
                                }
                                "ACCESSIBILITY_DISABLED" -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_settings_shield)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#F59E0B"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        PermissionHelper.openAccessibilitySettings(requireContext())
                                    }
                                }
                                "PERMISSIONS_MISSING" -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_settings_shield)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#F59E0B"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        startActivity(Intent(requireContext(), com.jarvis.assistant.ui.permission.PermissionActivity::class.java))
                                    }
                                }
                                else -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_warning)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#EF4444"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        (activity as? MainActivity)?.handleMicOrOrbClick()
                                    }
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.connectionStatus.collect { status ->
                        if (status.startsWith("ERROR")) {
                            binding.tvListeningStatus.text = status
                            binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                Color.parseColor("#EF4444")
                            )
                        }
                    }
                }
            }
        }
    }

    private fun showLiveErrorOverlay() {
        if (_binding == null) return
        val card = binding.cardLiveError
        if (card.visibility == View.VISIBLE && card.alpha == 1f) return
        card.alpha = 0f
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.visibility = View.VISIBLE
        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(240)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.15f))
            .start()
    }

    private fun hideLiveErrorOverlay(onEnd: (() -> Unit)? = null) {
        if (_binding == null) {
            onEnd?.invoke()
            return
        }
        val card = binding.cardLiveError
        if (card.visibility != View.VISIBLE) {
            onEnd?.invoke()
            return
        }
        card.animate()
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(180)
            .withEndAction {
                if (_binding != null) {
                    card.visibility = View.GONE
                }
                onEnd?.invoke()
            }
            .start()
    }

    private fun createTaskFallbackPanel() {
        val root = binding.root as? androidx.constraintlayout.widget.ConstraintLayout ?: return
        val panel = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(12, 20, 35))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), android.graphics.Color.rgb(34, 211, 238))
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        panel.addView(android.widget.TextView(requireContext()).apply {
            text = "JARVIS AGENT PROGRESS"
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.rgb(103, 232, 249))
        })
        val details = android.widget.TextView(requireContext()).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            maxLines = 4
            setPadding(0, dp(5), 0, dp(2))
        }
        panel.addView(details)
        panel.addView(android.widget.Button(requireContext()).apply {
            text = "Enable floating overlay"
            isAllCaps = false
            setOnClickListener {
                try {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${requireContext().packageName}")
                    ))
                } catch (_: Exception) {
                    android.widget.Toast.makeText(requireContext(), "Open Android Settings → Apps → JARVIS → Display over other apps.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }, android.widget.LinearLayout.LayoutParams(-2, dp(44)))
        val params = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(0, -2).apply {
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            topMargin = dp(70)
            marginStart = dp(16)
            marginEnd = dp(16)
        }
        root.addView(panel, params)
        taskFallbackPanel = panel
        taskFallbackText = details
    }

    private fun renderTaskFallback(snapshot: com.jarvis.assistant.automation.agent.AgentTaskSnapshot?) {
        val panel = taskFallbackPanel ?: return
        val show = snapshot != null && !android.provider.Settings.canDrawOverlays(requireContext())
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (!show || snapshot == null) return
        val activeStep = snapshot.steps.firstOrNull { it.stepId == snapshot.currentStepId }?.title ?: snapshot.currentStatus
        val checklist = snapshot.steps.take(4).joinToString("\n") { step ->
            val mark = if (step.status == com.jarvis.assistant.automation.agent.TaskStepStatus.COMPLETED) "✓" else "○"
            "$mark ${step.title}"
        }
        taskFallbackText?.text = "${snapshot.originalGoal}\n${snapshot.progressPercentage}% · ${snapshot.currentStatus.replace('_', ' ')}\nCurrent: $activeStep\n$checklist"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun toggleZenMode() {
        isZenModeActive = !isZenModeActive
        applyZenModeState(animated = true)
    }

    private fun applyZenModeState(animated: Boolean = true) {
        val b = _binding ?: return
        val density = resources.displayMetrics.density
        val isMora = preferences.orbEntityType == "mora_cyber"

        b.btnToggleZenMode.setImageResource(
            if (isZenModeActive) R.drawable.ic_zen_mode_active else R.drawable.ic_zen_mode
        )
        b.btnExitZenMode.visibility = if (isZenModeActive) View.VISIBLE else View.GONE

                b.btnSwitchToJarvisMode.visibility = View.GONE

        val hudViews = listOf(
            b.layoutHomeCardsContainer
        )

        hudViews.forEach { view ->
            if (animated) {
                if (isZenModeActive) {
                    view.animate().alpha(0f).setDuration(220).withEndAction {
                        view.visibility = View.GONE
                    }.start()
                } else {
                    view.visibility = View.VISIBLE
                    view.alpha = 0f
                    view.animate().alpha(1f).setDuration(250).start()
                }
            } else {
                view.visibility = if (isZenModeActive) View.GONE else View.VISIBLE
                view.alpha = if (isZenModeActive) 0f else 1f
            }
        }

                if (!isZenModeActive && !preferences.hideAllHomeCards) {
            b.layoutHomeCardsContainer.visibility = View.VISIBLE
            b.layoutStatusIndicator.visibility = if (preferences.showStatusIndicatorWidget) View.VISIBLE else View.GONE
            b.cardWeather.visibility = if (preferences.showWeatherWidget) View.VISIBLE else View.GONE
            b.cardTelemetry.visibility = if (preferences.showTelemetryWidget) View.VISIBLE else View.GONE
            b.layoutActionGrid.visibility = if (preferences.showQuickActionsWidget) View.VISIBLE else View.GONE
            b.layoutTacticalProtocols.visibility = if (preferences.showProtocolsWidget) View.VISIBLE else View.GONE
            b.layoutUpdatesSectionHeader.visibility = if (preferences.showUpdatesWidget) View.VISIBLE else View.GONE
            b.layoutUpdatesContainer.visibility = if (preferences.showUpdatesWidget) View.VISIBLE else View.GONE
        } else if (preferences.hideAllHomeCards) {
            b.layoutHomeCardsContainer.visibility = View.GONE
        }

        val screenHeight = resources.displayMetrics.heightPixels
        val targetStageHeight = if (isZenModeActive || preferences.hideAllHomeCards) {
            (520 * density).toInt().coerceAtMost((screenHeight * 0.70f).toInt())
        } else if (preferences.isOrbCinematic50) {
            (screenHeight * 0.48f).toInt()
        } else if (isMora) {
            (360 * density).toInt()
        } else {
            (280 * density).toInt()
        }

        val currentParams = b.orbContainer.layoutParams
        if (currentParams.height != targetStageHeight) {
            if (animated) {
                val anim = android.animation.ValueAnimator.ofInt(currentParams.height, targetStageHeight)
                anim.duration = 260
                anim.addUpdateListener { va ->
                    currentParams.height = va.animatedValue as Int
                    b.orbContainer.layoutParams = currentParams
                }
                anim.start()
            } else {
                currentParams.height = targetStageHeight
                b.orbContainer.layoutParams = currentParams
            }
        }

        b.orbWebView.postDelayed({
            b.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.resize){ window.JarvisOrb.resize(); }", null)
        }, 300)
    }

}

private data class HomeStatusState(
    val isOn: Boolean,
    val isMuted: Boolean,
    val state: ConversationState,
    val isStandby: Boolean
)
