package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Opens the camera app. Launching full-screen apps from the background is
 * restricted on Android 10+; when the system blocks the start the tool says
 * so instead of crashing. (The camera app owns the camera — no CAMERA
 * permission is needed for this intent.)
 */
class CameraTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "open_camera",
        description = "Open the camera app.",
        parameters = buildJsonObject { put("type", "object") },
        permission = ToolPermission.CAMERA,
        category = ToolCategory.MEDIA
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val launched = ToolIntents.launch(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) ||
            ToolIntents.launch(context, Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        if (!launched) {
            return ToolResult.Failure(
                "Android blocked opening the camera from the background. Open the camera app manually."
            )
        }
        return ToolResult.Success(
            summary = "Opening the camera.",
            data = buildJsonObject { put("status", "opened") }
        )
    }
}
