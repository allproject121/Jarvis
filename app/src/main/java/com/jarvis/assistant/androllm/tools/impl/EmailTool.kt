package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Opens the user's email app with a pre-filled draft. Confirmed first; the
 * user presses send in their own mail client — the tool never sends anything
 * by itself.
 *
 * Jarvis-first: the draft is built by Jarvis' existing send_email
 * (androllm.tools.AndroLlmTools, which also sets EXTRA_EMAIL so every mail
 * client pre-fills the recipient). This class adds AndroLLM's spec and the
 * confirm-first gate.
 */
class EmailTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "send_email",
        description = "Open the email app with a pre-filled draft (recipient, subject, body) for the user to review and send. Always requires confirmation.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("to") {
                    put("type", "string")
                    put("description", "Recipient email address")
                }
                putJsonObject("subject") { put("type", "string") }
                putJsonObject("body") { put("type", "string") }
            }
            putJsonArray("required") { add("to") }
        },
        permission = ToolPermission.EMAIL,
        requiresConfirmation = true,
        confirmationPrompt = "open the email to {to}",
        category = ToolCategory.COMMUNICATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val to = ToolArgs.str(arguments, "to", "recipient")
            ?: return ToolResult.Failure("Missing required argument: to")
        val subject = ToolArgs.str(arguments, "subject").orEmpty()
        val approved = HighRiskConfirmation.ensureApproved(
            context, spec.name,
            "open an email draft to $to${if (subject.isNotBlank()) " about \"$subject\"" else ""}"
        )
        if (!approved) return HighRiskConfirmation.declined()
        return JarvisToolBridge.runAndroLlmTool(context, "send_email", arguments)
    }
}
