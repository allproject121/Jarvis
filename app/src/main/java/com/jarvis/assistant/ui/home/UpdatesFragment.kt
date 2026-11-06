package com.jarvis.assistant.ui.home

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.macro.Macro
import com.jarvis.assistant.automation.macro.MacroEngine
import com.jarvis.assistant.automation.macro.MacroExecutionResult
import com.jarvis.assistant.automation.macro.MacroRepository
import com.jarvis.assistant.databinding.FragmentUpdatesBinding
import com.jarvis.assistant.voice.PocketTtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdatesFragment : Fragment() {

    private var _binding: FragmentUpdatesBinding? = null
    private val binding get() = _binding!!
    private val macroRepository by lazy { MacroRepository(requireContext()) }
    private val macroEngine by lazy { MacroEngine(requireContext(), macroRepository) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUpdatesBinding.inflate(inflater, container, false)
        renderContent()
        return binding.root
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            renderContent()
        }
    }

    private fun renderContent() {
        val list = binding.updatesList
        list.removeAllViews()

        // 1. System Readiness & Accessibility Status Card
        renderSystemReadinessCard(list)

        // 2. Section Header: Autonomous Macros
        renderSectionHeader(list, "Autonomous macros", "Direct UI execution pipelines driven by Accessibility")

        // 3. Render all installed macros with real execution buttons
        val macros = macroRepository.getAllMacros()
        macros.forEach { macro ->
            renderMacroCard(list, macro)
        }

        // 4. Section Header: Verified System Capabilities & Catalog
        renderSectionHeader(list, "Core capabilities", "Verified device & OS integration modules")

        // 5. Render Feature Updates
        renderFeatureUpdates(list)
    }

    // Dark Bento (2026-11-05 bento-all): section title 19sp semibold + muted 13sp subtitle,
    // cards = bg_nova_card with 18dp padding, buttons = accent pill (primary) / plate pill (secondary).
    private fun inter(weight: Int): android.graphics.Typeface {
        val base = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.inter)
            ?: android.graphics.Typeface.DEFAULT
        return android.graphics.Typeface.create(base, weight, false)
    }

    private fun bentoCard(bottomMarginDp: Int = 12): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_nova_card)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(bottomMarginDp)
        }
    }

    private fun bentoButton(label: String, primary: Boolean): Button = Button(requireContext()).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        typeface = inter(600)
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(14), 0, dp(14), 0)
        if (primary) {
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_jarvis_pill_button)
            setTextColor(com.jarvis.assistant.util.ThemeManager.onAccentColor(requireContext()))
        } else {
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_set_chip)
            setTextColor(themeColor(R.attr.themeTextPrimary))
        }
    }

    private fun renderSectionHeader(parent: LinearLayout, title: String, subtitle: String) {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(16), 0, dp(10))
        }
        container.addView(TextView(requireContext()).apply {
            text = title
            textSize = 19f
            typeface = inter(600)
            setTextColor(themeColor(R.attr.themeTextPrimary))
        })
        container.addView(TextView(requireContext()).apply {
            text = subtitle
            textSize = 13f
            typeface = inter(400)
            setTextColor(themeColor(R.attr.themeTextMuted))
            setPadding(0, dp(2), 0, 0)
        })
        parent.addView(container)
    }

    private fun renderSystemReadinessCard(parent: LinearLayout) {
        val card = bentoCard(bottomMarginDp = 8)

        card.addView(TextView(requireContext()).apply {
            text = "System readiness"
            textSize = 18f
            typeface = inter(600)
            setTextColor(themeColor(R.attr.themeTextPrimary))
        })
        card.addView(TextView(requireContext()).apply {
            text = "Services Jarvis needs for hands-free actions"
            textSize = 13f
            typeface = inter(400)
            setTextColor(themeColor(R.attr.themeTextMuted))
            setPadding(0, dp(2), 0, dp(12))
        })

        val isAccessEnabled = JarvisAccessibilityService.instance != null
        val tvAccess = TextView(requireContext()).apply {
            text = if (isAccessEnabled) "\u25CF  Accessibility service \u00B7 Active, ready for autonomous actions"
                   else "\u25CF  Accessibility service \u00B7 Disabled, tap below to enable"
            textSize = 13f
            typeface = inter(500)
            setTextColor(if (isAccessEnabled) ContextCompat.getColor(requireContext(), R.color.status_green) else ContextCompat.getColor(requireContext(), R.color.status_amber))
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_jarvis_row_item)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        card.addView(tvAccess)

        val tvVoice = TextView(requireContext()).apply {
            text = "\u25CF  PocketTTS engine \u00B7 Offline 24 kHz Hindi / English speech"
            textSize = 13f
            typeface = inter(500)
            setTextColor(themeColor(R.attr.themeTextSecondary))
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_jarvis_row_item)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
        card.addView(tvVoice)

        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        if (!isAccessEnabled) {
            val btnGrant = bentoButton("Grant accessibility", primary = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener {
                    try {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (_: Exception) {}
                }
            }
            btnRow.addView(btnGrant)
        }

        val btnTestTts = bentoButton("Test Pocket TTS", primary = isAccessEnabled).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
            setOnClickListener {
                PocketTtsEngine.speak("नमस्ते! Jarvis Pocket TTS इंजन बिल्कुल ठीक चल रहा है।", requireContext())
                Toast.makeText(requireContext(), "Synthesizing test speech\u2026", Toast.LENGTH_SHORT).show()
            }
        }
        btnRow.addView(btnTestTts)

        card.addView(btnRow)
        parent.addView(card)
    }

    private fun renderMacroCard(parent: LinearLayout, macro: Macro) {
        val card = bentoCard()

        card.addView(TextView(requireContext()).apply {
            text = macro.name
            textSize = 18f
            typeface = inter(600)
            setTextColor(themeColor(R.attr.themeTextPrimary))
        })

        card.addView(TextView(requireContext()).apply {
            text = macro.intent
            textSize = 13f
            typeface = inter(400)
            setTextColor(themeColor(R.attr.themeTextMuted))
            setPadding(0, dp(2), 0, dp(14))
        })

        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnRun = bentoButton("Run macro", primary = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                executeMacroWithPrompt(macro)
            }
        }
        btnRow.addView(btnRun)

        val btnSteps = bentoButton("View steps (${macro.steps.size})", primary = false).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
            setOnClickListener {
                showMacroStepsDialog(macro)
            }
        }
        btnRow.addView(btnSteps)

        card.addView(btnRow)
        parent.addView(card)
    }

    private fun executeMacroWithPrompt(macro: Macro) {
        if (JarvisAccessibilityService.instance == null) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Accessibility Permission Required")
                .setMessage("Autonomous macros require Jarvis Accessibility Service to interact with other apps.")
                .setPositiveButton("Open Settings") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (_: Exception) {}
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        if (macro.params.isNotEmpty()) {
            val paramKey = macro.params.first()
            val defaultVal = if (paramKey.contains("prompt", ignoreCase = true)) {
                "A futuristic sci-fi city with neon lights and flying cars"
            } else {
                "What is the capital of India?"
            }

            val input = EditText(requireContext()).apply {
                setText(defaultVal)
                setSelection(defaultVal.length)
                setPadding(32, 24, 32, 24)
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Run '${macro.name}'")
                .setMessage("Enter parameter for {$paramKey}:")
                .setView(input)
                .setPositiveButton("Run Now") { _, _ ->
                    val value = input.text.toString().trim()
                    runMacro(macro.name, mapOf(paramKey to value))
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            runMacro(macro.name, emptyMap())
        }
    }

    private fun runMacro(macroName: String, params: Map<String, String>) {
        Toast.makeText(requireContext(), "Executing '$macroName'…", Toast.LENGTH_SHORT).show()
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

    private fun showMacroStepsDialog(macro: Macro) {
        val stepsText = macro.steps.mapIndexed { idx, s ->
            "${idx + 1}. [${s.tool}] ${s.args}"
        }.joinToString("\n\n")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Macro Steps: ${macro.name}")
            .setMessage(stepsText)
            .setPositiveButton("Close", null)
            .show()
    }

    private data class FeatureNotice(val feature: String, val title: String, val details: String, val kind: String = "FEATURE")

    private fun renderFeatureUpdates(parent: LinearLayout) {
        val features = listOf(
            FeatureNotice("PocketTTS On-Device Voice", "Offline 24kHz Neural Speech Engine", "High-fidelity on-device voice generation powered by ONNX Runtime and Kyutai Mimi neural vocoder. Works without internet for instant Hindi & English voice replies."),
            FeatureNotice("Autonomous Directives", "Accessibility UI Macro Automation", "Drives apps automatically (ChatGPT, Gemini, WhatsApp) using structured accessibility nodes without manual tapping."),
            FeatureNotice("Touch Guard Sentinel", "Device Anti-Theft Sensor", "Monitors device accelerometer and gyroscope motion. Triggers an audible alarm and flashes screen when picked up unexpectedly."),
            FeatureNotice("WhatsApp Voice Reply", "Notification Reader & RemoteInput", "Automatically detects incoming WhatsApp notifications. Reply directly using voice through Android RemoteInput without launching WhatsApp."),
            FeatureNotice("Biometric AppLock", "Hardware-Backed Face & Fingerprint", "Secures sensitive apps using Android BiometricPrompt with PIN fallback and failed intruder logging."),
            FeatureNotice("Default Digital Assistant", "System Home/Power Button Trigger", "JARVIS integrates natively as Android's default Digital Assistant. Long-press home or power key to invoke JARVIS instantly."),
            FeatureNotice("Termux Linux Bridge", "Native Linux Terminal & Python", "Dispatches and executes bash commands, Python scripts, and Termux recipes directly on device.")
        )

        features.forEach { feature ->
            val card = bentoCard()
            // pill badge (dot + feature name) instead of the old accent caps label
            card.addView(TextView(requireContext()).apply {
                text = feature.feature
                textSize = 11f
                typeface = inter(600)
                setTextColor(themeColor(R.attr.themeTextSecondary))
                background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_set_badge)
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.bg_bento_dot, 0, 0, 0)
                compoundDrawablePadding = dp(6)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            card.addView(TextView(requireContext()).apply {
                text = feature.title
                textSize = 18f
                typeface = inter(600)
                setTextColor(themeColor(R.attr.themeTextPrimary))
                setPadding(0, dp(10), 0, dp(4))
            })
            card.addView(TextView(requireContext()).apply {
                text = feature.details
                textSize = 13f
                typeface = inter(400)
                setTextColor(themeColor(R.attr.themeTextMuted))
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            parent.addView(card)
        }
    }

    private fun themeColor(attribute: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

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
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            renderContent()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
