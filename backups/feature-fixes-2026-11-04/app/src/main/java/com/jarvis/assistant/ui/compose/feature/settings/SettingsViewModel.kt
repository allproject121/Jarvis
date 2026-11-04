package com.jarvis.assistant.ui.compose.feature.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.local.LocalAiConfig
import com.jarvis.assistant.ai.local.LocalModelManager
import com.jarvis.assistant.ai.router.AiModelMode
import com.jarvis.assistant.ai.router.ModelRouter
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.androllm.accessibility.settings.AccessibilitySettingsStore
import com.jarvis.assistant.mcp.McpManager
import com.jarvis.assistant.voice.normalize.NormalizationSettingsStore
import com.jarvis.assistant.mcp.McpServer
import com.jarvis.assistant.ui.compose.model.ChatFontSize
import com.jarvis.assistant.ui.compose.model.ThemeMode
import com.jarvis.assistant.ui.compose.model.UiDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-lifetime scope for Jarvis singletons that need a scope on first
 * creation (LocalModelManager, ModelRouter). Never the ViewModel scope, so
 * leaving Settings can't cancel a model download.
 */
internal object SettingsSingletonScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/** One locally installable on-device model with its live install state. */
data class LocalModelRow(
    val id: String,
    val name: String,
    val sizeLabel: String,
    val ready: Boolean
)

/**
 * Everything the Compose settings screen renders. Values are read straight
 * from Jarvis' real preference store / singletons — nothing is fabricated.
 */
