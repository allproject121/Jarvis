package com.jarvis.assistant.androllm.tools.impl

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import com.jarvis.assistant.androllm.permissions.Feature
import com.jarvis.assistant.androllm.permissions.PermissionManager
import com.jarvis.assistant.automation.tools.ContactResolver
import com.jarvis.assistant.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Sends an SMS. Never fires silently: the agent executor routes this tool
 * through a user confirmation, and [HighRiskConfirmation] asks again through
 * Jarvis' ToolConfirmationManager if the executor's confirmation mode was set
 * to "Never".
 *
 * Jarvis-first: the recipient resolution + multipart send is Jarvis' existing
 * send_sms (androllm.tools.AndroLlmTools — the earlier port of this tool using
 * Jarvis' ContactResolver and the API 31+ SmsManager); this class resolves the
 * recipient for the confirmation text, confirms, then calls it with
 * `confirmed=true`.
 */
class SmsTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "send_sms",
        description = "Send an SMS text message. The recipient may be a phone number (e.g. +919876543210) OR a contact name like 'Mom' — the contact is resolved automatically. Always requires the user's confirmation before sending.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("phone") {
                    put("type", "string")
                    put("description", "Recipient phone number with country code (e.g. +919876543210) or a contact name (e.g. 'Mom')")
                }
                putJsonObject("message") {
                    put("type", "string")
                    put("description", "The message text")
                }
            }
            putJsonArray("required") { add("phone"); add("message") }
        },
        permission = ToolPermission.SMS,
        requiresConfirmation = true,
        confirmationPrompt = "send the SMS to {phone}",
        category = ToolCategory.COMMUNICATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val rawRecipient = ToolArgs.str(arguments, "phone", "to", "number")
            ?: return ToolResult.Failure("Missing required argument: phone")
        val message = ToolArgs.str(arguments, "message", "text", "body")
            ?: return ToolResult.Failure("Missing required argument: message")

        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.Failure(
                PermissionManager.getInstance(context).missingMessage(
                    Feature.SMS, "Sending a text message needs the SMS permission."
                ),
                retryable = false
            )
        }

        // Resolve a contact name up-front so the confirmation shows the real number.
        val display = if (ContactResolver.isPhoneNumber(rawRecipient)) {
            ContactResolver.normalize(rawRecipient)
        } else {
            if (!PermissionHelper.hasContactsPermission(context)) {
                return ToolResult.Failure(
                    "\"$rawRecipient\" isn't a phone number. " + PermissionManager.getInstance(context).missingMessage(
                        Feature.CONTACTS, "Resolving \"$rawRecipient\" to a number needs contacts access."
                    ) + " Or give me the number directly.",
                    retryable = false
                )
            }
            val resolved = withContext(Dispatchers.IO) { ContactResolver.resolveByName(context, rawRecipient) }
                ?.let { ContactResolver.normalize(it) }
            if (resolved.isNullOrEmpty()) {
                return ToolResult.Failure(
                    "Could not send the SMS: no contact named \"$rawRecipient\" was found. " +
                        "Please give me the recipient's phone number."
                )
            }
            "$rawRecipient ($resolved)"
        }

        val approved = HighRiskConfirmation.ensureApproved(
            context, spec.name, "send an SMS to $display: \"${message.take(80)}\""
        )
        if (!approved) return HighRiskConfirmation.declined()

        return JarvisToolBridge.runAndroLlmTool(
            context, "send_sms", arguments, extra = mapOf("confirmed" to true)
        )
    }
}
