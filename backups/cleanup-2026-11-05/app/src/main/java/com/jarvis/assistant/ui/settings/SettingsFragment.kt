package com.jarvis.assistant.ui.settings

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.backup.BackupManager
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.FragmentSettingsBinding
import com.jarvis.assistant.security.AppIdentityVerifier
import com.jarvis.assistant.security.guardian.EmergencySosManager
import com.jarvis.assistant.security.guardian.TouchGuardManager
import com.jarvis.assistant.security.guardian.WatchmanSecurityManager
import com.jarvis.assistant.social.SocialStoryManager
import com.jarvis.assistant.ui.facelock.JarvisAppLockActivity
import com.jarvis.assistant.ui.godseye.GodsEyeActivity
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.matrix.JarvisMatrixActivity
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.ui.supercore.SuperCoreActivity
import com.jarvis.assistant.androllm.accessibility.settings.AccessibilitySettingsStore
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity
import com.jarvis.assistant.util.HudSoundManager
import com.jarvis.assistant.voice.normalize.NormalizationSettingsStore
import com.jarvis.assistant.wallpaper.JarvisWallpaperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }
    private val accessibilityStore by lazy { AccessibilitySettingsStore.getInstance(requireContext()) }
    private var isUpdatingSwitchProgrammatically = false
    /** Maya group (characters, Maya screens, overlay, wallpaper, licences). */
    private var mayaRows: com.jarvis.assistant.maya.ui.MayaSettingsRows? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val statusBarInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val topInset = statusBarInsets.top
            val safeTopPadding = if (topInset > 0) topInset + (6 * density).toInt() else (14 * density).toInt()
            v.setPadding(v.paddingLeft, safeTopPadding, v.paddingRight, v.paddingBottom)
            insets
        }

        loadUserData()
        setupClickListeners()
        setupObservers()
        setupLocalAiRows()
        mayaRows = com.jarvis.assistant.maya.ui.MayaSettingsRows(this).also { it.bind(binding.root) }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
            setupLocalAiRows()
            mayaRows?.refresh()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
            mayaRows?.refresh()
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    private fun loadUserData() {
        val userName = preferences.userName.ifBlank { "Roshan" }
        val isCompanion = preferences.isJarvisCompanionMode
        binding.tvProfileSubtitle.text = if (isCompanion) "JARVIS PROTOCOL â€¢ PERSONAL COMPANION" else "JARVIS OPERATOR â€¢ PRIMARY USER"
        binding.tvProfilePersonaChip.text = if (isCompanion) "JARVIS MODE" else "${preferences.personality.uppercase(Locale.ROOT)} MODE"

        // Switch states
        isUpdatingSwitchProgrammatically = true
        try {
            binding.switchJarvisMode.isChecked = isCompanion
            binding.switchPrivacyMode.isChecked = preferences.isPrivacyModeEnabled
            binding.switchDirectAutonomousMode.isChecked = preferences.isDirectAutonomousMode
            binding.switchSilentMode.isChecked = preferences.isSilentExecutionMode
            binding.switchBlurEffect.isChecked = preferences.isBlurEffectEnabled
            binding.switchBatterySaver.isChecked = preferences.isBatterySaverEnabled
            binding.switchDeveloperLinks.isChecked = preferences.showDeveloperLinks

            // AndroLLM Switches & Accordion initial states
            val normalization = NormalizationSettingsStore.run {
                load(requireContext())
                current()
            }
            binding.switchNormalizeNumbers.isChecked = normalization.numbers
            binding.switchNormalizeCurrency.isChecked = normalization.currency
            binding.switchSentenceAssembler.isChecked = preferences.isSentenceAssemblerEnabled
        } finally {
            isUpdatingSwitchProgrammatically = false
        }

        // Auto-scroll lives in the accessibility DataStore, so it loads asynchronously.
        lifecycleScope.launch {
            val autoScroll = accessibilityStore.current().autoScrollIntoView
            if (_binding == null) return@launch
            isUpdatingSwitchProgrammatically = true
            try {
                binding.switchAutoScroll.isChecked = autoScroll
            } finally {
                isUpdatingSwitchProgrammatically = false
            }
        }

        // Accessibility status badge
        val isAccessibilityActive = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.isRunning()
        if (isAccessibilityActive) {
            binding.tvAccessibilityStatusBadge.text = "ACTIVE"
            binding.tvAccessibilityStatusBadge.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.tvAccessibilityStatusBadge.text = "INACTIVE"
            binding.tvAccessibilityStatusBadge.setTextColor(Color.parseColor("#EF4444"))
        }

        // Model Hub status
        val localModelMgr = com.jarvis.assistant.ai.local.LocalModelManager.getInstance(requireContext(), viewLifecycleOwner.lifecycleScope)
        val activeModelId = localModelMgr.activeModelId.value
        val activeDescriptor = com.jarvis.assistant.ai.local.LocalAiConfig.AVAILABLE_MODELS.find { it.id == activeModelId }
        val activeModelName = activeDescriptor?.name ?: "Qwen 2.5 (1.5B Instruct)"
        binding.tvModelHubActiveModel.text = "Active: $activeModelName â€¢ ${com.jarvis.assistant.ai.local.LocalAiConfig.AVAILABLE_MODELS.size} Models Available"

        if (localModelMgr.isModelReady(com.jarvis.assistant.ai.local.LocalAiConfig.MODEL_LLAMA_3_2_1B)) {
            binding.btnDownloadLlama.text = "Loaded & Ready (809 MB)"
            binding.btnDownloadLlama.isEnabled = true
        } else {
            binding.btnDownloadLlama.text = "Download Weights (809 MB)"
            binding.btnDownloadLlama.isEnabled = true
        }

        // Voice profile status
        val hasVoiceProfile = preferences.hasOwnerVoiceProfile()
        if (hasVoiceProfile) {
            binding.tvProfileVoiceStatus.text = "VOICE ENROLLED"
            binding.tvProfileVoiceStatus.setTextColor(Color.parseColor("#10B981"))
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_verified_badge)
            binding.ivProfileVoiceBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981"))
        } else {
            binding.tvProfileVoiceStatus.text = "ENROLL VOICE"
            binding.tvProfileVoiceStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_settings_owner_voice)
            binding.ivProfileVoiceBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.accent_sapphire)
            )
        }

        // Section 1: Assistant & Persona
        val directLabel = if (preferences.isDirectAutonomousMode) "âš¡ Direct Mode" else "Safe Mode"
        binding.tvCurrentAssistantName.text = "${preferences.assistantName} (${preferences.personality.capitalize(Locale.ROOT)}) â€¢ $directLabel"
        binding.tvVoiceEngineBadge.text = com.jarvis.assistant.ai.router.AiModelMode.fromString(preferences.aiModelMode).displayName
        binding.tvSecretSettingsBadge.text = if (preferences.isSecretSettingsEnabled) "INTIMACY LV ${preferences.secretIntimacyLevel}" else "DISABLED"
        binding.tvCurrentLanguage.text = preferences.language.uppercase(Locale.ROOT)
        binding.tvCurrentPersona.text = if (isCompanion) "JARVIS Companion Mode" else preferences.personality.capitalize(Locale.ROOT)
        binding.switchDirectAutonomousMode.isChecked = preferences.isDirectAutonomousMode
        binding.tvOwnerVoiceBadge.text = if (hasVoiceProfile) "VERIFIED" else "SETUP"
        binding.tvOwnerVoiceSubtitle.text = com.jarvis.assistant.audio.OwnerVoiceManager.voiceLockStatusLine(preferences)
        binding.switchSilentMode.isChecked = preferences.isSilentExecutionMode

        // Section 4: Security
        val isTouchArmed = TouchGuardManager.isArmed()
        binding.tvTouchGuardBadge.text = if (isTouchArmed) "ARMED" else "DISARMED"

        // Section 5: Studio & Theme
        binding.tvStudioSummary.text = "Arc Reactor, pulse rings & glow"
        binding.tvCurrentTheme.text = preferences.appTheme.uppercase(Locale.ROOT)
        binding.switchBlurEffect.isChecked = preferences.isBlurEffectEnabled
        binding.switchBatterySaver.isChecked = preferences.isBatterySaverEnabled

        // Section 6: Brain & Developer
        binding.tvCurrentModel.text = preferences.aiModel
        binding.switchDeveloperLinks.isChecked = preferences.showDeveloperLinks
        binding.containerDeveloperLinks.visibility = if (preferences.showDeveloperLinks) View.VISIBLE else View.GONE
    }

    private fun setupClickListeners() {
        binding.btnSettingsBack.setOnClickListener {
            HudSoundManager.playTapSound()
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_HOME) ?: activity?.onBackPressedDispatcher?.onBackPressed()
        }
        binding.cardSettingsCustomizeHome.setOnClickListener {
            HudSoundManager.playTapSound()
            com.jarvis.assistant.ui.home.HomeCardsCustomizationBottomSheet.newInstance().show(
                parentFragmentManager,
                com.jarvis.assistant.ui.home.HomeCardsCustomizationBottomSheet.TAG
            )
        }
        // Profile Card -> Edit name
        binding.cardProfile.setOnClickListener {
            showEditNameDialog()
        }

        binding.chipProfileVoiceStatus.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        // ==================== QUICK PROTOCOLS STRIP ====================
        binding.rowSuperCore.setOnClickListener {
            startActivity(Intent(requireContext(), SuperCoreActivity::class.java))
        }

        binding.rowMatrixTools.setOnClickListener {
            startActivity(Intent(requireContext(), JarvisMatrixActivity::class.java))
        }

        binding.rowQuickStudio.setOnClickListener {
            startActivity(Intent(requireContext(), CustomizationStudioActivity::class.java))
        }

        binding.rowQuickGodsEye.setOnClickListener {
            startActivity(Intent(requireContext(), GodsEyeActivity::class.java))
        }

        // ==================== ANDROLLM ACCORDIONS & TELEMETRY ====================
        setupAccordion(binding.accordionModelHubHeader, binding.accordionModelHubBody, binding.ivAccordionModelHubChevron)
        setupAccordion(binding.accordionMcpHeader, binding.accordionMcpBody, binding.ivAccordionMcpChevron)
        setupAccordion(binding.accordionAccessibilityHeader, binding.accordionAccessibilityBody, binding.ivAccordionAccessibilityChevron)
        setupAccordion(binding.accordionNormalizationHeader, binding.accordionNormalizationBody, binding.ivAccordionNormalizationChevron)

        binding.cardTelemetryDashboard.setOnClickListener {
            HudSoundManager.playTapSound()
            showTelemetryDetailDialog()
        }

        val localModelManager = com.jarvis.assistant.ai.local.LocalModelManager.getInstance(requireContext(), viewLifecycleOwner.lifecycleScope)
        binding.btnDownloadLlama.setOnClickListener {
            HudSoundManager.playTapSound()
            localModelManager.downloadModel(com.jarvis.assistant.ai.local.LocalAiConfig.MODEL_LLAMA_3_2_1B)
        }

        binding.btnAddMcpServer.setOnClickListener {
            HudSoundManager.playTapSound()
            showAddMcpServerDialog()
        }
        renderMcpServersList()

        // Merged AndroLLM pages, opened inside Jarvis (AndroLlmPageActivity)
        binding.btnOpenModelManager.setOnClickListener {
            openAndroLlmRoute(com.jarvis.assistant.ui.compose.navigation.ComposeRoutes.MODELS)
        }
        binding.btnManageMcpServers.setOnClickListener {
            openAndroLlmSection(AndroLlmPageActivity.Section.MCP)
        }
        binding.btnOpenScreenAutomation.setOnClickListener {
            openAndroLlmSection(AndroLlmPageActivity.Section.AUTOMATION)
        }
        binding.btnOpenVoiceNormalization.setOnClickListener {
            openAndroLlmSection(AndroLlmPageActivity.Section.VOICE)
        }

        binding.switchAutoScroll.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener
            HudSoundManager.playTapSound()
            // The accessibility controller reads AccessibilitySettings.autoScrollIntoView
            // (AccessibilityController.kt), not an AppPreferences flag.
            lifecycleScope.launch {
                accessibilityStore.update { it.copy(autoScrollIntoView = isChecked) }
            }
            val msg = if (isChecked) "Auto-scroll elements into view enabled" else "Auto-scroll disabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenAccessibilitySettings.setOnClickListener {
            HudSoundManager.playTapSound()
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Unable to open Accessibility Settings", Toast.LENGTH_SHORT).show()
            }
        }

        // The TTS pipeline reads NormalizationSettingsStore (TextNormalizationPipeline),
        // so these switches must write there â€” AppPreferences flags were never read.
        binding.switchNormalizeNumbers.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener
            NormalizationSettingsStore.update(requireContext()) { it.copy(numbers = isChecked) }
            HudSoundManager.playTapSound()
        }

        binding.switchNormalizeCurrency.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener
            NormalizationSettingsStore.update(requireContext()) { it.copy(currency = isChecked) }
            HudSoundManager.playTapSound()
        }

        binding.switchSentenceAssembler.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener
            preferences.isSentenceAssemblerEnabled = isChecked
            HudSoundManager.playTapSound()
        }

        // ==================== SECTION 1: ASSISTANT & NEURAL CORE ====================
        binding.rowAssistantConfig.setOnClickListener {
            showAssistantNameDialog()
        }

        binding.rowPersonaVoice.setOnClickListener {
            showPersonaSelectionDialog()
        }

        binding.rowJarvisModeSwitch.setOnClickListener {
            binding.switchJarvisMode.toggle()
        }

        binding.switchJarvisMode.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener
            HudSoundManager.playTapSound()
            preferences.activateJarvisCompanionMode(isChecked)
            loadUserData()
            (activity as? MainActivity)?.switchExperienceMode(preferences.experienceMode)
            val msg = if (isChecked) "JARVIS Companion Mode activated â€¢ Sweet voice & Live 3D Companion" else "Jarvis System Mode activated"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.rowPrivacyMode.setOnClickListener {
            binding.switchPrivacyMode.toggle()
        }

        binding.switchPrivacyMode.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener
            HudSoundManager.playTapSound()
            preferences.isPrivacyModeEnabled = isChecked
            val msg = if (isChecked) "Privacy Mode ON: Sensitive chat logs & readouts shielded" else "Privacy Mode OFF: Standard display"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.rowSecretSettings.setOnClickListener {
            HudSoundManager.playTapSound()
            SecretSettingsBottomSheet.newInstance().show(childFragmentManager, SecretSettingsBottomSheet.TAG)
        }

        binding.rowAssistantMemory.setOnClickListener {
            startActivity(Intent(requireContext(), MemoryActivity::class.java))
        }

        binding.rowVoiceEngine.setOnClickListener {
            showVoiceEngineDialog()
        }

        binding.rowLanguage.setOnClickListener {
            showLanguageDialog()
        }

        binding.rowDirectAutonomousMode.setOnClickListener {
            binding.switchDirectAutonomousMode.toggle()
        }

        binding.switchDirectAutonomousMode.setOnCheckedChangeListener { _, isChecked ->
            preferences.isDirectAutonomousMode = isChecked
            val msg = if (isChecked) {
                "âš¡ Direct Autonomous Execution Mode ACTIVE (Zero confirmations, instant actions)"
            } else {
                "Safe Mode active (Commands require confirmation)"
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            loadUserData()
        }

        binding.rowOwnerVoice.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        binding.rowSilentMode.setOnClickListener {
            binding.switchSilentMode.toggle()
        }

        binding.switchSilentMode.setOnCheckedChangeListener { _, isChecked ->
            preferences.isSilentExecutionMode = isChecked
            val msg = if (isChecked) "Silent execution mode enabled" else "Standard voice responses enabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // ==================== SECTION 2: CORE INTELLIGENCE & AGENTS ====================
        binding.rowSkills.setOnClickListener {
            HudSoundManager.playTapSound()
            SkillsBottomSheet.newInstance().show(childFragmentManager, SkillsBottomSheet.TAG)
        }

        binding.rowSubAgents.setOnClickListener {
            HudSoundManager.playTapSound()
            SubAgentsBottomSheet.newInstance().show(childFragmentManager, SubAgentsBottomSheet.TAG)
        }

        binding.rowApplications.setOnClickListener {
            startActivity(Intent(requireContext(), HandsFreeActivity::class.java))
        }

        binding.rowPermissions.setOnClickListener {
            startActivity(Intent(requireContext(), PermissionActivity::class.java))
        }

        binding.rowOverlayAvatars.setOnClickListener {
            startActivity(Intent(requireContext(), OverlayAvatarActivity::class.java))
        }

        // ==================== SECTION 3: MESSAGES, DISPATCH & AUTOMATION ====================
        binding.rowWhatsApp.setOnClickListener {
            showWhatsAppDialog()
        }

        binding.rowEmail.setOnClickListener {
            showEmailDialog()
        }

        binding.rowSocialStory.setOnClickListener {
            showSocialMediaDialog()
        }

        binding.rowConnectors.setOnClickListener {
            HudSoundManager.playTapSound()
            com.jarvis.assistant.connectors.AppConnectorsBottomSheet.newInstance().show(childFragmentManager, com.jarvis.assistant.connectors.AppConnectorsBottomSheet.TAG)
        }

        // ==================== SECTION 4: SECURITY, GUARDIAN & PRIVACY ====================
        binding.rowTouchGuard.setOnClickListener {
            showTouchGuardDialog()
        }

        binding.rowEmergencySos.setOnClickListener {
            showEmergencySosDialog()
        }

        binding.rowWatchman.setOnClickListener {
            showWatchmanLogDialog()
        }

        binding.rowPrivacyPolicy.setOnClickListener {
            startActivity(Intent(requireContext(), PrivacyShieldActivity::class.java))
        }

        binding.rowAppIdentity.setOnClickListener {
            showAppIdentityDialog()
        }

        // ==================== SECTION 5: STUDIO, APPEARANCE & SYSTEM ====================
        binding.rowCustomizationStudio.setOnClickListener {
            startActivity(Intent(requireContext(), CustomizationStudioActivity::class.java))
        }

        binding.rowAppTheme.setOnClickListener {
            ThemeSelectionBottomSheet.newInstance().show(childFragmentManager, ThemeSelectionBottomSheet.TAG)
        }

        binding.rowFrostedGlassBlur.setOnClickListener {
            binding.switchBlurEffect.toggle()
        }

        binding.switchBlurEffect.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener
            preferences.isBlurEffectEnabled = isChecked
            Toast.makeText(requireContext(), if (isChecked) "Liquid Glass effect enabled" else "Liquid Glass disabled", Toast.LENGTH_SHORT).show()
            activity?.recreate()
        }

        binding.switchHideAllHomeCards.isChecked = preferences.hideAllHomeCards
        binding.rowHideAllHomeCards.setOnClickListener {
            binding.switchHideAllHomeCards.toggle()
        }
        binding.switchHideAllHomeCards.setOnCheckedChangeListener { _, isChecked ->
            preferences.hideAllHomeCards = isChecked
            val msg = if (isChecked) "Home Screen Cards hidden â€¢ Full-screen Mora active" else "Home Screen Cards restored"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.rowHomeWidgetsConfig.setOnClickListener {
            HudSoundManager.playTapSound()
            showHomeWidgetsDialog()
        }

        binding.rowDrivingMode.setOnClickListener {
            showDrivingModeDialog()
        }

        binding.rowPcPhone.setOnClickListener {
            showPcHubDialog()
        }

        binding.rowBatterySaver.setOnClickListener {
            binding.switchBatterySaver.toggle()
        }

        binding.switchBatterySaver.setOnCheckedChangeListener { _, isChecked ->
            preferences.isBatterySaverEnabled = isChecked
            val msg = if (isChecked) {
                "Battery saver ON: telemetry sampling every 5 s (was 1 s) and slower Dynamic Island polling"
            } else {
                "Battery saver OFF: telemetry back to 1 s sampling"
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.rowJarvisRules.setOnClickListener {
            showJarvisRulesDialog()
        }

        binding.rowBackupRestore.setOnClickListener {
            showBackupRestoreDialog()
        }

        // ==================== SECTION 6: BRAIN, MODEL & DEVELOPER ====================
        binding.rowApiConnection.setOnClickListener {
            startActivity(Intent(requireContext(), ApiConnectionActivity::class.java))
        }

        binding.rowGlobalApiCatalog.setOnClickListener {
            ApiRegistryBottomSheet().show(childFragmentManager, "ApiRegistryBottomSheet")
        }



        binding.rowDeveloperLinksToggle.setOnClickListener {
            binding.switchDeveloperLinks.toggle()
        }

        binding.switchDeveloperLinks.setOnCheckedChangeListener { _, isChecked ->
            preferences.showDeveloperLinks = isChecked
            binding.containerDeveloperLinks.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.btnSocialEmail.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:tiwariroshan2601@gmail.com")
                    putExtra(Intent.EXTRA_SUBJECT, "Jarvis AI Assistant Inquiry")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Email: tiwariroshan2601@gmail.com", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnSocialGithub1.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tiwwtiroshan121")))
            } catch (_: Exception) {}
        }

        binding.btnSocialGithub2.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/freeid2601-dotcom")))
            } catch (_: Exception) {}
        }

        binding.tvFooterCredit.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tiwwtiroshan121")))
            } catch (_: Exception) {}
        }
    }

    // ==================== DIALOG IMPLEMENTATIONS ====================

    private fun showAssistantDialog() {
        val directStatus = if (preferences.isDirectAutonomousMode) "ACTIVE (Instant Direct Execution)" else "OFF (Requires Confirmations)"
        val secretStatus = if (preferences.isSecretSettingsEnabled) "LV ${preferences.secretIntimacyLevel}" else "OFF"

        val options = arrayOf(
            "ðŸ·ï¸ Assistant Name: ${preferences.assistantName}",
            "ðŸŽ­ Persona: ${preferences.personality.capitalize(Locale.ROOT)}",
            "âš¡ Direct Autonomous Execution: $directStatus",
            "ðŸ”’ Secret Settings (Companion / Intimacy): $secretStatus",
            "ðŸŒ Spoken Language: ${preferences.language}",
            "ðŸ§  Neural Memory Vault (Mem0 & Muse)"
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("${preferences.assistantName} System Intelligence")
            .setItems(options) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> showAssistantNameDialog()
                    1 -> showPersonaSelectionDialog()
                    2 -> {
                        preferences.isDirectAutonomousMode = !preferences.isDirectAutonomousMode
                        val state = if (preferences.isDirectAutonomousMode) "Direct Autonomous Mode ENABLED (Zero delays, instant actions)" else "Standard Safe Mode enabled"
                        Toast.makeText(requireContext(), state, Toast.LENGTH_LONG).show()
                        loadUserData()
                    }
                    3 -> showSecretSettingsDialog()
                    4 -> showLanguageDialog()
                    5 -> startActivity(Intent(requireContext(), MemoryActivity::class.java))
                }
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showAssistantNameDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_hud_assistant_name, null, false)
        val etName = dialogView.findViewById<EditText>(R.id.etDialogAssistantName)
        val btnJarvis = dialogView.findViewById<TextView>(R.id.btnDialogChipJarvis)
        val btnJarvisCompanion = dialogView.findViewById<TextView>(R.id.btnDialogChipJarvisCompanion)
        val btnFriday = dialogView.findViewById<TextView>(R.id.btnDialogChipFriday)
        val btnEdith = dialogView.findViewById<TextView>(R.id.btnDialogChipEdith)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnDialogCancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btnDialogSave)

        etName.setText(preferences.assistantName)
        etName.setSelection(etName.text.length)

        val chips = listOf(
            btnJarvis to "JARVIS",
            btnJarvisCompanion to "JARVIS",
            btnFriday to "FRIDAY",
            btnEdith to "EDITH"
        )

        fun updateChips(active: String) {
            val upper = active.uppercase(Locale.ROOT)
            chips.forEach { (chip, key) ->
                if (key == upper) {
                    chip.setBackgroundResource(R.drawable.bg_jarvis_chip)
                    chip.setTextColor(com.jarvis.assistant.util.ThemeManager.getThemeAccentColor(requireContext()))
                } else {
                    chip.setBackgroundResource(R.drawable.bg_jarvis_row_item)
                    chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                }
            }
        }

        updateChips(preferences.assistantName)

        chips.forEach { (chip, key) ->
            chip.setOnClickListener {
                HudSoundManager.playTapSound()
                etName.setText(key)
                etName.setSelection(key.length)
                updateChips(key)
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val newName = etName.text.toString().trim()
            if (newName.isNotEmpty()) {
                preferences.assistantName = newName
                if (newName.equals("JARVIS", ignoreCase = true)) {
                    preferences.activateJarvisCompanionMode(true)
                    (activity as? MainActivity)?.switchExperienceMode(preferences.experienceMode)
                }
                loadUserData()
                HudSoundManager.playSuccessSound()
                Toast.makeText(requireContext(), "Assistant identifier set to $newName", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showPersonaSelectionDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_hud_persona_selection, null, false)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val cardJarvisCompanion = dialogView.findViewById<View>(R.id.cardDialogPersonaJarvisCompanion)
        val cardJarvis = dialogView.findViewById<View>(R.id.cardDialogPersonaJarvis)
        val cardFriday = dialogView.findViewById<View>(R.id.cardDialogPersonaFriday)
        val cardEdith = dialogView.findViewById<View>(R.id.cardDialogPersonaEdith)
        val cardPari = dialogView.findViewById<View>(R.id.cardDialogPersonaPari)

        val badgeJarvisCompanion = dialogView.findViewById<View>(R.id.badgeDialogPersonaJarvisCompanion)
        val badgeJarvis = dialogView.findViewById<View>(R.id.badgeDialogPersonaJarvis)
        val badgeFriday = dialogView.findViewById<View>(R.id.badgeDialogPersonaFriday)
        val badgeEdith = dialogView.findViewById<View>(R.id.badgeDialogPersonaEdith)
        val badgePari = dialogView.findViewById<View>(R.id.badgeDialogPersonaPari)

        val active = preferences.personality.lowercase(Locale.ROOT)
        val isCompanionActive = preferences.isJarvisCompanionMode || active == GeminiConstants.PERSONALITY_COMPANION.lowercase() || active == "JARVIS"

        badgeJarvisCompanion.visibility = if (isCompanionActive) View.VISIBLE else View.GONE
        badgeJarvis.visibility = if (!isCompanionActive && active == GeminiConstants.PERSONALITY_ROSHAN.lowercase()) View.VISIBLE else View.GONE
        badgeFriday.visibility = if (active == "friday") View.VISIBLE else View.GONE
        badgeEdith.visibility = if (active == GeminiConstants.PERSONALITY_ASSISTANT.lowercase() || active == "edith") View.VISIBLE else View.GONE
        badgePari.visibility = if (active == GeminiConstants.PERSONALITY_GIRLFRIEND.lowercase() || active == "pari") View.VISIBLE else View.GONE
        dialogView.findViewById<View>(R.id.badgeDialogPersonaMaria)?.visibility =
            if (com.jarvis.assistant.data.model.MariaPersona.isActive(preferences.personality)) View.VISIBLE else View.GONE

        cardJarvisCompanion.setOnClickListener {
            HudSoundManager.playTapSound()
            preferences.activateJarvisCompanionMode(true)
            loadUserData()
            (activity as? MainActivity)?.switchExperienceMode(preferences.experienceMode)
            Toast.makeText(requireContext(), "JARVIS Companion Mode Activated", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        cardJarvis.setOnClickListener {
            HudSoundManager.playTapSound()
            preferences.activateJarvisCompanionMode(false)
            preferences.personality = GeminiConstants.PERSONALITY_ROSHAN
            preferences.voice = "Puck"
            preferences.assistantName = "JARVIS"
            loadUserData()
            (activity as? MainActivity)?.switchExperienceMode(preferences.experienceMode)
            Toast.makeText(requireContext(), "Stark J.A.R.V.I.S. Prime Activated", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        cardFriday.setOnClickListener {
            HudSoundManager.playTapSound()
            preferences.activateJarvisCompanionMode(false)
            preferences.personality = "friday"
            preferences.voice = "Aoede"
            preferences.assistantName = "FRIDAY"
            loadUserData()
            (activity as? MainActivity)?.switchExperienceMode(preferences.experienceMode)
            Toast.makeText(requireContext(), "F.R.I.D.A.Y. Tactical Activated", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        cardEdith.setOnClickListener {
            HudSoundManager.playTapSound()
            preferences.activateJarvisCompanionMode(false)
            preferences.personality = GeminiConstants.PERSONALITY_ASSISTANT
            preferences.voice = "Charon"
            preferences.assistantName = "EDITH"
            loadUserData()
            (activity as? MainActivity)?.switchExperienceMode(preferences.experienceMode)
            Toast.makeText(requireContext(), "E.D.I.T.H. Defense System Activated", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        cardPari.setOnClickListener {
            HudSoundManager.playTapSound()
            preferences.activateJarvisCompanionMode(false)
            preferences.personality = GeminiConstants.PERSONALITY_GIRLFRIEND
            preferences.voice = "Aoede"
            preferences.assistantName = "PARI"
            loadUserData()
            (activity as? MainActivity)?.switchExperienceMode(preferences.experienceMode)
            Toast.makeText(requireContext(), "Pari Romantic Mode Activated", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // Maria (Hinglish assistant persona)
        dialogView.findViewById<View>(R.id.cardDialogPersonaMaria)?.setOnClickListener {
            HudSoundManager.playTapSound()
            com.jarvis.assistant.data.model.MariaPersona.activate(requireContext(), preferences)
            loadUserData()
            (activity as? MainActivity)?.switchExperienceMode(preferences.experienceMode)
            Toast.makeText(requireContext(), "Maria Hinglish Assistant Activated", Toast.LENGTH_SHORT).show()
            com.jarvis.assistant.voice.PocketTtsEngine.speak(
                com.jarvis.assistant.data.model.MariaPersona.randomGreeting(),
                requireContext()
            )
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.btnDialogClosePersona).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showLanguageDialog() {
        val languages = GeminiConstants.SUPPORTED_LANGUAGES.toTypedArray()
        val currentIndex = languages.indexOfFirst { it.equals(preferences.language, ignoreCase = true) }.let { if (it >= 0) it else 0 }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Conversation Language")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val chosen = languages[which]
                preferences.language = chosen
                loadUserData()
                dialog.dismiss()
                Toast.makeText(requireContext(), "Language set to $chosen", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVoiceEngineDialog() {
        val engines = arrayOf(
            "ðŸ§  On-Device Local SLM (Qwen 2.5 â€¢ 100% Offline â€¢ llama.cpp)",
            "ðŸ¤– Hermes Autonomous Agent (Multi-step Tool Calling)",
            "âš¡ Cloud Gemini Live (Low-latency Bidirectional WebSocket)",
            "ðŸ”„ Auto Hybrid (Cloud with Zero-delay Local Failover)",
            "ðŸ’» Workstation PC Core (Ollama 11434 / LAN)"
        )
        val engineModes = arrayOf(
            com.jarvis.assistant.ai.router.AiModelMode.LOCAL_OFFLINE,
            com.jarvis.assistant.ai.router.AiModelMode.HERMES_AGENT,
            com.jarvis.assistant.ai.router.AiModelMode.GEMINI_LIVE,
            com.jarvis.assistant.ai.router.AiModelMode.AUTO_HYBRID,
            com.jarvis.assistant.ai.router.AiModelMode.LOCAL_HOST
        )
        val currentMode = (context?.applicationContext as? com.jarvis.assistant.JarvisApp)?.modelRouter?.currentMode?.value
            ?: com.jarvis.assistant.ai.router.AiModelMode.AUTO_HYBRID
        val currentIdx = engineModes.indexOf(currentMode).let { if (it >= 0) it else 3 }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("AI Voice Engine Architecture")
            .setSingleChoiceItems(engines, currentIdx) { dialog, which ->
                val chosenMode = engineModes[which]
                (context?.applicationContext as? com.jarvis.assistant.JarvisApp)?.modelRouter?.setMode(chosenMode)
                preferences.aiModelMode = chosenMode.name
                preferences.voiceEngineType = chosenMode.name
                loadUserData()
                dialog.dismiss()
                Toast.makeText(requireContext(), "Engine configured: ${chosenMode.displayName}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Manage Local Models") { _, _ ->
                showLocalModelManagerDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLocalModelManagerDialog() {
        val modelManager = com.jarvis.assistant.ai.local.LocalModelManager.getInstance(requireContext(), lifecycleScope)
        val models = com.jarvis.assistant.ai.local.LocalAiConfig.AVAILABLE_MODELS
        val modelItems = models.map { m ->
            val ready = modelManager.isModelReady(m.id)
            val status = if (ready) "âœ… READY (Installed)" else "â¬‡ï¸ Download (${m.formattedSize})"
            "${m.name}\n$status"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("On-Device GGUF Models (llama.cpp)")
            .setItems(modelItems) { _, which ->
                val selectedModel = models[which]
                val isReady = modelManager.isModelReady(selectedModel.id)
                if (isReady) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(selectedModel.name)
                        .setMessage("Model is installed and ready for 100% offline inference and tool execution.\n\nFile: ${modelManager.getModelFile(selectedModel.id).absolutePath}\nSize: ${selectedModel.formattedSize}")
                        .setPositiveButton("Set as Active") { _, _ ->
                            modelManager.setActiveModel(selectedModel.id)
                            Toast.makeText(requireContext(), "${selectedModel.name} is now active.", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Delete Model") { _, _ ->
                            modelManager.deleteModel(selectedModel.id)
                            Toast.makeText(requireContext(), "Deleted ${selectedModel.name}.", Toast.LENGTH_SHORT).show()
                        }
                        .setNeutralButton("Cancel", null)
                        .show()
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Download ${selectedModel.name}")
                        .setMessage("Download on-device GGUF weights (${selectedModel.formattedSize})?\n\n${selectedModel.description}\n\nâ€¢ Zero cloud dependency\nâ€¢ 20+ tokens/second on Dimensity 7200\nâ€¢ Full device control & WhatsApp offline")
                        .setPositiveButton("Start Download") { _, _ ->
                            Toast.makeText(requireContext(), "Starting download of ${selectedModel.name}â€¦", Toast.LENGTH_SHORT).show()
                            modelManager.setActiveModel(selectedModel.id)
                            modelManager.downloadModel(selectedModel.id)
                            showActiveDownloadProgressDialog(selectedModel)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showActiveDownloadProgressDialog(model: com.jarvis.assistant.ai.local.LocalModelDescriptor) {
        val modelManager = com.jarvis.assistant.ai.local.LocalModelManager.getInstance(requireContext(), lifecycleScope)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Downloading ${model.name}")
            .setMessage("Initializing download stream...\n0% downloaded\n\nNotification is active in system tray.")
            .setNegativeButton("Run in Background", null)
            .create()

        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            modelManager.downloadState.collect { state ->
                when (state) {
                    is com.jarvis.assistant.ai.local.ModelDownloadState.Downloading -> {
                        val dlMb = state.bytesDownloaded / (1024 * 1024)
                        val totMb = state.totalBytes / (1024 * 1024)
                        dialog.setMessage("Downloading GGUF weights...\n\nProgress: ${state.progressPercent}%\nDownloaded: ${dlMb} MB / ${totMb} MB\n\nYou can keep this dialog open or tap 'Run in Background'.")
                    }
                    is com.jarvis.assistant.ai.local.ModelDownloadState.Completed -> {
                        dialog.setMessage("âœ… Download & GGUF header verification complete!\n\n${model.name} is installed and ready for 100% offline inference.")
                        Toast.makeText(requireContext(), "${model.name} is ready for offline use!", Toast.LENGTH_LONG).show()
                    }
                    is com.jarvis.assistant.ai.local.ModelDownloadState.Error -> {
                        dialog.setMessage("âŒ Download failed:\n${state.message}\n\nPlease check your internet connection and try again.")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun showSecretSettingsDialog() {
        val levels = arrayOf("Friendly (Normal Care)", "Romantic (Affectionate Partner)", "Intense 18+ (Deep Companion)")
        val currentLevel = (preferences.secretIntimacyLevel - 1).coerceIn(0, 2)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Secret Settings (Companion Mode)")
            .setSingleChoiceItems(levels, currentLevel) { dialog, which ->
                preferences.secretIntimacyLevel = which + 1
                preferences.isSecretSettingsEnabled = true
                preferences.personality = GeminiConstants.PERSONALITY_GIRLFRIEND
                loadUserData()
                dialog.dismiss()
                Toast.makeText(requireContext(), "Companion Mode set to ${levels[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(if (preferences.isSecretSettingsEnabled) "Disable Secret Mode" else "Keep Disabled") { _, _ ->
                preferences.isSecretSettingsEnabled = false
                preferences.personality = GeminiConstants.PERSONALITY_ASSISTANT
                loadUserData()
                Toast.makeText(requireContext(), "Restored standard Jarvis Assistant", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSkillsDialog() {
        val skillOptions = arrayOf(
            "ðŸŽ¨ ChatGPT Image Generator (Autonomous Macro)",
            "ðŸŽ¬ Gemini Video & Image Creator (Veo & Imagen Macro)",
            "ðŸ’¬ WhatsApp Hands-Free Voice Dispatcher",
            "ðŸŽ™ï¸ PocketTTS On-Device Voice Engine",
            "ðŸš¨ Touch Guard Anti-Theft Sentinel",
            "ðŸ“‹ View All Autonomous Macros & Directives"
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Installed Skills & Playbooks")
            .setItems(skillOptions) { _, which ->
                when (which) {
                    0 -> runMacroFromSettings("chatgpt image", "image prompt", "Futuristic cyberpunk warrior with neon armor")
                    1 -> runMacroFromSettings("gemini image", "image prompt", "Majestic dragon flying over hyper-tech city")
                    2 -> showWhatsAppDialog()
                    3 -> showVoiceEngineDialog()
                    4 -> showTouchGuardDialog()
                    5 -> {
                        (activity as? MainActivity)?.selectTab(MainActivity.TAB_UPDATES)
                    }
                }
            }
            .setPositiveButton("Open Macro Console") { _, _ ->
                (activity as? MainActivity)?.selectTab(MainActivity.TAB_UPDATES)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun runMacroFromSettings(macroName: String, paramKey: String, defaultPrompt: String) {
        val input = EditText(requireContext()).apply {
            setText(defaultPrompt)
            setSelection(defaultPrompt.length)
            setPadding(32, 24, 32, 24)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Execute '$macroName'")
            .setMessage("Enter parameter for {$paramKey}:")
            .setView(input)
            .setPositiveButton("Execute Now") { _, _ ->
                val promptText = input.text.toString().trim()
                val repo = com.jarvis.assistant.automation.macro.MacroRepository(requireContext())
                val engine = com.jarvis.assistant.automation.macro.MacroEngine(requireContext(), repo)
                Toast.makeText(requireContext(), "Executing macro '$macroName'â€¦", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.Default) {
                        engine.executeMacroByName(macroName, mapOf(paramKey to promptText))
                    }
                    when (result) {
                        is com.jarvis.assistant.automation.macro.MacroExecutionResult.Success -> {
                            Toast.makeText(requireContext(), "Macro Finished: ${result.message}", Toast.LENGTH_LONG).show()
                        }
                        is com.jarvis.assistant.automation.macro.MacroExecutionResult.Failure -> {
                            Toast.makeText(requireContext(), "Macro Failed: ${result.reason}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubAgentsDialog() {
        val items = arrayOf(
            "1. Code Executor Agent (Termux Python Sandbox)",
            "2. Vision & UI Grounding Agent (Screen Target Predictor)",
            "3. Autonomous Task Overlay HUD"
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Autonomous Sub-agents & Sandboxes")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        startActivity(Intent(requireContext(), com.jarvis.assistant.termux.TermuxPermissionActivity::class.java))
                    }
                    1 -> {
                        (activity as? MainActivity)?.toggleScreenShare()
                    }
                    2 -> {
                        startActivity(Intent(requireContext(), OverlayAvatarActivity::class.java))
                    }
                }
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showEmailDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        val etSig = EditText(requireContext()).apply {
            hint = "Email signature (e.g. Sent via Jarvis AI)"
            setText(preferences.emailSignature)
        }

        layout.addView(TextView(requireContext()).apply { text = "Signature:"; setTextColor(Color.LTGRAY); textSize = 13f })
        layout.addView(etSig)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Email Configuration")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                preferences.emailSignature = etSig.text.toString().trim()
                Toast.makeText(requireContext(), "Email configuration updated", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Send Test Mail") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:")
                        putExtra(Intent.EXTRA_SUBJECT, "Test dispatch from Jarvis")
                        putExtra(Intent.EXTRA_TEXT, "Hello,\n\nThis is a verified test email.\n\n${preferences.emailSignature}")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "No email client installed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWhatsAppDialog() {
        val items = arrayOf(
            "Auto-read incoming WhatsApp messages",
            "Ask me before Jarvis sends a reply or WhatsApp message",
            "Screen calls & announce caller names"
        )
        val checked = booleanArrayOf(
            preferences.isWhatsAppReaderEnabled,
            preferences.isMessageAutoReplyConfirmEnabled,
            preferences.isCallerIdAnnouncementEnabled
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Messages & WhatsApp Automation")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                when (which) {
                    0 -> preferences.isWhatsAppReaderEnabled = isChecked
                    1 -> preferences.isMessageAutoReplyConfirmEnabled = isChecked
                    2 -> preferences.isCallerIdAnnouncementEnabled = isChecked
                }
            }
            .setPositiveButton("Done") { _, _ ->
                Toast.makeText(requireContext(), "WhatsApp settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Notification Access") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {}
            }
            .show()
    }

    private fun showSocialMediaDialog() {
        lifecycleScope.launch {
            val story = SocialStoryManager.generateDailyStory(requireContext())
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Social Media & Daily Dispatch")
                    .setMessage(story)
                    .setPositiveButton("Copy Story") { _, _ ->
                        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Jarvis Story", story))
                        Toast.makeText(requireContext(), "Story copied to clipboard!", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("Generate Caption") { _, _ ->
                        lifecycleScope.launch {
                            val caption = SocialStoryManager.generateDynamicSocialCaption(requireContext(), "Building the Stark Ecosystem")
                            withContext(Dispatchers.Main) {
                                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Caption", caption))
                                Toast.makeText(requireContext(), "Caption copied: $caption", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    private fun showConnectorsDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Connected Accounts & Connectors")
            .setMessage(
                "â€¢ GitHub Integration: Active (Roshan Tiwari)\n" +
                "â€¢ Telegram Bridge: Active (Bot Controller)\n" +
                "â€¢ Notion Workspace: Synchronized\n" +
                "â€¢ Spotify & YouTube Music: Hands-free playback ready\n" +
                "â€¢ Termux Command Bridge: Granted"
            )
            .setPositiveButton("Manage") { _, _ ->
                Toast.makeText(requireContext(), "All connectors are healthy.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showTouchGuardDialog() {
        val isArmed = TouchGuardManager.isArmed()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Touch Guard Sentinel")
            .setMessage(
                "Touch Guard monitors your phone with the accelerometer sensor.\n\n" +
                "If anyone picks up or moves the phone while you are away, Touch Guard sounds a loud siren alarm and takes an intruder selfie.\n\n" +
                "Current Status: ${if (isArmed) "ðŸ”´ ARMED" else "âšª DISARMED"}"
            )
            .setPositiveButton(if (isArmed) "DISARM" else "ARM SENTRY") { _, _ ->
                if (isArmed) {
                    TouchGuardManager.disarm(requireContext())
                } else {
                    TouchGuardManager.arm(requireContext(), preferences.isTouchGuardStealth)
                }
                loadUserData()
            }
            .setNeutralButton("Stealth: ${if (preferences.isTouchGuardStealth) "ON" else "OFF"}") { _, _ ->
                preferences.isTouchGuardStealth = !preferences.isTouchGuardStealth
                Toast.makeText(requireContext(), "Stealth mode: ${preferences.isTouchGuardStealth}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showEmergencySosDialog() {
        val etContacts = EditText(requireContext()).apply {
            hint = "Emergency numbers (e.g. 112, 100, 9876543210)"
            setText(preferences.emergencySosContacts)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Emergency SOS")
            .setMessage("Set trusted contact numbers for emergency location broadcast:")
            .setView(etContacts)
            .setPositiveButton("Save Contacts") { _, _ ->
                preferences.emergencySosContacts = etContacts.text.toString().trim()
                Toast.makeText(requireContext(), "Emergency contacts saved", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("TEST SOS DISPATCH") { _, _ ->
                val typed = etContacts.text.toString().trim()
                if (typed.isNotBlank() && typed != preferences.emergencySosContacts) {
                    preferences.emergencySosContacts = typed
                }
                startSosDispatch()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Sends the SOS SMS with the phone's real location. Refuses when no
     * emergency contacts are configured; asks for SMS / location permission
     * first when missing (location denied -> "location unavailable").
     */
    private fun startSosDispatch() {
        if (EmergencySosManager.configuredContacts().isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("No Emergency Contacts")
                .setMessage("SOS was not sent because no emergency contacts are configured. Add at least one phone number in Emergency SOS and tap Save Contacts.")
                .setPositiveButton("Add Contacts") { _, _ -> showEmergencySosDialog() }
                .setNegativeButton("Close", null)
                .show()
            return
        }
        val missing = listOf(
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            sosPermissionLauncher.launch(missing.toTypedArray())
        } else {
            dispatchSosWithLocation()
        }
    }

    private val sosPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val ctx = context ?: return@registerForActivityResult
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(ctx, "SMS permission denied - SOS message not sent.", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        dispatchSosWithLocation()
    }

    private fun dispatchSosWithLocation() {
        val appContext = requireContext().applicationContext
        Toast.makeText(appContext, "Getting your location for SOS (up to 10 s)...", Toast.LENGTH_SHORT).show()
        EmergencySosManager.fetchCurrentLocation(appContext, timeoutMs = 10_000L) { location ->
            EmergencySosManager.triggerSos(appContext, EmergencySosManager.formatLocation(location))
        }
    }

    private fun showWatchmanLogDialog() {
        val photos = WatchmanSecurityManager(requireContext()).listIntruderPhotos()
        if (photos.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Who Tried To Get In (Watchman)")
                .setMessage("No intruder attempts detected. All systems secure.")
                .setPositiveButton("OK", null)
                .show()
        } else {
            val items = photos.take(10).map { f ->
                val date = SimpleDateFormat("dd MMM, HH:mm:ss", Locale.US).format(Date(f.lastModified()))
                "$date â€” Intruder Attempt"
            }.toTypedArray()

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Who Tried To Get In (${photos.size} Attempts)")
                .setItems(items) { _, which ->
                    val chosen = photos[which]
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(androidx.core.content.FileProvider.getUriForFile(
                            requireContext(),
                            "${requireContext().packageName}.provider",
                            chosen
                        ), "image/jpeg")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(requireContext(), "Saved at: ${chosen.name}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun showAppIdentityDialog() {
        val report = AppIdentityVerifier.report(requireContext())
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("App Identity & Verification")
            .setMessage(report)
            .setPositiveButton("Close", null)
            .show()
    }
    private fun showHomeWidgetsDialog() {
        val options = arrayOf(
            "Show 3D Mora Cyber Character",
            "Clean Immersion View (Hide all cards)",
            "Live Weather Card",
            "Stark Telemetry HUD",
            "Quick Actions Strip (Screen/Memories/Lang)",
            "Tactical Protocols Hub",
            "Updates & Announcements Feed",
            "Voice Status Indicator & Hint"
        )
        val checkedItems = booleanArrayOf(
            preferences.orbEntityType == "mora_cyber",
            preferences.hideAllHomeCards,
            preferences.showWeatherWidget,
            preferences.showTelemetryWidget,
            preferences.showQuickActionsWidget,
            preferences.showProtocolsWidget,
            preferences.showUpdatesWidget,
            preferences.showStatusIndicatorWidget
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Home Screen HUD & 3D Character")
            .setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
                HudSoundManager.playTapSound()
                when (which) {
                    0 -> {
                        preferences.userExplicitlyChoseEntity = true
                        preferences.orbEntityType = if (isChecked) "mora_cyber" else "orb"
                        if (isChecked) {
                            com.jarvis.assistant.util.ModelVaultManager.setActiveVaultItemId(requireContext(), com.jarvis.assistant.util.ModelVaultManager.BUILTIN_MORA.id)
                        }
                    }
                    1 -> {
                        preferences.hideAllHomeCards = isChecked
                        binding.switchHideAllHomeCards.isChecked = isChecked
                    }
                    2 -> preferences.showWeatherWidget = isChecked
                    3 -> preferences.showTelemetryWidget = isChecked
                    4 -> preferences.showQuickActionsWidget = isChecked
                    5 -> preferences.showProtocolsWidget = isChecked
                    6 -> preferences.showUpdatesWidget = isChecked
                    7 -> preferences.showStatusIndicatorWidget = isChecked
                }
            }
            .setPositiveButton("Apply") { _, _ ->
                binding.switchHideAllHomeCards.isChecked = preferences.hideAllHomeCards
                Toast.makeText(requireContext(), "Home HUD settings applied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showDrivingModeDialog() {
        val manager = com.jarvis.assistant.core.JarvisDrivingModeManager.getInstance(requireContext())
        val isDriving = manager.isDrivingMode.value
        val autoCar = preferences.isDrivingModeAutoCarEnabled
        val announceCaller = preferences.isCallerIdAnnouncementEnabled
        val autoReject = manager.drivingCallMode == com.jarvis.assistant.data.preferences.AppPreferences.DRIVING_CALL_MODE_AUTO_REJECT_SMS

        val items = mutableListOf(
            if (isDriving) "Exit Driving Mode" else "Activate Driving Mode Now",
            "Auto-Engage on Car Bluetooth: " + if (autoCar) "ON" else "OFF",
            "Caller ID Voice Announcement: " + if (announceCaller) "ON" else "OFF",
            "Open Google Maps Navigation",
            "When a call comes while driving: " + if (autoReject) "Auto-reject + SMS reply" else "Announce caller"
        )
        // The SMS text only matters for auto-reject.
        if (autoReject) items.add("Auto-reply SMS text: \"${manager.autoReplyText}\"")

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Driving Mode Sentinel")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    4 -> showDrivingCallModeDialog()
                    5 -> showDrivingAutoReplyTextDialog()
                    0 -> {
                        manager.setDrivingMode(!isDriving)
                        val newState = if (!isDriving) "ENGAGED" else "DISENGAGED"
                        android.widget.Toast.makeText(requireContext(), "Driving Mode $newState", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        preferences.isDrivingModeAutoCarEnabled = !autoCar
                        android.widget.Toast.makeText(requireContext(), "Auto-Car Bluetooth: " + if (!autoCar) "Enabled" else "Disabled", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        preferences.isCallerIdAnnouncementEnabled = !announceCaller
                        android.widget.Toast.makeText(requireContext(), "Caller ID Voice: " + if (!announceCaller) "Enabled" else "Disabled", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=gas+station+nearby")).apply {
                                setPackage("com.google.android.apps.maps")
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        } catch (_: Exception) {
                            val webIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://maps.google.com")).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(webIntent)
                        }
                    }
                }
            }
            .setPositiveButton("Close", null)
            .show()
    }

    /** "When a call comes while driving": Announce caller / Auto-reject + SMS reply. */
    private fun showDrivingCallModeDialog() {
        DrivingCallSettings.showCallModeDialog(
            requireContext(),
            requestPermissions = { missing -> drivingCallPermissionLauncher.launch(missing) },
            onChanged = { if (_binding != null) showDrivingModeDialog() }
        )
    }

    private fun showDrivingAutoReplyTextDialog() {
        DrivingCallSettings.showAutoReplyTextDialog(requireContext()) {
            if (_binding != null) showDrivingModeDialog()
        }
    }

    private val drivingCallPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val ctx = context ?: return@registerForActivityResult
        // Enables auto-reject only when call control + SMS were granted; otherwise reverts to Announce.
        Toast.makeText(ctx, DrivingCallSettings.applyAfterPermissionResult(ctx), Toast.LENGTH_LONG).show()
        if (_binding != null) showDrivingModeDialog()
    }

    private fun showPcHubDialog() {
        lifecycleScope.launch {
            val configuredEndpoint = preferences.customEndpoint.ifBlank { com.jarvis.assistant.data.preferences.AppPreferences.DEFAULT_CUSTOM_ENDPOINT }
            val client = com.jarvis.assistant.network.LocalHostLlmClient()
            val pingResult = withContext(Dispatchers.IO) {
                client.pingServer(configuredEndpoint)
            }

            withContext(Dispatchers.Main) {
                val statusText = if (pingResult.isOnline) {
                    "ONLINE (" + pingResult.pingMs + "ms)\nActive Model: " + (if (pingResult.activeModel.isNotBlank()) pingResult.activeModel else "Ready") + "\nAvailable Models: " + (if (pingResult.availableModels.isNotEmpty()) pingResult.availableModels.joinToString(", ") else "None detected")
                } else {
                    "OFFLINE (" + (pingResult.error ?: "Cannot connect") + ")\n\nTroubleshooting:\n- USB: Run 'adb reverse tcp:11434 tcp:11434' on PC\n- Wi-Fi LAN: Set PC IP (e.g. http://192.168.1.50:11434) in API Connections\n- PC Firewall: Ensure Ollama is listening on 0.0.0.0 (OLLAMA_HOST=0.0.0.0:11434)"
                }

                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("PC Phone Hub")
                    .setMessage(
                        "Local Host Bridge: " + configuredEndpoint + "\n" +
                        "Live Status: " + statusText + "\n\n" +
                        "Your Windows Ollama instance runs locally on port 11434 with zero internet dependency."
                    )
                    .setPositiveButton("Configure Endpoint") { _, _ ->
                        startActivity(android.content.Intent(requireContext(), ApiConnectionActivity::class.java))
                    }
                    .setNeutralButton("Retry Ping") { _, _ -> showPcHubDialog() }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    private fun showBackupRestoreDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Backup & Restore")
            .setMessage("Export all your persistent memories, settings, and conversations to an offline JSON backup, or restore from a previous backup.")
            .setPositiveButton("Export Backup") { _, _ ->
                lifecycleScope.launch {
                    val result = BackupManager.exportBackup(requireContext())
                    withContext(Dispatchers.Main) {
                        result.onSuccess { file ->
                            Toast.makeText(requireContext(), "Backup exported to:\n${file.name} in Downloads", Toast.LENGTH_LONG).show()
                        }.onFailure { e ->
                            Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNeutralButton("Restore") { _, _ ->
                Toast.makeText(requireContext(), "To restore, place a valid Jarvis_Backup_*.json in Downloads.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showJarvisRulesDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Jarvis Protocols & Rules")
            .setMessage(
                "PROTOCOL 1: Strict owner loyalty & command execution.\n" +
                "PROTOCOL 2: Proactive device safety and privacy preservation.\n" +
                "PROTOCOL 3: Natural multilingual speech with no forced accents.\n" +
                "PROTOCOL 4: Immediate action over lengthy verbal deliberation.\n" +
                "PROTOCOL 5: Offline-first architecture for local AI queries.\n" +
                "PROTOCOL 6: Touch Guard and Watchman sentry defense.\n\n" +
                "Directives authored by Roshan Tiwari."
            )
            .setPositiveButton("Understood", null)
            .show()
    }

    private fun showEditNameDialog() {
        val input = EditText(requireContext()).apply {
            setText(preferences.userName.ifBlank { "Roshan" })
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Operator Name")
            .setMessage("Enter the name Jarvis should use to address you:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    preferences.userName = newName
                    loadUserData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ==================== ON-DEVICE AI (merged AndroLLM settings) ====================

    private fun openAndroLlmRoute(route: String) {
        HudSoundManager.playTapSound()
        startActivity(AndroLlmPageActivity.routeIntent(requireContext(), route))
    }

    private fun openAndroLlmSection(section: AndroLlmPageActivity.Section) {
        HudSoundManager.playTapSound()
        startActivity(AndroLlmPageActivity.sectionIntent(requireContext(), section))
    }

    /**
     * Builds the "ON-DEVICE AI, MODELS & AGENT" card: Jarvis-styled rows that open each merged
     * AndroLLM page inside Jarvis. MCP is not repeated here â€” it lives in the existing MCP
     * accordion above ("Manage Servers"). Rebuilt on resume so badges stay current.
     */
    private fun setupLocalAiRows() {
        val b = _binding ?: return
        val ctx = context ?: return
        val container = b.layoutLocalAiRows
        container.removeAllViews()

        val liteRtOn = runCatching {
            com.jarvis.assistant.androllm.AndroLlmFlags.isEnabled(ctx, com.jarvis.assistant.androllm.AndroLlmFlags.LITERT_ENGINE)
        }.getOrDefault(false)
        val routes = com.jarvis.assistant.ui.compose.navigation.ComposeRoutes

        data class Row(val icon: Int, val title: String, val subtitle: String, val badge: String?, val onClick: () -> Unit)
        val rows = listOf(
            Row(R.drawable.ic_code_chip, "Code Studio", "AI coding IDE â€¢ projects, editor, AI edit, Auto-Fix, preview & terminal",
                "NEW") { com.jarvis.assistant.codestudio.CodeStudioActivity.open(ctx) },
            Row(R.drawable.ic_code_chip, "AI Engines", "LiteRT-LM on-device engine & cloud gateway",
                if (liteRtOn) "LITERT ON" else null) { openAndroLlmSection(AndroLlmPageActivity.Section.ENGINES) },
            Row(R.drawable.ic_androllm_layers, "Models & Downloads", "Full model manager â€¢ Hugging Face, import, benchmark",
                "MODELS") { openAndroLlmRoute(routes.MODELS) },
            Row(R.drawable.ic_settings_db, "Model Defaults & Storage", "Default local model, downloads & disk usage",
                null) { openAndroLlmSection(AndroLlmPageActivity.Section.MODELS) },
            Row(R.drawable.ic_chat_bubble, "Chat & Attachments", "Chat behaviour, attachments & export",
                null) { openAndroLlmSection(AndroLlmPageActivity.Section.CHAT) },
            Row(R.drawable.ic_settings_book, "Prompt Library", "Prompt Studio templates & presets",
                null) { openAndroLlmRoute(routes.PROMPTS) },
            Row(R.drawable.ic_settings_db, "Memory", "Long-term memory, knowledge graph & inspector",
                null) { openAndroLlmSection(AndroLlmPageActivity.Section.MEMORY) },
            Row(R.drawable.ic_androllm_waveform, "Voice", "Sherpa / Whisper ASR, TTS, AndroLLM voice assistant, normalization",
                null) { openAndroLlmSection(AndroLlmPageActivity.Section.VOICE) },
            Row(R.drawable.ic_androllm_robot, "Agent & Tools", "Tool graph, planner & tool toggles",
                null) { openAndroLlmSection(AndroLlmPageActivity.Section.AGENT) },
            Row(R.drawable.ic_settings_advanced, "Tool Debug", "Inspect & test agent tool calls",
                null) { openAndroLlmRoute(routes.TOOL_DEBUG) },
            Row(R.drawable.ic_auto_cycle, "Screen Automation", "Accessibility automation & tool confirmations",
                null) { openAndroLlmSection(AndroLlmPageActivity.Section.AUTOMATION) },
            Row(R.drawable.ic_settings_shield, "AI Feature Access", "Permissions used by on-device AI tools",
                null) { openAndroLlmSection(AndroLlmPageActivity.Section.PERMISSIONS) },
            Row(R.drawable.ic_settings_connectors, "Cloud Providers", "Cloud gateway models, keys & usage",
                null) { openAndroLlmRoute(routes.CLOUD) },
            Row(R.drawable.ic_settings_pc, "Developer & Diagnostics", "Runtime registry, LiteRT diagnostics, benchmarks",
                "DEV") { openAndroLlmRoute(routes.DEVELOPER) }
        )

        val inflater = LayoutInflater.from(ctx)
        rows.forEachIndexed { index, row ->
            val item = inflater.inflate(R.layout.item_jarvis_settings_link_row, container, false)
            item.findViewById<View>(R.id.rowDivider).visibility = if (index == 0) View.GONE else View.VISIBLE
            item.findViewById<ImageView>(R.id.ivRowIcon).setImageResource(row.icon)
            item.findViewById<TextView>(R.id.tvRowTitle).text = row.title
            item.findViewById<TextView>(R.id.tvRowSubtitle).text = row.subtitle
            item.findViewById<TextView>(R.id.tvRowBadge).apply {
                if (row.badge != null) {
                    text = row.badge
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }
            item.findViewById<View>(R.id.rowRoot).setOnClickListener { row.onClick() }
            container.addView(item)
        }
    }

    private fun setupAccordion(header: View, body: View, chevron: ImageView) {
        header.setOnClickListener {
            HudSoundManager.playTapSound()
            val isExpanded = body.visibility == View.VISIBLE
            if (isExpanded) {
                body.visibility = View.GONE
                chevron.setImageResource(R.drawable.ic_chevron_down)
            } else {
                body.visibility = View.VISIBLE
                chevron.setImageResource(R.drawable.ic_chevron_up)
            }
        }
    }

    private fun showTelemetryDetailDialog() {
        val telemetryRepo = com.jarvis.assistant.telemetry.TelemetryRepository.getInstance(requireContext())
        val metrics = telemetryRepo.deviceMetrics.value
        val model = metrics?.deviceModel ?: "Android Device"
        val ram = if (metrics != null) "${metrics.totalRamMb - metrics.availableRamMb} MB / ${metrics.totalRamMb} MB" else "Active"
        val cores = metrics?.cpuCores ?: Runtime.getRuntime().availableProcessors()
        val vulkan = if (metrics?.isVulkanSupported == true) "Vulkan 1.3 Available" else "OpenGLES Only"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("System Diagnostics & Telemetry")
            .setMessage(
                "DEVICE: $model\n" +
                "CPU CORES: $cores Thread Units\n" +
                "RAM UTILIZATION: $ram\n" +
                "GRAPHICS ACCELERATION: $vulkan\n" +
                "SAMPLING: every ${com.jarvis.assistant.telemetry.TelemetryRepository.currentSampleIntervalMs() / 1000} s" +
                (if (preferences.isBatterySaverEnabled) " (Battery saver)" else "") + ", rolling window of 300 samples\n" +
                "THREADING PROFILE: Mobile Low-Latency Cores\n" +
                "LOCAL AI ENGINE: GGUF llama.cpp Runtime"
            )
            .setPositiveButton("Dismiss", null)
            .show()
    }

    private fun showAddMcpServerDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }
        val etName = EditText(requireContext()).apply {
            hint = "Server Name (e.g. Local MCP Bridge)"
            setText("Local MCP Bridge")
        }
        val etUrl = EditText(requireContext()).apply {
            hint = "Streamable HTTP endpoint (e.g. http://127.0.0.1:3000/mcp)"
            setText("http://127.0.0.1:3000/mcp")
        }
        val etToken = EditText(requireContext()).apply {
            hint = "Bearer token (optional)"
        }
        layout.addView(etName)
        layout.addView(etUrl)
        layout.addView(etToken)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Register MCP Server")
            .setView(layout)
            .setPositiveButton("Register") { _, _ ->
                val name = etName.text.toString().trim()
                val url = etUrl.text.toString().trim()
                if (url.isNotBlank()) {
                    val server = com.jarvis.assistant.mcp.McpServer.fromName(
                        name = name.ifBlank { "Custom Server" },
                        url = url,
                        authToken = etToken.text.toString()
                    )
                    com.jarvis.assistant.mcp.McpManager.getInstance(requireContext())
                        .registerServer(server)
                    Toast.makeText(requireContext(), "MCP Server '${server.name}' saved. Connectingâ€¦", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderMcpServersList() {
        val container = binding.containerMcpServersList ?: return
        container.removeAllViews()
        val mcpManager = com.jarvis.assistant.mcp.McpManager.getInstance(requireContext())
        val servers = mcpManager.registeredServers()
        val states = mcpManager.states.value
        val density = resources.displayMetrics.density

        if (servers.isEmpty()) {
            val tvEmpty = TextView(requireContext()).apply {
                text = "No custom MCP servers configured. Tap below to connect desktop or remote MCP tools."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11f
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            container.addView(tvEmpty)
            return
        }

        for (server in servers) {
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_jarvis_row_item)
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * density).toInt() }
            }

            val rowHeader = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(requireContext()).apply {
                text = server.name
                setTextColor(Color.WHITE)
                textSize = 13f
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            rowHeader.addView(tvName)

            val state = states[server.id]
            val (statusText, statusColor) = when (state) {
                is com.jarvis.assistant.mcp.McpManager.State.Connected -> ("● CONNECTED (" + state.toolCount + " TOOLS)") to "#4ADE80"
                is com.jarvis.assistant.mcp.McpManager.State.Connecting -> "● CONNECTING..." to "#F59E0B"
                is com.jarvis.assistant.mcp.McpManager.State.Failed -> "● OFFLINE" to "#EF4444"
                else -> "● DISCONNECTED" to "#94A3B8"
            }

            val tvState = TextView(requireContext()).apply {
                text = statusText
                setTextColor(Color.parseColor(statusColor))
                textSize = 10f
                paint.isFakeBoldText = true
            }
            rowHeader.addView(tvState)
            card.addView(rowHeader)

            val tvUrl = TextView(requireContext()).apply {
                text = server.url
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, (2 * density).toInt(), 0, (4 * density).toInt())
            }
            card.addView(tvUrl)

            val btnRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val btnReconnect = com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "⚡ PING / RECONNECT"
                textSize = 10f
                setTextColor(Color.parseColor("#22D3EE"))
                setOnClickListener {
                    com.jarvis.assistant.util.HudSoundManager.playTapSound()
                    tvState.text = "● CONNECTING..."
                    tvState.setTextColor(Color.parseColor("#F59E0B"))
                    viewLifecycleOwner.lifecycleScope.launch {
                        mcpManager.reconnect(server.id)
                        renderMcpServersList()
                    }
                }
            }
            btnRow.addView(btnReconnect)

            val btnDelete = com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "✕ DELETE"
                textSize = 10f
                setTextColor(Color.parseColor("#EF4444"))
                setOnClickListener {
                    com.jarvis.assistant.util.HudSoundManager.playTapSound()
                    mcpManager.unregisterServer(server.id)
                    renderMcpServersList()
                    Toast.makeText(requireContext(), "Removed MCP server '" + server.name + "'", Toast.LENGTH_SHORT).show()
                }
            }
            btnRow.addView(btnDelete)
            card.addView(btnRow)

            container.addView(card)
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            val mcpManager = com.jarvis.assistant.mcp.McpManager.getInstance(requireContext())
            mcpManager.states.collect {
                if (_binding != null) {
                    renderMcpServersList()
                }
            }
        }
        // 1Hz Telemetry Flow
        viewLifecycleOwner.lifecycleScope.launch {
            val telemetryRepo = com.jarvis.assistant.telemetry.TelemetryRepository.getInstance(requireContext())
            telemetryRepo.startSampling()
            telemetryRepo.deviceMetrics.collect { metrics ->
                if (metrics != null && _binding != null) {
                    val usedRam = metrics.totalRamMb - metrics.availableRamMb
                    binding.tvTelemetryRam.text = "${usedRam}MB"
                    binding.tvTelemetryThreadingProfile.text = "${metrics.cpuCores}T â€¢ ${metrics.deviceModel.take(12)}"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val telemetryRepo = com.jarvis.assistant.telemetry.TelemetryRepository.getInstance(requireContext())
            telemetryRepo.history.collect { samples ->
                val latest = samples.lastOrNull()
                if (latest != null && _binding != null) {
                    binding.tvTelemetryTps.text = if (latest.tokensPerSecond > 0f) String.format(Locale.ROOT, "%.1f", latest.tokensPerSecond) else "0.0"
                    val ttft = telemetryRepo.generationHistory.value.lastOrNull()?.firstTokenMs ?: 0L
                    binding.tvTelemetryTtft.text = if (ttft > 0L) "${ttft}ms" else "--"
                }
            }
        }

        // Local Model Download Flow
        val localModelManager = com.jarvis.assistant.ai.local.LocalModelManager.getInstance(requireContext(), viewLifecycleOwner.lifecycleScope)
        viewLifecycleOwner.lifecycleScope.launch {
            localModelManager.downloadState.collect { state ->
                if (_binding == null) return@collect
                when (state) {
                    is com.jarvis.assistant.ai.local.ModelDownloadState.Downloading -> {
                        binding.progressBarLlama.visibility = View.VISIBLE
                        binding.tvDownloadProgressLlama.visibility = View.VISIBLE
                        binding.progressBarLlama.progress = state.progressPercent
                        val mb = state.bytesDownloaded / (1024 * 1024)
                        val totalMb = state.totalBytes / (1024 * 1024)
                        binding.tvDownloadProgressLlama.text = "Downloading: ${state.progressPercent}% ($mb MB / $totalMb MB)"
                        binding.btnDownloadLlama.isEnabled = false
                        binding.btnDownloadLlama.text = "Downloading (${state.progressPercent}%)"
                    }
                    is com.jarvis.assistant.ai.local.ModelDownloadState.Completed -> {
                        binding.progressBarLlama.visibility = View.GONE
                        binding.tvDownloadProgressLlama.visibility = View.VISIBLE
                        binding.tvDownloadProgressLlama.text = "Model ready and verified (GGUF magic valid)"
                        binding.btnDownloadLlama.isEnabled = true
                        binding.btnDownloadLlama.text = "Loaded & Ready (809 MB)"
                    }
                    is com.jarvis.assistant.ai.local.ModelDownloadState.Error -> {
                        binding.progressBarLlama.visibility = View.GONE
                        binding.tvDownloadProgressLlama.visibility = View.VISIBLE
                        binding.tvDownloadProgressLlama.text = "Download error: ${state.message}"
                        binding.btnDownloadLlama.isEnabled = true
                        binding.btnDownloadLlama.text = "Retry Download"
                    }
                    is com.jarvis.assistant.ai.local.ModelDownloadState.Idle -> {
                        if (localModelManager.isModelReady(com.jarvis.assistant.ai.local.LocalAiConfig.MODEL_LLAMA_3_2_1B)) {
                            binding.progressBarLlama.visibility = View.GONE
                            binding.tvDownloadProgressLlama.visibility = View.VISIBLE
                            binding.tvDownloadProgressLlama.text = "Model ready on device"
                            binding.btnDownloadLlama.text = "Loaded & Ready (809 MB)"
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mayaRows = null
        context?.let { ctx ->
            com.jarvis.assistant.telemetry.TelemetryRepository.getInstance(ctx).stopSampling()
        }
        _binding = null
    }
}