data class SettingsUiState(
    // Compose-only appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val uiDensity: UiDensity = UiDensity.DEFAULT,
    val fontSize: ChatFontSize = ChatFontSize.MEDIUM,
    val reduceMotion: Boolean = false,
    val blurEnabled: Boolean = true,
    // Identity
    val userName: String = "",
    val assistantName: String = "",
    val personality: String = "",
    val versionName: String = "",
    // AI engine
    val aiMode: AiModelMode = AiModelMode.AUTO_HYBRID,
    val activeProvider: String = "",
    val geminiModel: String = "",
    val geminiKeySet: Boolean = false,
    val canStoreKeySecurely: Boolean = true,
    val openAiKeySet: Boolean = false,
    val customEndpoint: String = "",
    val localHostModel: String = "",
    val localModels: List<LocalModelRow> = emptyList(),
    val activeLocalModelId: String = "",
    val recommendedThreads: Int = 0,
    val recommendedContext: Int = 0,
    // Storage
    val freeBytes: Long? = null,
    val modelBytes: Long? = null,
    val cacheBytes: Long? = null,
    // Memory
    val memoryEnabled: Boolean = true,
    val memoryCount: Int? = null,
    val memoryCategories: Int = 0,
    // Voice
    val voice: String = "",
    val language: String = "",
    val wakeSensitivity: Float = 0.65f,
    val hudSoundEnabled: Boolean = true,
    val hudSoundVolume: Float = 0.8f,
    val silentExecution: Boolean = false,
    val batterySaver: Boolean = false,
    val ownerVoiceEnabled: Boolean = false,
    val ownerVoiceEnrolled: Boolean = false,
    val taskOverlayEnabled: Boolean = true,
    val overlayGranted: Boolean = false,
    // Text normalization
    val normalizeNumbers: Boolean = true,
    val normalizeCurrency: Boolean = true,
    val sentenceAssembler: Boolean = true,
    // Automation
    val directAutonomous: Boolean = true,
    val autoReplyConfirm: Boolean = false,
    // UI automation
    val accessibilityRunning: Boolean = false,
    val autoScroll: Boolean = false,
    val screenCapture: Boolean = true,
    // MCP
    val mcpServers: List<McpServer> = emptyList(),
    val mcpBridgeEnabled: Boolean = true,
    // Safety / developer
    val privacyMode: Boolean = false,
    val developerLinks: Boolean = true
) {
    val downloadedModelCount: Int get() = localModels.count { it.ready }
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application get() = getApplication()
    private val prefs: AppPreferences get() = JarvisApp.instance.preferences
    private val mcpManager: McpManager = McpManager.getInstance(application)
    private val accessibilityStore = AccessibilitySettingsStore.getInstance(application)
    private val modelManager: LocalModelManager =
        LocalModelManager.getInstance(application, SettingsSingletonScope.scope)
    private val router: ModelRouter =
        ModelRouter.getInstance(application, SettingsSingletonScope.scope)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** Short-lived feedback line (cleared automatically after a few seconds). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Live tool count imported from every connected MCP server. */
    val mcpToolCount: StateFlow<Int> = mcpManager.availableToolsCount

    /** Live voice-session flags from the Jarvis conversation service. */
    val voiceSessionOn: StateFlow<Boolean> = JarvisConversationService.isSessionOn
    val voiceConnectionStatus: StateFlow<String> = JarvisConversationService.connectionStatus

    init {
        refresh()
    }

    /** Re-reads every value (prefs are cheap; disk stats run on IO). */
    fun refresh() {
        reloadPrefs()
        refreshDisk()
        refreshMemory()
        refreshAccessibility()
    }

    /** Auto-scroll lives in the accessibility DataStore, so it loads off the main thread. */
    private fun refreshAccessibility() {
        viewModelScope.launch {
            val autoScroll = runCatching { accessibilityStore.current().autoScrollIntoView }.getOrNull() ?: return@launch
            _state.update { it.copy(autoScroll = autoScroll) }
        }
    }

    private fun reloadPrefs() {
        val p = prefs
        val ctx = app
        NormalizationSettingsStore.load(ctx)
        val normalization = NormalizationSettingsStore.current()
        _state.update { s ->
            s.copy(
                themeMode = ComposeUiPrefs.themeMode(ctx),
                uiDensity = ComposeUiPrefs.density(ctx),
                fontSize = ComposeUiPrefs.fontSize(ctx),
                reduceMotion = ComposeUiPrefs.reduceMotion(ctx),
                blurEnabled = p.isBlurEffectEnabled,
                userName = p.userName,
                assistantName = p.assistantName,
                personality = p.personality,
                versionName = versionName(),
                aiMode = AiModelMode.fromString(p.aiModelMode),
                activeProvider = router.lastActiveProviderName.value,
                geminiModel = p.aiModel,
                geminiKeySet = runCatching { p.apiKey.isNotBlank() }.getOrDefault(false),
                canStoreKeySecurely = p.canStoreApiKeySecurely,
                openAiKeySet = p.openAiApiKey.isNotBlank(),
                customEndpoint = p.customEndpoint,
                localHostModel = p.localHostModel,
                activeLocalModelId = modelManager.activeModelId.value,
                memoryEnabled = p.isMem0MemoryEnabled,
                voice = p.voice,
                language = p.language,
                wakeSensitivity = p.voskWakeWordSensitivity,
                hudSoundEnabled = p.isHudSoundEnabled,
                hudSoundVolume = p.hudSoundVolume,
                silentExecution = p.isSilentExecutionMode,
                batterySaver = p.isBatterySaverEnabled,
                ownerVoiceEnabled = p.isOwnerVoiceEnabled,
                ownerVoiceEnrolled = p.hasOwnerVoiceProfile(),
                taskOverlayEnabled = p.isTaskOverlayEnabled,
                overlayGranted = runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false),
                normalizeNumbers = normalization.numbers,
                normalizeCurrency = normalization.currency,
                sentenceAssembler = p.isSentenceAssemblerEnabled,
                directAutonomous = p.isDirectAutonomousMode,
                autoReplyConfirm = p.isMessageAutoReplyConfirmEnabled,
                accessibilityRunning = runCatching { JarvisAccessibilityService.isRunning() }.getOrDefault(false),
                autoScroll = _state.value.autoScroll,
                screenCapture = p.isDefaultAssistantScreenCaptureEnabled,
                mcpServers = mcpManager.registeredServers().sortedBy { it.name.lowercase() },
                mcpBridgeEnabled = p.isMcpBridgeEnabled,
                privacyMode = p.isPrivacyModeEnabled,
                developerLinks = p.showDeveloperLinks
            )
        }
    }

    private fun refreshDisk() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val models = LocalAiConfig.AVAILABLE_MODELS.map { d ->
                    LocalModelRow(
                        id = d.id,
                        name = d.name,
                        sizeLabel = d.formattedSize,
                        ready = runCatching { modelManager.isModelReady(d.id) }.getOrDefault(false)
                    )
                }
                val modelBytes = LocalAiConfig.AVAILABLE_MODELS.sumOf { d ->
                    runCatching { modelManager.getModelFile(d.id).let { f -> if (f.exists()) f.length() else 0L } }
                        .getOrDefault(0L)
                }
                val free = runCatching {
                    val stat = StatFs(app.filesDir.path)
                    stat.availableBlocksLong * stat.blockSizeLong
                }.getOrNull()
                val cache = runCatching { dirSize(app.cacheDir) }.getOrNull()
                val threads = runCatching { com.jarvis.assistant.ai.local.JarvisThreadManager.recommendedThreads(app) }.getOrDefault(0)
                val ctxLen = runCatching { com.jarvis.assistant.ai.local.JarvisThreadManager.recommendedContextLength(app) }.getOrDefault(0)
                DiskSnapshot(models, modelBytes, free, cache, threads, ctxLen)
            }
            _state.update {
                it.copy(
                    localModels = result.models,
                    modelBytes = result.modelBytes,
                    freeBytes = result.freeBytes,
                    cacheBytes = result.cacheBytes,
                    recommendedThreads = result.threads,
                    recommendedContext = result.contextLength
                )
            }
        }
    }

    private data class DiskSnapshot(
        val models: List<LocalModelRow>,
        val modelBytes: Long,
        val freeBytes: Long?,
        val cacheBytes: Long?,
        val threads: Int,
        val contextLength: Int
    )

    private fun dirSize(dir: java.io.File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }

    private fun refreshMemory() {
        viewModelScope.launch {
            val memories = withContext(Dispatchers.IO) {
                runCatching { JarvisApp.instance.memoryRepository.getAllMemories() }.getOrNull()
            }
            _state.update {
                it.copy(
                    memoryCount = memories?.size,
                    memoryCategories = memories?.map { m -> m.category }?.distinct()?.size ?: 0
                )
            }
        }
    }

    private fun versionName(): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "—"
    }.getOrDefault("—")

    private fun say(text: String) {
        _message.value = text
        viewModelScope.launch {
            delay(3500)
            if (_message.value == text) _message.value = null
        }
    }

    private inline fun edit(block: AppPreferences.() -> Unit) {
        runCatching { prefs.block() }.onFailure { say("Could not save: ${it.message}") }
        reloadPrefs()
    }

    // ── Appearance (Compose-only prefs) ──────────────────────────────────────

    fun cycleTheme() {
        val next = when (_state.value.themeMode) {
            ThemeMode.SYSTEM -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.AMOLED
            ThemeMode.AMOLED -> ThemeMode.SYSTEM
        }
        ComposeUiPrefs.setThemeMode(app, next)
        reloadPrefs()
    }

    fun cycleFontSize() {
        val next = when (_state.value.fontSize) {
            ChatFontSize.SMALL -> ChatFontSize.MEDIUM
            ChatFontSize.MEDIUM -> ChatFontSize.LARGE
            ChatFontSize.LARGE -> ChatFontSize.SMALL
        }
        ComposeUiPrefs.setFontSize(app, next)
        reloadPrefs()
    }

    fun cycleDensity() {
        val next = when (_state.value.uiDensity) {
            UiDensity.COMPACT -> UiDensity.DEFAULT
            UiDensity.DEFAULT -> UiDensity.COMFORTABLE
            UiDensity.COMFORTABLE -> UiDensity.COMPACT
        }
        ComposeUiPrefs.setDensity(app, next)
        reloadPrefs()
    }

    fun setReduceMotion(enabled: Boolean) {
        ComposeUiPrefs.setReduceMotion(app, enabled)
        reloadPrefs()
    }

    fun setBlur(enabled: Boolean) = edit { isBlurEffectEnabled = enabled }

    // ── Identity ─────────────────────────────────────────────────────────────

    fun setUserName(name: String) {
        if (name.isBlank()) return
        edit { userName = name }
        say("Name saved")
    }

    fun setAssistantName(name: String) {
        if (name.isBlank()) return
        edit { assistantName = name }
        say("Assistant renamed to ${name.trim()}")
    }

    fun setPersonality(value: String) = edit { personality = value }

    // ── AI engine ────────────────────────────────────────────────────────────

    fun setAiMode(mode: AiModelMode) {
        router.setMode(mode)
        edit {
            aiModelMode = mode.name
            voiceEngineType = mode.name
        }
        say("Engine configured: ${mode.displayName}")
    }

    fun setGeminiModel(model: String) = edit { aiModel = model }

    fun setGeminiApiKey(key: String) {
        val p = prefs
        runCatching { p.apiKey = key }
            .onSuccess { say(if (key.isBlank()) "Gemini API key cleared" else "Gemini API key saved securely") }
            .onFailure { say("Could not save API key: ${it.message}") }
        reloadPrefs()
    }

    fun setOpenAiApiKey(key: String) {
        edit { openAiApiKey = key.trim() }
        say(if (key.isBlank()) "OpenAI API key cleared" else "OpenAI API key saved")
    }

    fun setCustomEndpoint(url: String) {
        edit { customEndpoint = url }
        say("Ollama endpoint saved")
    }

    fun setLocalHostModel(model: String) {
        edit { localHostModel = model }
        say("Ollama model saved")
    }

    fun selectLocalModel(id: String) {
        val row = _state.value.localModels.firstOrNull { it.id == id } ?: return
        if (!row.ready) {
            say("${row.name} isn't downloaded yet")
            return
        }
        modelManager.setActiveModel(id)
        reloadPrefs()
        say("On-device model set to ${row.name}")
    }

    fun downloadLocalModel(id: String) {
        modelManager.downloadModel(id)
        say("Download started — progress is shown in the notification")
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { app.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
            }
            refreshDisk()
            say("Cache cleared")
        }
    }

    fun refreshStorage() = refreshDisk()

    // ── Memory ───────────────────────────────────────────────────────────────

    fun setMemoryEnabled(enabled: Boolean) {
        edit { isMem0MemoryEnabled = enabled }
        say(if (enabled) "Memory enabled" else "Memory disabled")
    }

    fun refreshMemoryStats() = refreshMemory()

    fun deleteAllMemories() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { JarvisApp.instance.memoryRepository.clearAll() }.isSuccess
            }
            refreshMemory()
            say(if (ok) "All memories deleted" else "Could not delete memories")
        }
    }

    /** Shares every stored memory as plain text through the system share sheet. */
    fun exportMemories() {
        viewModelScope.launch {
            val memories = withContext(Dispatchers.IO) {
                runCatching { JarvisApp.instance.memoryRepository.getAllMemories() }.getOrNull()
            }
            if (memories.isNullOrEmpty()) {
                say("No memories to export")
                return@launch
            }
            val body = memories.joinToString("\n") { "[${it.category}] ${it.key}: ${it.value}" }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Jarvis Memory Export")
                putExtra(Intent.EXTRA_TEXT, body)
            }
            val chooser = Intent.createChooser(send, "Export memories").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(chooser) }
                .onSuccess { say("Export shared") }
                .onFailure { say("Export failed: ${it.message}") }
        }
    }

    // ── Voice ────────────────────────────────────────────────────────────────

    fun setVoice(value: String) = edit { voice = value }
    fun setLanguage(value: String) = edit { language = value }
    fun setWakeSensitivity(value: Float) = edit { voskWakeWordSensitivity = value }
    fun setHudSound(enabled: Boolean) = edit { isHudSoundEnabled = enabled }
    fun setHudVolume(value: Float) = edit { hudSoundVolume = value }
    fun setSilentExecution(enabled: Boolean) = edit { isSilentExecutionMode = enabled }
    fun setBatterySaver(enabled: Boolean) = edit { isBatterySaverEnabled = enabled }
    fun setOwnerVoice(enabled: Boolean) = edit { isOwnerVoiceEnabled = enabled }
    fun setTaskOverlay(enabled: Boolean) = edit { isTaskOverlayEnabled = enabled }

    fun startVoiceSession() = sendServiceAction(JarvisConversationService.ACTION_START_CONVERSATION)
    fun stopVoiceSession() = sendServiceAction(JarvisConversationService.ACTION_STOP_ALL)

    private fun sendServiceAction(action: String) {
        runCatching {
            val intent = Intent(app, JarvisConversationService::class.java).setAction(action)
            ContextCompat.startForegroundService(app, intent)
        }.onFailure { say("Voice service unavailable: ${it.message}") }
    }

    // ── Text normalization ───────────────────────────────────────────────────

    // TextNormalizationPipeline reads NormalizationSettingsStore, so the stage
    // toggles must write there; the AppPreferences mirror keeps this screen's
    // state in sync without a second source of truth for the pipeline.
    fun setNormalizeNumbers(enabled: Boolean) {
        NormalizationSettingsStore.update(app) { it.copy(numbers = enabled) }
        edit { isNormalizeNumbersEnabled = enabled }
    }

    fun setNormalizeCurrency(enabled: Boolean) {
        NormalizationSettingsStore.update(app) { it.copy(currency = enabled) }
        edit { isNormalizeCurrencyEnabled = enabled }
    }

    fun setSentenceAssembler(enabled: Boolean) = edit { isSentenceAssemblerEnabled = enabled }

    // ── Automation ───────────────────────────────────────────────────────────

    fun setDirectAutonomous(enabled: Boolean) = edit { isDirectAutonomousMode = enabled }
    fun setAutoReplyConfirm(enabled: Boolean) = edit { isMessageAutoReplyConfirmEnabled = enabled }
    /** AccessibilityController reads AccessibilitySettings.autoScrollIntoView. */
    fun setAutoScroll(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { accessibilityStore.update { it.copy(autoScrollIntoView = enabled) } }
                .onFailure { say("Could not save: ${it.message}") }
            _state.update { it.copy(autoScroll = enabled) }
        }
    }
    fun setScreenCapture(enabled: Boolean) = edit { isDefaultAssistantScreenCaptureEnabled = enabled }

    // ── MCP ──────────────────────────────────────────────────────────────────

    fun addMcpServer(name: String, url: String, token: String) {
        if (url.isBlank()) return
        val server = McpServer.fromName(name.ifBlank { "Custom Server" }, url, token)
        runCatching { mcpManager.registerServer(server) }
            .onSuccess { say("MCP server '${server.name}' saved. Connecting…") }
            .onFailure { say("Could not add server: ${it.message}") }
        reloadPrefs()
    }

    fun removeMcpServer(id: String) {
        runCatching { mcpManager.unregisterServer(id) }
        reloadPrefs()
    }

    fun setMcpServerEnabled(server: McpServer, enabled: Boolean) {
        runCatching {
            if (enabled) {
                mcpManager.registerServer(server.copy(enabled = true))
            } else {
                // Drop the live client + its imported tools, then persist disabled.
                mcpManager.unregisterServer(server.id)
                mcpManager.registerServer(server.copy(enabled = false))
            }
        }
        reloadPrefs()
    }

    fun setMcpBridge(enabled: Boolean) = edit { isMcpBridgeEnabled = enabled }

    // ── Safety / developer ───────────────────────────────────────────────────

    fun setPrivacyMode(enabled: Boolean) = edit { isPrivacyModeEnabled = enabled }
    fun setDeveloperLinks(enabled: Boolean) = edit { showDeveloperLinks = enabled }

    fun overlaySettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${app.packageName}"))

    companion object {
        val voices: List<String> get() = GeminiConstants.SUPPORTED_VOICES
        val languages: List<String> get() = GeminiConstants.SUPPORTED_LANGUAGES
        val geminiModels: List<String> get() = GeminiConstants.SUPPORTED_MODELS
        val personalities: List<String> get() = GeminiConstants.SUPPORTED_PERSONALITIES
    }
}
