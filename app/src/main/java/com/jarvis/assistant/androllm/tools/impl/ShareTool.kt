package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import android.content.Intent
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Opens the Android share sheet with the given text ("share this answer").
 * Same behaviour as Jarvis' share_text.
 */
class ShareTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "share_text",
        description = "Open the share sheet with text so the user can send it to another app or person.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "The text to share")
                }
            }
            putJsonArray("required") { add("text") }
        },
        permission = ToolPermission.SHARE,
        category = ToolCategory.COMMUNICATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = ToolArgs.str(arguments, "text", "content")
            ?: return ToolResult.Failure("Missing required argument: text")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, "Share with…")
        val launched = ToolIntents.launch(context, chooser)
        if (!launched) {
            return ToolResult.Failure("No app could handle the share request.")
        }
        return ToolResult.Success(
            summary = "Share sheet opened.",
            data = buildJsonObject {
                put("status", "opened")
                put("length", text.length)
            }
        )
    }
}
