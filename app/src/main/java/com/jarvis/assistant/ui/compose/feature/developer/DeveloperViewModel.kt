package com.jarvis.assistant.ui.compose.feature.developer

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Debug
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.diagnostics.EngineCrashGuard
import com.jarvis.assistant.ai.local.JarvisThreadManager
import com.jarvis.assistant.ai.local.LocalAiConfig
import com.jarvis.assistant.ai.local.LocalAiEngine
import com.jarvis.assistant.ai.local.LocalEngineState
import com.jarvis.assistant.ai.local.LocalModelManager
import com.jarvis.assistant.ai.router.AiModelMode
import com.jarvis.assistant.ai.router.ModelRouter
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.mcp.McpManager
import com.jarvis.assistant.telemetry.DeviceHardwareInfo
import com.jarvis.assistant.telemetry.DeviceInfoCollector
import com.jarvis.assistant.telemetry.DeviceMetrics
import com.jarvis.assistant.telemetry.GenerationStat
import com.jarvis.assistant.telemetry.TelemetryRepository
import com.jarvis.assistant.telemetry.TelemetrySample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jarvis.assistant.ai.provider.LocalPhoneProvider
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.agent.AgentToolGraph
import com.jarvis.assistant.androllm.agent.trace.ToolExecutionTrace
import com.jarvis.assistant.androllm.diagnostics.GeminiSelfTest

/** App-lifetime scope for singletons that need one on first creation. */
private object DeveloperSingletonScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/** System RAM + native-heap snapshot (same budgets AndroLLM showed). */
data class SystemMemoryInfo(
    val totalRamBytes: Long = 0L,
    val availableRamBytes: Long = 0L,
    val nativeHeapAllocatedBytes: Long = 0L,
    val nativeHeapSizeBytes: Long = 0L,
    val lowMemory: Boolean = false
) {
    fun totalRamMb(): Long = totalRamBytes / (1024L * 1024L)
    fun availableRamMb(): Long = availableRamBytes / (1024L * 1024L)
    fun nativeHeapAllocatedMb(): Long = nativeHeapAllocatedBytes / (1024L * 1024L)
    fun nativeHeapSizeMb(): Long = nativeHeapSizeBytes / (1024L * 1024L)
}

/** One stored Jarvis memory, trimmed for the context preview. */
data class MemoryPreview(
    val category: String,
    val text: String
)

/** Snapshot of Jarvis' long-term memory store (Room). */
data class MemoryInspectorSnapshot(
    val enabled: Boolean,
    val memoryCount: Int,
    val categoryCount: Int,
    val categories: List<Pair<String, Int>>,
    val recent: List<MemoryPreview>
)

/** A Jarvis subsystem and its live availability (replaces AndroLLM's Runtime Registry). */
data class RuntimeRow(
    val name: String,
    val category: String,
    val available: Boolean,
    val summary: String,
    val detail: String? = null
)

/**
 * Developer dashboard ViewModel — every value comes from real Jarvis state:
 * [TelemetryRepository] (1 Hz samples + per-generation stats), the local
 * llama.cpp engine, the model router, MCP manager and [EngineCrashGuard].
 */
class DeveloperViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application get() = getApplication()

    val telemetry: TelemetryRepository = TelemetryRepository.getInstance(application)
    private val localEngine: LocalAiEngine = LocalAiEngine.getInstance(application, DeveloperSingletonScope.scope)
    private val modelManager: LocalModelManager = LocalModelManager.getInstance(application, DeveloperSingletonScope.scope)
    private val router: ModelRouter = ModelRouter.getInstance(application, DeveloperSingletonScope.scope)

    val history: StateFlow<List<TelemetrySample>> = telemetry.history
    val generations: StateFlow<List<GenerationStat>> = telemetry.generationHistory
    val deviceMetrics: StateFlow<DeviceMetrics?> = telemetry.deviceMetrics
    val isSampling: StateFlow<Boolean> = telemetry.isSampling
    val engineState: StateFlow<LocalEngineState> = localEngine.engineState
    val activeModelId: StateFlow<String> = modelManager.activeModelId
    val aiMode: StateFlow<AiModelMode> = router.currentMode
    val lastProvider: StateFlow<String> = router.lastActiveProviderName
    val fallbackActive: StateFlow<Boolean> = router.isOfflineFallbackActive

    /** Every tool call Jarvis made (JarvisToolDispatcher records them all). */
    val toolTraces: StateFlow<List<ToolExecutionTrace>> = AgentToolGraph.getInstance(application).traceStore.traces

    /**
     * The offline brain loads its model through LocalPhoneProvider directly (ModelRouter), not
     * through LocalAiEngine — so "model loaded" must come from the provider, or the page said
     * "No model loaded" while Qwen was answering.
     */
    private val phoneProvider: LocalPhoneProvider = LocalPhoneProvider.getInstance(application, DeveloperSingletonScope.scope)
    private val _phoneModelReady = MutableStateFlow(false)
    val phoneModelReady: StateFlow<Boolean> = _phoneModelReady.asStateFlow()

    /** LiteRT-LM engine switched on in Settings → Engines (otherwise its card is hidden). */
    fun liteRtEnabled(): Boolean = runCatching { AndroLlmFlags.isEnabled(app, AndroLlmFlags.LITERT_ENGINE) }.getOrDefault(false)

    // ── Gemini check (real requests with the saved key / model) ──
    private val _geminiText = MutableStateFlow<GeminiSelfTest.TextResult?>(null)
    val geminiText: StateFlow<GeminiSelfTest.TextResult?> = _geminiText.asStateFlow()
    private val _geminiLive = MutableStateFlow<GeminiSelfTest.LiveResult?>(null)
    val geminiLive: StateFlow<GeminiSelfTest.LiveResult?> = _geminiLive.asStateFlow()
    private val _geminiBusy = MutableStateFlow<String?>(null)
    val geminiBusy: StateFlow<String?> = _geminiBusy.asStateFlow()

    fun testGeminiText() {
        if (_geminiBusy.value != null) return
        _geminiBusy.value = "Asking Gemini (text)…"
        viewModelScope.launch {
            _geminiText.value = runCatching {
                GeminiSelfTest.runText(app, "Reply with exactly: Jarvis online.")
            }.getOrNull()
            _geminiBusy.value = null
            refreshRuntimes()
        }
    }

    fun testGeminiLive() {
        if (_geminiBusy.value != null) return
        _geminiBusy.value = "Opening a Gemini Live session…"
        viewModelScope.launch {
            _geminiLive.value = runCatching {
                GeminiSelfTest.runLive(app, "Say: Jarvis online.")
            }.getOrNull()
            _geminiBusy.value = null
            refreshRuntimes()
        }
    }

    private val _systemMemory = MutableStateFlow<SystemMemoryInfo?>(null)
    val systemMemory: StateFlow<SystemMemoryInfo?> = _systemMemory.asStateFlow()

    private val _hardware = MutableStateFlow<DeviceHardwareInfo?>(null)
    val hardware: StateFlow<DeviceHardwareInfo?> = _hardware.asStateFlow()

    private val _memory = MutableStateFlow<MemoryInspectorSnapshot?>(null)
    val memory: StateFlow<MemoryInspectorSnapshot?> = _memory.asStateFlow()

    private val _runtimes = MutableStateFlow<List<RuntimeRow>>(emptyList())
    val runtimes: StateFlow<List<RuntimeRow>> = _runtimes.asStateFlow()

    private val _crashes = MutableStateFlow<List<EngineCrashGuard.CrashEvent>>(emptyList())
    val crashes: StateFlow<List<EngineCrashGuard.CrashEvent>> = _crashes.asStateFlow()

    private val _crashTotal = MutableStateFlow(0)
    val crashTotal: StateFlow<Int> = _crashTotal.asStateFlow()

    private val _contextLength = MutableStateFlow(0)
    val contextLength: StateFlow<Int> = _contextLength.asStateFlow()

    private val _threads = MutableStateFlow(0)
    val threads: StateFlow<Int> = _threads.asStateFlow()

    // ── AndroLLM LiteRT-LM engine diagnostics (optional secondary engine) ──

    /** Engine graph is cheap to construct; no model loads until the user asks. */
    val liteRt: com.jarvis.assistant.androllm.engine.AndroLlmEngines =
        com.jarvis.assistant.androllm.engine.AndroLlmEngines.getInstance(application)

    val liteRtEngineState: StateFlow<com.jarvis.assistant.androllm.engine.api.EngineState> = liteRt.repository.engineState
    val liteRtStats: StateFlow<com.jarvis.assistant.androllm.engine.models.EngineStats?> = liteRt.repository.performanceStats
    val liteRtMemory: StateFlow<com.jarvis.assistant.androllm.engine.models.MemoryStats?> = liteRt.repository.memoryStats

    private val _liteRtCaps = MutableStateFlow<com.jarvis.assistant.androllm.engine.backend.BackendCapabilities?>(null)
    val liteRtCaps: StateFlow<com.jarvis.assistant.androllm.engine.backend.BackendCapabilities?> = _liteRtCaps.asStateFlow()

    private val _liteRtModels = MutableStateFlow<List<com.jarvis.assistant.androllm.engine.LocalLiteRtModel>>(emptyList())
    val liteRtModels: StateFlow<List<com.jarvis.assistant.androllm.engine.LocalLiteRtModel>> = _liteRtModels.asStateFlow()

    private val _liteRtBenchmarks = MutableStateFlow<List<com.jarvis.assistant.androllm.engine.models.BackendBenchmarkResult>>(emptyList())
    val liteRtBenchmarks: StateFlow<List<com.jarvis.assistant.androllm.engine.models.BackendBenchmarkResult>> = _liteRtBenchmarks.asStateFlow()

    private val _liteRtBusy = MutableStateFlow<String?>(null)
    /** Non-null while a LiteRT action runs (label of the action). */
    val liteRtBusy: StateFlow<String?> = _liteRtBusy.asStateFlow()

    private val _liteRtMessage = MutableStateFlow<String?>(null)
    val liteRtMessage: StateFlow<String?> = _liteRtMessage.asStateFlow()

    fun liteRtCrashes(): List<EngineCrashGuard.CrashEvent> =
        runCatching { EngineCrashGuard.getRecentEvents() }.getOrDefault(emptyList())

    fun refreshLiteRt() {
        viewModelScope.launch {
            _liteRtModels.value = withContext(Dispatchers.IO) { runCatching { liteRt.listLocalModels() }.getOrDefault(emptyList()) }
            val repoCaps = liteRt.repository.backendCapabilities.value
            _liteRtCaps.value = if (repoCaps.probedAtMs > 0L) repoCaps else liteRt.probeHardware()
        }
    }

    private fun runLiteRtAction(label: String, block: suspend () -> String) {
        if (_liteRtBusy.value != null) return
        viewModelScope.launch {
            _liteRtBusy.value = label
            _liteRtMessage.value = null
            val message = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                "$label failed: ${e.message}"
            }
            _liteRtMessage.value = message
            _liteRtBusy.value = null
            refreshLiteRt()
        }
    }

    fun loadLiteRtModel() = runLiteRtAction("Load model") {
        when (val r = liteRt.loadSelected()) {
            is com.jarvis.assistant.androllm.engine.support.Result.Success ->
                "Loaded ${liteRt.engine.getLoadedModel()?.id ?: "model"} on ${liteRt.engine.getLoadedModel()?.backend ?: "-"}"
            is com.jarvis.assistant.androllm.engine.support.Result.Error -> "Load failed: ${r.exception.message}"
        }
    }

    fun unloadLiteRtModel() = runLiteRtAction("Unload") {
        liteRt.unload()
        "LiteRT-LM model unloaded"
    }

    fun benchmarkLiteRtBackends() = runLiteRtAction("Benchmark") {
        if (!liteRt.isModelLoaded()) {
            val load = liteRt.loadSelected()
            if (load is com.jarvis.assistant.androllm.engine.support.Result.Error) {
                return@runLiteRtAction "Benchmark needs a loaded model: ${load.exception.message}"
            }
        }
        when (val r = liteRt.repository.benchmarkBackends()) {
            is com.jarvis.assistant.androllm.engine.support.Result.Success -> {
                _liteRtBenchmarks.value = r.data
                if (r.data.isEmpty()) "No backend could be benchmarked" else "Benchmarked ${r.data.size} backend(s)"
            }
            is com.jarvis.assistant.androllm.engine.support.Result.Error -> "Benchmark failed: ${r.exception.message}"
        }
    }

    init {
        refresh()
    }


    /** Display name for the active on-device model (descriptor lookup). */
    fun modelName(id: String): String =
        LocalAiConfig.getModelById(id)?.name ?: id

    fun refresh() {
        telemetry.refreshDeviceMetrics()
        refreshLiteRt()
        refreshSystemMemory()
        refreshHardware()
        refreshMemoryInspector()
        refreshRuntimes()
        refreshCrashes()
    }

    fun refreshSystemMemory() {
        _systemMemory.value = runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            SystemMemoryInfo(
                totalRamBytes = memInfo.totalMem.takeIf { it > 0 } ?: 0L,
                availableRamBytes = memInfo.availMem.takeIf { it > 0 } ?: 0L,
                nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
                nativeHeapSizeBytes = Debug.getNativeHeapSize(),
                lowMemory = memInfo.lowMemory
            )
        }.getOrNull()
    }

    private fun refreshHardware() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { DeviceInfoCollector.collectDeviceInfo(app) }.getOrNull()
            }
            _hardware.value = info
            _contextLength.value = runCatching { JarvisThreadManager.recommendedContextLength(app) }.getOrDefault(0)
            _threads.value = runCatching { JarvisThreadManager.recommendedThreads(app) }.getOrDefault(0)
        }
    }

    fun refreshMemoryInspector() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                runCatching {
                    val all = JarvisApp.instance.memoryRepository.getAllMemories()
                    val byCategory = all.groupBy { it.category }
                        .map { (cat, list) -> cat to list.size }
                        .sortedByDescending { it.second }
                    MemoryInspectorSnapshot(
                        enabled = JarvisApp.instance.preferences.isMem0MemoryEnabled,
                        memoryCount = all.size,
                        categoryCount = byCategory.size,
                        categories = byCategory,
                        recent = all.sortedByDescending { it.timestamp }
                            .take(6)
                            .map { MemoryPreview(category = it.category, text = "${it.key}: ${it.value}") }
                    )
                }.getOrNull()
            }
            _memory.value = snapshot
        }
    }

    fun refreshRuntimes() {
        val rows = mutableListOf<RuntimeRow>()

        val phoneReady = runCatching { phoneProvider.isReady() }.getOrDefault(false)
        _phoneModelReady.value = phoneReady
        val state = engineState.value
        val engineUp = phoneReady || state is LocalEngineState.Ready || state is LocalEngineState.Generating
        rows += RuntimeRow(
            name = "Offline brain (on-device model)",
            category = "Inference",
            available = engineUp,
            summary = (if (phoneReady) "Model loaded" else engineStateLabel(state)) + " • " + modelName(activeModelId.value),
            detail = (state as? LocalEngineState.Error)?.message
        )

        val prefs = JarvisApp.instance.preferences
        val live = geminiLive.value
        val liveStatus = JarvisConversationService.connectionStatus.value
        rows += RuntimeRow(
            name = "Gemini Live (voice)",
            category = "Voice",
            available = live?.ok ?: (liveStatus.contains("LIVE", ignoreCase = true) || liveStatus.contains("ONLINE", ignoreCase = true)),
            summary = live?.summary() ?: "Voice session status: $liveStatus (run the Live check to really test it)",
            detail = "Model ${prefs.aiModel} • voice ${prefs.voice}" +
                if (prefs.apiKey.isBlank()) " • NO API KEY" else ""
        )
        val text = geminiText.value
        rows += RuntimeRow(
            name = "Gemini text (chat)",
            category = "Routing",
            available = text?.ok ?: false,
            summary = text?.summary() ?: "Not tested yet - tap \"Test text\" in Gemini check",
            detail = text?.steps?.joinToString("\n") { it.line() }
        )

        val mode = aiMode.value
        rows += RuntimeRow(
            name = "Model router",
            category = "Routing",
            available = true,
            summary = "Mode: ${mode.displayName}",
            detail = "Last provider: ${lastProvider.value}" +
                if (fallbackActive.value) " • offline fallback active" else ""
        )

        rows += RuntimeRow(
            name = "Conversation service",
            category = "Voice",
            available = JarvisConversationService.isSessionOn.value,
            summary = if (JarvisConversationService.isSessionOn.value) "Session on" else "Idle",
            detail = "Status: ${JarvisConversationService.connectionStatus.value}"
        )

        val accessibility = runCatching { JarvisAccessibilityService.isRunning() }.getOrDefault(false)
        rows += RuntimeRow(
            name = "Accessibility service",
            category = "Automation",
            available = accessibility,
            summary = if (accessibility) "Connected" else "Not enabled in system settings"
        )

        runCatching {
            val mcp = McpManager.getInstance(app)
            val servers = mcp.registeredServers()
            val tools = mcp.availableToolsCount.value
            rows += RuntimeRow(
                name = "MCP manager",
                category = "Tools",
                available = tools > 0,
                summary = "${servers.size} server(s) • $tools tool(s) imported",
                detail = servers.joinToString(", ") { it.name }.ifBlank { null }
            )
        }

        rows += RuntimeRow(
            name = "Telemetry sampler",
            category = "Diagnostics",
            available = isSampling.value,
            summary = if (isSampling.value) "Sampling every 1 s" else "Stopped",
            detail = "${history.value.size} samples • ${generations.value.size} generations"
        )

        val lrState = liteRtEngineState.value
        val lrModel = liteRt.engine.getLoadedModel()
        rows += RuntimeRow(
            name = "LiteRT-LM engine (AndroLLM)",
            category = "Inference",
            available = lrModel != null,
            summary = liteRtStateLabel(lrState) + (lrModel?.let { " • ${it.id} on ${it.backend.name}" } ?: ""),
            detail = "Feature flag: ${if (liteRt.isFeatureEnabled()) "on" else "off"} • backend pref: ${liteRt.backendPreference.name}" +
                ((lrState as? com.jarvis.assistant.androllm.engine.api.EngineState.Failed)?.let { " • ${it.message}" } ?: "")
        )

        runCatching {
            val gateway = com.jarvis.assistant.androllm.cloud.AndroLlmCloud.getInstance(app)
            val enabled = com.jarvis.assistant.androllm.AndroLlmFlags.isEnabled(app, com.jarvis.assistant.androllm.AndroLlmFlags.CLOUD_GATEWAY)
            val snap = gateway.usageMeter.snapshots.value
            rows += RuntimeRow(
                name = "Cloud gateway (AndroLLM)",
                category = "Routing",
                available = enabled,
                summary = if (enabled) "Enabled" else "Off (opt-in)",
                detail = "${snap.total.requests} request(s) metered (snapshot)"
            )
        }

        _runtimes.value = rows
    }

    fun refreshCrashes() {
        _crashes.value = runCatching { EngineCrashGuard.recentCrashes() }.getOrDefault(emptyList())
            .sortedByDescending { it.timestamp }
        _crashTotal.value = runCatching { EngineCrashGuard.totalGuardedFailures() }.getOrDefault(0)
    }

    companion object {
        fun liteRtStateLabel(state: com.jarvis.assistant.androllm.engine.api.EngineState): String = when (state) {
            is com.jarvis.assistant.androllm.engine.api.EngineState.Unloaded -> "Unloaded"
            is com.jarvis.assistant.androllm.engine.api.EngineState.Loading -> "Loading: ${state.stage}"
            is com.jarvis.assistant.androllm.engine.api.EngineState.WarmingUp -> "Warming up: ${state.step}"
            is com.jarvis.assistant.androllm.engine.api.EngineState.Ready -> "Ready"
            is com.jarvis.assistant.androllm.engine.api.EngineState.Generating -> "Generating"
            is com.jarvis.assistant.androllm.engine.api.EngineState.Unloading -> "Unloading"
            is com.jarvis.assistant.androllm.engine.api.EngineState.Failed -> "Failed (${state.stage})"
        }

        fun engineStateLabel(state: LocalEngineState): String = when (state) {
            is LocalEngineState.Uninitialized -> "Idle"
            is LocalEngineState.Loading -> "Loading…"
            is LocalEngineState.Ready -> "Ready"
            is LocalEngineState.Generating -> "Generating"
            is LocalEngineState.Error -> "Error"
        }
    }
}
