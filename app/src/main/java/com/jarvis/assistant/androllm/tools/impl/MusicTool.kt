package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
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
 * Media playback control (play / pause / next / previous / toggle).
 *
 * Jarvis-first: delegates to Jarvis' control_music (androllm.tools
 * .AndroLlmTools), the earlier port of this AndroLLM tool: active media
 * session first, then global media-key dispatch — and, unlike the original,
 * it controls only the top session instead of every session at once and
 * adds a "toggle" action.
 */
class MusicTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "control_music",
        description = "Control the active music or media player: play, pause, toggle, next or previous track.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") {
                        listOf("play", "pause", "toggle", "next", "previous").forEach { add(it) }
                    }
                }
            }
            putJsonArray("required") { add("action") }
        },
        permission = ToolPermission.MUSIC,
        category = ToolCategory.MEDIA
    )

    override suspend fun execute(arguments: JsonObject): ToolResult =
        JarvisToolBridge.runAndroLlmTool(context, "control_music", arguments)
}
