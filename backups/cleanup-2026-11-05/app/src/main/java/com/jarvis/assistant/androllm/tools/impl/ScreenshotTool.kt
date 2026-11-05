package com.jarvis.assistant.androllm.tools.impl

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import com.jarvis.assistant.androllm.permissions.Feature
import com.jarvis.assistant.androllm.permissions.PermissionManager
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Screen capture.
 *
 * AndroLLM's version only checked whether a MediaProjection session existed
 * and then reported "Screen captured" without capturing anything. Jarvis
 * already captures real screenshots through its accessibility service
 * (GLOBAL_ACTION_TAKE_SCREENSHOT, the same path as Jarvis' take_screenshot —
 * saved to the gallery by the system), so this tool uses that. Without the
 * service it raises a lazy request for Accessibility access.
 */
class ScreenshotTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "take_screenshot",
        description = "Capture the screen (saved to the gallery). Requires Jarvis' accessibility service.",
        parameters = buildJsonObject { put("type", "object") },
        permission = ToolPermission.SCREENSHOT,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult.Failure(
                PermissionManager.getInstance(context).missingMessage(
                    Feature.ACCESSIBILITY_AUTOMATION, "Taking a screenshot uses Jarvis' accessibility service."
                ),
                retryable = false
            )
        val ok = withContext(Dispatchers.Main) {
            runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }
                .getOrDefault(false)
        }
        return if (ok) {
            ToolResult.Success(
                summary = "Screenshot captured and saved to the gallery.",
                data = buildJsonObject {
                    put("status", "captured")
                    put("method", "accessibility")
                }
            )
        } else {
            ToolResult.Failure("The system refused the screenshot (secure screens such as banking apps block capture).")
        }
    }
}

/**
 * Holder for an active MediaProjection token (set by a consent flow). Kept
 * from AndroLLM so a future MediaProjection-based capture path can plug in
 * without touching the framework; [ScreenshotTool] does not need it.
 */
object MediaProjectionHolder {
    @Volatile var current: Any? = null
}
