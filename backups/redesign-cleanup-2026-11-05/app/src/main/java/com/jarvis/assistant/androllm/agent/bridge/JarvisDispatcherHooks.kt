package com.jarvis.assistant.androllm.agent.bridge

import android.content.Context
import com.jarvis.assistant.androllm.agent.AgentFlags
import com.jarvis.assistant.androllm.agent.AgentToolGraph
import com.jarvis.assistant.androllm.agent.core.ToolCall
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.settings.AutomationSettingsStore
import com.jarvis.assistant.androllm.engine.support.AlLog
import com.jarvis.assistant.androllm.tools.ToolOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

/**
 * The small, additive hooks JarvisToolDispatcher calls into the agent
 * framework. Each hook is a no-op with the default settings:
 *
 * - [isToolDisabled]: per-tool toggles from Settings → Agent tools (default:
 *   nothing disabled). The master switch is deliberately NOT applied to the
 *   dispatcher — it only governs the agent pipeline.
 * - [filterSchemas]: hides user-disabled tools from the advertised schema
 *   list and, ONLY when [AgentFlags.AGENT_PIPELINE] is on, appends the
 *   AndroLLM-native registry tools so non-Gemini engines can call them.
 * - [executeRegistryTool]: last-resort executor for a name the dispatcher
 *   does not know (previously an "Unsupported tool action" error) — runs an
 *   AndroLLM-native registry tool through the gated [ToolExecutor]
 *   (validation, confirmation, toggles, timeout, trace). Never runs a Jarvis
 *   adapter (that would re-enter the dispatcher).
 */
object JarvisDispatcherHooks {

    private const val TAG = "JarvisDispatcherHooks"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun isToolDisabled(context: Context, name: String): Boolean = runCatching {
        name in AutomationSettingsStore.getInstance(context).current().disabledTools
    }.getOrDefault(false)

    fun filterSchemas(context: Context, schemas: List<JSONObject>): List<JSONObject> = runCatching {
        val disabled = AutomationSettingsStore.getInstance(context).currentBlocking().disabledTools
        val out = schemas.filter { schemaName(it) !in disabled }.toMutableList()
        if (AgentFlags.isPipelineEnabled(context)) {
            val graph = AgentToolGraph.peek()
            if (graph != null) {
                val existing = out.mapNotNull { schemaName(it) }.toMutableSet()
                graph.registry.all()
                    .filter { it !is JarvisDispatcherTool }
                    .map { it.spec }
                    .filter { it.name !in existing && it.name !in disabled && it.isAvailable }
                    .sortedBy { it.name }
                    .forEach { spec ->
                        out += JarvisToolBridge.toJarvisSchema(spec)
                        existing += spec.name
                    }
            }
        }
        out
    }.getOrElse {
        AlLog.tag(TAG).w(it, "schema filtering failed — returning the unfiltered list")
        schemas
    }

    /** Null when no AndroLLM-native registry tool has [name]. */
    suspend fun executeRegistryTool(context: Context, name: String, args: JSONObject): ToolOutcome? {
        val graph = AgentToolGraph.peek() ?: return null
        graph.androLlmTool(name) ?: return null
        val arguments: JsonObject = runCatching { json.parseToJsonElement(args.toString()).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        val result = graph.executor.execute(
            ToolCall(id = "jarvis_${name}_${System.nanoTime()}", name = name, arguments = arguments)
        )
        val data = runCatching {
            val d = when (result) {
                is ToolResult.Success -> result.data
                is ToolResult.Failure -> result.data
            }
            if (d.isEmpty()) null else JSONObject(d.toString())
        }.getOrNull()
        return if (result.isSuccess) ToolOutcome.ok(result.summary, data) else ToolOutcome.fail(result.summary, data)
    }

    private fun schemaName(schema: JSONObject): String? =
        (schema.optJSONObject("function") ?: schema).optString("name").takeIf { it.isNotBlank() }
}
