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
import com.jarvis.assistant.ai.provider.AiToolCall
import com.jarvis.assistant.ai.provider.AiToolResult
import com.jarvis.assistant.androllm.accessibility.planner.SafetyClassifier
import com.jarvis.assistant.androllm.accessibility.settings.AccessibilitySettingsStore
import com.jarvis.assistant.androllm.agent.confirm.ConfirmationOverlay
import com.jarvis.assistant.androllm.agent.settings.ConfirmationMode
import com.jarvis.assistant.automation.agent.PendingAction
import com.jarvis.assistant.automation.agent.ToolConfirmationManager
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

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
        val settings = AutomationSettingsStore.getInstance(context).currentBlocking()
        // Settings → Agent & Tools → "Tool calling" OFF: no tool is offered to any engine.
        if (!settings.toolCallingEnabled) return@runCatching emptyList<JSONObject>()
        val disabled = settings.disabledTools
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

    /**
     * Coroutine marker set by [JarvisDispatcherTool] when the AndroLLM agent pipeline runs a
     * Jarvis tool: its ToolExecutor already applied toggles + confirmation and recorded the
     * trace, so [preflight] / [recordCall] must not do it a second time.
     */
    object AgentPipelineCallKey : CoroutineContext.Key<AgentPipelineCall>

    object AgentPipelineCall : AbstractCoroutineContextElement(AgentPipelineCallKey)

    private suspend fun viaAgentPipeline(): Boolean = coroutineContext[AgentPipelineCallKey] != null

    /** Bookkeeping tools Gemini calls on its own; never worth a confirmation prompt. */
    private val NEVER_CONFIRM = setOf(
        "task_completed", "get_task_status", "get_active_task", "get_failed_tasks",
        "get_last_failure_reason", "find_tool"
    )

    /** Tap tools whose target label decides whether the tap is risky (Screen automation page). */
    private val TAP_TOOLS = setOf("tap_text", "tap_element", "ui_click", "click_ui_element")

    /**
     * Gates every JarvisToolDispatcher call (Gemini Live, offline brain, chat, Tool Debug "Run")
     * with the user's settings BEFORE the tool runs. Returns null to proceed, or the refusal.
     *
     * - Agent & Tools → Tool calling OFF: every tool is refused.
     * - Agent & Tools → per-tool switch OFF: that tool is refused.
     * - Agent & Tools → Confirmations: Always = every action, High-risk only = calls, messages,
     *   emails (tools marked "confirm"), Never = no prompt. Answered on the in-app dialog or on
     *   the card shown over other apps ([ConfirmationOverlay]).
     * - Screen automation → Confirm high-risk steps: a tap whose label looks like
     *   send / pay / delete / install / post / call ... needs an OK first.
     */
    suspend fun preflight(context: Context, call: AiToolCall): AiToolResult? {
        // Start the over-other-apps Allow/Deny card early: ui_run asks mid-task inside other apps.
        ConfirmationOverlay.ensureStarted(context)
        if (viaAgentPipeline()) return null
        // run_tool(name, args) runs a hidden registry tool: judge the tool it actually runs.
        val inner = call.functionName == "run_tool"
        val name = if (inner) call.arguments.optString("name").trim().ifBlank { "run_tool" } else call.functionName
        val args = if (inner) {
            call.arguments.optJSONObject("args")
                ?: call.arguments.optString("args").takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject()
        } else call.arguments
        val settings = runCatching { AutomationSettingsStore.getInstance(context).current() }.getOrNull()
            ?: return null
        if (!settings.toolCallingEnabled) {
            return refusal(call, "Tool calling is turned off in Settings → Agent & Tools, so '$name' was not run.")
        }
        if (name in settings.disabledTools || call.functionName in settings.disabledTools) {
            return refusal(call, "The '$name' tool is switched off in Settings → Agent & Tools.")
        }
        if (args.optBoolean("confirmed", false)) return null
        // Settings → Direct autonomous mode = "run tools & commands without confirmations".
        // Only an explicit "Always confirm" here overrides it.
        val directMode = runCatching { com.jarvis.assistant.JarvisApp.instance.preferences.isDirectAutonomousMode }.getOrDefault(false)
        if (directMode && settings.confirmationMode != ConfirmationMode.ALWAYS) return null

        val needsToolConfirm = name !in NEVER_CONFIRM && when (settings.confirmationMode) {
            ConfirmationMode.ALWAYS -> true
            ConfirmationMode.NEVER -> false
            ConfirmationMode.HIGH_RISK -> JarvisToolBridge.metaFor(name).requiresConfirmation ||
                runCatching {
                    com.jarvis.assistant.automation.dispatcher.MayaToolRegistry.ensureInstalled(context)
                    com.jarvis.assistant.automation.dispatcher.MayaToolRegistry.isRisky(name)
                }.getOrDefault(false)
        }
        val riskyTap = if (name in TAP_TOOLS) riskyTapLabel(context, name, args) else null
        if (!needsToolConfirm && riskyTap == null) return null

        val what = riskyTap?.let { "tap \"$it\"" } ?: describe(name, args)
        val approved = runCatching {
            ToolConfirmationManager.awaitApproval(
                PendingAction(
                    id = "tool_${name}_${System.nanoTime()}",
                    toolName = name,
                    description = "Jarvis wants to $what.",
                    speakableQuestion = "Should I $what?"
                )
            )
        }.getOrDefault(false)
        if (approved) return null
        val hint = if (ConfirmationOverlay.canShow(context)) "" else
            " (Jarvis can only ask inside the app: allow 'Display over other apps' for Jarvis, or set Confirmations to Never.)"
        return refusal(call, "Not done: the user did not approve '$what' within 15 seconds.$hint")
    }

    /** Label of a tap that the Screen-automation "Confirm high-risk steps" switch must confirm. */
    private suspend fun riskyTapLabel(context: Context, name: String, args: JSONObject): String? {
        val confirmTaps = runCatching { AccessibilitySettingsStore.getInstance(context).current().confirmHighRisk }
            .getOrDefault(true)
        if (!confirmTaps) return null
        val label = when (name) {
            "tap_element" -> {
                val id = args.optInt("id", args.optInt("element_id", -1))
                runCatching {
                    com.jarvis.assistant.maya.screen.ScreenElementSerializer.last?.byId?.get(id)?.line?.label
                }.getOrNull().orEmpty()
            }
            else -> args.optString("label").ifBlank { args.optString("target") }.ifBlank { args.optString("text") }
        }
        return label.takeIf { it.isNotBlank() && SafetyClassifier.isRiskyTarget(it) }
    }

    private val PLACEHOLDER = Regex("\\{(\\w+)\\}")

    /** "call Mom" from the tool's confirmation template, or "run send_sms (to=…, …)". */
    private fun describe(name: String, args: JSONObject): String {
        val template = JarvisToolBridge.metaFor(name).confirmationPrompt
        if (template.isNotBlank()) {
            return PLACEHOLDER.replace(template) { m -> args.optString(m.groupValues[1]).ifBlank { "?" } }
        }
        val shown = args.keys().asSequence().filter { it != "confirmed" }.take(3)
            .joinToString(", ") { k -> "$k=${args.opt(k).toString().take(40)}" }
        return if (shown.isBlank()) "run $name" else "run $name ($shown)"
    }

    private fun refusal(call: AiToolCall, message: String): AiToolResult = AiToolResult(
        callId = call.callId,
        functionName = call.functionName,
        isSuccess = false,
        resultJson = JSONObject().apply {
            put("success", false)
            put("blocked_by_settings", true)
            put("message", message)
        },
        errorMessage = message
    )

    /**
     * Records one dispatcher call in the shared Tool Debug log (Settings → Tool Debug and
     * Developer & Diagnostics), whoever made it: Gemini Live, the offline brain, chat, adb.
     */
    suspend fun recordCall(context: Context, call: AiToolCall, result: AiToolResult, durationMs: Long) {
        if (viaAgentPipeline()) return
        runCatching {
            val message = result.resultJson.optString("message").ifBlank { result.resultJson.toString() }
            val status = when {
                result.isSuccess -> "ok"
                result.resultJson.optBoolean("blocked_by_settings") ||
                    result.resultJson.optBoolean("blocked_by_voice_guard") -> "blocked"
                else -> "failed"
            }
            AgentToolGraph.getInstance(context).traceStore.record(
                toolName = call.functionName,
                arguments = call.arguments.toString(),
                status = status,
                result = message,
                error = if (result.isSuccess) null else result.errorMessage,
                durationMs = durationMs
            )
        }
    }

    private fun schemaName(schema: JSONObject): String? =
        (schema.optJSONObject("function") ?: schema).optString("name").takeIf { it.isNotBlank() }
}
