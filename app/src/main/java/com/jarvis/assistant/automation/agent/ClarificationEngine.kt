package com.jarvis.assistant.automation.agent

/**
 * Generates targeted clarification questions for missing tool parameters,
 * avoiding generic "Could you clarify?" responses.
 */
object ClarificationEngine {

    fun forMissingParam(toolName: String, param: String): String {
        return when (toolName to param) {
            "send_whatsapp_message" to "recipient" -> "Who would you like me to send the WhatsApp message to?"
            "send_whatsapp_message" to "message" -> "What should the message say, Sir?"
            "call_contact" to "contact_name" -> "Which contact would you like me to call?"
            "open_app" to "app_name" -> "Which application should I open?"
            "get_weather" to "location" -> "For which city or location would you like the weather forecast?"
            "search_web" to "query" -> "What topic or question would you like me to search for?"
            "create_note" to "text" -> "What note content would you like me to record?"
            "set_reminder" to "message" -> "What should the reminder be for?"
            "calculate" to "expression" -> "What math expression would you like me to calculate?"
            else -> "Sir, could you specify the $param for $toolName?"
        }
    }
}
