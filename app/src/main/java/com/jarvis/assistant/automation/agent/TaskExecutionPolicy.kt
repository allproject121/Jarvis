package com.jarvis.assistant.automation.agent

import android.app.KeyguardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.util.Log
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.query.DeviceInfoQueryHelper

/**
 * Categorization of tasks and execution safety boundaries.
 */
enum class TaskCategory {
    SAFE_BACKGROUND_QUERY,
    REQUIRES_FOREGROUND_ACCESSIBILITY,
    CONSEQUENTIAL_EXTERNAL_ACTION,
    UNKNOWN
}

sealed class PolicyEvaluation {
    abstract val canExecute: Boolean
    open val reason: String? get() = null
    open val requiredAction: String? get() = null

    data class Allowed(val category: TaskCategory, val message: String = "Allowed") : PolicyEvaluation() {
        override val canExecute: Boolean = true
    }
    data class Blocked(
        override val reason: String,
        val targetState: UnifiedTaskState,
        override val requiredAction: String,
        val userPrompt: String
    ) : PolicyEvaluation() {
        override val canExecute: Boolean = false
    }
}

/**
 * Authoritative background execution policy and precondition validator.
 * Enforces Android background, accessibility, and locked-screen safety rules.
 */
object TaskExecutionPolicy {

    private const val TAG = "TaskExecutionPolicy"

    private val BACKGROUND_SAFE_TOOLS = setOf(
        "get_contact_count",
        "get_whatsapp_contact_count",
        "get_app_usage_stats",
        "get_telemetry",
        "set_flashlight",
        "set_volume",
        "set_brightness",
        "builtin_chrome_search",
        "get_iss_telemetry",
        "get_crypto_price",
        "get_live_weather_and_aqi",
        "get_task_status",
        "get_active_task",
        "get_failed_tasks",
        "cancel_active_task",
        "get_last_failure_reason",
        "resume_task"
    )

    private val FOREGROUND_UI_TOOLS = setOf(
        "click_ui_element",
        "type_text",
        "scroll_screen",
        "press_key",
        "open_app",
        "open_website",
        "search_in_chrome",
        "search_youtube",
        "search_and_play_youtube",
        "execute_connected_app_action",
        "inspect_screen"
    )

    private val NON_IDEMPOTENT_CONSEQUENTIAL_TOOLS = setOf(
        "send_whatsapp_message",
        "call_contact",
        "whatsapp_call"
    )

    fun categorizeTool(toolName: String): TaskCategory = when {
        toolName in BACKGROUND_SAFE_TOOLS -> TaskCategory.SAFE_BACKGROUND_QUERY
        toolName in NON_IDEMPOTENT_CONSEQUENTIAL_TOOLS -> TaskCategory.CONSEQUENTIAL_EXTERNAL_ACTION
        toolName in FOREGROUND_UI_TOOLS -> TaskCategory.REQUIRES_FOREGROUND_ACCESSIBILITY
        else -> TaskCategory.UNKNOWN
    }

    fun categorizeTask(taskOrTool: String): TaskCategory = categorizeTool(taskOrTool)

    fun isNonIdempotent(actionOrGoal: String): Boolean {
        val clean = actionOrGoal.trim().lowercase()
        if (clean in NON_IDEMPOTENT_CONSEQUENTIAL_TOOLS) return true
        val nonIdempotentKeywords = listOf(
            "send", "bhejo", "call", "dial", "lagao", "delete", "hatao", "remove",
            "unlike", "post", "submit", "pay"
        )
        return nonIdempotentKeywords.any { clean.contains(it) }
    }

    fun isScreenInteractive(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }

    fun isDeviceLocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return km.isKeyguardLocked
    }

    fun isAccessibilityActive(): Boolean {
        return JarvisAccessibilityService.instance != null
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Evaluates preconditions before attempting tool or UI execution.
     */
    fun evaluatePreconditions(context: Context, toolName: String): PolicyEvaluation {
        val category = categorizeTool(toolName)

        // 1. Check network connectivity for network-bound tasks
        val networkTools = setOf("search_in_chrome", "builtin_chrome_search", "get_crypto_price", "get_live_weather_and_aqi", "get_iss_telemetry")
        if (toolName in networkTools && !isNetworkAvailable(context)) {
            return PolicyEvaluation.Blocked(
                reason = "No active internet connection available.",
                targetState = UnifiedTaskState.FAILED,
                requiredAction = "CONNECT_INTERNET",
                userPrompt = "Internet connection is currently unavailable. Please check Wi-Fi or mobile data."
            )
        }

        // 2. Safe background queries require no screen state
        if (category == TaskCategory.SAFE_BACKGROUND_QUERY) {
            return PolicyEvaluation.Allowed(category)
        }

        // 3. UI automation preconditions
        if (category == TaskCategory.REQUIRES_FOREGROUND_ACCESSIBILITY) {
            if (isDeviceLocked(context)) {
                Log.w(TAG, "Blocked UI action '$toolName': device is locked.")
                return PolicyEvaluation.Blocked(
                    reason = "Device screen is locked. Android security prevents UI automation on locked devices.",
                    targetState = UnifiedTaskState.WAITING_FOR_USER,
                    requiredAction = "UNLOCK_DEVICE",
                    userPrompt = "Your phone is currently locked. Please unlock the screen so Jarvis can perform this action."
                )
            }

            if (!isScreenInteractive(context)) {
                Log.w(TAG, "Blocked UI action '$toolName': screen is not interactive (turned off).")
                return PolicyEvaluation.Blocked(
                    reason = "Device display is off. Screen must be interactive for UI automation.",
                    targetState = UnifiedTaskState.WAITING_FOR_USER,
                    requiredAction = "WAKE_SCREEN",
                    userPrompt = "Display is turned off. Please turn on your screen to proceed with UI automation."
                )
            }

            if (!isAccessibilityActive()) {
                Log.w(TAG, "Blocked UI action '$toolName': Jarvis Accessibility Service is disabled.")
                return PolicyEvaluation.Blocked(
                    reason = "Jarvis Accessibility Service is disabled.",
                    targetState = UnifiedTaskState.WAITING_FOR_PERMISSION,
                    requiredAction = "ENABLE_ACCESSIBILITY",
                    userPrompt = "Jarvis Accessibility Service is currently disabled. Please enable it in Android Settings > Accessibility."
                )
            }
        }

        // 4. Permissions check for specific tools
        if (toolName in setOf("get_contact_count", "get_whatsapp_contact_count", "call_contact", "send_whatsapp_message")) {
            if (!DeviceInfoQueryHelper.hasContactsPermission(context)) {
                return PolicyEvaluation.Blocked(
                    reason = "READ_CONTACTS permission is not granted.",
                    targetState = UnifiedTaskState.WAITING_FOR_PERMISSION,
                    requiredAction = "GRANT_CONTACTS_PERMISSION",
                    userPrompt = "Contacts permission is required. Please grant Contacts permission in Settings."
                )
            }
        }

        if (toolName == "get_app_usage_stats") {
            if (!DeviceInfoQueryHelper.hasUsageStatsPermission(context)) {
                return PolicyEvaluation.Blocked(
                    reason = "PACKAGE_USAGE_STATS permission is not granted.",
                    targetState = UnifiedTaskState.WAITING_FOR_PERMISSION,
                    requiredAction = "GRANT_USAGE_ACCESS",
                    userPrompt = "Usage Access permission is required. Please grant Usage Access for Jarvis in Settings."
                )
            }
        }

        return PolicyEvaluation.Allowed(category)
    }
}
