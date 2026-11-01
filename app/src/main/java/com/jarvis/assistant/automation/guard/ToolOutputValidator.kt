package com.jarvis.assistant.automation.guard

import com.jarvis.assistant.ai.provider.AiToolResult
import org.json.JSONObject

/**
 * Validates tool execution output before it is fed back into conversational model context.
 * Rejects empty responses, missing fields, or raw unformatted exceptions.
 */
object ToolOutputValidator {

    sealed interface OutputValidation {
        data object Valid : OutputValidation
        data class Invalid(val reason: String, val retryable: Boolean = true) : OutputValidation
    }

    fun validate(toolName: String, result: AiToolResult): OutputValidation {
        val resultJson = result.resultJson
        val message = resultJson.optString("message", "")

        if (!result.isSuccess) {
            val err = result.errorMessage ?: message.ifBlank { "Tool failed with unknown error" }
            return OutputValidation.Invalid(err, retryable = true)
        }

        if (resultJson.length() == 0) {
            return OutputValidation.Invalid("Tool '$toolName' returned empty JSON payload", retryable = true)
        }

        if (message.isBlank() && !resultJson.has("data") && !resultJson.has("success")) {
            return OutputValidation.Invalid("Tool '$toolName' produced no actionable message or data", retryable = true)
        }

        return OutputValidation.Valid
    }
}
