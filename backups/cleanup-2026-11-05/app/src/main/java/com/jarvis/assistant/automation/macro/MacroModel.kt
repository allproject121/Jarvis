package com.jarvis.assistant.automation.macro

import com.google.gson.annotations.SerializedName

data class MacroCatalog(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("macros") val macros: List<Macro> = emptyList()
)

data class Macro(
    @SerializedName("name") val name: String,
    @SerializedName("intent") val intent: String,
    @SerializedName("params") val params: List<String> = emptyList(),
    @SerializedName("steps") val steps: List<MacroStep> = emptyList()
)

data class MacroStep(
    @SerializedName("tool") val tool: String,
    @SerializedName("args") val args: Map<String, Any?> = emptyMap(),
    @SerializedName("_why") val why: String? = null
) {
    val isOptional: Boolean
        get() = (args["_optional"] as? Boolean) == true
}

sealed class MacroExecutionResult {
    data class Success(val message: String, val output: String? = null) : MacroExecutionResult()
    data class Failure(val error: String, val failedStepIndex: Int) : MacroExecutionResult() {
        val reason: String get() = error
    }
}
