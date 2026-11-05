package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import com.jarvis.assistant.JarvisApp
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
 * Toggles the camera flashlight ("turn on the torch").
 *
 * Jarvis-first: runs Jarvis' PhoneControlManager.setFlashlight (the same path
 * as Jarvis' set_flashlight; it prefers the back camera's torch and needs no
 * CAMERA permission for setTorchMode).
 */
class FlashlightTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "set_flashlight",
        description = "Turn the flashlight on or off.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("on") {
                    put("type", "boolean")
                    put("description", "true to turn on, false to turn off")
                }
            }
            putJsonArray("required") { add("on") }
        },
        permission = ToolPermission.FLASHLIGHT,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        // Accept Jarvis' "enabled" too (same tool name on the dispatcher side).
        val on = if (arguments.containsKey("on")) ToolArgs.bool(arguments, "on")
        else ToolArgs.bool(arguments, "enabled", true)
        val pcm = runCatching { (context.applicationContext as? JarvisApp)?.phoneControlManager }.getOrNull()
            ?: return ToolResult.Failure("Phone controls are not ready yet — try again in a moment.")
        val msg = withContext(Dispatchers.Main) { pcm.setFlashlight(on) }
        return if (msg.startsWith("Flashlight turned")) {
            ToolResult.Success(
                summary = msg,
                data = buildJsonObject {
                    put("on", on)
                    put("status", if (on) "on" else "off")
                }
            )
        } else {
            ToolResult.Failure(msg)
        }
    }
}
