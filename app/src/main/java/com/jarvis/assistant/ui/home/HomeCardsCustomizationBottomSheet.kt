package com.jarvis.assistant.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.androllm.HomeDashboardPrefs
import com.jarvis.assistant.databinding.BottomSheetHomeCardsCustomizationBinding
import com.jarvis.assistant.util.FrostedGlassEffect
import com.jarvis.assistant.util.HudSoundManager
import kotlin.math.roundToInt

class HomeCardsCustomizationBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetHomeCardsCustomizationBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }

    var onCustomizationChanged: (() -> Unit)? = null

    /** True while loadState() pushes prefs into the switches (their listeners must not echo). */
    private var loading = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetHomeCardsCustomizationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        FrostedGlassEffect.applyWindowBlur(dialog?.window, blurRadius = 45)
        // Open fully so every section (layout, HUD size, cards) is reachable by scrolling.
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadState()
        setupListeners()
    }

    private fun loadState() {
        val b = _binding ?: return
        val ctx = requireContext()
        loading = true

        // 0. Layout, HUD size & character (same prefs HomeFragment.applyCustomizationPreferences reads)
        b.switchHideAllCards.isChecked = preferences.hideAllHomeCards
        b.switchCharacter3d.isChecked = preferences.orbEntityType != "orb"
        b.switchCompactStage.isChecked = preferences.isOrbCinematic50
        val scaleProgress = ((preferences.orbScale - HUD_SCALE_MIN) / HUD_SCALE_STEP).roundToInt().coerceIn(0, HUD_SCALE_STEPS)
        b.seekHudScale.max = HUD_SCALE_STEPS
        b.seekHudScale.progress = scaleProgress
        b.tvHudScaleValue.text = formatScale(HUD_SCALE_MIN + scaleProgress * HUD_SCALE_STEP)

        // 1. Primary HUD
        b.switchStatusIndicator.isChecked = preferences.showStatusIndicatorWidget
        b.switchWeather.isChecked = preferences.showWeatherWidget
        b.switchTelemetry.isChecked = preferences.showTelemetryWidget
        b.switchLocalAiDashboard.isChecked = HomeDashboardPrefs.isEnabled(ctx)

        // 2. Quick Actions
        b.switchQuickActionsBar.isChecked = preferences.showQuickActionsWidget
        b.switchScreenShare.isChecked = preferences.showScreenShareButton
        b.switchMemories.isChecked = preferences.showMemoriesButton
        b.switchLanguage.isChecked = preferences.showLanguageButton

        // 3. Tactical Protocols
        b.switchProtocolsSection.isChecked = preferences.showProtocolsWidget
        b.switchPocketTtsCard.isChecked = preferences.showPocketTtsCard
        b.switchMatrixCard.isChecked = preferences.showMatrixCard

        // 4. Updates Section
        b.switchUpdatesSection.isChecked = preferences.showUpdatesWidget
        b.switchUpdateAgent.isChecked = preferences.showUpdateAgentCard
        b.switchUpdateVoice.isChecked = preferences.showUpdateVoiceCard
        b.switchUpdateGodsEye.isChecked = preferences.showUpdateGodsEyeCard
        loading = false
    }

    private fun formatScale(v: Float) = String.format(java.util.Locale.US, "%.2fx", v)

    private fun setupListeners() {
        val b = _binding ?: return
        val ctx = requireContext()

        fun notifyChange() {
            if (loading) return
            HudSoundManager.playTapSound()
            onCustomizationChanged?.invoke()
            // Opened from Home: refresh it live. Opened from Settings: Home re-applies these
            // prefs itself in onResume/onHiddenChanged when the user goes back to it.
            (parentFragment as? HomeFragment)?.applyCustomizationPreferencesExternal()
        }

        // Layout, HUD size & character (merged from Settings "Home HUD & widgets" + "Hide home screen cards")
        b.rowHideAllCards.setOnClickListener { b.switchHideAllCards.toggle() }
        b.switchHideAllCards.setOnCheckedChangeListener { _, isChecked ->
            if (loading) return@setOnCheckedChangeListener
            preferences.hideAllHomeCards = isChecked
            notifyChange()
        }

        b.rowCharacter3d.setOnClickListener { b.switchCharacter3d.toggle() }
        b.switchCharacter3d.setOnCheckedChangeListener { _, isChecked ->
            if (loading) return@setOnCheckedChangeListener
            preferences.userExplicitlyChoseEntity = true
            if (isChecked) {
                if (preferences.orbEntityType == "orb") {
                    preferences.orbEntityType = "mora_cyber"
                    runCatching {
                        com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(ctx, com.jarvis.assistant.util.ModelVaultManager.BUILTIN_MORA.id)
                    }
                }
            } else {
                preferences.orbEntityType = "orb"
            }
            notifyChange()
        }

        b.rowCompactStage.setOnClickListener { b.switchCompactStage.toggle() }
        b.switchCompactStage.setOnCheckedChangeListener { _, isChecked ->
            if (loading) return@setOnCheckedChangeListener
            preferences.isOrbCinematic50 = isChecked
            notifyChange()
        }

        b.seekHudScale.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val scale = HUD_SCALE_MIN + progress * HUD_SCALE_STEP
                b.tvHudScaleValue.text = formatScale(scale)
                if (fromUser && !loading) preferences.orbScale = scale
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                notifyChange()
            }
        })

        // Show All
        b.btnShowAllHomeCards.setOnClickListener {
            preferences.hideAllHomeCards = false
            preferences.showStatusIndicatorWidget = true
            preferences.showWeatherWidget = true
            preferences.showTelemetryWidget = true
            HomeDashboardPrefs.setEnabled(ctx, true)

            preferences.showQuickActionsWidget = true
            preferences.showScreenShareButton = true
            preferences.showMemoriesButton = true
            preferences.showLanguageButton = true

            preferences.showProtocolsWidget = true
            preferences.showPocketTtsCard = true
            preferences.showMatrixCard = true

            preferences.showUpdatesWidget = true
            preferences.showUpdateAgentCard = true
            preferences.showUpdateVoiceCard = true
            preferences.showUpdateGodsEyeCard = true

            loadState()
            notifyChange()
        }


        // Primary HUD
        b.switchStatusIndicator.setOnCheckedChangeListener { _, isChecked ->
            preferences.showStatusIndicatorWidget = isChecked
            notifyChange()
        }

        b.switchWeather.setOnCheckedChangeListener { _, isChecked ->
            preferences.showWeatherWidget = isChecked
            notifyChange()
        }

        b.switchTelemetry.setOnCheckedChangeListener { _, isChecked ->
            preferences.showTelemetryWidget = isChecked
            notifyChange()
        }

        b.switchLocalAiDashboard.setOnCheckedChangeListener { _, isChecked ->
            HomeDashboardPrefs.setEnabled(ctx, isChecked)
            notifyChange()
        }

        // Quick Actions
        b.switchQuickActionsBar.setOnCheckedChangeListener { _, isChecked ->
            preferences.showQuickActionsWidget = isChecked
            notifyChange()
        }

        b.switchScreenShare.setOnCheckedChangeListener { _, isChecked ->
            preferences.showScreenShareButton = isChecked
            notifyChange()
        }

        b.switchMemories.setOnCheckedChangeListener { _, isChecked ->
            preferences.showMemoriesButton = isChecked
            notifyChange()
        }

        b.switchLanguage.setOnCheckedChangeListener { _, isChecked ->
            preferences.showLanguageButton = isChecked
            notifyChange()
        }

        // Tactical Protocols
        b.switchProtocolsSection.setOnCheckedChangeListener { _, isChecked ->
            preferences.showProtocolsWidget = isChecked
            notifyChange()
        }

        b.switchPocketTtsCard.setOnCheckedChangeListener { _, isChecked ->
            preferences.showPocketTtsCard = isChecked
            notifyChange()
        }

        b.switchMatrixCard.setOnCheckedChangeListener { _, isChecked ->
            preferences.showMatrixCard = isChecked
            notifyChange()
        }

        // Updates
        b.switchUpdatesSection.setOnCheckedChangeListener { _, isChecked ->
            preferences.showUpdatesWidget = isChecked
            notifyChange()
        }

        b.switchUpdateAgent.setOnCheckedChangeListener { _, isChecked ->
            preferences.showUpdateAgentCard = isChecked
            notifyChange()
        }

        b.switchUpdateVoice.setOnCheckedChangeListener { _, isChecked ->
            preferences.showUpdateVoiceCard = isChecked
            notifyChange()
        }

        b.switchUpdateGodsEye.setOnCheckedChangeListener { _, isChecked ->
            preferences.showUpdateGodsEyeCard = isChecked
            notifyChange()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "HomeCardsCustomizationBottomSheet"
        /** Same range/step as Customization Studio's orb scale slider (0.70x .. 1.30x). */
        private const val HUD_SCALE_MIN = 0.70f
        private const val HUD_SCALE_STEP = 0.05f
        private const val HUD_SCALE_STEPS = 12
        fun newInstance() = HomeCardsCustomizationBottomSheet()
    }
}
