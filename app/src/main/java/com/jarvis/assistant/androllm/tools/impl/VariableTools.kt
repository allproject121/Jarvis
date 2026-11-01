package com.jarvis.assistant.androllm.tools.impl

import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import com.jarvis.assistant.automation.agent.AgentVariableStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Writes a named workflow variable. Lets the model implement loops and
 * conditionals across rounds (e.g. counter++ in a FOR-EACH over search
 * results). Values live in Jarvis' shared [AgentVariableStore] — the same
 * working memory the agent ToolRunCoordinator / AgentContextBuilder read and
 * that is cleared at the start of every turn.
 */
class VariableSetTool(
    private val store: AgentVariableStore = AgentVariableStore
) : Tool {

    override val spec = ToolSpec(
        name = "variable_set",
        description = "Store a value under a name for the rest of this task (e.g. result of a previous step). Later steps can read it with variable_get. Use to chain tool outputs or implement loops.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("key") { put("type", "string") }
                putJsonObject("value") { put("type", "string") }
            }
            putJsonArray("required") { add("key"); add("value") }
        },
        // Internal workflow memory — no user-facing permission toggle.
        permission = null,
        category = ToolCategory.INFORMATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val key = ToolArgs.str(arguments, "key", "name")
            ?: return ToolResult.Failure("Missing required argument: key")
        val value = ToolArgs.str(arguments, "value") ?: ""
        store.set(key, value)
        return ToolResult.Success("Saved variable '$key'.")
    }
}

/**
 * Reads a named workflow variable (or the whole snapshot). Values were
 * written by [VariableSetTool] or by other tools earlier in this task.
 */
class VariableGetTool(
    private val store: AgentVariableStore = AgentVariableStore
) : Tool {

    override val spec = ToolSpec(
        name = "variable_get",
        description = "Read a value saved earlier in this task by variable_set or another tool (e.g. weather, search_results). With no key, returns every saved variable.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("key") { put("type", "string") }
            }
        },
        permission = null,
        category = ToolCategory.INFORMATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val key = ToolArgs.str(arguments, "key", "name")
        val all = store.snapshot()
        return if (key != null) {
            val value = store.get(key)
            if (value == null) {
                ToolResult.Success("Variable '$key' is not set.")
            } else {
                ToolResult.Success("$key: $value")
            }
        } else if (all.isEmpty()) {
            ToolResult.Success("No workflow variables are set yet.")
        } else {
            ToolResult.Success(all.entries.joinToString("; ") { "${it.key}=${it.value.take(120)}" })
        }
    }
}
