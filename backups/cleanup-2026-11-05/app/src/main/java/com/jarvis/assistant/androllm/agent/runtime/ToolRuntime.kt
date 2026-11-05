package com.jarvis.assistant.androllm.agent.runtime

import com.jarvis.assistant.androllm.agent.registry.ToolRegistry

/**
 * Status snapshot of the tool-calling runtime (mirrors AndroLLM's
 * core/runtime `RuntimeStatus`, which Jarvis does not have yet).
 */
data class ToolRuntimeStatus(
    val available: Boolean,
    val summary: String,
    val detail: String? = null
)

/**
 * The tool-calling runtime (planner, executor, registry and every built-in +
 * Jarvis + MCP tool), described for diagnostics screens. AndroLLM registers
 * this into its central RuntimeRegistry; Jarvis has no such registry, so the
 * Tool Debug screen and settings read it directly. Mirrors the shared
 * [ToolRegistry] — no duplication.
 */
class ToolRuntime(
    private val registry: ToolRegistry
) {

    val id = "tools"
    val displayName = "Tool Calling"
    val category = "TOOLS"
    val description = "The assistant's tool system: planner, executor, registry and built-in tools."

    suspend fun status(): ToolRuntimeStatus = runCatching {
        val count = registry.size()
        ToolRuntimeStatus(
            available = count > 0,
            summary = "$count tool(s) registered",
            detail = if (count > 0) null else "No tools registered — tool calling unavailable."
        )
    }.getOrElse { e ->
        ToolRuntimeStatus(false, "Status check failed", e.message ?: e.javaClass.simpleName)
    }
}
