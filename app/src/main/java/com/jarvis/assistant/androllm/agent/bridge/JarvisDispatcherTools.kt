package com.jarvis.assistant.androllm.agent.bridge

import android.content.Context
import com.jarvis.assistant.ai.provider.AiToolCall
import com.jarvis.assistant.ai.provider.AiToolResult
import com.jarvis.assistant.androllm.agent.core.PrivacyLevel
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolCost
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import com.jarvis.assistant.automation.dispatcher.JarvisToolDispatcher
import com.jarvis.assistant.androllm.engine.support.AlLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

/**
 * Adapter that exposes one existing [JarvisToolDispatcher] tool as an AndroLLM
 * [Tool], so the agent framework (router, planner, executor, coordinator,
 * Tool Debug) sees every Jarvis tool without duplicating any implementation.
 *
 * Execution ALWAYS goes through [JarvisToolDispatcher.dispatchTool]: Jarvis'
 * own loop guard, idempotency cache, skill/MCP routing and per-tool
 * behaviour stay exactly as they are. The adapter only converts between
 * kotlinx JSON / [ToolResult] and org.json / [AiToolResult].
 */
class JarvisDispatcherTool(
    override val spec: ToolSpec,
    private val dispatcher: JarvisToolDispatcher
) : Tool {

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val args = runCatching { JSONObject(arguments.toString()) }.getOrDefault(JSONObject())
        val result = dispatcher.dispatchTool(
            AiToolCall(
                callId = "agent_${spec.name}_${System.nanoTime()}",
                functionName = spec.name,
                arguments = args
            )
        )
        return JarvisToolBridge.toToolResult(result)
    }
}

/**
 * Conversions + the static metadata table that maps Jarvis' tool names onto
 * AndroLLM's [ToolSpec] semantics (category for the five-way toggle list,
 * logical permission, confirmation, cacheability, routing tasks).
 */
object JarvisToolBridge {

    private const val TAG = "JarvisToolBridge"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Static metadata for a Jarvis tool name. */
    data class Meta(
        val category: ToolCategory,
        val permission: ToolPermission? = null,
        val requiresConfirmation: Boolean = false,
        val cacheable: Boolean = false,
        val supportedTasks: List<String> = emptyList(),
        val executionTimeoutMs: Long? = null,
        val privacyLevel: PrivacyLevel = PrivacyLevel.LOCAL,
        val cost: ToolCost = ToolCost.FREE,
        val confirmationPrompt: String = ""
    )

