package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolBackend
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import com.jarvis.assistant.androllm.permissions.Feature
import com.jarvis.assistant.androllm.permissions.PermissionManager
import com.jarvis.assistant.automation.notification.JarvisNotificationListener
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Reads recent notifications ("what just pinged?").
 *
 * Jarvis-first: AndroLLM shipped its own NotificationListenerService
 * (AndroNotificationListener). Jarvis already runs
 * [JarvisNotificationListener], so instead of a second listener (and a second
 * "Notification access" grant) this tool delegates to Jarvis'
 * read_notifications (androllm.tools.AndroLlmTools) over that listener. When
 * access is missing it raises a lazy request through [PermissionManager].
 */
class NotificationTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "read_notifications",
        description = "Read the most recent chat and call notifications, newest first.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max notifications to return (default 5)")
                }
            }
        },
        permission = ToolPermission.NOTIFICATIONS,
        category = ToolCategory.INFORMATION,
        requiresConfirmation = true,
        supportedBackends = setOf(ToolBackend.LOCAL, ToolBackend.CLOUD),
        availableOnDevice = true,
        worksLocally = true
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!JarvisNotificationListener.isNotificationServiceEnabled(context)) {
            return ToolResult.Failure(
                PermissionManager.getInstance(context).missingMessage(
                    Feature.NOTIFICATION_ACCESS, "Reading notifications needs notification access."
                ),
                retryable = false
            )
        }
        return JarvisToolBridge.runAndroLlmTool(context, "read_notifications", arguments)
    }
}
