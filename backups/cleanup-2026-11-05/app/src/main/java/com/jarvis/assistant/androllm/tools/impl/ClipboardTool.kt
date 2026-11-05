package com.jarvis.assistant.androllm.tools.impl

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Copies generated text to the system clipboard ("copy that answer"). Same
 * behaviour as Jarvis' copy_to_clipboard (clip label "Jarvis", main thread).
 */
class ClipboardTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "copy_to_clipboard",
        description = "Copy text to the clipboard. Use when the user asks to copy or save text.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "The text to copy")
                }
            }
            putJsonArray("required") { add("text") }
        },
        permission = ToolPermission.CLIPBOARD,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = ToolArgs.str(arguments, "text", "content")
            ?: return ToolResult.Failure("Missing required argument: text")
        val ok = withContext(Dispatchers.Main) {
            runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Jarvis", text))
                true
            }.getOrDefault(false)
        }
        if (!ok) return ToolResult.Failure("Could not access the clipboard.")
        return ToolResult.Success(
            summary = "Copied ${text.length} characters to the clipboard.",
            data = buildJsonObject {
                put("length", text.length)
                put("status", "copied")
            }
        )
    }
}
