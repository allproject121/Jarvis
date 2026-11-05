package com.jarvis.assistant.ui.compose.feature.tooldebug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.ai.provider.AiToolCall
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.agent.AgentFlags
import com.jarvis.assistant.androllm.agent.AgentToolGraph
import com.jarvis.assistant.androllm.agent.bridge.JarvisDispatcherTool
import com.jarvis.assistant.androllm.agent.planner.LocalToolCapability
import com.jarvis.assistant.androllm.agent.settings.AutomationSettings
import com.jarvis.assistant.androllm.agent.trace.ToolExecutionTrace
import com.jarvis.assistant.androllm.engine.AndroLlmEngines
import com.jarvis.assistant.automation.dispatcher.JarvisToolDispatcher
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One registered tool, rendered on the Tool Debug diagnostics card. */
data class RegisteredToolInfo(
    val name: String,
    val description: String,
    val category: String,
    val enabled: Boolean,
    val requiresConfirmation: Boolean,
    /** "Jarvis" for dispatcher tools, "Extra" for agent-pipeline-only tools. */
    val source: String,
    /** JSON Schema for the tool's arguments (the function schema). */
    val parametersJson: String
)

/**
 * Snapshot of Jarvis' real tool system shown at the top of Tool Debug: what Gemini Live and
 * the offline brain are actually offered right now (JarvisToolDispatcher), and the registry.
 */
data class ToolDiagnostics(
    /** Settings → Agent & Tools → Tool calling (applies to every engine). */
    val pipelineEnabled: Boolean = false,
    /** AGENT_PIPELINE flag: extra engines (LiteRT / Hermes / Cloud Gateway) use the planner. */
    val agentPipelineFlag: Boolean = false,
    /** LiteRT-LM engine switched on (Settings → Engines) — only then is the capability probe shown. */
    val liteRtEnabled: Boolean = false,
    /** Tools present in the registry. */
    val registeredCount: Int = 0,
    val tools: List<RegisteredToolInfo> = emptyList(),
    /** Dispatcher tools offered to Gemini Live / chat right now (after the user's switches). */
    val advertisedCount: Int = 0,
    /** The short tool list the offline brain (Qwen on the phone) sees. */
    val offlineToolNames: List<String> = emptyList(),
    /** Preview of the agent pipeline's AVAILABLE TOOLS block (only with the pipeline on). */
    val advertisementPreview: String = "",
    /** Live assessment of the LiteRT-LM model's tool-call capability. */
    val capability: LocalToolCapability = LocalToolCapability()
)

/** State of the "Run a tool" card. */
data class ToolRunState(
    val running: Boolean = false,
    val toolName: String = "",
    val success: Boolean? = null,
    val durationMs: Long = 0,
    val output: String = ""
)

/**
 * Tool Debug ViewModel. Every row of the log is a REAL call: JarvisToolDispatcher records each
 * call it gets (Gemini Live, offline brain, chat, this page's Run button, adb) into the shared
 * trace store, and the agent pipeline's executor records its own.
 */
class ToolDebugViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = AgentToolGraph.getInstance(app)
    private val dispatcher = JarvisToolDispatcher.getInstance(app)

    val traces: StateFlow<List<ToolExecutionTrace>> = graph.traceStore.traces

    private val _diagnostics = MutableStateFlow(ToolDiagnostics())
    val diagnostics: StateFlow<ToolDiagnostics> = _diagnostics.asStateFlow()

    /** Every tool name the dispatcher can run (built-ins, skills, MCP, all Maya pack tools). */
    private val _runnableTools = MutableStateFlow<List<String>>(emptyList())
    val runnableTools: StateFlow<List<String>> = _runnableTools.asStateFlow()

    private val _run = MutableStateFlow(ToolRunState())
    val run: StateFlow<ToolRunState> = _run.asStateFlow()

    private var schemasByName: Map<String, JSONObject> = emptyMap()

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
        val agentFlag = AgentFlags.isPipelineEnabled(app)
        val liteRt = runCatching { AndroLlmFlags.isEnabled(app, AndroLlmFlags.LITERT_ENGINE) }.getOrDefault(false)
        val snapshot = withContext(Dispatchers.IO) {
            runCatching { graph.refreshTools() }
            val all = runCatching { dispatcher.getAvailableToolSchemas(includeDisabled = true) }.getOrDefault(emptyList())
            val offered = runCatching { dispatcher.getAvailableToolSchemas(includeDisabled = false) }.getOrDefault(emptyList())
            val offline = runCatching { dispatcher.getOfflineToolSchemas() }.getOrDefault(emptyList())
            runCatching { MayaToolRegistry.ensureInstalled(app) }
            val maya = runCatching { MayaToolRegistry.all().associate { it.name to it.schema() } }.getOrDefault(emptyMap())
            Triple(all, offered, offline) to maya
        }
        val (lists, maya) = snapshot
        val (all, offered, offline) = lists
        val byName = LinkedHashMap<String, JSONObject>()
        all.forEach { schema -> nameOf(schema)?.let { byName[it] = schema } }
        maya.forEach { (name, schema) -> byName.putIfAbsent(name, schema) }
        schemasByName = byName
        _runnableTools.value = byName.keys.sorted()

        val tools = graph.registry.all().sortedBy { it.spec.name }
        val advertisement = if (agentFlag) runCatching { graph.promptBuilder.advertisement() }.getOrNull() else null
        _diagnostics.value = ToolDiagnostics(
            pipelineEnabled = settings.toolCallingEnabled,
            agentPipelineFlag = agentFlag,
            liteRtEnabled = liteRt,
            registeredCount = tools.size,
            tools = tools.map { tool ->
                RegisteredToolInfo(
                    name = tool.spec.name,
                    description = tool.spec.description,
                    category = tool.spec.category.displayName,
                    enabled = settings.isToolEnabled(tool.spec.name),
                    requiresConfirmation = tool.spec.requiresConfirmation,
                    source = if (tool is JarvisDispatcherTool) "Jarvis" else "Extra",
                    parametersJson = tool.spec.parameters.toString()
                )
            },
            advertisedCount = offered.size,
            offlineToolNames = offline.mapNotNull { nameOf(it) },
            advertisementPreview = advertisement?.take(600) ?: "",
            capability = if (liteRt) graph.planner.capability.value else LocalToolCapability()
        )
    }

    private fun nameOf(schema: JSONObject): String? =
        (schema.optJSONObject("function") ?: schema).optString("name").takeIf { it.isNotBlank() }

    /**
     * A JSON template of [name]'s arguments (required ones first) so the user only fills values.
     */
    fun argsTemplate(name: String): String {
        val schema = schemasByName[name] ?: return "{}"
        val params = (schema.optJSONObject("function") ?: schema).optJSONObject("parameters") ?: return "{}"
        val props = params.optJSONObject("properties") ?: return "{}"
        val required = params.optJSONArray("required")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
            ?: emptyList()
        val keys = props.keys().asSequence().toList().sortedBy { if (it in required) 0 else 1 }
        if (keys.isEmpty()) return "{}"
        val out = JSONObject()
        keys.filter { it in required }.ifEmpty { keys.take(2) }.forEach { k ->
            val type = props.optJSONObject(k)?.optString("type").orEmpty().lowercase()
            out.put(
                k, when (type) {
                    "integer", "number" -> 0
                    "boolean" -> false
                    "object" -> JSONObject()
                    else -> ""
                }
            )
        }
        return out.toString(2)
    }

    /** Runs [name] through Jarvis' real dispatcher (same path Gemini Live / offline brain use). */
    fun runTool(name: String, argsText: String) {
        val tool = name.trim()
        if (tool.isEmpty() || _run.value.running) return
        val args = runCatching { JSONObject(argsText.ifBlank { "{}" }) }.getOrElse {
            _run.value = ToolRunState(toolName = tool, success = false, output = "Arguments are not valid JSON: ${it.message}")
            return
        }
        _run.value = ToolRunState(running = true, toolName = tool)
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val state = try {
                val result = dispatcher.dispatchTool(AiToolCall("tooldebug_${started}", tool, args))
                ToolRunState(
                    toolName = tool,
                    success = result.isSuccess,
                    durationMs = System.currentTimeMillis() - started,
                    output = runCatching { result.resultJson.toString(2) }.getOrDefault(result.resultJson.toString()) +
                        (result.errorMessage?.takeIf { !result.isSuccess }?.let { "\n\nError: $it" } ?: "")
                )
            } catch (e: Exception) {
                ToolRunState(toolName = tool, success = false, durationMs = System.currentTimeMillis() - started,
                    output = "Crashed: ${e.javaClass.simpleName}: ${e.message}")
            }
            _run.value = state
        }
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
