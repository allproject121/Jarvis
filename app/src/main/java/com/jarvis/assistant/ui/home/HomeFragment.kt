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

        // Center JARVIS Interactive Brand Logo & Glow Effect
        binding.layoutJarvisLogoTouch.setOnClickListener {
            triggerJarvisLogoClickEffect()
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
            preferences.hideAllHomeCards = false
            isZenModeActive = false
            applyZenModeState(animated = true)
        }

        binding.btnExitZenModeTop.setOnClickListener {
            HudSoundManager.playTapSound()
            preferences.hideAllHomeCards = false
            isZenModeActive = false
            applyZenModeState(animated = true)
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
        try {
            val explicitChoice = preferences.userExplicitlyChoseEntity
            if (!explicitChoice || preferences.orbEntityType == "orb" || preferences.orbEntityType == "ai_girl") {
                preferences.orbEntityType = "mora_cyber"
                com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(requireContext(), com.jarvis.assistant.util.ModelVaultManager.BUILTIN_MORA.id)
            }
        } catch (_: Throwable) {}

        val isMoraInit = preferences.orbEntityType == "mora_cyber"
        if (isMoraInit) {
            binding.orbHaloRing.visibility = View.GONE
            try {
                val density = resources.displayMetrics.density
                val screenHeight = resources.displayMetrics.heightPixels
                val initialHeight = if (preferences.hideAllHomeCards) screenHeight else (360 * density).toInt()
                val params = binding.orbContainer.layoutParams
                if (params != null) {
                    params.height = initialHeight
                    binding.orbContainer.layoutParams = params
                }
            } catch (_: Throwable) {}
        }

        applyCustomizationPreferences()
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
        if (binding.orbHaloRing.visibility != View.GONE) {
            HudAnimationHelper.startBreathingHalo(binding.orbHaloRing)
        }

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

            try {
                android.webkit.WebView.setWebContentsDebuggingEnabled(com.jarvis.assistant.BuildConfig.DEBUG)
            } catch (_: Throwable) {}
            try {
                settings.apply {
                    try { databaseEnabled = true } catch (_: Throwable) {}
                    try { loadsImagesAutomatically = true } catch (_: Throwable) {}
                    try { mediaPlaybackRequiresUserGesture = false } catch (_: Throwable) {}
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    @Suppress("DEPRECATION")
                    try { allowFileAccessFromFileURLs = true } catch (_: Throwable) {}
                    @Suppress("DEPRECATION")
                    try { allowUniversalAccessFromFileURLs = true } catch (_: Throwable) {}
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
            } catch (e: Throwable) {
                Log.e("OrbWebView", "Error configuring WebSettings", e)
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

            try {
                val ctx = context ?: JarvisApp.instance
                addJavascriptInterface(com.jarvis.assistant.util.Jarvis3DModelBridge(ctx), "Jarvis3DModelBridge")
            } catch (e: Throwable) {
                Log.e("OrbWebView", "Error adding Jarvis3DModelBridge", e)
            }
            try {
                val initialUrl = if (preferences.orbEntityType == "mora_cyber") {
                    "${com.jarvis.assistant.util.JarvisWebViewClient.MORA_URL}?theme=${preferences.appTheme}&hex=${android.net.Uri.encode(preferences.customThemeColor)}"
                } else {
                    com.jarvis.assistant.util.JarvisWebViewClient.ORB_URL
                }
                currentLoadedOrbUrl = initialUrl
                loadUrl(initialUrl)
            } catch (e: Throwable) {
                Log.e("OrbWebView", "Error loading initial url", e)
            }
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
            binding.orbWebView.evaluateJavascript(
                "if(window.MoraCompanion && window.MoraCompanion.setTheme){ window.MoraCompanion.setTheme('$activeTheme', '$hex'); } " +
                "if(window.JarvisOrb && window.JarvisOrb.setTheme){ window.JarvisOrb.setTheme('$activeTheme', '$hex'); if(window.JarvisOrb.setPreset){ window.JarvisOrb.setPreset('$activeTheme', '$hex'); } }",
                null
            )
        }
    }

    private fun pushExternalAudio(amp: Float, bass: Float, high: Float) {
        val isAvatar = preferences.orbEntityType in setOf("mora_cyber", "custom_3d", "ai_girl", "hinata_anime")
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
        (activity as? MainActivity)?.setBottomDockVisibility(true, animated = false)
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
            (activity as? MainActivity)?.setBottomDockVisibility(true, animated = false)
        }
    }

    private fun applyCustomizationPreferences() {
        val b = _binding ?: return

        // Mode Switcher Banner: Always hidden per user specification
        b.btnSwitchToJarvisMode.visibility = View.GONE

        // Master Clean Immersion Mode or Zen Mode hides all bottom cards
        val hideAll = isZenModeActive || preferences.hideAllHomeCards
        b.btnToggleZenMode.setImageResource(
            if (hideAll) R.drawable.ic_zen_mode_active else R.drawable.ic_zen_mode
        )
        b.btnExitZenMode.visibility = if (hideAll) View.VISIBLE else View.GONE
        b.btnExitZenModeTop.visibility = if (hideAll) View.VISIBLE else View.GONE

        val density = resources.displayMetrics.density
        val screenHeight = resources.displayMetrics.heightPixels
        val fullHeight = b.root.height.takeIf { it > 0 } ?: screenHeight

        if (hideAll) {
            b.layoutHeader.visibility = View.GONE
            b.layoutHomeCardsContainer.visibility = View.GONE
            b.homeScrollView.setPadding(0, 0, 0, 0)
            (activity as? MainActivity)?.setBottomDockVisibility(false, animated = false)
        } else {
            b.layoutHeader.visibility = View.VISIBLE
            b.layoutHomeCardsContainer.visibility = View.VISIBLE
            val pStart = (18 * density).toInt()
            val pTop = (14 * density).toInt()
            val pBottom = (100 * density).toInt()
            b.homeScrollView.setPadding(pStart, pTop, pStart, pBottom)
            (activity as? MainActivity)?.setBottomDockVisibility(true, animated = false)

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
            "${com.jarvis.assistant.util.JarvisWebViewClient.MORA_URL}?theme=${preferences.appTheme}&hex=${android.net.Uri.encode(preferences.customThemeColor)}"
        } else {
            com.jarvis.assistant.util.JarvisWebViewClient.ORB_URL
        }

        if (currentLoadedOrbUrl != desiredUrl) {
            currentLoadedOrbUrl = desiredUrl
            isPageLoaded = false
            b.orbWebView.loadUrl(desiredUrl)
        }

        // Hide purple orb halo ring when Mora is active to avoid circle across character
        b.orbHaloRing.visibility = if (isMora || hideAll) View.GONE else View.VISIBLE

        // Dynamic Stage Height
        try {
            val targetStageHeight = if (hideAll) {
                fullHeight
            } else if (preferences.isOrbCinematic50) {
                (screenHeight * 0.48f).toInt()
            } else if (isMora) {
                (360 * density).toInt()
            } else {
                (280 * density).toInt()
            }
            val params = b.orbContainer.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null) {
                params.topMargin = if (hideAll) 0 else (4 * density).toInt()
                params.bottomMargin = if (hideAll) 0 else (6 * density).toInt()
                if (params.height != targetStageHeight) {
                    params.height = targetStageHeight
                    b.orbContainer.layoutParams = params
                    b.orbContainer.requestLayout()
                    b.orbWebView.postDelayed({
                        b.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.resize){ window.JarvisOrb.resize(); }", null)
                    }, 200)
                }
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
            val ctx = context ?: JarvisApp.instance
            val hasCustom3D = com.jarvis.assistant.util.CustomAvatarHelper.hasActiveCustom3dModel(ctx) ||
                        com.jarvis.assistant.util.ModelVaultManager.getActiveModelFile(ctx) != null ||
                        (com.jarvis.assistant.util.CustomAvatarHelper.get3DModelFile(ctx)?.exists() == true)
            val hasBuiltin3D = rawEntityType == "hinata_anime" || rawEntityType == "ai_girl"
            val entityType = if (rawEntityType == "custom_3d" && !com.jarvis.assistant.util.CustomAvatarHelper.hasCustomAvatar(ctx) && !hasCustom3D) {
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
                        "window.JarvisOrb.setResonance($resonanceLevel); } " +
                        "if(window.MoraCompanion && window.MoraCompanion.setTheme){ " +
                        "window.MoraCompanion.setTheme('$activeTheme', '$customHex'); }",
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

        // Dynamic Brand Logo & Shadow Tinting
        val brandColor = resolveActiveThemeColor()
        binding.ivHeaderJarvisLogo.setColorFilter(brandColor)
        binding.ivHeaderJarvisLogoShadow.setColorFilter(brandColor)

        val currentLang = preferences.language.ifBlank { "Auto" }
        binding.tvQuickLanguageStatus.text = if (currentLang.contains("Auto", ignoreCase = true)) "Auto" else currentLang
    }

    private fun resolveActiveThemeColor(): Int {
        val customHex = preferences.customThemeColor
        if (preferences.appTheme == "custom" && !customHex.isNullOrBlank()) {
            try { return android.graphics.Color.parseColor(customHex) } catch (_: Exception) {}
        }
        return com.jarvis.assistant.util.ThemeManager.getThemeAccentColor(context)
    }

    private fun triggerJarvisLogoClickEffect() {
        val b = _binding ?: return
        com.jarvis.assistant.util.HudSoundManager.playTapSound()
        try {
            val vibrator = requireContext().getSystemService(android.os.Vibrator::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(28, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(28)
            }
        } catch (_: Throwable) {}

        // 1. Tactile bounce animation on the logo
        b.ivHeaderJarvisLogo.animate()
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(90)
            .withEndAction {
                b.ivHeaderJarvisLogo.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(220)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.5f))
                    .start()
            }
            .start()

        // 2. Holographic Neon Shadow Burst Animation
        val brandColor = resolveActiveThemeColor()
        b.ivHeaderJarvisLogoShadow.setColorFilter(brandColor)
        b.ivHeaderJarvisLogoShadow.apply {
            scaleX = 1.0f
            scaleY = 1.0f
            alpha = 0.95f
            animate()
                .scaleX(1.4f)
                .scaleY(1.4f)
                .alpha(0.0f)
                .setDuration(450)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        // 3. Status Feedback
        com.google.android.material.snackbar.Snackbar.make(
            b.root,
            "✦ JARVIS CORE ONLINE • ALL SYSTEMS NOMINAL",
            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
        ).setBackgroundTint(brandColor)
         .setTextColor(android.graphics.Color.WHITE)
         .show()
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

    
    fun triggerOrbReaction(reaction: String) {
        if (!isPageLoaded) return
        activity?.runOnUiThread {
            binding.orbWebView.evaluateJavascript(
                "if(window.JarvisOrb && window.JarvisOrb.triggerReaction){ window.JarvisOrb.triggerReaction('$reaction'); }",
                null
            )
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.collect { snapshot ->
                        renderTaskFallback(snapshot)
                        if (snapshot != null) {
                            when (snapshot.currentStatus) {
                                "COMPLETED" -> {
                                    binding.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.triggerReaction){ window.JarvisOrb.triggerReaction('success'); }", null)
                                }
                                "FAILED", "BLOCKED" -> {
                                    binding.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.triggerReaction){ window.JarvisOrb.triggerReaction('failed'); }", null)
                                }
                                "RUNNING", "EXECUTING", "PLANNING", "IN_PROGRESS" -> {
                                    binding.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.triggerReaction){ window.JarvisOrb.triggerReaction('executing'); }", null)
                                }
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
                // Physical Avatar (Mora / Orb) Voice Intent & Body Control
                launch {
                    com.jarvis.assistant.background.JarvisConversationService.liveUserTranscript.collect { transcript ->
                        if (transcript.isNotBlank()) {
                            val lower = transcript.lowercase().trim()
                            if (lower.contains("dance") || lower.contains("nach") || lower.contains("naach")) {
                                triggerOrbReaction("dance")
                            } else if (lower.contains("hello") || lower.contains("hi jarvis") || lower.contains("hey jarvis") || lower.contains("namaste") || lower.contains("wave") || lower.contains("haath hilao")) {
                                triggerOrbReaction("wave")
                            } else if (lower.contains("clap") || lower.contains("taali") || lower.contains("taaliyan") || lower.contains("shabash")) {
                                triggerOrbReaction("clapping")
                            } else if (lower.contains("thank") || lower.contains("shukriya") || lower.contains("dhanyawad") || lower.contains("dhanyavad") || lower.contains("well done") || lower.contains("awesome")) {
                                triggerOrbReaction("thankful")
                            } else if (lower.contains("laugh") || lower.contains("hanso") || lower.contains("haso") || lower.contains("joke") || lower.contains("mazaak")) {
                                triggerOrbReaction("joy")
                            } else if (lower.contains("excited") || lower.contains("party") || lower.contains("celebrate") || lower.contains("kudo") || lower.contains("kudoo")) {
                                triggerOrbReaction("excited")
                            } else if (lower.contains("cry") || lower.contains("ro mat") || lower.contains("rona") || lower.contains("sad")) {
                                triggerOrbReaction("crying")
                            } else if (lower.contains("remember") || lower.contains("from now on") || lower.contains("my preference") || lower.contains("aise karna") || lower.contains("seekh lo")) {
                                triggerOrbReaction("teach")
                            } else if (lower.contains("stop") || lower.contains("chup") || lower.contains("ruko") || lower.contains("ruk jao") || lower.contains("bas") || lower.contains("band karo")) {
                                binding.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.interrupt){ window.JarvisOrb.interrupt(); } if(window.JarvisOrb && window.JarvisOrb.triggerReaction){ window.JarvisOrb.triggerReaction('stop'); }", null)
                            } else if (lower.contains("?") || lower.startsWith("what") || lower.startsWith("how") || lower.startsWith("why") || lower.startsWith("kya") || lower.startsWith("kaise") || lower.contains("socho")) {
                                triggerOrbReaction("curious")
                            }
                        }
                    }
                }
                launch {
                    com.jarvis.assistant.background.JarvisConversationService.liveAssistantResponse.collect { response ->
                        if (response.isNotBlank()) {
                            val lower = response.lowercase().trim()
                            if (lower.contains("dancing") || lower.contains("dance for you") || lower.contains("dance karta") || lower.contains("dance karti") || lower.contains("nach raha")) {
                                triggerOrbReaction("dance")
                            } else if (lower.contains("namaste") || lower.contains("hello there")) {
                                triggerOrbReaction("wave")
                            }
                        }
                    }
                }
                launch {
                    com.jarvis.assistant.background.JarvisConversationService.avatarAction.collect { action ->
                        if (action.isNotBlank()) {
                            triggerOrbReaction(action)
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
        if (snapshot == null || android.provider.Settings.canDrawOverlays(requireContext())) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE
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
        if (preferences.hideAllHomeCards || isZenModeActive) {
            preferences.hideAllHomeCards = false
            isZenModeActive = false
        } else {
            isZenModeActive = true
            preferences.hideAllHomeCards = true
        }
        applyZenModeState(animated = true)
    }

    private fun applyZenModeState(animated: Boolean = true) {
        val b = _binding ?: return
        val density = resources.displayMetrics.density
        val isMora = preferences.orbEntityType == "mora_cyber"
        val hideCards = isZenModeActive || preferences.hideAllHomeCards

        b.btnToggleZenMode.setImageResource(
            if (hideCards) R.drawable.ic_zen_mode_active else R.drawable.ic_zen_mode
        )
        b.btnExitZenMode.visibility = if (hideCards) View.VISIBLE else View.GONE
        b.btnExitZenModeTop.visibility = if (hideCards) View.VISIBLE else View.GONE
        b.btnSwitchToJarvisMode.visibility = View.GONE

        (activity as? MainActivity)?.setBottomDockVisibility(!hideCards, animated = animated)

        // Animate Header out/in
        if (animated) {
            if (hideCards) {
                b.layoutHeader.animate().alpha(0f).setDuration(180).withEndAction {
                    b.layoutHeader.visibility = View.GONE
                }.start()
            } else {
                b.layoutHeader.visibility = View.VISIBLE
                b.layoutHeader.alpha = 0f
                b.layoutHeader.animate().alpha(1f).setDuration(220).start()
            }
        } else {
            b.layoutHeader.visibility = if (hideCards) View.GONE else View.VISIBLE
            b.layoutHeader.alpha = if (hideCards) 0f else 1f
        }

        // Animate Cards Container out/in
        if (animated) {
            if (hideCards) {
                b.layoutHomeCardsContainer.animate().alpha(0f).setDuration(180).withEndAction {
                    b.layoutHomeCardsContainer.visibility = View.GONE
                }.start()
            } else {
                b.layoutHomeCardsContainer.visibility = View.VISIBLE
                b.layoutHomeCardsContainer.alpha = 0f
                b.layoutHomeCardsContainer.animate().alpha(1f).setDuration(220).start()
            }
        } else {
            b.layoutHomeCardsContainer.visibility = if (hideCards) View.GONE else View.VISIBLE
            b.layoutHomeCardsContainer.alpha = if (hideCards) 0f else 1f
        }

        if (!hideCards) {
            b.layoutStatusIndicator.visibility = if (preferences.showStatusIndicatorWidget) View.VISIBLE else View.GONE
            b.cardWeather.visibility = if (preferences.showWeatherWidget) View.VISIBLE else View.GONE
            b.cardTelemetry.visibility = if (preferences.showTelemetryWidget) View.VISIBLE else View.GONE
            b.layoutActionGrid.visibility = if (preferences.showQuickActionsWidget) View.VISIBLE else View.GONE
            b.layoutTacticalProtocols.visibility = if (preferences.showProtocolsWidget) View.VISIBLE else View.GONE
            b.layoutUpdatesSectionHeader.visibility = if (preferences.showUpdatesWidget) View.VISIBLE else View.GONE
            b.layoutUpdatesContainer.visibility = if (preferences.showUpdatesWidget) View.VISIBLE else View.GONE
        }

        if (hideCards) {
            b.homeScrollView.setPadding(0, 0, 0, 0)
            b.homeScrollView.scrollTo(0, 0)
        } else {
            val pStart = (18 * density).toInt()
            val pTop = (14 * density).toInt()
            val pBottom = (100 * density).toInt()
            b.homeScrollView.setPadding(pStart, pTop, pStart, pBottom)
        }

        val screenHeight = resources.displayMetrics.heightPixels
        val fullHeight = b.root.height.takeIf { it > 0 } ?: screenHeight
        val targetStageHeight = if (hideCards) {
            fullHeight
        } else if (preferences.isOrbCinematic50) {
            (screenHeight * 0.48f).toInt()
        } else if (isMora) {
            (360 * density).toInt()
        } else {
            (280 * density).toInt()
        }

        val currentParams = b.orbContainer.layoutParams as? ViewGroup.MarginLayoutParams
        if (currentParams != null) {
            currentParams.topMargin = if (hideCards) 0 else (4 * density).toInt()
            currentParams.bottomMargin = if (hideCards) 0 else (6 * density).toInt()

            if (currentParams.height != targetStageHeight) {
                if (animated) {
                    val anim = android.animation.ValueAnimator.ofInt(currentParams.height, targetStageHeight)
                    anim.duration = 240
                    anim.addUpdateListener { va ->
                        currentParams.height = va.animatedValue as Int
                        b.orbContainer.layoutParams = currentParams
                    }
                    anim.start()
                } else {
                    currentParams.height = targetStageHeight
                    b.orbContainer.layoutParams = currentParams
                }
            } else {
                b.orbContainer.layoutParams = currentParams
            }
        }

        b.orbWebView.postDelayed({
            b.orbWebView.evaluateJavascript("if(window.JarvisOrb && window.JarvisOrb.resize){ window.JarvisOrb.resize(); }", null)
        }, 280)
    }

}

private data class HomeStatusState(
    val isOn: Boolean,
    val isMuted: Boolean,
    val state: ConversationState,
    val isStandby: Boolean
)
