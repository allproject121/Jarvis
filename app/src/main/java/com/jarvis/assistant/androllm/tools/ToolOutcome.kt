package com.jarvis.assistant.androllm.tools

import org.json.JSONObject

/**
 * Result of an AndroLLM-ported tool, shaped for JarvisToolDispatcher:
 * [toJson] produces the `{"success", "message", ...data}` object the
 * dispatcher returns, and [success] maps onto AiToolResult.isSuccess.
 */
data class ToolOutcome(
    val success: Boolean,
    val message: String,
    val data: JSONObject? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        data?.let { d -> d.keys().forEach { k -> json.put(k, d.get(k)) } }
        json.put("success", success)
        json.put("message", message)
        return json
    }

    /** Copies this outcome's fields into an existing result object. */
    fun writeTo(target: JSONObject) {
        val json = toJson()
        json.keys().forEach { k -> target.put(k, json.get(k)) }
    }

    companion object {
        fun ok(message: String, data: JSONObject? = null) = ToolOutcome(true, message, data)
        fun fail(message: String, data: JSONObject? = null) = ToolOutcome(false, message, data)
    }
}
