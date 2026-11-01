package com.jarvis.assistant.androllm.runtime

/**
 * One self-contained capability of the app — local GGUF inference, LiteRT,
 * cloud chat, voice, memory, tool calling, MCP… (ported from AndroLLM
 * `core/runtime/Runtime`).
 *
 * Independence contract: runtimes own their own lifecycle and internals. The
 * [RuntimeRegistry] only ever reads [status]; it never starts, stops or
 * configures a runtime, so a failure in one runtime can never disable another.
 */
interface Runtime {

    /** Stable machine id, e.g. `"gguf"`, `"cloud"`, `"voice"`. */
    val id: String

    /** Human name shown in the registry view. */
    val displayName: String

    val category: RuntimeCategory

    val description: String

    /**
     * Cheap, side-effect-free availability snapshot. Must never load a model,
     * open a microphone or touch the network.
     */
    suspend fun status(): RuntimeStatus
}

/**
 * Coarse bucket for every registered runtime (upstream values + Jarvis'
 * [MEMORY] and [ASSISTANT] buckets).
 */
enum class RuntimeCategory(val displayName: String) {
    LLM("Local LLM"),
    CLOUD("Cloud"),
    IMAGE("Image Generation"),
    VOICE("Voice Assistant"),
    AUTOMATION("Automation"),
    TOOLS("Tools"),
    MCP("MCP"),
    MEMORY("Memory"),
    ASSISTANT("Assistant")
}

/**
 * Availability snapshot of one runtime. A runtime that throws while reporting
 * is caught by the [RuntimeRegistry] and turned into a failed status.
 */
data class RuntimeStatus(
    val available: Boolean,
    val summary: String,
    val detail: String? = null,
    /** True when the runtime is an optional feature the user has switched off. */
    val disabledByUser: Boolean = false
)