    private val META: Map<String, Meta> = mapOf(
        // Device
        "set_flashlight" to Meta(ToolCategory.DEVICE, ToolPermission.FLASHLIGHT, supportedTasks = listOf("flashlight", "torch")),
        "set_volume" to Meta(ToolCategory.DEVICE, ToolPermission.SYSTEM, supportedTasks = listOf("volume", "mute")),
        "get_telemetry" to Meta(ToolCategory.DEVICE, ToolPermission.DEVICE, supportedTasks = listOf("battery", "charging", "network")),
        "get_app_usage_stats" to Meta(ToolCategory.DEVICE, ToolPermission.DEVICE, supportedTasks = listOf("screen time", "app usage")),
        "get_wifi_status" to Meta(ToolCategory.DEVICE, ToolPermission.WIFI, supportedTasks = listOf("wifi", "wi-fi")),
        "set_wifi" to Meta(ToolCategory.DEVICE, ToolPermission.WIFI, supportedTasks = listOf("wifi", "wi-fi")),
        "get_bluetooth_status" to Meta(ToolCategory.DEVICE, ToolPermission.BLUETOOTH, supportedTasks = listOf("bluetooth")),
        "control_smart_home" to Meta(ToolCategory.DEVICE, ToolPermission.SYSTEM, supportedTasks = listOf("lights", "smart home")),
        "get_smart_home_devices" to Meta(ToolCategory.DEVICE, ToolPermission.SYSTEM, supportedTasks = listOf("smart home")),
        "click_ui_element" to Meta(ToolCategory.DEVICE, ToolPermission.ACCESSIBILITY),
        "type_text" to Meta(ToolCategory.DEVICE, ToolPermission.ACCESSIBILITY),
        "scroll_screen" to Meta(ToolCategory.DEVICE, ToolPermission.ACCESSIBILITY),
        "ui_read_screen" to Meta(ToolCategory.DEVICE, ToolPermission.ACCESSIBILITY, supportedTasks = listOf("read screen")),
        "ui_click" to Meta(ToolCategory.DEVICE, ToolPermission.ACCESSIBILITY),
        "ui_type" to Meta(ToolCategory.DEVICE, ToolPermission.ACCESSIBILITY),
        "ui_scroll" to Meta(ToolCategory.DEVICE, ToolPermission.ACCESSIBILITY),
        "ui_swipe" to Meta(ToolCategory.DEVICE, ToolPermission.ACCESSIBILITY),
        "ui_navigate" to Meta(ToolCategory.DEVICE, ToolPermission.ACCESSIBILITY),
        "ui_gesture" to Meta(ToolCategory.DEVICE, ToolPermission.ACCESSIBILITY),
        "ui_run" to Meta(ToolCategory.DEVICE, ToolPermission.ACCESSIBILITY, executionTimeoutMs = 240_000L),
        "ocr_screen" to Meta(ToolCategory.DEVICE, ToolPermission.ACCESSIBILITY, supportedTasks = listOf("read screen", "ocr")),
        "vibrate" to Meta(ToolCategory.DEVICE, ToolPermission.SYSTEM),
        "take_screenshot" to Meta(ToolCategory.DEVICE, ToolPermission.SCREENSHOT, supportedTasks = listOf("screenshot")),
        // Media & apps
        "open_app" to Meta(ToolCategory.MEDIA, ToolPermission.APPS, supportedTasks = listOf("open app", "launch")),
        "search_apps" to Meta(ToolCategory.MEDIA, ToolPermission.APPS, cacheable = true),
        "ui_launch_app" to Meta(ToolCategory.MEDIA, ToolPermission.APPS),
        "control_music" to Meta(ToolCategory.MEDIA, ToolPermission.MUSIC, supportedTasks = listOf("music", "play", "pause")),
        "scan_qr" to Meta(ToolCategory.MEDIA, ToolPermission.QR, supportedTasks = listOf("qr")),
        // Information
        "get_weather" to Meta(ToolCategory.INFORMATION, ToolPermission.WEATHER, cacheable = true, executionTimeoutMs = 45_000L,
            supportedTasks = listOf("weather", "forecast"), privacyLevel = PrivacyLevel.NETWORK, cost = ToolCost.NETWORK),
        "search_web" to Meta(ToolCategory.INFORMATION, ToolPermission.SEARCH, cacheable = true, executionTimeoutMs = 60_000L,
            supportedTasks = listOf("search", "web search", "news"), privacyLevel = PrivacyLevel.NETWORK, cost = ToolCost.NETWORK),
        "open_website" to Meta(ToolCategory.INFORMATION, ToolPermission.SEARCH, supportedTasks = listOf("website", "url")),
        "search_in_chrome" to Meta(ToolCategory.INFORMATION, ToolPermission.SEARCH, supportedTasks = listOf("google", "search")),
        "calculate" to Meta(ToolCategory.INFORMATION, ToolPermission.CALCULATOR, cacheable = true, supportedTasks = listOf("math", "calculate")),
        "convert_units" to Meta(ToolCategory.INFORMATION, ToolPermission.CALCULATOR, cacheable = true, supportedTasks = listOf("convert")),
        "convert_currency" to Meta(ToolCategory.INFORMATION, ToolPermission.CALCULATOR, cacheable = true,
            supportedTasks = listOf("currency"), privacyLevel = PrivacyLevel.NETWORK, cost = ToolCost.NETWORK),
        "read_notifications" to Meta(ToolCategory.INFORMATION, ToolPermission.NOTIFICATIONS, supportedTasks = listOf("notification")),
        // Communication
        "send_whatsapp_message" to Meta(ToolCategory.COMMUNICATION, ToolPermission.SMS, requiresConfirmation = true,
            supportedTasks = listOf("whatsapp", "message"), privacyLevel = PrivacyLevel.SENSITIVE, confirmationPrompt = "send the WhatsApp message to {recipient}"),
        "whatsapp_call" to Meta(ToolCategory.COMMUNICATION, ToolPermission.CALLS, requiresConfirmation = true, privacyLevel = PrivacyLevel.SENSITIVE),
        "call_contact" to Meta(ToolCategory.COMMUNICATION, ToolPermission.CALLS, requiresConfirmation = true,
            supportedTasks = listOf("call", "phone"), privacyLevel = PrivacyLevel.SENSITIVE, confirmationPrompt = "call {contact_name}"),
        "send_sms" to Meta(ToolCategory.COMMUNICATION, ToolPermission.SMS, requiresConfirmation = true,
            supportedTasks = listOf("sms", "text"), privacyLevel = PrivacyLevel.SENSITIVE),
        "send_email" to Meta(ToolCategory.COMMUNICATION, ToolPermission.EMAIL, requiresConfirmation = true,
            supportedTasks = listOf("email"), privacyLevel = PrivacyLevel.SENSITIVE),
        "share_text" to Meta(ToolCategory.COMMUNICATION, ToolPermission.SHARE, supportedTasks = listOf("share")),
        "get_contact_count" to Meta(ToolCategory.COMMUNICATION, ToolPermission.CONTACTS, privacyLevel = PrivacyLevel.SENSITIVE),
        "get_whatsapp_contact_count" to Meta(ToolCategory.COMMUNICATION, ToolPermission.CONTACTS, privacyLevel = PrivacyLevel.SENSITIVE),
        "resolve_contact" to Meta(ToolCategory.COMMUNICATION, ToolPermission.CONTACTS, supportedTasks = listOf("contact"),
            privacyLevel = PrivacyLevel.SENSITIVE),
        // Productivity
        "add_calendar_event" to Meta(ToolCategory.PRODUCTIVITY, ToolPermission.CALENDAR, supportedTasks = listOf("calendar", "meeting")),
        "create_note" to Meta(ToolCategory.PRODUCTIVITY, ToolPermission.NOTES, supportedTasks = listOf("note")),
        "list_notes" to Meta(ToolCategory.PRODUCTIVITY, ToolPermission.NOTES, supportedTasks = listOf("note")),
        "set_reminder" to Meta(ToolCategory.PRODUCTIVITY, ToolPermission.ALARMS, supportedTasks = listOf("reminder", "timer", "alarm")),
        "read_clipboard" to Meta(ToolCategory.PRODUCTIVITY, ToolPermission.CLIPBOARD, supportedTasks = listOf("clipboard")),
        "copy_to_clipboard" to Meta(ToolCategory.PRODUCTIVITY, ToolPermission.CLIPBOARD, supportedTasks = listOf("clipboard", "copy")),
        "open_navigation" to Meta(ToolCategory.PRODUCTIVITY, ToolPermission.MAPS, supportedTasks = listOf("navigate", "directions")),
        "search_places" to Meta(ToolCategory.PRODUCTIVITY, ToolPermission.MAPS, supportedTasks = listOf("nearby", "places")),
        "execute_connected_app_action" to Meta(ToolCategory.PRODUCTIVITY, ToolPermission.ACCESSIBILITY, requiresConfirmation = true),
        "task_completed" to Meta(ToolCategory.PRODUCTIVITY),
        "get_task_status" to Meta(ToolCategory.PRODUCTIVITY),
        "get_active_task" to Meta(ToolCategory.PRODUCTIVITY),
        "get_failed_tasks" to Meta(ToolCategory.PRODUCTIVITY),
        "cancel_active_task" to Meta(ToolCategory.PRODUCTIVITY),
        "get_last_failure_reason" to Meta(ToolCategory.PRODUCTIVITY),
        "resume_task" to Meta(ToolCategory.PRODUCTIVITY)
    )

