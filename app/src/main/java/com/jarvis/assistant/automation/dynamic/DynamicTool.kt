package com.jarvis.assistant.automation.dynamic

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents an autonomous, dynamically created tool authored by Jarvis at runtime.
 */
data class DynamicTool(
    val name: String,
    val description: String,
    val parametersSchemaJson: String,
    val scriptType: ScriptType,
    val scriptCode: String,
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val author: String = "JARVIS_AI",
    val lastExecutionSuccess: Boolean = true,
    val failureCount: Int = 0,
    val lastError: String? = null
) {
    enum class ScriptType {
        JAVASCRIPT,
        SHELL,
        MACRO_JSON
    }

    fun toGeminiSchema(): JSONObject {
        val parameters = runCatching { JSONObject(parametersSchemaJson) }.getOrElse {
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        }

        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", parameters)
            })
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parametersSchemaJson", parametersSchemaJson)
            put("scriptType", scriptType.name)
            put("scriptCode", scriptCode)
            put("version", version)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
            put("author", author)
            put("lastExecutionSuccess", lastExecutionSuccess)
            put("failureCount", failureCount)
            put("lastError", lastError ?: JSONObject.NULL)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): DynamicTool {
            val typeStr = json.optString("scriptType", ScriptType.JAVASCRIPT.name)
            val scriptType = runCatching { ScriptType.valueOf(typeStr) }.getOrDefault(ScriptType.JAVASCRIPT)

            return DynamicTool(
                name = json.getString("name"),
                description = json.optString("description", "Dynamic tool authored by Jarvis"),
                parametersSchemaJson = json.optString("parametersSchemaJson", "{\"type\":\"object\",\"properties\":{}}"),
                scriptType = scriptType,
                scriptCode = json.optString("scriptCode", ""),
                version = json.optInt("version", 1),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                author = json.optString("author", "JARVIS_AI"),
                lastExecutionSuccess = json.optBoolean("lastExecutionSuccess", true),
                failureCount = json.optInt("failureCount", 0),
                lastError = if (json.isNull("lastError")) null else json.optString("lastError")
            )
        }
    }
}
