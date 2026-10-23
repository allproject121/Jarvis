package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.macro.MacroEngine
import com.jarvis.assistant.automation.macro.MacroExecutionResult
import com.jarvis.assistant.automation.macro.MacroRepository
import com.jarvis.assistant.databinding.BottomSheetSkillsBinding
import com.jarvis.assistant.util.HudSoundManager
import com.jarvis.assistant.voice.PocketTtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rich interactive BottomSheet for viewing and triggering Installed Skills & Playbooks.
 */
class SkillsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSkillsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSkillsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        com.jarvis.assistant.util.FrostedGlassEffect.applyWindowBlur(dialog?.window, blurRadius = 45)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCloseSkills.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
        }

        // 1. ChatGPT Image Skill
        binding.btnRunChatGptSkill.setOnClickListener {
            HudSoundManager.playTapSound()
            promptAndRunMacro("chatgpt image", "image prompt", "Futuristic cyberpunk warrior with neon armor")
        }

        // 2. Gemini Veo Skill
        binding.btnRunGeminiSkill.setOnClickListener {
            HudSoundManager.playTapSound()
            promptAndRunMacro("gemini image", "image prompt", "Majestic dragon soaring over hyper-tech city")
        }

        // 3. WhatsApp Dispatch Skill
        binding.btnRunWhatsAppSkill.setOnClickListener {
            HudSoundManager.playTapSound()
            Toast.makeText(requireContext(), "Say: 'Send WhatsApp message to [Contact] saying [Message]'", Toast.LENGTH_LONG).show()
        }

        // 4. PocketTTS Voice Test
        binding.btnRunPocketTtsSkill.setOnClickListener {
            HudSoundManager.playTapSound()
            Toast.makeText(requireContext(), "Testing on-device neural voice…", Toast.LENGTH_SHORT).show()
            PocketTtsEngine.speak("Sir, PocketTTS on-device neural voice engine is operating with low latency.", requireContext())
        }

        // 5. Touch Guard Sentinel
        binding.btnRunTouchGuardSkill.setOnClickListener {
            HudSoundManager.playTapSound()
            val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
            val currentArmed = prefs.isTouchGuardArmed
            if (currentArmed) {
                prefs.isTouchGuardArmed = false
                com.jarvis.assistant.security.guardian.TouchGuardManager.disarm(requireContext())
                Toast.makeText(requireContext(), "Touch Guard Disarmed.", Toast.LENGTH_SHORT).show()
            } else {
                prefs.isTouchGuardArmed = true
                com.jarvis.assistant.security.guardian.TouchGuardManager.arm(requireContext())
                Toast.makeText(requireContext(), "Touch Guard ARMED! Keep phone still.", Toast.LENGTH_LONG).show()
            }
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
            .setMessage("Enter parameter for {$paramKey}:")
            .setView(input)
            .setPositiveButton("Execute Now") { _, _ ->
                val promptText = input.text.toString().trim()
                val repo = MacroRepository(requireContext())
                val engine = MacroEngine(requireContext(), repo)
                Toast.makeText(requireContext(), "Executing macro '$macroName'…", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.Default) {
                        engine.executeMacroByName(macroName, mapOf(paramKey to promptText))
                    }
                    when (result) {
                        is MacroExecutionResult.Success -> {
                            Toast.makeText(requireContext(), "Macro Finished: ${result.message}", Toast.LENGTH_LONG).show()
                        }
                        is MacroExecutionResult.Failure -> {
                            Toast.makeText(requireContext(), "Macro Notice: ${result.error}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SkillsBottomSheet"
        fun newInstance() = SkillsBottomSheet()
    }
}
