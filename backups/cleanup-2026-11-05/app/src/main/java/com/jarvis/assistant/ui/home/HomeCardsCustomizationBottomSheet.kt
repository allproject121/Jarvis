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

class HomeCardsCustomizationBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetHomeCardsCustomizationBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }

    var onCustomizationChanged: (() -> Unit)? = null

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
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadState()
        setupListeners()
    }

    private fun loadState() {
        val b = _binding ?: return
        val ctx = requireContext()

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
        b.switchWallpaperCard.isChecked = preferences.showWallpaperCard
        b.switchAppLockCard.isChecked = preferences.showAppLockCard
        b.switchPocketTtsCard.isChecked = preferences.showPocketTtsCard
        b.switchMatrixCard.isChecked = preferences.showMatrixCard

        // 4. Updates Section
        b.switchUpdatesSection.isChecked = preferences.showUpdatesWidget
        b.switchUpdateAgent.isChecked = preferences.showUpdateAgentCard
        b.switchUpdateVoice.isChecked = preferences.showUpdateVoiceCard
        b.switchUpdateGodsEye.isChecked = preferences.showUpdateGodsEyeCard
    }

    private fun setupListeners() {
        val b = _binding ?: return
        val ctx = requireContext()

        fun notifyChange() {
            HudSoundManager.playTapSound()
            onCustomizationChanged?.invoke()
            (parentFragment as? HomeFragment)?.applyCustomizationPreferencesExternal()
        }

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
            preferences.showWallpaperCard = true
            preferences.showAppLockCard = true
            preferences.showPocketTtsCard = true
            preferences.showMatrixCard = true

            preferences.showUpdatesWidget = true
            preferences.showUpdateAgentCard = true
            preferences.showUpdateVoiceCard = true
            preferences.showUpdateGodsEyeCard = true

            loadState()
            notifyChange()
        }

        // Hide All
        b.btnHideAllHomeCards.setOnClickListener {
            preferences.hideAllHomeCards = true
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

        b.switchWallpaperCard.setOnCheckedChangeListener { _, isChecked ->
            preferences.showWallpaperCard = isChecked
            notifyChange()
        }

        b.switchAppLockCard.setOnCheckedChangeListener { _, isChecked ->
            preferences.showAppLockCard = isChecked
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
        fun newInstance() = HomeCardsCustomizationBottomSheet()
    }
}
