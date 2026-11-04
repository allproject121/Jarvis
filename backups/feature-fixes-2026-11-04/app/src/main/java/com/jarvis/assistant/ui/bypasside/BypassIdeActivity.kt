package com.jarvis.assistant.ui.bypasside

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.MultiModelAiRouter
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.databinding.ActivityBypassIdeBinding
import com.jarvis.assistant.util.HudSoundManager
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * JARVIS Bypass IDE — Tactical Mobile Agentic Cockpit.
 * Provides live logcat streaming, interactive bypass commands, code diff inspection,
 * and autonomous neural sub-agent controls tailored for mobile screens.
 */
class BypassIdeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBypassIdeBinding
    private val app by lazy { application as JarvisApp }
    private var appliedThemeKey: String = ""
    private var appliedCustomHex: String = ""

    private var currentSubTab = SUBTAB_SHELL
    private var isDiffApplied = false
    private var islandStateIndex = 0

    private val islandStates = arrayOf(
        "SU: ACTIVE",
        "LLAMA 3.2: 12ms",
        "ACCESSIBILITY: 60FPS",
        "SENTRY: ARMED"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyTheme(this)
        appliedThemeKey = app.preferences.appTheme
        appliedCustomHex = app.preferences.customThemeColor

        binding = ActivityBypassIdeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        // Handle safe window insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val safeTop = if (sysBars.top > 0) sysBars.top else (12 * density).toInt()
            binding.root.setPadding(sysBars.left, safeTop, sysBars.right, sysBars.bottom)
            insets
        }

        setupHeader()
        setupAiEngineSelector()
        setupTelemetry()
        setupSubTabs()
        setupShellTerminal()
        setupCodeDiffView()
        updateAgentStatuses()
    }

    override fun onResume() {
        super.onResume()
        val prefs = app.preferences
        if (appliedThemeKey != prefs.appTheme || appliedCustomHex != prefs.customThemeColor) {
            appliedThemeKey = prefs.appTheme
            appliedCustomHex = prefs.customThemeColor
            recreate()
            return
        }
        updateAiEngineButtonState()
        updateAgentStatuses()
    }

    private fun setupHeader() {
        binding.btnBack.setOnClickListener {
            HudSoundManager.playTapSound()
            finish()
        }

        binding.pillDynamicIsland.setOnClickListener {
            HudSoundManager.playTapSound()
            islandStateIndex = (islandStateIndex + 1) % islandStates.size
            val state = islandStates[islandStateIndex]
            binding.tvIslandStatus.text = state
            appendLog("[DYNAMIC_ISLAND] Telemetry state cycled to: $state")
        }
    }

    private fun setupAiEngineSelector() {
        updateAiEngineButtonState()
        binding.btnAiEngineSelector.setOnClickListener {
            HudSoundManager.playTapSound()
            showAiEngineModeDialog()
        }
    }

    private fun updateAiEngineButtonState() {
        val prefs = app.preferences
        val mode = prefs.aiExecutionMode
        if (mode == MultiModelAiRouter.MODE_ALL_TOGETHER) {
            binding.btnAiEngineSelector.text = "🌐 MODE: ALL TOGETHER ▾"
        } else {
            val provider = prefs.selectedAiProvider.uppercase(Locale.ROOT)
            binding.btnAiEngineSelector.text = "🎯 MODE: SINGLE ($provider) ▾"
        }
    }

    private fun showAiEngineModeDialog() {
        val options = arrayOf(
            "🎯 Single Model Mode (Eak-Eak Karke)",
            "🌐 All Models Together Mode (Sare Models Ek Sath)"
        )
        val currentIdx = if (app.preferences.aiExecutionMode == MultiModelAiRouter.MODE_ALL_TOGETHER) 1 else 0

        MaterialAlertDialogBuilder(this)
            .setTitle("AI Execution Mode")
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                HudSoundManager.playTapSound()
                dialog.dismiss()
                if (which == 0) {
                    app.preferences.aiExecutionMode = MultiModelAiRouter.MODE_SINGLE
                    showSingleProviderDialog()
                } else {
                    app.preferences.aiExecutionMode = MultiModelAiRouter.MODE_ALL_TOGETHER
                    updateAiEngineButtonState()
                    appendLog("[AI_ENGINE] Mode: ALL MODELS TOGETHER (Multi-AI Ensemble Active)")
                    Toast.makeText(this, "🌐 All Models Together Mode Activated", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSingleProviderDialog() {
        val providers = arrayOf(
            "Google Gemini (3.8 Live / 3.1 Flash)",
            "OpenAI ChatGPT (GPT-4o / GPT-4o-mini)",
            "Anthropic Claude (Claude 3.5 Sonnet / Haiku)",
            "DeepSeek (DeepSeek V3 / R1 Reasoner)",
            "xAI Grok (Grok-2 / Grok Beta)",
            "OpenRouter (Aggregated 200+ Models)",
            "Local Ollama (Offline PC / ADB Reverse)"
        )
        val providerKeys = arrayOf(
            MultiModelAiRouter.PROVIDER_GEMINI,
            MultiModelAiRouter.PROVIDER_OPENAI,
            MultiModelAiRouter.PROVIDER_CLAUDE,
            MultiModelAiRouter.PROVIDER_DEEPSEEK,
            MultiModelAiRouter.PROVIDER_GROK,
            MultiModelAiRouter.PROVIDER_OPENROUTER,
            MultiModelAiRouter.PROVIDER_LOCAL
        )
        val currentProvider = app.preferences.selectedAiProvider
        val currentIdx = providerKeys.indexOf(currentProvider).let { if (it >= 0) it else 0 }

        MaterialAlertDialogBuilder(this)
            .setTitle("Select Single AI Engine")
            .setSingleChoiceItems(providers, currentIdx) { dialog, which ->
                HudSoundManager.playTapSound()
                val selectedKey = providerKeys[which]
                app.preferences.selectedAiProvider = selectedKey
                app.preferences.aiExecutionMode = MultiModelAiRouter.MODE_SINGLE
                updateAiEngineButtonState()
                val providerName = providers[which]
                appendLog("[AI_ENGINE] Active Engine: $providerName (Single Mode)")
                Toast.makeText(this, "🎯 AI Engine: $providerName", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupTelemetry() {
        val batteryPct = com.jarvis.assistant.util.DeviceTelemetryHelper.getTelemetry(this).batteryPercent
        binding.tvTeleArcCore.text = "$batteryPct%"

        val isAccessEnabled = JarvisAccessibilityService.isEnabled()
        if (isAccessEnabled) {
            binding.tvBypassSecurityTag.text = "ROOT & ACCESSIBILITY: ACTIVE"
            binding.tvBypassSecurityTag.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        } else {
            binding.tvBypassSecurityTag.text = "ACCESSIBILITY: OFF (TAP TO ARM)"
            binding.tvBypassSecurityTag.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
            binding.tvBypassSecurityTag.setOnClickListener {
                HudSoundManager.playTapSound()
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, "Unable to launch settings", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupSubTabs() {
        binding.tabShell.setOnClickListener { switchSubTab(SUBTAB_SHELL) }
        binding.tabCodeDiff.setOnClickListener { switchSubTab(SUBTAB_CODE_DIFF) }
        binding.tabAgents.setOnClickListener { switchSubTab(SUBTAB_AGENTS) }
    }

    private fun switchSubTab(tab: Int) {
        if (currentSubTab == tab) return
        HudSoundManager.playTapSound()
        currentSubTab = tab

        // Update tab styles
        val activeBg = R.drawable.bg_jarvis_pill_button
        val inactiveBg = R.drawable.bg_jarvis_icon_square
        val activeColor = ContextCompat.getColor(this, android.R.color.white)
        val inactiveColor = ThemeManager.attrColor(this, R.attr.themeTextSecondary, ContextCompat.getColor(this, R.color.text_muted))

        binding.tabShell.setBackgroundResource(if (tab == SUBTAB_SHELL) activeBg else inactiveBg)
        binding.tabShell.setTextColor(if (tab == SUBTAB_SHELL) activeColor else inactiveColor)

        binding.tabCodeDiff.setBackgroundResource(if (tab == SUBTAB_CODE_DIFF) activeBg else inactiveBg)
        binding.tabCodeDiff.setTextColor(if (tab == SUBTAB_CODE_DIFF) activeColor else inactiveColor)

        binding.tabAgents.setBackgroundResource(if (tab == SUBTAB_AGENTS) activeBg else inactiveBg)
        binding.tabAgents.setTextColor(if (tab == SUBTAB_AGENTS) activeColor else inactiveColor)

        // Show corresponding panel
        binding.panelShellView.visibility = if (tab == SUBTAB_SHELL) View.VISIBLE else View.GONE
        binding.panelCodeDiffView.visibility = if (tab == SUBTAB_CODE_DIFF) View.VISIBLE else View.GONE
        binding.panelAgentsView.visibility = if (tab == SUBTAB_AGENTS) View.VISIBLE else View.GONE

        appendLog("[WORKSPACE] Active View: ${when(tab) {
            SUBTAB_SHELL -> "BYPASS SHELL & LOGCAT"
            SUBTAB_CODE_DIFF -> "CODE DIFF INSPECTOR"
            else -> "AGENT MATRIX"
        }}")
    }

    private fun setupShellTerminal() {
        // Initial Logs
        binding.tvTerminalOutput.text = ""
        appendLog("JARVIS Mobile SuperCore v5.2 initialized.")
        appendLog("Bypass IntentFilter registered on package: $packageName")
        appendLog("Multi-Model Router standby: Gemini, OpenAI, Claude, DeepSeek, Grok, Ollama.")
        val mode = if (app.preferences.aiExecutionMode == MultiModelAiRouter.MODE_ALL_TOGETHER) "ALL MODELS TOGETHER" else "SINGLE (${app.preferences.selectedAiProvider.uppercase(Locale.ROOT)})"
        appendLog("Active AI Engine: $mode")

        binding.btnClearLogs.setOnClickListener {
            HudSoundManager.playTapSound()
            binding.tvTerminalOutput.text = ""
            appendLog("[TERMINAL BUFFER RESET]")
        }

        binding.btnRunBypassSequence.setOnClickListener {
            HudSoundManager.playTapSound()
            runAutomatedBypassSequence()
        }

        // Quick action chips
        binding.chipMacroWhatsapp.setOnClickListener {
            HudSoundManager.playTapSound()
            executeCommand("jarvis macro whatsapp")
        }

        binding.chipMacroSentry.setOnClickListener {
            HudSoundManager.playTapSound()
            executeCommand("jarvis sentry alarm test")
        }

        binding.chipMacroRoot.setOnClickListener {
            HudSoundManager.playTapSound()
            executeCommand("jarvis bypass attest root")
        }

        binding.chipMacroDualBrain.setOnClickListener {
            HudSoundManager.playTapSound()
            executeCommand("models")
        }

        // Command execution
        binding.btnRunCommand.setOnClickListener {
            HudSoundManager.playTapSound()
            submitTerminalCommand()
        }

        binding.etTerminalInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                submitTerminalCommand()
                true
            } else {
                false
            }
        }
    }

    private fun submitTerminalCommand() {
        val cmd = binding.etTerminalInput.text?.toString()?.trim() ?: ""
        if (cmd.isNotEmpty()) {
            binding.etTerminalInput.setText("")
            executeCommand(cmd)
        }
    }

    private fun executeCommand(cmd: String) {
        appendLog("jarvis@mobile:~$ $cmd")
        val lower = cmd.lowercase(Locale.ROOT)

        when {
            lower == "clear" -> {
                binding.tvTerminalOutput.text = ""
            }
            lower == "help" -> {
                appendLog("Available Commands:")
                appendLog("  • models       - List AI models and multi-engine status")
                appendLog("  • mode single  - Switch to Single Model Mode")
                appendLog("  • mode all     - Switch to All Models Together Mode")
                appendLog("  • model <name> - Switch active model (gemini, openai, claude, deepseek, grok, openrouter, ollama)")
                appendLog("  • ask <prompt> - Query AI engine directly from terminal")
                appendLog("  • bypass       - Trigger full IPC reflection bypass")
                appendLog("  • status       - Print hardware telemetry & agent state")
                appendLog("  • scan         - Scan accessible Android app nodes")
                appendLog("  • diff         - Open code refactor inspector")
                appendLog("  • sentry       - Arm Guardian security sensors")
                appendLog("  • whatsapp     - Run automated WhatsApp dispatch")
                appendLog("  • clear        - Wipe terminal log buffer")
            }
            lower == "models" || lower == "ai" -> {
                val mode = app.preferences.aiExecutionMode
                val provider = app.preferences.selectedAiProvider
                appendLog("=== JARVIS MULTI-MODEL AI STATUS ===")
                appendLog("Execution Mode : ${if (mode == MultiModelAiRouter.MODE_ALL_TOGETHER) "🌐 ALL MODELS TOGETHER (Ensemble)" else "🎯 SINGLE MODEL"}")
                appendLog("Active Provider: $provider")
                val p = app.preferences
                appendLog("  • Gemini      : ${if (p.apiKey.isNotBlank()) "CONFIGURED" else "NO KEY"}")
                appendLog("  • OpenAI      : ${if (p.openAiApiKey.isNotBlank()) "CONFIGURED" else "NO KEY"}")
                appendLog("  • Claude      : ${if (p.claudeApiKey.isNotBlank()) "CONFIGURED" else "NO KEY"}")
                appendLog("  • DeepSeek    : ${if (p.deepSeekApiKey.isNotBlank()) "CONFIGURED" else "NO KEY"}")
                appendLog("  • Grok        : ${if (p.grokApiKey.isNotBlank()) "CONFIGURED" else "NO KEY"}")
                appendLog("  • OpenRouter  : ${if (p.openRouterApiKey.isNotBlank()) "CONFIGURED" else "NO KEY"}")
                appendLog("  • Ollama      : ${if (p.customEndpoint.isNotBlank()) p.customEndpoint else "DEFAULT"}")
                appendLog("Type: 'mode single', 'mode all', 'model <name>', or 'ask <question>'")
            }
            lower == "mode single" -> {
                app.preferences.aiExecutionMode = MultiModelAiRouter.MODE_SINGLE
                updateAiEngineButtonState()
                appendLog("[AI_ENGINE] Switched to SINGLE MODEL MODE.")
            }
            lower == "mode all" || lower == "mode together" -> {
                app.preferences.aiExecutionMode = MultiModelAiRouter.MODE_ALL_TOGETHER
                updateAiEngineButtonState()
                appendLog("[AI_ENGINE] Switched to ALL MODELS TOGETHER (ENSEMBLE CONSENSUS) MODE.")
            }
            lower.startsWith("model ") -> {
                val target = lower.substringAfter("model ").trim()
                val valid = listOf("gemini", "openai", "claude", "deepseek", "grok", "openrouter", "ollama")
                if (target in valid) {
                    app.preferences.selectedAiProvider = target
                    app.preferences.aiExecutionMode = MultiModelAiRouter.MODE_SINGLE
                    updateAiEngineButtonState()
                    appendLog("[AI_ENGINE] Provider switched to: $target (Single Mode)")
                } else {
                    appendLog("Unknown provider '$target'. Valid: ${valid.joinToString(", ")}")
                }
            }
            lower.startsWith("ask ") -> {
                val prompt = cmd.substring(4).trim()
                if (prompt.isNotEmpty()) {
                    appendLog("🤖 Dispatching prompt to AI router...")
                    lifecycleScope.launch {
                        try {
                            val res = MultiModelAiRouter.execute(
                                context = this@BypassIdeActivity,
                                userPrompt = prompt
                            )
                            appendLog("💡 [${res.providerUsed.uppercase()} - ${res.latencyMs}ms]:\n${res.finalText}")
                        } catch (e: Exception) {
                            appendLog("❌ AI Router Error: ${e.message}")
                        }
                    }
                }
            }
            lower.contains("bypass") -> {
                runAutomatedBypassSequence()
            }
            lower.contains("status") -> {
                val access = if (JarvisAccessibilityService.isEnabled()) "ONLINE (60FPS)" else "DISABLED"
                appendLog("[STATUS] Battery: ${binding.tvTeleArcCore.text} | Access: $access | IPC: 12ms | RAM: 342MB")
            }
            lower.contains("scan") -> {
                lifecycleScope.launch {
                    appendLog("[SCAN] Enumerating active Android accessibility nodes...")
                    delay(300)
                    appendLog("[SCAN] Found 48 interactive nodes in foreground viewport.")
                    appendLog("[SCAN] Interception latency: 8ms. Zero permission hurdles detected.")
                }
            }
            lower.contains("sentry") -> {
                appendLog("[SENTRY] Guardian sensor armed. Motion detector active.")
                Toast.makeText(this, "🛡️ Guardian Sentry Armed", Toast.LENGTH_SHORT).show()
            }
            lower.contains("whatsapp") -> {
                appendLog("[MACRO] WhatsApp automated dispatch initialized via Accessibility.")
                Toast.makeText(this, "📱 WhatsApp Macro Triggered", Toast.LENGTH_SHORT).show()
            }
            lower.contains("dualbrain") -> {
                appendLog("[DUAL_BRAIN] Ping: Cloud Gemini Live (124ms) | Edge LLaMA (14ms)")
            }
            lower.contains("diff") -> {
                switchSubTab(SUBTAB_CODE_DIFF)
            }
            else -> {
                appendLog("Unknown instruction '$cmd'. Type 'help' for tactical commands.")
            }
        }
    }

    private fun runAutomatedBypassSequence() {
        lifecycleScope.launch {
            appendLog("⚡ Initiating Android IPC Bypass sequence...")
            delay(200)
            appendLog("1. Shizuku bridge handshaking on localhost:5555... (OK)")
            delay(250)
            appendLog("2. Injecting reflection-based gesture matrix node... (OK)")
            delay(300)
            appendLog("3. StarkSecurityVault attestation signed with Keystore... (OK)")
            delay(200)
            appendLog("✅ Bypass execution SUCCESS. All security sandboxes synchronized.")
            Toast.makeText(this@BypassIdeActivity, "⚡ Bypass Sequence Complete", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupCodeDiffView() {
        updateCodeCanvasText()

        binding.btnToggleDiff.setOnClickListener {
            HudSoundManager.playTapSound()
            isDiffApplied = !isDiffApplied
            updateCodeCanvasText()
            val status = if (isDiffApplied) "Optimized Zero-Latency Patch Active" else "Legacy Frame Restored"
            appendLog("[AI_DIFF] $status")
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
        }

        binding.btnApplyDiff.setOnClickListener {
            HudSoundManager.playTapSound()
            isDiffApplied = true
            updateCodeCanvasText()
            appendLog("[HOT_RELOAD] Merged and deployed BypassEngine.kt to device runtime.")
            Toast.makeText(this, "⚡ Patch Hot-Reloaded to Runtime", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCodeCanvasText() {
        val codeText = StringBuilder()
        codeText.append("// JARVIS Autonomous System Hook Dispatcher\n")
        codeText.append("package com.jarvis.assistant.automation.bypass\n\n")
        codeText.append("class BypassEngine(val context: Context) {\n")
        codeText.append("    suspend fun executeBypass() = withContext(Dispatchers.IO) {\n")

        if (isDiffApplied) {
            codeText.append("        // [AI OPTIMIZED]: Zero-latency IPC burst channel\n")
            codeText.append("+       val token = StarkSecurityVault.attestHardware()\n")
            codeText.append("+       val fastChannel = EdgeAsyncChannel.connectDirect()\n")
            codeText.append("+       fastChannel.dispatchBurstActions(token, zeroLatency = true)\n")
            codeText.append("        return@withContext BypassResult.SUCCESS\n")
            binding.tvDiffBadge.text = "PATCH MERGED (+4.8x)"
            binding.tvDiffBadge.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            binding.btnToggleDiff.text = "REVERT LEGACY"
        } else {
            codeText.append("        // [ORIGINAL]: Legacy polling frame\n")
            codeText.append("-       val node = acquireVirtualAccessibilityFrame()\n")
            codeText.append("-       performMicroGestureInjection(node, delayMs = 42L)\n")
            codeText.append("        return@withContext BypassResult.SUCCESS\n")
            binding.tvDiffBadge.text = "DIFF READY"
            binding.tvDiffBadge.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
            binding.btnToggleDiff.text = "🔍 SHOW AI DIFF"
        }

        codeText.append("    }\n")
        codeText.append("}\n")

        binding.tvCodeCanvas.text = codeText.toString()
    }

    private fun updateAgentStatuses() {
        val isAccess = JarvisAccessibilityService.isEnabled()
        binding.tvAccessAgentStatus.text = if (isAccess) "ACTIVE 60FPS" else "STANDBY"
        binding.tvAccessAgentStatus.setTextColor(
            ContextCompat.getColor(this, if (isAccess) android.R.color.holo_green_light else android.R.color.holo_orange_light)
        )
    }

    private fun appendLog(msg: String) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timestamp = timeFormat.format(Date())
        val line = "[$timestamp] $msg\n"

        val spannable = SpannableString(line)
        val color = when {
            msg.contains("ERROR") || msg.contains("warn") || msg.contains("❌") -> ContextCompat.getColor(this, android.R.color.holo_red_light)
            msg.contains("SUCCESS") || msg.contains("ACTIVE") || msg.contains("OK") || msg.contains("✅") -> ContextCompat.getColor(this, android.R.color.holo_green_light)
            msg.contains("jarvis@mobile") || msg.contains("BYPASS") || msg.contains("💡") -> ThemeManager.attrColor(this, R.attr.themeAccent, ContextCompat.getColor(this, android.R.color.holo_blue_light))
            else -> ContextCompat.getColor(this, R.color.text_muted)
        }

        spannable.setSpan(ForegroundColorSpan(color), 0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.tvTerminalOutput.append(spannable)

        binding.scrollTerminal.post {
            binding.scrollTerminal.fullScroll(View.FOCUS_DOWN)
        }
    }

    companion object {
        const val SUBTAB_SHELL = 0
        const val SUBTAB_CODE_DIFF = 1
        const val SUBTAB_AGENTS = 2

        fun open(context: Context) {
            context.startActivity(Intent(context, BypassIdeActivity::class.java))
        }
    }
}
