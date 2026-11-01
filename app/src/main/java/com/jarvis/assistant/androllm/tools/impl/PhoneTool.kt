package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
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
 * Opens the dialer pre-filled with the number (ACTION_DIAL — the safest call
 * path: no CALL_PHONE permission, the user still presses the final button).
 * Always confirmed, like sending an SMS.
 *
 * (Jarvis' call_contact places calls directly; this agent tool keeps
 * AndroLLM's safer dial-only behaviour. A contact name in `phone` is resolved
 * through Jarvis' [ContactResolver].)
 */
class PhoneTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "make_call",
        description = "Open the dialer with a phone number so the user can make a call. Always requires the user's confirmation.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("phone") {
                    put("type", "string")
                    put("description", "Phone number with country code, e.g. +919876543210")
                }
                putJsonObject("contact") {
                    put("type", "string")
                    put("description", "Optional contact name for display")
                }
            }
            putJsonArray("required") { add("phone") }
        },
        permission = ToolPermission.CALLS,
        requiresConfirmation = true,
        confirmationPrompt = "call {contact}",
        category = ToolCategory.COMMUNICATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val raw = ToolArgs.str(arguments, "phone", "number", "to")
            ?: return ToolResult.Failure("Missing required argument: phone")
        var contact = ToolArgs.str(arguments, "contact")

        val phone = if (ContactResolver.isPhoneNumber(raw)) {
            ContactResolver.normalize(raw)
        } else {
            val resolved = if (PermissionHelper.hasContactsPermission(context)) {
                withContext(Dispatchers.IO) { ContactResolver.resolveByName(context, raw) }
            } else null
            if (resolved.isNullOrBlank()) {
                return ToolResult.Failure(
                    "\"$raw\" isn't a phone number and no matching contact was found — please give me the number."
                )
            }
            if (contact == null) contact = raw
            ContactResolver.normalize(resolved)
        }

        val label = contact ?: phone
        if (!HighRiskConfirmation.ensureApproved(context, spec.name, "call $label ($phone)")) {
            return HighRiskConfirmation.declined()
        }

        val launched = ToolIntents.launch(context, ToolIntents.dialUri(phone))
        if (!launched) {
            return ToolResult.Failure("Could not open the dialer for $phone.")
        }
        return ToolResult.Success(
            summary = "Dialer opened for $label ($phone).",
            data = buildJsonObject {
                put("phone", phone)
                put("contact", contact ?: "")
                put("status", "dialer-opened")
            }
        )
    }
}
