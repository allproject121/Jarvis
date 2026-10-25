package com.jarvis.assistant.ui.home

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.databinding.ActivityMainBinding
import com.jarvis.assistant.ui.chat.ChatFragment
import com.jarvis.assistant.ui.settings.SettingsFragment
import com.jarvis.assistant.util.PermissionHelper
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAB_HOME = 0
        const val TAB_CHAT = 1
        const val TAB_SETTINGS = 2
        const val TAB_UPDATES = 3
    }

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()
    private var activeSnackbar: Snackbar? = null

    private var currentTab = TAB_HOME
    private val homeFragment by lazy { HomeFragment() }
    private val jarvisCompanionHomeFragment by lazy { com.jarvis.assistant.ui.companion.JarvisCompanionHomeFragment() }
    private val chatFragment by lazy { ChatFragment() }
    private val settingsFragment by lazy { SettingsFragment() }
    private val updatesFragment by lazy { UpdatesFragment() }

    fun getActiveHomeFragment(): Fragment {
        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        return if (prefs.isJarvisCompanionMode) jarvisCompanionHomeFragment else homeFragment
    }

    fun getInactiveHomeFragment(): Fragment {
        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        return if (prefs.isJarvisCompanionMode) homeFragment else jarvisCompanionHomeFragment
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkPermissionsAndStart()
        } else {
            Toast.makeText(
                this,
                "Microphone permission is required for voice conversation.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkPermissionsAndStart()
        } else {
            Toast.makeText(
                this,
                "Notification permission is required to run in the background.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val mediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
    }

    val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            try {
                if (!JarvisConversationService.isSessionOn.value || JarvisConversationService.connectionStatus.value != "LIVE") {
                    Toast.makeText(this, "Start Jarvis and wait for LIVE before sharing your screen.", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                if (!JarvisConversationService.elevateToMediaProjection()) {
                    Toast.makeText(this, "Could not start Android's screen-capture service.", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                val projection = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
                if (projection == null) {
                    JarvisConversationService.demoteFromMediaProjection()
                    Toast.makeText(this, "Could not acquire screen projection.", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                val started = JarvisConversationService.startScreenCapture(projection, metrics)
                if (!started) {
                    JarvisConversationService.demoteFromMediaProjection()
                    Toast.makeText(this, "Android did not start screen capture. Please try again.", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                Toast.makeText(this, "Checking screen capture…", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    delay(2500L)
                    val captureManager = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance()
                    if (!captureManager.isSharing.value) return@launch
                    if (captureManager.hasReceivedFrame.value) {
                        Toast.makeText(this@MainActivity, "Screen sharing is live. Jarvis is receiving screen frames.", Toast.LENGTH_LONG).show()
                    } else {
                        JarvisConversationService.stopScreenCapture()
                        Toast.makeText(this@MainActivity, "No screen frames arrived, so sharing was stopped. Please try again.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error starting MediaProjection: ${e.message}", e)
                JarvisConversationService.demoteFromMediaProjection()
                Toast.makeText(this, "Screen capture failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    private var appliedThemeKey: String = ""
    private var appliedCustomHex: String = ""

    fun toggleScreenShare() {
        if (com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value) {
            JarvisConversationService.stopScreenCapture()
            Toast.makeText(this, "Screen sharing stopped.", Toast.LENGTH_SHORT).show()
        } else {
            if (!JarvisConversationService.isSessionOn.value || JarvisConversationService.connectionStatus.value != "LIVE") {
                Toast.makeText(this, "Start Jarvis and wait for LIVE before sharing your screen.", Toast.LENGTH_LONG).show()
                return
            }
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        appliedThemeKey = prefs.appTheme
        appliedCustomHex = prefs.customThemeColor
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!com.jarvis.assistant.security.AppIdentityVerifier.isTrustedRelease(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("App integrity check failed")
                .setMessage("This JARVIS build is not signed with the configured official release certificate. Sensitive features have been blocked.")
                .setCancelable(false)
                .setPositiveButton("Close") { _, _ -> finish() }
                .show()
            return
        }

        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.rootMainLayout)

        val requestedTab = intent.getIntExtra("TARGET_TAB", -1)
        currentTab = if (requestedTab in listOf(TAB_HOME, TAB_CHAT, TAB_SETTINGS, TAB_UPDATES)) {
            requestedTab
        } else {
            savedInstanceState?.getInt("CURRENT_TAB", TAB_HOME) ?: TAB_HOME
        }

        // Clear any old restored fragments to prevent ghost overlapping
        val existingFragments = supportFragmentManager.fragments
        if (existingFragments.isNotEmpty()) {
            val trans = supportFragmentManager.beginTransaction()
            for (fragment in existingFragments) {
                trans.remove(fragment)
            }
            trans.commitNowAllowingStateLoss()
        }

        // Add all fragments fresh into container
        val trans = supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, homeFragment, "TAB_HOME_JARVIS")
            .add(R.id.fragmentContainer, jarvisCompanionHomeFragment, "TAB_HOME_JARVIS_COMPANION")
            .add(R.id.fragmentContainer, chatFragment, "TAB_1")
            .add(R.id.fragmentContainer, settingsFragment, "TAB_2")
            .add(R.id.fragmentContainer, updatesFragment, "TAB_3")

        val activeHome = getActiveHomeFragment()
        val inactiveHome = getInactiveHomeFragment()

        val allTabs = listOf(
            TAB_HOME to activeHome,
            TAB_CHAT to chatFragment,
            TAB_SETTINGS to settingsFragment,
            TAB_UPDATES to updatesFragment
        )

        allTabs.forEach { (tabId, frag) ->
            if (tabId == currentTab) {
                trans.show(frag)
            } else {
                trans.hide(frag)
            }
        }
        trans.hide(inactiveHome)
        trans.commitNow()

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.rootMainLayout) { _, insets ->
            val navInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            val baseMargin = (16 * resources.displayMetrics.density).toInt()
            val params = binding.bottomDock.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            if (params != null) {
                params.bottomMargin = baseMargin + navInsets.bottom
                binding.bottomDock.layoutParams = params
            }
            insets
        }

        setupNavigationDock()
        selectTab(currentTab)
        observeViewModel()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab != TAB_HOME) {
                    selectTab(TAB_HOME)
                } else {
                    // Preserve Android's normal root behavior (return to launcher) only
                    // after the user is already on JARVIS Home.
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("CURRENT_TAB", currentTab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestedTab = intent.getIntExtra("TARGET_TAB", -1)
        if (requestedTab in listOf(TAB_HOME, TAB_CHAT, TAB_SETTINGS, TAB_UPDATES)) {
            selectTab(requestedTab)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSettings()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            binding.bottomDock.setRenderEffect(null)
        }
        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        if (appliedThemeKey != prefs.appTheme || appliedCustomHex != prefs.customThemeColor) {
            appliedThemeKey = prefs.appTheme
            appliedCustomHex = prefs.customThemeColor
            recreate()
            return
        }

        val shouldBeCompanion = prefs.isJarvisCompanionMode
        val isCompanionActive = jarvisCompanionHomeFragment.isAdded && jarvisCompanionHomeFragment.isVisible
        if (currentTab == TAB_HOME && ((shouldBeCompanion && !isCompanionActive) || (!shouldBeCompanion && isCompanionActive))) {
            switchExperienceMode(prefs.experienceMode)
        }

        if (isDockExpanded) {
            binding.bottomDock.post {
                moveLumaSpotlight(currentTab, animate = false)
            }
        }
    }

    private fun setupNavigationDock() {
        binding.tabHome.setOnClickListener { selectTab(TAB_HOME) }
        binding.tabChat.setOnClickListener { selectTab(TAB_CHAT) }

        // Tab 4: God's Eye Tactical View (Replaces Settings as requested)
        binding.tabGodsEye.setOnClickListener {
            com.jarvis.assistant.util.HudSoundManager.playTapSound()
            moveLumaSpotlight(998, animate = true)
            startActivity(Intent(this, com.jarvis.assistant.ui.godseye.GodsEyeActivity::class.java))
        }

        // Tab 5: Customization Studio (Direct access in dock)
        binding.tabStudio.setOnClickListener {
            com.jarvis.assistant.util.HudSoundManager.playTapSound()
            moveLumaSpotlight(999, animate = true)
            startActivity(Intent(this, com.jarvis.assistant.ui.settings.CustomizationStudioActivity::class.java))
        }

        binding.tabMic.setOnClickListener {
            handleMicOrOrbClick()
        }

        binding.tabMic.setOnLongClickListener {
            handleHoldMicOrOrb()
            true
        }

        binding.btnToggleDockMode.setOnClickListener {
            toggleDockMode()
        }

        binding.layoutCompactIsland.setOnClickListener {
            expandLumaDock()
        }

        binding.ivIslandMic.setOnClickListener {
            handleMicOrOrbClick()
        }

        listOf(
            binding.tabHome,
            binding.tabChat,
            binding.tabMic,
            binding.tabGodsEye,
            binding.tabStudio,
            binding.btnToggleDockMode,
            binding.layoutCompactIsland
        ).forEach { com.jarvis.assistant.util.HudAnimationHelper.attachSpringTouch(it) }

        binding.bottomDock.post {
            moveLumaSpotlight(currentTab, animate = false)
        }
    }

    fun setBottomDockVisibility(visible: Boolean, animated: Boolean = true) {
        val dock = binding.bottomDock
        if (animated) {
            if (visible) {
                dock.visibility = View.VISIBLE
                dock.animate().alpha(1f).translationY(0f).setDuration(240).start()
            } else {
                val dist = dock.height.toFloat().takeIf { it > 0 } ?: (100f * resources.displayMetrics.density)
                dock.animate().alpha(0f).translationY(dist).setDuration(200).withEndAction {
                    dock.visibility = View.GONE
                }.start()
            }
        } else {
            dock.visibility = if (visible) View.VISIBLE else View.GONE
            dock.alpha = if (visible) 1f else 0f
            dock.translationY = if (visible) 0f else (dock.height.toFloat().takeIf { it > 0 } ?: (100f * resources.displayMetrics.density))
        }
    }

    fun selectTab(tab: Int) {
        if (tab != TAB_HOME) {
            setBottomDockVisibility(true, animated = false)
        }
        val previousTab = currentTab
        currentTab = tab

        val activeHome = getActiveHomeFragment()
        val inactiveHome = getInactiveHomeFragment()

        val tabs = listOf(
            TAB_HOME to activeHome,
            TAB_CHAT to chatFragment,
            TAB_SETTINGS to settingsFragment,
            TAB_UPDATES to updatesFragment
        )

        val trans = supportFragmentManager.beginTransaction()
        tabs.forEach { (tabId, frag) ->
            if (frag.isAdded) {
                if (tabId == tab) {
                    trans.show(frag)
                } else {
                    trans.hide(frag)
                }
            }
        }
        if (inactiveHome.isAdded) {
            trans.hide(inactiveHome)
        }
        trans.commitAllowingStateLoss()
        inactiveHome.view?.visibility = View.GONE

        tabs.forEach { (tabId, frag) ->
            val v = frag.view
            if (v != null) {
                if (tabId == tab) {
                    v.visibility = View.VISIBLE
                    com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(v)
                    if (previousTab != tab) {
                        v.alpha = 0f
                        v.translationX = if (tab > previousTab) 18f else -18f
                        v.animate().cancel()
                        v.animate()
                            .alpha(1f)
                            .translationX(0f)
                            .setDuration(190L)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                    } else {
                        v.alpha = 1f
                        v.translationX = 0f
                    }
                } else {
                    v.animate().cancel()
                    v.visibility = View.GONE
                }
            }
        }

        updateDockUI(tab)
    }

    fun switchExperienceMode(newMode: String) {
        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        prefs.experienceMode = newMode
        binding.root.post {
            if (isFinishing || isDestroyed || supportFragmentManager.isDestroyed) return@post
            val trans = supportFragmentManager.beginTransaction()
            if (newMode == com.jarvis.assistant.data.preferences.AppPreferences.MODE_JARVIS_COMPANION) {
                if (homeFragment.isAdded) trans.hide(homeFragment)
                if (jarvisCompanionHomeFragment.isAdded) trans.show(jarvisCompanionHomeFragment)
                homeFragment.view?.visibility = View.GONE
                jarvisCompanionHomeFragment.view?.visibility = View.VISIBLE
            } else {
                if (jarvisCompanionHomeFragment.isAdded) trans.hide(jarvisCompanionHomeFragment)
                if (homeFragment.isAdded) trans.show(homeFragment)
                jarvisCompanionHomeFragment.view?.visibility = View.GONE
                homeFragment.view?.visibility = View.VISIBLE
            }
            trans.commitAllowingStateLoss()
            selectTab(TAB_HOME)
        }
    }

    private var isDockExpanded = true

    private fun updateDockUI(tab: Int) {
        val tvAccent = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeAccent, tvAccent, true)
        val activeColor = if (tvAccent.data != 0) tvAccent.data else ContextCompat.getColor(this, R.color.accent_sapphire)

        val tvMuted = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextMuted, tvMuted, true)
        val inactiveColor = if (tvMuted.data != 0) tvMuted.data else ContextCompat.getColor(this, R.color.text_subtle)

        val tvPrimary = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextPrimary, tvPrimary, true)
        val activeTextColor = if (tvPrimary.data != 0) tvPrimary.data else android.graphics.Color.WHITE

        val navItems = listOf(
            Triple(TAB_HOME, binding.pillTabHome, Pair(binding.ivTabHome, binding.tvTabHome)),
            Triple(TAB_CHAT, binding.pillTabChat, Pair(binding.ivTabChat, binding.tvTabChat)),
            Triple(998, binding.pillTabGodsEye, Pair(binding.ivTabGodsEye, binding.tvTabGodsEye)),
            Triple(999, binding.pillTabStudio, Pair(binding.ivTabStudio, binding.tvTabStudio))
        )

        navItems.forEach { (tabId, pill, views) ->
            val isSelected = tabId == tab
            val (iv, tv) = views
            if (isSelected) {
                pill.setBackgroundResource(R.drawable.bg_dock_item_active)
                pill.alpha = 0f
                pill.scaleX = 0.90f
                pill.scaleY = 0.90f
                pill.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240L)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()

                // Luma active SVG icon spring bounce (original SVGs preserved)
                iv.animate().cancel()
                iv.animate()
                    .scaleX(1.18f)
                    .scaleY(1.18f)
                    .setDuration(240L)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .start()

                iv.imageTintList = ColorStateList.valueOf(activeTextColor)
                tv.setTextColor(activeTextColor)
                tv.alpha = 1f
            } else {
                pill.animate().cancel()
                pill.setBackgroundResource(0)
                pill.alpha = 1f
                pill.scaleX = 1f
                pill.scaleY = 1f

                // Inactive icon returns to 1.0x
                iv.animate().cancel()
                iv.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200L)
                    .start()

                iv.imageTintList = ColorStateList.valueOf(inactiveColor)
                tv.setTextColor(inactiveColor)
                tv.alpha = 0.72f
            }
        }

        // Slide the radiant Luma spotlight to the active tab
        moveLumaSpotlight(tab, animate = true)
    }

    private fun moveLumaSpotlight(tab: Int, animate: Boolean = true) {
        val targetPill = when (tab) {
            TAB_HOME -> binding.pillTabHome
            TAB_CHAT -> binding.pillTabChat
            998 -> binding.pillTabGodsEye
            999 -> binding.pillTabStudio
            else -> binding.pillTabHome
        }

        binding.dockTabsLayout.post {
            if (isFinishing || isDestroyed) return@post
            val targetLocation = IntArray(2)
            val dockLocation = IntArray(2)
            targetPill.getLocationOnScreen(targetLocation)
            binding.bottomDock.getLocationOnScreen(dockLocation)

            val pillCenterInDockX = (targetLocation[0] - dockLocation[0]) + (targetPill.width / 2f)
            val density = resources.displayMetrics.density
            val spotlightW = binding.ivLumaSpotlight.width.takeIf { it > 0 } ?: (68 * density).toInt()
            val targetX = pillCenterInDockX - (spotlightW / 2f)

            val tvAccent = android.util.TypedValue()
            theme.resolveAttribute(R.attr.themeAccent, tvAccent, true)
            val activeColor = if (tvAccent.data != 0) tvAccent.data else ContextCompat.getColor(this, R.color.accent_sapphire)
            binding.ivLumaSpotlight.imageTintList = ColorStateList.valueOf(activeColor)

            if (animate) {
                binding.ivLumaSpotlight.animate().cancel()
                binding.ivLumaSpotlight.animate()
                    .translationX(targetX)
                    .setDuration(300L)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            } else {
                binding.ivLumaSpotlight.translationX = targetX
            }
        }
    }

    fun toggleDockMode() {
        com.jarvis.assistant.util.HudSoundManager.playTapSound()
        if (isDockExpanded) {
            collapseLumaDock()
        } else {
            expandLumaDock()
        }
    }

    fun collapseLumaDock() {
        if (!isDockExpanded) return
        isDockExpanded = false

        binding.btnToggleDockMode.animate().cancel()
        binding.btnToggleDockMode.animate().alpha(0f).setDuration(120L).start()

        val startWidth = binding.bottomDock.width
        val density = resources.displayMetrics.density
        val targetWidth = (168 * density).toInt()

        val anim = ValueAnimator.ofInt(startWidth, targetWidth).apply {
            duration = 280L
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val w = va.animatedValue as Int
                val p = binding.bottomDock.layoutParams
                p.width = w
                binding.bottomDock.layoutParams = p
            }
        }
        anim.start()

        // Cross-fade to compact Dynamic Island capsule
        binding.dockTabsLayout.animate().cancel()
        binding.dockTabsLayout.animate().alpha(0f).setDuration(120L).withEndAction {
            binding.dockTabsLayout.visibility = View.GONE
        }.start()

        binding.dockCurvedLine.animate().cancel()
        binding.dockCurvedLine.animate().alpha(0f).setDuration(120L).withEndAction {
            binding.dockCurvedLine.visibility = View.GONE
        }.start()

        binding.ivLumaSpotlight.animate().cancel()
        binding.ivLumaSpotlight.animate().alpha(0f).setDuration(120L).start()

        binding.layoutCompactIsland.visibility = View.VISIBLE
        binding.layoutCompactIsland.alpha = 0f
        binding.layoutCompactIsland.animate().cancel()
        binding.layoutCompactIsland.animate()
            .alpha(1f)
            .setDuration(180L)
            .setStartDelay(100L)
            .start()
    }

    fun expandLumaDock() {
        if (isDockExpanded) return
        isDockExpanded = true

        binding.btnToggleDockMode.animate().cancel()
        binding.btnToggleDockMode.animate().alpha(1f).setDuration(240L).setStartDelay(100L).start()

        val startWidth = binding.bottomDock.width
        val density = resources.displayMetrics.density
        val targetWidth = (binding.rootMainLayout.width - (28 * density).toInt()).coerceAtLeast((340 * density).toInt())

        val anim = ValueAnimator.ofInt(startWidth, targetWidth).apply {
            duration = 320L
            interpolator = OvershootInterpolator(1.15f)
            addUpdateListener { va ->
                val w = va.animatedValue as Int
                val p = binding.bottomDock.layoutParams
                p.width = w
                binding.bottomDock.layoutParams = p
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    val p = binding.bottomDock.layoutParams
                    p.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    binding.bottomDock.layoutParams = p
                }
            })
        }
        anim.start()

        binding.layoutCompactIsland.animate().cancel()
        binding.layoutCompactIsland.animate().alpha(0f).setDuration(100L).withEndAction {
            binding.layoutCompactIsland.visibility = View.GONE
        }.start()

        binding.dockTabsLayout.visibility = View.VISIBLE
        binding.dockTabsLayout.alpha = 0f
        binding.dockTabsLayout.animate().cancel()
        binding.dockTabsLayout.animate()
            .alpha(1f)
            .setDuration(240L)
            .setStartDelay(80L)
            .start()

        binding.dockCurvedLine.visibility = View.VISIBLE
        binding.dockCurvedLine.alpha = 0f
        binding.dockCurvedLine.animate().cancel()
        binding.dockCurvedLine.animate()
            .alpha(1f)
            .setDuration(240L)
            .setStartDelay(80L)
            .start()

        binding.ivLumaSpotlight.animate().cancel()
        binding.ivLumaSpotlight.animate()
            .alpha(0.85f)
            .setDuration(240L)
            .setStartDelay(100L)
            .start()

        moveLumaSpotlight(currentTab, animate = true)
    }

    fun handleMicOrOrbClick() {
        com.jarvis.assistant.util.HudSoundManager.playTapSound()
        if (!JarvisConversationService.isSessionOn.value) {
            com.jarvis.assistant.util.HudSoundManager.playWakeSound()
            checkPermissionsAndStart()
        } else if (JarvisConversationService.conversationState.value == ConversationState.SPEAKING) {
            JarvisConversationService.interruptPlayback()
        } else {
            viewModel.toggleMicMute()
        }
    }

    fun handleHoldMicOrOrb() {
        binding.tabMic.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        if (JarvisConversationService.isSessionOn.value) {
            viewModel.turnOffSession()
        } else {
            checkPermissionsAndStart()
        }
    }

    fun handleTurnOff() {
        handleHoldMicOrOrb()
    }

    fun handlePowerClick() {
        handleHoldMicOrOrb()
    }

    private fun checkPermissionsAndStart() {
        if (!PermissionHelper.hasRecordAudioPermission(this)) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!PermissionHelper.hasNotificationPermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        if (!PermissionHelper.hasBatteryOptimizationPermission(this)) {
            showBatteryPermissionDialog()
            return
        }

        viewModel.toggleSession()
    }

    private fun showBatteryPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Unrestricted Battery Required")
            .setMessage("Jarvis requires Unrestricted Battery access so it can stay active in the background and respond to wake words.\n\nPlease allow it or open Settings.")
            .setPositiveButton("ALLOW") { _, _ ->
                PermissionHelper.requestBatteryOptimization(this)
            }
            .setNeutralButton("OPEN SETTINGS") { _, _ ->
                selectTab(TAB_SETTINGS)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.isMicMuted, viewModel.conversationState, viewModel.isSessionOn) { isMuted, state, isOn ->
                        Triple(isMuted, state, isOn)
                    }.collect { (isMuted, state, isOn) ->
                        val tvAccent = android.util.TypedValue()
                        theme.resolveAttribute(R.attr.themeAccent, tvAccent, true)
                        val accentColor = if (tvAccent.data != 0) tvAccent.data else ContextCompat.getColor(this@MainActivity, R.color.accent_sapphire)

                        val tvMuted = android.util.TypedValue()
                        theme.resolveAttribute(R.attr.themeTextMuted, tvMuted, true)
                        val inactiveColor = if (tvMuted.data != 0) tvMuted.data else ContextCompat.getColor(this@MainActivity, R.color.text_subtle)

                        if (!isOn) {
                            binding.ivTabMic.setImageResource(R.drawable.ic_mic_jarvis)
                            binding.ivTabMic.imageTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#94A3B8"))
                            binding.tvTabMic.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                            binding.pillTabMic.setBackgroundResource(R.drawable.bg_dock_mic_off)
                            binding.pillTabMic.animate().cancel()
                            binding.pillTabMic.alpha = 0.85f
                            binding.pillTabMic.scaleX = 1f
                            binding.pillTabMic.scaleY = 1f
                        } else if (isMuted) {
                            binding.ivTabMic.setImageResource(R.drawable.ic_mic_mute_jarvis)
                            binding.ivTabMic.imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
                            binding.tvTabMic.setTextColor(android.graphics.Color.WHITE)
                            binding.pillTabMic.setBackgroundResource(R.drawable.bg_dock_mic_muted)
                            binding.pillTabMic.animate().cancel()
                            binding.pillTabMic.alpha = 1f
                            binding.pillTabMic.scaleX = 1f
                            binding.pillTabMic.scaleY = 1f
                        } else {
                            binding.ivTabMic.setImageResource(R.drawable.ic_mic_jarvis)
                            binding.ivTabMic.imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
                            binding.tvTabMic.setTextColor(android.graphics.Color.WHITE)
                            binding.pillTabMic.setBackgroundResource(R.drawable.bg_dock_mic_highlight)
                            binding.pillTabMic.alpha = 1f
                            if (state == ConversationState.LISTENING || state == ConversationState.SPEAKING) {
                                binding.pillTabMic.animate()
                                    .scaleX(1.08f)
                                    .scaleY(1.08f)
                                    .setDuration(300)
                                    .start()
                            } else {
                                binding.pillTabMic.animate().cancel()
                                binding.pillTabMic.scaleX = 1f
                                binding.pillTabMic.scaleY = 1f
                            }
                        }
                    }
                }

                launch {
                    viewModel.eventFlow.collect { message ->
                        activeSnackbar?.dismiss()
                        activeSnackbar = Snackbar.make(binding.rootMainLayout, message, Snackbar.LENGTH_SHORT).apply {
                            setAnchorView(binding.bottomDock)
                            show()
                        }
                    }
                }
            }
        }
    }
}