    /** Metadata for [name]; unknown (skill / MCP) tools fall into PRODUCTIVITY / MCP. */
    fun metaFor(name: String): Meta = META[name] ?: Meta(
        category = ToolCategory.PRODUCTIVITY,
        permission = if (name.contains("mcp", ignoreCase = true)) ToolPermission.MCP else null
    )

    /**
     * Builds one adapter per tool currently advertised by
     * [JarvisToolDispatcher.getAvailableToolSchemas] (built-ins, AndroLLM
     * dispatcher tools, skills and MCP tools). Names that are not valid
     * registry identifiers are skipped (logged) instead of failing.
     */
    fun buildAdapters(context: Context): List<Tool> {
        val dispatcher = JarvisToolDispatcher.getInstance(context)
        val schemas = runCatching { dispatcher.getAvailableToolSchemas(includeDisabled = true) }
            .getOrElse {
                AlLog.tag(TAG).w(it, "could not read Jarvis tool schemas")
                emptyList()
            }
        return schemas.mapNotNull { schema -> toSpec(schema)?.let { JarvisDispatcherTool(it, dispatcher) } }
            .distinctBy { it.spec.name }
    }

    /** Converts one OpenAI-style Jarvis schema into a [ToolSpec]. */
    fun toSpec(schema: JSONObject): ToolSpec? {
        val fn = schema.optJSONObject("function") ?: schema
        val name = fn.optString("name").trim()
        if (name.isEmpty()) return null
        val params: JsonObject = fn.optJSONObject("parameters")?.let { p ->
            runCatching { json.parseToJsonElement(p.toString()).jsonObject }.getOrNull()
        } ?: buildJsonObject { }
        val meta = metaFor(name)
        return ToolSpec(
            name = name,
            description = fn.optString("description"),
            parameters = params,
            permission = meta.permission,
            requiresConfirmation = meta.requiresConfirmation,
            category = meta.category,
            confirmationPrompt = meta.confirmationPrompt,
            executionTimeoutMs = meta.executionTimeoutMs,
            supportedTasks = meta.supportedTasks,
            cacheable = meta.cacheable,
            privacyLevel = meta.privacyLevel,
            cost = meta.cost
        )
    }

