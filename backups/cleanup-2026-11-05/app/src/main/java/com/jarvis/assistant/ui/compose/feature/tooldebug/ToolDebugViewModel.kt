package com.jarvis.assistant.ui.compose.feature.tooldebug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.androllm.agent.AgentFlags
import com.jarvis.assistant.androllm.agent.AgentToolGraph
import com.jarvis.assistant.androllm.agent.bridge.JarvisDispatcherTool
import com.jarvis.assistant.androllm.agent.planner.LocalToolCapability
import com.jarvis.assistant.androllm.agent.settings.AutomationSettings
import com.jarvis.assistant.androllm.agent.trace.ToolExecutionTrace
import com.jarvis.assistant.androllm.engine.AndroLlmEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One registered tool, rendered on the Tool Debug diagnostics card. */
data class RegisteredToolInfo(
    val name: String,
    val description: String,
    val category: String,
    val enabled: Boolean,
    val requiresConfirmation: Boolean,
    /** "Jarvis" for dispatcher adapters, "AndroLLM" for native registry tools. */
    val source: String,
    /** JSON Schema for the tool's arguments (the function schema). */
    val parametersJson: String
)

/**
 * Snapshot of the tool-calling system shown at the top of the Tool Debug
 * screen: registry status, prompt-injection status, and function schemas.
 */
data class ToolDiagnostics(
    /** Master switch state (Settings → Agent tools). */
    val pipelineEnabled: Boolean = false,
    /** AGENT_PIPELINE flag: whether non-Gemini providers use this pipeline. */
    val agentPipelineFlag: Boolean = false,
    /** Tools present in the registry. */
    val registeredCount: Int = 0,
    val tools: List<RegisteredToolInfo> = emptyList(),
    /** How many of those tools are advertised into the chat system prompt. */
    val advertisedCount: Int = 0,
    /** Preview of the injected AVAILABLE TOOLS block. */
    val advertisementPreview: String = "",
    /** Live assessment of the loaded model's tool-call capability. */
    val capability: LocalToolCapability = LocalToolCapability()
)

/**
 * Tool Debug ViewModel (ported from AndroLLM feature/developer) — mirrors
 * the shared, bounded ToolExecutionTraceStore so every tool call of the
 * agent pipeline (prompt → tool → args → status → result → error → timing →
 * LLM output) is visible in one place, plus live registry and
 * prompt-injection diagnostics.
 */
class ToolDebugViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = AgentToolGraph.getInstance(app)

    val traces: StateFlow<List<ToolExecutionTrace>> = graph.traceStore.traces

    private val _diagnostics = MutableStateFlow(ToolDiagnostics())
    val diagnostics: StateFlow<ToolDiagnostics> = _diagnostics.asStateFlow()

    init {
        viewModelScope.launch {
            graph.settingsStore.settings.collect { refreshDiagnostics(it) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshDiagnostics(graph.settingsStore.current())
        }
    }

    private suspend fun refreshDiagnostics(settings: AutomationSettings) {
        val app = getApplication<Application>()
        withContext(Dispatchers.IO) { runCatching { graph.refreshTools() } }
        val tools = graph.registry.all().sortedBy { it.spec.name }
        val advertisement = runCatching { graph.promptBuilder.advertisement() }.getOrNull()
        // Live stats from the LiteRT planner (constructing the planner only
        // creates the engine graph objects — it never loads a model).
        val capability = graph.planner.capability.value
        _diagnostics.value = ToolDiagnostics(
            pipelineEnabled = settings.toolCallingEnabled,
            agentPipelineFlag = AgentFlags.isPipelineEnabled(app),
            registeredCount = tools.size,
            tools = tools.map { tool ->
                RegisteredToolInfo(
                    name = tool.spec.name,
                    description = tool.spec.description,
                    category = tool.spec.category.displayName,
                    enabled = settings.isToolEnabled(tool.spec.name),
                    requiresConfirmation = tool.spec.requiresConfirmation,
                    source = if (tool is JarvisDispatcherTool) "Jarvis" else "AndroLLM",
                    parametersJson = tool.spec.parameters.toString()
                )
            },
            advertisedCount = if (settings.toolCallingEnabled) {
                tools.count { settings.isToolEnabled(it.spec.name) }
            } else 0,
            advertisementPreview = advertisement?.take(600) ?: "",
            capability = capability
        )
    }

    /** Forces a live capability probe against the loaded LiteRT-LM model. */
    fun probeCapability() {
        viewModelScope.launch {
            val probe = if (AndroLlmEngines.peek()?.isModelLoaded() == true) {
                graph.planner.probeCapability()
            } else {
                LocalToolCapability(probeStatus = "no LiteRT-LM model loaded")
            }
            _diagnostics.value = _diagnostics.value.copy(capability = probe)
        }
    }

    fun clear() = graph.traceStore.clear()
}
