package com.jarvis.assistant.automation.dispatcher

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI / Hermes function schemas for the Batch-4 device tools handled in JarvisToolDispatcher
 * (battery, storage, WhatsApp notification reading, contacts search, press_key, DND, focus timer,
 * scheduled tasks). Pure — no Android types — so the schema list is unit-testable.
 */
object DeviceToolSchemas {

    /** Keys the press_key handler supports (mapped to AccessibilityService global actions). */
    val PRESS_KEYS: List<String> = listOf("back", "home", "recents", "notifications", "quick_settings", "lock_screen")

    val NAMES: List<String> = listOf(
        "get_battery_status",
        "get_storage_stats",
        "whatsapp_read_messages",
        "find_contacts",
        "search_contacts",
        "press_key",
        "get_scheduled_tasks",
        "set_dnd",
        "start_focus_timer"
    )

    fun schemas(): List<JSONObject> {
        val tools = mutableListOf<JSONObject>()

        fun tool(name: String, description: String, properties: JSONObject, required: List<String>) {
            tools.add(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", name)
                    put("description", description)
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", properties)
                        put("required", JSONArray(required))
                    })
                })
            })
        }

        tool(
            name = "get_battery_status",
            description = "Reports battery percentage, whether it is charging and from what (AC/USB/wireless), temperature, health, and estimated time to full when charging.",
            properties = JSONObject(),
            required = emptyList()
        )

        tool(
            name = "get_storage_stats",
            description = "Reports internal storage: total, used, free and percent full, plus the size of Jarvis' own cache.",
            properties = JSONObject(),
            required = emptyList()
        )

        tool(
            name = "whatsapp_read_messages",
            description = "Reads recent WhatsApp / WhatsApp Business messages from the notifications Jarvis has seen (needs Notification access). Optionally filter by sender/chat name.",
            properties = JSONObject().apply {
                put("contact", JSONObject().apply {
                    put("type", "string")
                    put("description", "Optional sender or chat name to filter by (partial match)")
                })
                put("limit", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Maximum messages to return (default 5, max 25)")
                })
            },
            required = emptyList()
        )

        val contactProps = {
            JSONObject().apply {
                put("query", JSONObject().apply {
                    put("type", "string")
                    put("description", "Name (or part of a name / number) to search the phone contacts for")
                })
                put("limit", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Maximum contacts to return (default 8, max 20)")
                })
            }
        }
        tool(
            name = "find_contacts",
            description = "Searches the phone address book and returns matching contacts with number, label and how often they were called.",
            properties = contactProps(),
            required = listOf("query")
        )
        tool(
            name = "search_contacts",
            description = "Alias of find_contacts: searches the phone address book for contacts matching a name.",
            properties = contactProps(),
            required = listOf("query")
        )

        tool(
            name = "press_key",
            description = "Presses a system navigation key via the accessibility service.",
            properties = JSONObject().apply {
                put("key", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(PRESS_KEYS))
                    put("description", "Which key/global action to press")
                })
            },
            required = listOf("key")
        )

        tool(
            name = "get_scheduled_tasks",
            description = "Lists the upcoming timers, focus timers, reminders and alarms that Jarvis itself scheduled.",
            properties = JSONObject(),
            required = emptyList()
        )

        tool(
            name = "set_dnd",
            description = "Turns Do Not Disturb on or off (needs Do Not Disturb access for Jarvis).",
            properties = JSONObject().apply {
                put("enabled", JSONObject().apply {
                    put("type", "boolean")
                    put("description", "true to turn Do Not Disturb on, false to turn it off")
                })
                put("mode", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(listOf("priority", "alarms", "total_silence")))
                    put("description", "Optional DND level when enabling (default priority)")
                })
            },
            required = listOf("enabled")
        )

        tool(
            name = "start_focus_timer",
            description = "Starts a focus countdown timer in the Clock app for N minutes, optionally turning on Do Not Disturb.",
            properties = JSONObject().apply {
                put("minutes", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Focus duration in minutes (1-600)")
                })
                put("dnd", JSONObject().apply {
                    put("type", "boolean")
                    put("description", "Also turn on Do Not Disturb (priority) for the session")
                })
            },
            required = listOf("minutes")
        )

        return tools
    }
}