    /** Jarvis result → AndroLLM result. The summary is what the LLM reads. */
    fun toToolResult(result: AiToolResult): ToolResult {
        val message = result.resultJson.optString("message", "").trim()
        val data: JsonObject = runCatching {
            json.parseToJsonElement(result.resultJson.toString()).jsonObject
        }.getOrElse { buildJsonObject { } }
        return if (result.isSuccess) {
            ToolResult.Success(
                summary = message.ifBlank { result.resultJson.toString().take(4_000) },
                data = data
            )
        } else {
            val error = result.errorMessage?.takeIf { it.isNotBlank() } ?: message.ifBlank { "Tool '${result.functionName}' failed." }
            // Loop-guard / unsupported outcomes cannot be fixed by retrying.
            val retryable = !error.contains("loop guard", ignoreCase = true) &&
                !error.contains("already been used", ignoreCase = true) &&
                !error.contains("execution limit", ignoreCase = true) &&
                !error.contains("Unsupported tool action", ignoreCase = true) &&
                !error.contains("disabled", ignoreCase = true)
            ToolResult.Failure(summary = error, data = data, retryable = retryable)
        }
    }

    /** AndroLLM result → Jarvis result (used when the pipeline answers a Jarvis provider). */
    fun toAiToolResult(callId: String, name: String, result: ToolResult): AiToolResult {
        val payload = JSONObject()
        val data = when (result) {
            is ToolResult.Success -> result.data
            is ToolResult.Failure -> result.data
        }
        runCatching {
            val obj = JSONObject(data.toString())
            obj.keys().forEach { k -> payload.put(k, obj.get(k)) }
        }
        payload.put("success", result.isSuccess)
        payload.put("message", result.summary)
        return AiToolResult(
            callId = callId,
            functionName = name,
            isSuccess = result.isSuccess,
            resultJson = payload,
            errorMessage = if (result is ToolResult.Failure) result.summary else null
        )
    }

    /** OpenAI-style schema for a registry [ToolSpec] (for llama.cpp / Hermes prompts). */
    fun toJarvisSchema(spec: ToolSpec): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", spec.name)
            put("description", spec.description)
            val params = runCatching { JSONObject(spec.parameters.toString()) }.getOrDefault(JSONObject())
            if (!params.has("type")) params.put("type", "object")
            if (!params.has("properties")) params.put("properties", JSONObject())
            put("parameters", params)
        })
    }

}
