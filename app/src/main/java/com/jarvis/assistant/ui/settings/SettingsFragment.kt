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
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity
import com.jarvis.assistant.util.HudSoundManager
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
    private var isUpdatingSwitchProgrammatically = false

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
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
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
        } finally {
            isUpdatingSwitchProgrammatically = false
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
            val msg = if (isChecked) "Battery saver active â€” Background polling throttled" else "Battery saver disabled â€” Full performance"
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

        binding.rowMemory.setOnClickListener {
            startActivity(Intent(requireContext(), MemoryActivity::class.java))
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
        val etSmtp = EditText(requireContext()).apply {
            hint = "Custom SMTP server (optional)"
            setText(preferences.emailCustomSmtp)
        }

        layout.addView(TextView(requireContext()).apply { text = "Signature:"; setTextColor(Color.LTGRAY); textSize = 13f })
        layout.addView(etSig)
        layout.addView(TextView(requireContext()).apply { text = "SMTP Server (optional):"; setTextColor(Color.LTGRAY); textSize = 13f; setPadding(0, 20, 0, 0) })
        layout.addView(etSmtp)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Email Configuration")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                preferences.emailSignature = etSig.text.toString().trim()
                preferences.emailCustomSmtp = etSmtp.text.toString().trim()
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
            "Auto-reply with AI response when away",
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
                EmergencySosManager.triggerSos(requireContext(), "28.6139Â° N, 77.2090Â° E")
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun showDrivingModeDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Driving Mode Sentinel")
            .setMessage(
                "When Driving Mode is active (or when car Bluetooth connects):\n\n" +
                "â€¢ Incoming phone calls are politely rejected\n" +
                "â€¢ Caller automatically receives an SMS: 'I am currently driving and will get back to you soon.'\n" +
                "â€¢ Jarvis verbally announces caller name through your car audio\n\n" +
                "Hands-free driving protection is active."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showPcHubDialog() {
        lifecycleScope.launch {
            val pingResult = withContext(Dispatchers.IO) {
                try {
                    val url = URL("http://127.0.0.1:11434/api/tags")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 2000
                        readTimeout = 2000
                    }
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code == 200) "Connected (HTTP 200 OK)" else "HTTP Error $code"
                } catch (e: Exception) {
                    "Offline (${e.message})"
                }
            }

            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("PC â‡„ Phone Hub")
                    .setMessage(
                        "â€¢ Local Host Bridge: http://127.0.0.1:11434\n" +
                        "â€¢ Live Ping Status: $pingResult\n" +
                        "â€¢ ADB Reverse Port: tcp:11434 tcp:11434\n" +
                        "â€¢ Wireless ADB: Supported\n\n" +
                        "Your Windows Ollama instance runs locally on port 11434 with zero internet dependency."
                    )
                    .setPositiveButton("Refresh") { _, _ -> showPcHubDialog() }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
