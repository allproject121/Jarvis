package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.settings.AutomationSettingsStore
import com.jarvis.assistant.androllm.agent.settings.ConfirmationMode
import com.jarvis.assistant.androllm.tools.AndroLlmTools
import com.jarvis.assistant.androllm.tools.ToolOutcome
import com.jarvis.assistant.automation.agent.PendingAction
import com.jarvis.assistant.automation.agent.ToolConfirmationManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

/**
 * Glue between the AndroLLM agent tool framework (kotlinx JsonObject +
 * [ToolResult]) and Jarvis' existing implementations (org.json +
 * [ToolOutcome]). Agent tools whose behaviour Jarvis already ships delegate
 * through here instead of duplicating it.
 */
internal object JarvisToolBridge {

    private val json = Json { ignoreUnknownKeys = true }

    /** kotlinx → org.json (arguments for Jarvis-side implementations). */
    fun toOrgJson(arguments: JsonObject): JSONObject =
        runCatching { JSONObject(arguments.toString()) }.getOrDefault(JSONObject())

    /** org.json → kotlinx (structured data for [ToolResult]). */
    fun toKotlinJson(obj: JSONObject?): JsonObject =
        obj?.let { runCatching { json.parseToJsonElement(it.toString()).jsonObject }.getOrNull() }
            ?: buildJsonObject { }

    fun toToolResult(outcome: ToolOutcome): ToolResult =
        if (outcome.success) {
            ToolResult.Success(outcome.message, toKotlinJson(outcome.data))
        } else {
            ToolResult.Failure(outcome.message, toKotlinJson(outcome.data))
        }

    /**
     * Runs one of Jarvis' AndroLLM-ported tools ([AndroLlmTools]) and maps the
     * outcome. [extra] lets callers add flags (e.g. `confirmed=true` after the
     * confirmation gate passed).
     */
    suspend fun runAndroLlmTool(
        context: Context,
        name: String,
        arguments: JsonObject,
        extra: Map<String, Any> = emptyMap()
    ): ToolResult {
        val args = toOrgJson(arguments)
        extra.forEach { (k, v) -> args.put(k, v) }
        val outcome = AndroLlmTools.execute(context.applicationContext, name, args)
            ?: return ToolResult.Failure("Tool '$name' is not available.", retryable = false)
        return toToolResult(outcome)
    }
}

/**
 * Confirm-first gate for high-risk tools (SMS, calls, email, deletions).
 *
 * The agent ToolExecutor already asks before running a tool whose spec has
 * `requiresConfirmation = true` when the Automation confirmation mode is
 * HIGH_RISK or ALWAYS. The user can however pick NEVER — Jarvis' rule is that
 * high-risk actions are always confirmed, so in that mode (or when the
 * setting cannot be read) the tool itself asks through Jarvis'
 * [ToolConfirmationManager] (rendered by the Compose UI / voice). An
 * unanswered request times out as "declined".
 */
internal object HighRiskConfirmation {

    suspend fun ensureApproved(
        context: Context,
        toolName: String,
        description: String,
        speakableQuestion: String = "Do you want me to $description? Say yes to confirm, or no to cancel."
    ): Boolean {
        val mode = runCatching {
            AutomationSettingsStore(context.applicationContext).current().confirmationMode
        }.getOrNull()
        if (mode == ConfirmationMode.HIGH_RISK || mode == ConfirmationMode.ALWAYS) {
            // ToolExecutor confirmed this call before execute() ran.
            return true
        }
        val action = PendingAction(
            id = "${toolName}_${System.currentTimeMillis()}_${description.hashCode().toUInt().toString(16)}",
            toolName = toolName,
            description = "Jarvis wants to $description",
            speakableQuestion = speakableQuestion
        )
        return runCatching { ToolConfirmationManager.awaitApproval(action) }.getOrDefault(false)
    }

    fun declined(): ToolResult =
        ToolResult.Failure("The user did not confirm the action, so nothing was done.", retryable = false)
}
