package com.jarvis.assistant.automation.guard

import com.jarvis.assistant.ai.provider.AiToolResult
import org.json.JSONObject

/**
 * Per-turn loop protection for tool execution.
 * Prevents runaway agent loops, excessive device battery drain, and hallucinated repetitive tool calls:
 *
 * - Total cap: at most [maxTotalCalls] (default 12) tool executions per user turn.
 * - Consecutive cap: at most [maxConsecutiveSameTool] (default 2) executions of the SAME tool back-to-back.
 * - Deduplication: a (name, arguments) pair that already ran this turn is blocked from running identically again.
 * - Disable on failure: a tool that fails twice in a row is disabled for the rest of the turn.
 */
class ToolLoopGuard(
    val maxTotalCalls: Int = 12,
    val maxConsecutiveSameTool: Int = 2
) {

    private val executedKeys = mutableSetOf<String>()
    private val consecutiveByTool = mutableMapOf<String, Int>()
    private val failuresByTool = mutableMapOf<String, Int>()
    private val disabled = mutableSetOf<String>()
    private var lastTool: String? = null

    /** Total tool executions recorded this turn. */
    var totalCalls: Int = 0
        private set

    /** True when at least one call was rejected by guard checks this turn. */
    var blockedThisTurn: Boolean = false
        private set

    fun isDisabled(name: String): Boolean = name in disabled

    fun disabledTools(): Set<String> = disabled.toSet()

    /**
     * Checks if [name] may execute with [arguments].
     * Returns false if the tool is disabled, capped, or already ran with identical parameters.
     */
    fun canExecute(name: String, arguments: JSONObject): Boolean {
        val ok = when {
            name in disabled -> false
            totalCalls >= maxTotalCalls -> false
            (consecutiveByTool[name] ?: 0) >= maxConsecutiveSameTool -> false
            key(name, arguments) in executedKeys -> false
            else -> true
        }
        if (!ok) blockedThisTurn = true
        return ok
    }

    /**
     * Records an execution so identical repeats or back-to-back spam are blocked.
     */
    fun record(name: String, arguments: JSONObject, result: AiToolResult) {
        totalCalls++
        val k = key(name, arguments)
        executedKeys += k

        if (name == lastTool) {
            consecutiveByTool[name] = (consecutiveByTool[name] ?: 0) + 1
        } else {
            lastTool?.let { consecutiveByTool[it] = 0 }
            consecutiveByTool[name] = 1
        }
        lastTool = name

        if (!result.isSuccess) {
            val count = (failuresByTool[name] ?: 0) + 1
            failuresByTool[name] = count
            if (count >= 2) {
                disable(name)
            }
        }
    }

    fun disable(name: String) {
        disabled += name
    }

    /**
     * Message to inject into model context when a tool call is blocked.
     */
    fun stopReason(): String? {
        if (totalCalls >= maxTotalCalls) {
            return "Tool execution limit reached ($maxTotalCalls calls). Continue reasoning and respond to the user without further tool calls."
        }
        val blockedTool = consecutiveByTool.entries
            .firstOrNull { it.value >= maxConsecutiveSameTool }
            ?.key
        if (blockedTool != null) {
            return "The tool '$blockedTool' has already been used $maxConsecutiveSameTool times consecutively. Continue reasoning and respond to the user without calling it again."
        }
        return null
    }

    private fun key(name: String, arguments: JSONObject): String =
        "$name|${arguments.toString().replace(" ", "")}"
}
