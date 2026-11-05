package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolBackend
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import com.jarvis.assistant.androllm.permissions.Feature
import com.jarvis.assistant.androllm.permissions.PermissionManager
import com.jarvis.assistant.util.ContactCaller
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
 * Looks up a contact by name and returns their phone numbers — useful before
 * sending an SMS, calling, or emailing ("message Mom").
 *
 * Jarvis-first: the lookup is Jarvis' [ContactCaller.searchContacts] (the same
 * engine behind the find_contacts dispatcher tool: fuzzy matching ranked by
 * call affinity, memory and call log), not AndroLLM's plain LIKE query.
 */
class ContactTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "find_contacts",
        description = "Look up a contact by name and return their phone numbers. Use before messaging or calling a named person.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Contact name or part of it")
                }
            }
            putJsonArray("required") { add("name") }
        },
        permission = ToolPermission.CONTACTS,
        category = ToolCategory.COMMUNICATION,
        requiresConfirmation = true,
        supportedBackends = setOf(ToolBackend.LOCAL, ToolBackend.CLOUD),
        availableOnDevice = true,
        worksLocally = true
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val name = ToolArgs.str(arguments, "name", "query", "contact")
            ?: return ToolResult.Failure("Missing required argument: name")
        if (!PermissionHelper.hasContactsPermission(context)) {
            return ToolResult.Failure(
                PermissionManager.getInstance(context).missingMessage(
                    Feature.CONTACTS, "Looking up \"$name\" needs contacts access."
                ),
                retryable = false
            )
        }
        return runCatching {
            val candidates = withContext(Dispatchers.Main) {
                ContactCaller.searchContacts(context, name, 8)
            }
            if (candidates.isEmpty()) {
                return ToolResult.Failure("No contacts matched \"$name\".")
            }
            // Group numbers per display name (AndroLLM shape: name + phones[]).
            val grouped = linkedMapOf<String, MutableList<ContactCaller.Candidate>>()
            candidates.forEach { grouped.getOrPut(it.contact.name) { mutableListOf() }.add(it) }

            val sb = StringBuilder("Contacts matching \"$name\":")
            val items = mutableListOf<JsonObject>()
            grouped.entries.take(3).forEach { (display, list) ->
                items += buildJsonObject {
                    put("name", display)
                    putJsonArray("phones") { list.take(2).forEach { add(it.contact.number) } }
                    list.first().contact.label.takeIf { it.isNotBlank() }?.let { put("label", it) }
                    put("times_called", list.maxOf { it.affinityCount })
                }
                sb.append(' ').append(display).append(": ").append(list.first().contact.number).append(';')
            }
            val data = buildJsonObject { putJsonArray("contacts") { items.forEach { add(it) } } }
            ToolResult.Success(summary = sb.toString().trimEnd(';'), data = data)
        }.getOrElse {
            ToolResult.Failure("Contact lookup failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }
}
