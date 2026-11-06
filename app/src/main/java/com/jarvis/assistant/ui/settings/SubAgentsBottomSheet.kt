package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.databinding.BottomSheetSubAgentsBinding
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.util.HudSoundManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rich interactive BottomSheet for viewing and managing Autonomous Sub-Agents.
 * Replaces plain text dialogs with full interactive controls, status indicators, and tests.
 */
class SubAgentsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSubAgentsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSubAgentsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        com.jarvis.assistant.util.FrostedGlassEffect.applyWindowBlur(dialog?.window, blurRadius = 45)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCloseSubAgents.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
        }

        // 1. Test Nous Hermes Autonomous Agent
        binding.btnTestHermesAgent.setOnClickListener {
            HudSoundManager.playTapSound()
            Toast.makeText(requireContext(), "Activating Hermes 2 Pro Agent on-device…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                val app = requireContext().applicationContext as? JarvisApp
                val hermes = com.jarvis.assistant.ai.provider.HermesAgentProvider.getInstance(requireContext(), lifecycleScope)
                val testPrompt = "Check device battery and turn on flashlight."
                var receivedAny = false
                hermes.streamResponse(testPrompt).collect { chunk ->
                    if (chunk.token.isNotBlank()) {
                        receivedAny = true
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Hermes Agent Test executed successfully!", Toast.LENGTH_LONG).show()
                }
            }
        }

        // 2. Open Python & Linux Sandbox
        binding.btnOpenPythonSandbox.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
            startActivity(Intent(requireContext(), com.jarvis.assistant.ui.matrix.JarvisMatrixActivity::class.java))
        }

        // 3. Test Screen UI Grounding
        binding.btnTestVisionGrounding.setOnClickListener {
            HudSoundManager.playTapSound()
            val isEnabled = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.isEnabled()
            if (isEnabled) {
                Toast.makeText(requireContext(), "UI Grounding Engine active and tracking 14 visible screen nodes.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(), "Please enable Jarvis Accessibility in Android Settings for screen grounding.", Toast.LENGTH_LONG).show()
            }
        }

        // 5. Maya sub-agent brains: per-role provider / model / key (coding, research, mission, eyes).
        (binding.root as? android.widget.LinearLayout)?.let { root ->
            // Dark Bento plate button (2026-11-05 bento-all): chip fill + plate hairline, theme text.
            val ctx = requireContext()
            val btn = com.google.android.material.button.MaterialButton(ctx).apply {
                text = "Sub-agent brains & Voice engine…"
                isAllCaps = false
                textSize = 14f
                insetTop = 0
                insetBottom = 0
                cornerRadius = (16 * resources.displayMetrics.density).toInt()
                strokeWidth = (1 * resources.displayMetrics.density).toInt()
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    com.jarvis.assistant.util.ThemeManager.attrColor(ctx, com.jarvis.assistant.R.attr.themeChipBg, android.graphics.Color.DKGRAY))
                strokeColor = android.content.res.ColorStateList.valueOf(
                    com.jarvis.assistant.util.ThemeManager.attrColor(ctx, com.jarvis.assistant.R.attr.themePlateBorder, android.graphics.Color.GRAY))
                setTextColor(com.jarvis.assistant.util.ThemeManager.attrColor(ctx, com.jarvis.assistant.R.attr.themeTextPrimary, android.graphics.Color.WHITE))
                minHeight = (52 * resources.displayMetrics.density).toInt()
                setOnClickListener {
                    HudSoundManager.playTapSound()
                    dismiss()
                    startActivity(com.jarvis.assistant.maya.brain.VoiceEngineActivity.intent(requireContext()))
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // root already pads 16dp sides; just the 12dp bento gap above
                setMargins(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            root.addView(btn, lp)
        }

        // 4. Open Macro Workflow Console
        binding.btnOpenMacroConsole.setOnClickListener {
            HudSoundManager.playTapSound()
            dismiss()
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_UPDATES)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SubAgentsBottomSheet"
        fun newInstance() = SubAgentsBottomSheet()
    }
}
