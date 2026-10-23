package com.jarvis.assistant.ui.companion

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.macro.MacroEngine
import com.jarvis.assistant.automation.macro.MacroExecutionResult
import com.jarvis.assistant.automation.macro.MacroRepository
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.databinding.FragmentJarvisCompanionHomeBinding
import com.jarvis.assistant.security.guardian.TouchGuardManager
import com.jarvis.assistant.ui.facelock.JarvisAppLockActivity
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.util.HudSoundManager
import com.jarvis.assistant.voice.PocketTtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JarvisCompanionHomeFragment : Fragment() {

    private var _binding: FragmentJarvisCompanionHomeBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }
    private val macroRepository by lazy { MacroRepository(requireContext()) }
    private val macroEngine by lazy { MacroEngine(requireContext(), macroRepository) }
    private var previewMediaPlayer: MediaPlayer? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentJarvisCompanionHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupModeSwitcher()
        setupOrbInteractions()
        setupCharacterCarousel()
        setupAutonomousActionGrid()
        updateActiveCharacterUI()
        updateTouchGuardStatusUI()

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.jarvisScrollView) { v, insets ->
            val statusBarInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val topInset = statusBarInsets.top
            val safeTopPadding = if (topInset > 0) topInset + (8 * density).toInt() else (24 * density).toInt()
            v.setPadding(v.paddingLeft, safeTopPadding, v.paddingRight, v.paddingBottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            updateActiveCharacterUI()
            updateTouchGuardStatusUI()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            updateActiveCharacterUI()
            updateTouchGuardStatusUI()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        previewMediaPlayer?.release()
        previewMediaPlayer = null
        _binding = null
    }

    private fun setupModeSwitcher() {
        binding.btnSwitchToJarvisMode.setOnClickListener {
            HudSoundManager.playTapSound()
            preferences.experienceMode = AppPreferences.MODE_JARVIS
            Toast.makeText(requireContext(), "⚡ Switched to JARVIS Mode", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.switchExperienceMode(AppPreferences.MODE_JARVIS)
        }
    }

    private fun setupOrbInteractions() {
        binding.btnJarvisOrb.setOnClickListener {
            HudSoundManager.playTapSound()
            binding.btnJarvisOrb.animate()
                .scaleX(1.12f).scaleY(1.12f)
                .setDuration(120)
                .withEndAction {
                    binding.btnJarvisOrb.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }.start()
            (activity as? MainActivity)?.handleMicOrOrbClick()
        }

        binding.btnJarvisOrb.setOnLongClickListener {
            (activity as? MainActivity)?.handleHoldMicOrOrb()
            true
        }
    }

    private fun setupCharacterCarousel() {
        binding.cardCharMaya.setOnClickListener {
            selectCharacter("Maya", GeminiConstants.PERSONALITY_COMPANION)
        }
        binding.btnPreviewMaya.setOnClickListener {
            playVoiceSample("voices/maya_Aoede.ogg", "नमस्ते! मैं माया हूँ।")
        }

        binding.cardCharMayaPro.setOnClickListener {
            selectCharacter("Maya Pro", GeminiConstants.PERSONALITY_EXECUTIVE)
        }
        binding.btnPreviewMayaPro.setOnClickListener {
            playVoiceSample("voices/maya_Kore.ogg", "Maya Pro active. Ready for autonomous directives.")
        }

        binding.cardCharMayaIntimate.setOnClickListener {
            showIntimateSettingsDialog()
        }
        binding.btnPreviewMayaIntimate.setOnClickListener {
            showIntimateSettingsDialog()
        }

        binding.cardCharFriday.setOnClickListener {
            selectCharacter("Friday", GeminiConstants.PERSONALITY_TACTICAL)
        }
        binding.btnPreviewFriday.setOnClickListener {
            playVoiceSample("voices/friday_Aoede.ogg", "F.R.I.D.A.Y. online and operational.")
        }

        binding.cardCharVenom.setOnClickListener {
            selectCharacter("Venom", GeminiConstants.PERSONALITY_AGGRESSIVE)
        }
        binding.btnPreviewVenom.setOnClickListener {
            playVoiceSample("voices/venom_Charon.ogg", "We are Venom!")
        }
    }

    private fun selectCharacter(charName: String, personality: String) {
        HudSoundManager.playTapSound()
        preferences.jarvisActiveCharacter = charName
        preferences.personality = personality
        updateActiveCharacterUI()
        Toast.makeText(requireContext(), "Selected Persona: $charName", Toast.LENGTH_SHORT).show()
    }

    private fun updateActiveCharacterUI() {
        val active = preferences.jarvisActiveCharacter
        binding.tvJarvisActivePersonaLabel.text = "Active: $active Core · Online"

        binding.cardCharMaya.setBackgroundResource(
            if (active == "Maya") R.drawable.bg_jarvis_character_active else R.drawable.bg_jarvis_character_inactive
        )
        binding.cardCharMayaPro.setBackgroundResource(
            if (active == "Maya Pro") R.drawable.bg_jarvis_character_active else R.drawable.bg_jarvis_character_inactive
        )
        binding.cardCharMayaIntimate.setBackgroundResource(
            if (active == "Maya Intimate") R.drawable.bg_jarvis_character_active else R.drawable.bg_jarvis_character_inactive
        )
        binding.cardCharFriday.setBackgroundResource(
            if (active == "Friday") R.drawable.bg_jarvis_character_active else R.drawable.bg_jarvis_character_inactive
        )
        binding.cardCharVenom.setBackgroundResource(
            if (active == "Venom") R.drawable.bg_jarvis_character_active else R.drawable.bg_jarvis_character_inactive
        )
    }

    private fun playVoiceSample(assetPath: String, fallbackText: String) {
        try {
            previewMediaPlayer?.release()
            previewMediaPlayer = MediaPlayer()
            val afd = requireContext().assets.openFd(assetPath)
            previewMediaPlayer?.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            previewMediaPlayer?.prepare()
            previewMediaPlayer?.start()
            Toast.makeText(requireContext(), "Playing neural sample…", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            PocketTtsEngine.speak(fallbackText, requireContext())
            Toast.makeText(requireContext(), "Synthesizing with PocketTTS…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showIntimateSettingsDialog() {
        val options = arrayOf(
            "1. Friendly & Sweet Companion (Everyday bond)",
            "2. Romantic & Loving Girlfriend (Pari)",
            "3. Intense (18+) Deep Romantic Register"
        )
        val currentLevel = (preferences.secretIntimacyLevel - 1).coerceIn(0, 2)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Maya Intimate Companion Configuration")
            .setSingleChoiceItems(options, currentLevel) { dialog, which ->
                preferences.isSecretSettingsEnabled = true
                preferences.secretIntimacyLevel = which + 1
                preferences.jarvisActiveCharacter = "Maya Intimate"
                preferences.personality = GeminiConstants.PERSONALITY_COMPANION
                updateActiveCharacterUI()
                Toast.makeText(requireContext(), "Intimacy level set: ${options[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupAutonomousActionGrid() {
        // 1. ChatGPT Image Macro
        binding.btnRunChatGptImage.setOnClickListener {
            promptAndRunMacro("chatgpt image", "image prompt", "Futuristic cyberpunk warrior with neon armor")
        }

        // 2. Gemini Video / Image Macro
        binding.btnRunGeminiMedia.setOnClickListener {
            val items = arrayOf("Gemini Image Generator", "Gemini Veo Video Creator")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Select Gemini Autonomous Directive")
                .setItems(items) { _, which ->
                    if (which == 0) {
                        promptAndRunMacro("gemini image", "image prompt", "Majestic dragon flying over hyper-tech city")
                    } else {
                        promptAndRunMacro("gemini video", "video prompt", "Cinematic drone shot flying through futuristic neon Tokyo")
                    }
                }
                .show()
        }

        // 3. Touch Guard
        binding.btnToggleTouchGuard.setOnClickListener {
            val newState = !preferences.isTouchGuardArmed
            preferences.isTouchGuardArmed = newState
            if (newState) {
                TouchGuardManager.arm(requireContext())
                Toast.makeText(requireContext(), "🚨 Touch Guard Sentinel ARMED", Toast.LENGTH_SHORT).show()
            } else {
                TouchGuardManager.disarm(requireContext())
                Toast.makeText(requireContext(), "Touch Guard Sentinel Disarmed", Toast.LENGTH_SHORT).show()
            }
            updateTouchGuardStatusUI()
        }

        // 4. PocketTTS Engine Test
        binding.btnTestPocketTts.setOnClickListener {
            val text = "नमस्ते Roshan! Pocket TTS इंजन बिल्कुल सही काम कर रहा है।"
            PocketTtsEngine.speak(text, requireContext())
            Toast.makeText(requireContext(), "Synthesizing: $text", Toast.LENGTH_LONG).show()
        }

        // 5. WhatsApp Settings
        binding.btnOpenWhatsAppSettings.setOnClickListener {
            showWhatsAppAutomationDialog()
        }

        // 6. Biometric AppLock
        binding.btnOpenBiometricLock.setOnClickListener {
            val intent = Intent(requireContext(), JarvisAppLockActivity::class.java).apply {
                putExtra(JarvisAppLockActivity.EXTRA_PACKAGE_NAME, requireContext().packageName)
                putExtra(JarvisAppLockActivity.EXTRA_APP_LABEL, "JARVIS Core Console")
            }
            startActivity(intent)
        }
    }

    private fun promptAndRunMacro(macroName: String, paramKey: String, defaultPrompt: String) {
        val input = EditText(requireContext()).apply {
            setText(defaultPrompt)
            setSelection(defaultPrompt.length)
            setPadding(32, 24, 32, 24)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Execute '$macroName'")
            .setMessage("Enter the prompt for the autonomous macro pipeline:")
            .setView(input)
            .setPositiveButton("Execute Now") { _, _ ->
                val promptText = input.text.toString().trim()
                executeMacroDirectly(macroName, mapOf(paramKey to promptText))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeMacroDirectly(macroName: String, params: Map<String, String>) {
        if (JarvisAccessibilityService.instance == null) {
            Toast.makeText(
                requireContext(),
                "Please enable Accessibility Service for Autonomous Macros",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {}
            return
        }

        Toast.makeText(requireContext(), "Starting macro '$macroName'…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                macroEngine.executeMacroByName(macroName, params)
            }
            when (result) {
                is MacroExecutionResult.Success -> {
                    Toast.makeText(requireContext(), "Macro Finished: ${result.message}", Toast.LENGTH_LONG).show()
                }
                is MacroExecutionResult.Failure -> {
                    Toast.makeText(requireContext(), "Macro Failed: ${result.reason}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateTouchGuardStatusUI() {
        val isArmed = preferences.isTouchGuardArmed
        binding.tvTouchGuardStatus.text = if (isArmed) {
            "Anti-theft motion detector: ARMED 🚨"
        } else {
            "Anti-theft motion detector: DISARMED"
        }
        binding.btnToggleTouchGuard.text = if (isArmed) "DISARM GUARD" else "ARM GUARD"
    }

    private fun showWhatsAppAutomationDialog() {
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
            .setTitle("WhatsApp & Messages Automation")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                when (which) {
                    0 -> preferences.isWhatsAppReaderEnabled = isChecked
                    1 -> preferences.isMessageAutoReplyConfirmEnabled = isChecked
                    2 -> preferences.isCallerIdAnnouncementEnabled = isChecked
                }
            }
            .setPositiveButton("Done") { _, _ ->
                Toast.makeText(requireContext(), "Automation settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Notification Access") { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {}
            }
            .show()
    }
}
