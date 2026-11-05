package com.jarvis.assistant.automation.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * Working memory for multi-step agent actions.
 * Allows tools to pass outputs ("weather_result", "contact_phone", "extracted_text")
 * forward to subsequent tools within the same conversation turn.
 */
object AgentVariableStore {

    private val variables = ConcurrentHashMap<String, String>()

    fun beginTurn() {
        variables.clear()
    }

    fun set(key: String, value: String) {
        variables[key.trim()] = value.trim()
    }

    fun get(key: String): String? = variables[key.trim()]

    fun snapshot(): Map<String, String> = variables.toMap()

    fun clear() {
        variables.clear()
    }
}
