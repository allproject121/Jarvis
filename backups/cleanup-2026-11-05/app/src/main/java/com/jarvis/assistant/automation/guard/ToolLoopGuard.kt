package com.jarvis.assistant.automation.guard

import com.jarvis.assistant.ai.provider.AiToolResult
import com.jarvis.assistant.androllm.agent.core.ToolResult
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/**
 * Per-turn loop protection for tool execution. One instance is created per
 * user turn and shared across every planning/execution round of that turn —
 * Jarvis' tool dispatcher and the AndroLLM agent pipeline both use it — so a
 * confused LLM can never loop a tool indefinitely, drain the battery, or spam
 * hallucinated repetitive calls:
 *
 * - **Total cap** — at most [maxTotalCalls] (default 12) tool executions per user turn.
 * - **Consecutive cap** — at most [maxConsecutiveSameTool] (default 2) executions of the
 *   SAME tool back-to-back.
 * - **Dedupe** — a (name, arguments) pair that already ran this turn is blocked from
 *   running identically again.
 * - **Disable on failure** — a tool that fails twice in a row is disabled for the rest of
 *   the turn; an agent-pipeline [ToolResult.Failure] that is not retryable disables it
 *   immediately.
 *
 * Both argument flavours are accepted: Jarvis' org.json [JSONObject] with an
 * [AiToolResult], and the agent pipeline's kotlinx [JsonObject] with a [ToolResult].
 * When a call is rejected the caller injects [stopReason] into the model context so
 * the model continues reasoning WITHOUT further tool calls.
 */
class ToolLoopGuard(
    val maxTotalCalls: Int = 12,
    val maxConsecutiveSameTool: Int = 2,
    /** Off for Gemini Live, where the same screen step (e.g. scroll) is legitimately repeated. */
    val blockIdenticalRepeats: Boolean = true
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

    /** Tool names disabled mid-turn (for diagnostics / UI). */
    fun disabledTools(): Set<String> = disabled.toSet()

    /**
     * Checks if [name] may execute with [arguments] (Jarvis dispatcher flavour).
     * Returns false if the tool is disabled, capped, or already ran with identical parameters.
     */
    fun canExecute(name: String, arguments: JSONObject): Boolean = check(name, key(name, arguments))

    /**
     * Checks if [name] may execute with [arguments] (agent pipeline flavour).
     * Returns false if the tool is disabled, capped, or already ran with identical parameters.
     */
    fun canExecute(name: String, arguments: JsonObject): Boolean = check(name, key(name, arguments))

    /**
     * Records a Jarvis dispatcher execution so identical repeats or back-to-back
     * spam are blocked. A tool that fails twice is disabled for the turn.
     */
    fun record(name: String, arguments: JSONObject, result: AiToolResult) {
        recordKey(name, key(name, arguments), failed = !result.isSuccess, hardFailure = false)
    }

    /**
     * Records an agent pipeline execution. Call right after the tool ran
     * (success or failure). A non-retryable failure disables the tool at once;
     * two failures of the same tool disable it as well.
     */
    fun record(name: String, arguments: JsonObject, result: ToolResult) {
        recordKey(
            name,
            key(name, arguments),
            failed = result is ToolResult.Failure,
            hardFailure = result is ToolResult.Failure && !result.retryable
        )
    }

    /** Explicitly disables a tool for the remainder of the turn. */
    fun disable(name: String) {
        disabled += name
    }

    /**
     * Message to inject into model context when a tool call is blocked, or
     * null when the guard is not currently blocking. Only reports the FIRST
     * blocking condition so the injected message stays concise.
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

    // ── Shared core ───────────────────────────────────────────────────────

    private fun check(name: String, key: String): Boolean {
        val ok = when {
            name in disabled -> false
            totalCalls >= maxTotalCalls -> false
            (consecutiveByTool[name] ?: 0) >= maxConsecutiveSameTool -> false
            blockIdenticalRepeats && key in executedKeys -> false
            else -> true
        }
        if (!ok) blockedThisTurn = true
        return ok
    }

    private fun recordKey(name: String, key: String, failed: Boolean, hardFailure: Boolean) {
        totalCalls++
        executedKeys += key

        if (name == lastTool) {
            consecutiveByTool[name] = (consecutiveByTool[name] ?: 0) + 1
        } else {
            // A different tool broke the previous tool's run — reset its
            // streak so the cap only applies to true back-to-back repeats.
            lastTool?.let { consecutiveByTool[it] = 0 }
            consecutiveByTool[name] = 1
        }
        lastTool = name

        if (failed) {
            val count = (failuresByTool[name] ?: 0) + 1
            failuresByTool[name] = count
            if (hardFailure || count >= 2) {
                disable(name)
            }
        }
    }

    private fun key(name: String, arguments: JSONObject): String =
        "$name|${arguments.toString().replace(" ", "")}"

    private fun key(name: String, arguments: JsonObject): String =
        "$name|${arguments.toString().replace(" ", "")}"
}
