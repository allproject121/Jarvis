package com.jarvis.assistant.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.BottomSheetSecretSettingsBinding
import com.jarvis.assistant.util.HudSoundManager

/**
 * Rich interactive BottomSheet for Secret Settings, Intimacy Level, and Developer Overrides.
 */
class SecretSettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSecretSettingsBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSecretSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        com.jarvis.assistant.util.FrostedGlassEffect.applyWindowBlur(dialog?.window, blurRadius = 45)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCloseSecret.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
        }

        // Initialize intimacy radio buttons
        when (preferences.secretIntimacyLevel) {
            1 -> binding.rbLevelFriendly.isChecked = true
            2 -> binding.rbLevelRomantic.isChecked = true
            3 -> binding.rbLevelIntense.isChecked = true
            else -> binding.rbLevelFriendly.isChecked = true
        }

        binding.rgIntimacyLevel.setOnCheckedChangeListener { _, checkedId ->
            HudSoundManager.playTapSound()
            val level = when (checkedId) {
                binding.rbLevelFriendly.id -> 1
                binding.rbLevelRomantic.id -> 2
                binding.rbLevelIntense.id -> 3
                else -> 1
            }
            preferences.secretIntimacyLevel = level
            preferences.isSecretSettingsEnabled = true
            preferences.personality = GeminiConstants.PERSONALITY_GIRLFRIEND
            Toast.makeText(requireContext(), "Companion Mode Intimacy set to Level $level", Toast.LENGTH_SHORT).show()
        }

        // Switches
        binding.swBypassConfirmations.isChecked = preferences.isDirectAutonomousMode
        binding.swBypassConfirmations.setOnCheckedChangeListener { _, isChecked ->
            preferences.isDirectAutonomousMode = isChecked
            Toast.makeText(requireContext(), if (isChecked) "Direct autonomous execution enabled." else "Confirmation prompts restored.", Toast.LENGTH_SHORT).show()
        }

        binding.swVerboseJni.isChecked = true
        binding.swVerboseJni.setOnCheckedChangeListener { _, isChecked ->
            Toast.makeText(requireContext(), if (isChecked) "Verbose llama.cpp JNI logs active." else "Verbose JNI logs suppressed.", Toast.LENGTH_SHORT).show()
        }

        binding.swHardwareAccel.isChecked = true
        binding.swHardwareAccel.setOnCheckedChangeListener { _, isChecked ->
            Toast.makeText(requireContext(), if (isChecked) "NEON vector acceleration active." else "NEON acceleration disabled.", Toast.LENGTH_SHORT).show()
        }

        binding.btnResetDefaultMode.setOnClickListener {
            HudSoundManager.playTapSound()
            preferences.isSecretSettingsEnabled = false
            preferences.personality = GeminiConstants.PERSONALITY_ASSISTANT
            preferences.secretIntimacyLevel = 1
            Toast.makeText(requireContext(), "Restored standard Jarvis Assistant profile.", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SecretSettingsBottomSheet"
        fun newInstance() = SecretSettingsBottomSheet()
    }
}
