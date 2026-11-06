package com.jarvis.assistant.ui.bypasside

import android.app.ActivityManager
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
import com.jarvis.assistant.automation.accessibility.MainThread
import com.jarvis.assistant.automation.accessibility.UiTreeBuilder
import com.jarvis.assistant.databinding.ActivityBypassIdeBinding
import com.jarvis.assistant.util.DeviceTelemetryHelper
import com.jarvis.assistant.util.HudSoundManager
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * JARVIS Bypass IDE — Tactical Mobile Agentic Cockpit.
 * A command terminal plus an agent status panel. Every number shown here is read
 * live from the device (battery, RAM, accessibility, root, Touch Guard, AI router).
 */
class BypassIdeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBypassIdeBinding
    private val app by lazy { application as JarvisApp }
    private var appliedThemeKey: String = ""
    private var appliedCustomHex: String = ""

    private var currentSubTab = SUBTAB_SHELL
    private var islandStateIndex = 0

    /** Path of the `su` binary if one was found, "" if none; null until checked. */
    private var suBinaryPath: String? = null

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
        setupSubTabs()
        setupShellTerminal()
        refreshTelemetry()
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
        refreshTelemetry()
    }

    private fun setupHeader() {
        binding.btnBack.setOnClickListener {
            HudSoundManager.playTapSound()
            finish()
        }

        binding.pillDynamicIsland.setOnClickListener {
            HudSoundManager.playTapSound()
            val states = islandStates()
            islandStateIndex = (islandStateIndex + 1) % states.size
            val state = states[islandStateIndex]
            binding.tvIslandStatus.text = state
            appendLog("[DYNAMIC_ISLAND] $state")
        }
    }

    /** Live states the header pill cycles through. */
    private fun islandStates(): List<String> = listOf(
        "SU: ${rootLabel()}",
        "AI: ${activeProviderName().uppercase(Locale.ROOT)}",
        "A11Y: ${if (JarvisAccessibilityService.isEnabled()) "ON" else "OFF"}"
    )

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
            binding.btnAiEngineSelector.text = "Mode: all together ▾"
        } else {
            val provider = prefs.selectedAiProvider.uppercase(Locale.ROOT)
            binding.btnAiEngineSelector.text = "Mode: single ($provider) ▾"
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

    // ── Live telemetry ──────────────────────────────────────────────────────

    /** Refreshes the telemetry card, the security tag and the agent panel from live state. */
    private fun refreshTelemetry() {
        val tele = DeviceTelemetryHelper.getTelemetry(this)
        binding.tvTeleArcCore.text = "${tele.batteryPercent}%"
        binding.tvTeleEdgeRam.text = "${freeRamMb()} MB"
        binding.tvTeleProvider.text = activeProviderName()
        binding.tvIslandStatus.text = islandStates()[islandStateIndex % 4]
        updateSecurityTag()
        updateAgentStatuses()

        if (suBinaryPath == null) {
            lifecycleScope.launch {
                suBinaryPath = withContext(Dispatchers.IO) { findSuBinary() }
                updateSecurityTag()
                binding.tvIslandStatus.text = islandStates()[islandStateIndex % 4]
            }
        }
    }

    private fun updateSecurityTag() {
        val isAccessEnabled = JarvisAccessibilityService.isEnabled()
        val tag = "ROOT: ${rootLabel()} · A11Y: ${if (isAccessEnabled) "ON" else "OFF (TAP)"}"
        binding.tvBypassSecurityTag.text = tag
        // Bento: status pill text uses theme success/warning instead of holo colours.
        binding.tvBypassSecurityTag.setTextColor(
            ThemeManager.attrColor(
                this,
                if (isAccessEnabled) R.attr.themeSuccess else R.attr.themeWarning,
                ContextCompat.getColor(this, if (isAccessEnabled) android.R.color.holo_green_light else android.R.color.holo_orange_light)
            )
        )
        if (isAccessEnabled) {
            binding.tvBypassSecurityTag.setOnClickListener(null)
            binding.tvBypassSecurityTag.isClickable = false
        } else {
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

    private fun rootLabel(): String = when (suBinaryPath) {
        null -> "CHECKING"
        "" -> "NO"
        else -> "SU FOUND"
    }

    /**
     * Looks for an `su` binary on the usual paths and on $PATH without executing it
     * (running `su` would pop a root-grant prompt). Returns its path, or "" if none.
     */
    private fun findSuBinary(): String {
        val candidates = mutableListOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su",
            "/debug_ramdisk/su"
        )
        System.getenv("PATH")?.split(':')?.filter { it.isNotBlank() }?.forEach {
            candidates.add("$it/su")
        }
        return candidates.distinct().firstOrNull { path ->
            try { File(path).exists() } catch (_: Exception) { false }
        } ?: ""
    }

    private fun freeRamMb(): Long {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }

    /** The provider Jarvis' model router actually used last ("Auto" = nothing routed yet). */
    private fun activeProviderName(): String {
        val name = runCatching { app.modelRouter.lastActiveProviderName.value }.getOrNull()
        return if (name.isNullOrBlank() || name == "Auto") {
            "Auto (${app.preferences.selectedAiProvider})"
        } else name
    }

    private fun setupSubTabs() {
        binding.tabShell.setOnClickListener { switchSubTab(SUBTAB_SHELL) }
        binding.tabAgents.setOnClickListener { switchSubTab(SUBTAB_AGENTS) }
    }

    private fun switchSubTab(tab: Int) {
        if (currentSubTab == tab) return
        HudSoundManager.playTapSound()
        currentSubTab = tab

        // Update tab styles
        // Bento pill tabs: active = accent-fill pill, idle = plate pill with hairline.
        val activeBg = R.drawable.bg_bento_x_chip_on
        val inactiveBg = R.drawable.bg_set_chip
        val activeColor = ThemeManager.onAccentColor(this)
        val inactiveColor = ThemeManager.attrColor(this, R.attr.themeTextSecondary, ContextCompat.getColor(this, R.color.text_muted))

        binding.tabShell.setBackgroundResource(if (tab == SUBTAB_SHELL) activeBg else inactiveBg)
        binding.tabShell.setTextColor(if (tab == SUBTAB_SHELL) activeColor else inactiveColor)

        binding.tabAgents.setBackgroundResource(if (tab == SUBTAB_AGENTS) activeBg else inactiveBg)
        binding.tabAgents.setTextColor(if (tab == SUBTAB_AGENTS) activeColor else inactiveColor)

        // Show corresponding panel
        binding.panelShellView.visibility = if (tab == SUBTAB_SHELL) View.VISIBLE else View.GONE
        binding.panelAgentsView.visibility = if (tab == SUBTAB_AGENTS) View.VISIBLE else View.GONE

        if (tab == SUBTAB_AGENTS) updateAgentStatuses()
        appendLog("[WORKSPACE] Active View: ${if (tab == SUBTAB_SHELL) "COMMAND TERMINAL" else "AGENT MATRIX"}")
    }

    private fun setupShellTerminal() {
        // Initial Logs
        binding.tvTerminalOutput.text = ""
        appendLog("JARVIS Mobile SuperCore v5.2 initialized.")
        appendLog("Multi-Model Router standby: Gemini, OpenAI, Claude, DeepSeek, Grok, Ollama.")
        val mode = if (app.preferences.aiExecutionMode == MultiModelAiRouter.MODE_ALL_TOGETHER) "ALL MODELS TOGETHER" else "SINGLE (${app.preferences.selectedAiProvider.uppercase(Locale.ROOT)})"
        appendLog("Active AI Engine: $mode")
        appendLog("Accessibility service: ${if (JarvisAccessibilityService.isEnabled()) "ON" else "OFF"}")

        binding.btnClearLogs.setOnClickListener {
            HudSoundManager.playTapSound()
            binding.tvTerminalOutput.text = ""
            appendLog("[TERMINAL BUFFER RESET]")
        }

        // Quick action chips
        binding.chipMacroWhatsapp.setOnClickListener {
            HudSoundManager.playTapSound()
            executeCommand("whatsapp")
        }

        binding.chipMacroRoot.setOnClickListener {
            HudSoundManager.playTapSound()
            executeCommand("root")
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
                appendLog("  • status       - Live battery, RAM, root, accessibility & AI provider")
                appendLog("  • scan         - Count interactive accessibility nodes on screen")
                appendLog("  • root         - Check whether an su binary is present")
                appendLog("  • whatsapp     - Open WhatsApp")
                appendLog("  • clear        - Wipe terminal log buffer")
            }
            lower == "models" || lower == "ai" -> {
                val mode = app.preferences.aiExecutionMode
                val provider = app.preferences.selectedAiProvider
                appendLog("=== JARVIS MULTI-MODEL AI STATUS ===")
                appendLog("Execution Mode : ${if (mode == MultiModelAiRouter.MODE_ALL_TOGETHER) "🌐 ALL MODELS TOGETHER (Ensemble)" else "🎯 SINGLE MODEL"}")
                appendLog("Active Provider: $provider")
                appendLog("Last used by router: ${activeProviderName()}")
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
            lower.contains("status") || lower.contains("dualbrain") -> {
                refreshTelemetry()
                printLiveStatus()
            }
            lower == "root" || lower.contains("su check") -> {
                lifecycleScope.launch {
                    val path = withContext(Dispatchers.IO) { findSuBinary() }
                    suBinaryPath = path
                    updateSecurityTag()
                    if (path.isEmpty()) {
                        appendLog("[ROOT] No su binary found — this device is not rooted (or root is hidden).")
                    } else {
                        appendLog("[ROOT] su binary found at $path. Jarvis has not been granted root by this check.")
                    }
                }
            }
            lower.contains("scan") -> {
                runAccessibilityScan()
            }
            lower.contains("whatsapp") -> {
                openWhatsApp()
            }
            else -> {
                appendLog("Unknown instruction '$cmd'. Type 'help' for tactical commands.")
            }
        }
    }

    private fun printLiveStatus() {
        val tele = DeviceTelemetryHelper.getTelemetry(this)
        val runtime = Runtime.getRuntime()
        val appHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val access = if (JarvisAccessibilityService.isEnabled()) "ON" else "OFF"
        appendLog("[STATUS] Battery: ${tele.batteryPercent}%${if (tele.isCharging) " (charging)" else ""}")
        appendLog("[STATUS] RAM: ${freeRamMb()} MB free · device ${tele.ramUsedGb}/${tele.ramTotalGb} GB used · Jarvis heap $appHeapMb MB")
        appendLog("[STATUS] Accessibility: $access · Root: ${rootLabel()}")
        appendLog("[STATUS] AI provider (last used): ${activeProviderName()}")
        appendLog("[STATUS] Network: ${if (tele.isOnline) tele.networkType else "OFFLINE"}")
    }

    /** Counts interactive nodes in the current foreground window via the accessibility service. */
    private fun runAccessibilityScan() {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            appendLog("[SCAN] Accessibility service is not running — enable Jarvis in Accessibility settings to scan.")
            return
        }
        appendLog("[SCAN] Reading the active window's accessibility tree...")
        lifecycleScope.launch {
            val result = MainThread.node {
                val root = service.getActiveRootNode() ?: return@node null
                val pkg = root.packageName?.toString().orEmpty()
                val nodes = UiTreeBuilder.build(root)?.flatten()?.toList().orEmpty()
                val interactive = nodes.count { n ->
                    n.enabled && (n.clickable || n.longClickable || n.editable || n.scrollable || n.checkable)
                }
                Triple(pkg, nodes.size, interactive)
            }
            if (result == null) {
                appendLog("[SCAN] No active window could be read right now.")
            } else {
                val (pkg, total, interactive) = result
                appendLog("[SCAN] Window: ${pkg.ifBlank { "unknown" }}")
                appendLog("[SCAN] $interactive interactive nodes out of $total total nodes.")
                if (pkg == packageName) {
                    appendLog("[SCAN] Note: the foreground window is Jarvis itself.")
                }
            }
        }
    }

    private fun openWhatsApp() {
        val intent = packageManager.getLaunchIntentForPackage("com.whatsapp")
            ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
        if (intent == null) {
            appendLog("❌ [WHATSAPP] WhatsApp is not installed on this device.")
            return
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            appendLog("[WHATSAPP] Opened WhatsApp.")
        } catch (e: Exception) {
            appendLog("❌ [WHATSAPP] Could not open WhatsApp: ${e.message}")
        }
    }

    private fun updateAgentStatuses() {
        val isAccess = JarvisAccessibilityService.isEnabled()
        binding.tvAccessAgentStatus.text = if (isAccess) "ON" else "OFF"
        binding.tvAccessAgentStatus.setTextColor(
            ThemeManager.attrColor(
                this,
                if (isAccess) R.attr.themeSuccess else R.attr.themeWarning,
                ContextCompat.getColor(this, if (isAccess) android.R.color.holo_green_light else android.R.color.holo_orange_light)
            )
        )

        binding.tvRouterAgentDetail.text = "Last provider used: ${activeProviderName()}"
    }

    private fun appendLog(msg: String) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timestamp = timeFormat.format(Date())
        val line = "[$timestamp] $msg\n"

        val spannable = SpannableString(line)
        val color = when {
            msg.contains("ERROR") || msg.contains("warn") || msg.contains("❌") -> ContextCompat.getColor(this, android.R.color.holo_red_light)
            msg.contains("SUCCESS") || msg.contains("ACTIVE") || msg.contains("ARMED") || msg.contains("✅") -> ContextCompat.getColor(this, android.R.color.holo_green_light)
            msg.contains("jarvis@mobile") || msg.contains("💡") -> ThemeManager.attrColor(this, R.attr.themeAccent, ContextCompat.getColor(this, android.R.color.holo_blue_light))
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
        const val SUBTAB_AGENTS = 2

        fun open(context: Context) {
            context.startActivity(Intent(context, BypassIdeActivity::class.java))
        }
    }
}
