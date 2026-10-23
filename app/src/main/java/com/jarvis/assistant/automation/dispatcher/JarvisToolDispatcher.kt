package com.jarvis.assistant.automation.dispatcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.provider.AiToolCall
import com.jarvis.assistant.ai.provider.AiToolResult
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.util.ContactCaller
import com.jarvis.assistant.util.DeviceTelemetryHelper
import com.jarvis.assistant.util.WhatsAppMessenger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unified Tool Dispatcher for Jarvis.
 * Decouples tool execution from Gemini WebSocket so that:
 *  1. Online Gemini Live
 *  2. On-Device Local LLM (llama.cpp)
 *  3. Hermes Autonomous Agent
 *  4. Workstation Laptop Bridge
 * can all execute device controls and automation through a single, shared, thread-safe dispatcher.
 */
class JarvisToolDispatcher private constructor(private val context: Context) {

    companion object {
        private const val TAG = "JarvisToolDispatcher"

        @Volatile
        private var instance: JarvisToolDispatcher? = null

        fun getInstance(context: Context): JarvisToolDispatcher {
            return instance ?: synchronized(this) {
                instance ?: JarvisToolDispatcher(context.applicationContext).also { instance = it }
            }
        }
    }

    private val executionMutex = Mutex()
    private val idempotencyCache = mutableMapOf<String, AiToolResult>()
    private var lastTurnId: String = ""

    /**
     * Resets idempotency cache when a new user turn starts.
     */
    fun startNewTurn(turnId: String) {
        synchronized(idempotencyCache) {
            lastTurnId = turnId
            idempotencyCache.clear()
        }
    }

    /**
     * Dispatches and executes an AI Tool Call safely across threads.
     */
    suspend fun dispatchTool(toolCall: AiToolCall): AiToolResult = executionMutex.withLock {
        withContext(Dispatchers.IO) {
            val callId = toolCall.callId
            val name = toolCall.functionName
            val args = toolCall.arguments

            // Idempotency check: Prevent duplicate side-effects (e.g. sending SMS/WhatsApp or Calling twice)
            val nonIdempotentTools = setOf("send_whatsapp_message", "call_contact", "whatsapp_call")
            val actionKey = "$lastTurnId:$name:${args.toString().hashCode()}"
            if (name in nonIdempotentTools) {
                synchronized(idempotencyCache) {
                    val cached = idempotencyCache[actionKey]
                    if (cached != null) {
                        Log.w(TAG, "Replaying cached result for non-idempotent tool '$name' (idempotency guard)")
                        return@withContext cached
                    }
                }
            }

            Log.i(TAG, "Executing tool '$name' (callId=$callId) with args: $args")
            val app = context.applicationContext as? JarvisApp

            // ── 0. MODULAR SKILL / PLUGIN ENGINE DISPATCH ─────────────────────────
            try {
                val skillRegistry = com.jarvis.assistant.automation.skills.SkillRegistry.getInstance(context)
                val registeredSkill = skillRegistry.findSkillForTool(name)
                if (registeredSkill != null) {
                    Log.i(TAG, "Dispatching tool '$name' to modular skill '${registeredSkill.metadata.name}' (${registeredSkill.metadata.id})")
                    val skillCtx = com.jarvis.assistant.automation.skills.SkillExecutionContext(
                        context = context,
                        toolCallId = callId,
                        toolName = name,
                        arguments = args,
                        isForeground = true
                    )
                    val skillResult = skillRegistry.executeTool(skillCtx)
                    val finalResult = AiToolResult(
                        callId = callId,
                        functionName = name,
                        isSuccess = skillResult.isSuccess,
                        resultJson = skillResult.toResultJsonObject(),
                        errorMessage = skillResult.errorMessage
                    )
                    if (name in nonIdempotentTools) {
                        synchronized(idempotencyCache) {
                            idempotencyCache[actionKey] = finalResult
                        }
                    }
                    return@withContext finalResult
                }
            } catch (e: Exception) {
                Log.w(TAG, "Skill dispatch evaluation error for '$name': ${e.message}")
            }

            val resultJson = JSONObject()
            var isSuccess = true
            var errorMessage: String? = null

            try {
                when (name) {
                    // ── 1. DEVICE HARDWARE CONTROLS ────────────────────────────
                    "set_flashlight" -> {
                        val enabled = args.optBoolean("enabled", true)
                        val msg = withContext(Dispatchers.Main) {
                            app?.phoneControlManager?.setFlashlight(enabled)
                                ?: if (enabled) "Flashlight turned on." else "Flashlight turned off."
                        }
                        resultJson.put("success", true)
                        resultJson.put("message", msg)
                        resultJson.put("flashlight_enabled", enabled)
                    }

                    "set_volume" -> {
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        if (audioManager != null) {
                            val level = args.optInt("level", -1)
                            val action = args.optString("action", "")
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

                            withContext(Dispatchers.Main) {
                                when {
                                    level in 0..100 -> {
                                        val target = (level.toFloat() / 100f * maxVol).toInt()
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                                        resultJson.put("message", "Volume set to $level%")
                                    }
                                    action == "up" -> {
                                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                                        resultJson.put("message", "Volume increased")
                                    }
                                    action == "down" -> {
                                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                                        resultJson.put("message", "Volume decreased")
                                    }
                                    action == "mute" -> {
                                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                                        resultJson.put("message", "Volume muted")
                                    }
                                    else -> {
                                        val curr = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                        val pct = (curr.toFloat() / maxVol.coerceAtLeast(1) * 100).toInt()
                                        resultJson.put("message", "Current volume is $pct%")
                                    }
                                }
                            }
                            resultJson.put("success", true)
                        } else {
                            isSuccess = false
                            errorMessage = "AudioManager unavailable"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        }
                    }

                    "get_telemetry" -> {
                        val telem = DeviceTelemetryHelper.getTelemetry(context)
                        resultJson.put("success", true)
                        resultJson.put("battery_percent", telem.batteryPercent)
                        resultJson.put("is_charging", telem.isCharging)
                        resultJson.put("network_type", telem.networkType)
                        resultJson.put("message", "Battery: ${telem.batteryPercent}%, Charging: ${telem.isCharging}, Network: ${telem.networkType}")
                    }

                    // ── 2. APP LAUNCHING & WEB NAVIGATION ──────────────────────
                    "open_app" -> {
                        val appName = args.optString("app_name", "").ifBlank { args.optString("name", "") }
                        val actionType = args.optString("action_type", "")
                        val query = args.optString("query", "").ifBlank { args.optString("target_query", "") }

                        if (appName.isNotBlank()) {
                            if (actionType.isNotBlank() || query.isNotBlank()) {
                                val executor = com.jarvis.assistant.connectors.UniversalAppExecutor.getInstance(context)
                                val execResult = executor.executeAppGoal(appName, actionType.ifBlank { "search" }, query, "Open $appName and perform action")
                                isSuccess = execResult.success
                                errorMessage = if (!execResult.success) execResult.message else null
                                resultJson.put("success", execResult.success)
                                resultJson.put("message", execResult.message)
                                resultJson.put("action_taken", execResult.actionTaken)
                                if (execResult.screenSummary != null) {
                                    resultJson.put("screen_summary", execResult.screenSummary)
                                }
                            } else {
                                val opened = withContext(Dispatchers.Main) {
                                    app?.phoneControlManager?.openApp(appName)
                                }
                                val success = opened != null
                                isSuccess = success
                                resultJson.put("success", success)
                                resultJson.put("opened", success)
                                resultJson.put("message", if (success) "Opened $appName. (Note: Application opened in foreground. If you need to perform an internal task inside $appName, please specify the action)." else "Failed to find or open $appName.")
                            }
                        } else {
                            isSuccess = false
                            errorMessage = "Missing app_name argument"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        }
                    }

                    "execute_connected_app_action" -> {
                        val appName = args.optString("app_name", "").ifBlank { args.optString("app", "") }
                        val actionType = args.optString("action_type", "search").ifBlank { "search" }
                        val targetQuery = args.optString("target_query", "").ifBlank { args.optString("query", "") }
                        val userGoal = args.optString("user_goal", "").ifBlank { "$actionType $targetQuery in $appName" }

                        if (appName.isNotBlank()) {
                            val executor = com.jarvis.assistant.connectors.UniversalAppExecutor.getInstance(context)
                            val execResult = executor.executeAppGoal(appName, actionType, targetQuery, userGoal)

                            resultJson.put("success", execResult.success)
                            resultJson.put("message", execResult.message)
                            resultJson.put("action_taken", execResult.actionTaken)
                            if (execResult.screenSummary != null) {
                                resultJson.put("screen_summary", execResult.screenSummary)
                            }
                        } else {
                            isSuccess = false
                            errorMessage = "Missing app_name parameter"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        }
                    }

                    "open_website" -> {
                        val url = args.optString("url", "").ifBlank { args.optString("target_url", "") }
                        if (url.isNotBlank()) {
                            val launched = withContext(Dispatchers.Main) {
                                app?.phoneControlManager?.openUrlsInChromeTabs(listOf(url)) ?: false
                            }
                            resultJson.put("success", launched)
                            resultJson.put("message", if (launched) "Opened $url in browser." else "Failed to open $url.")
                        } else {
                            isSuccess = false
                            errorMessage = "Missing url parameter"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        }
                    }

                    "search_in_chrome" -> {
                        val query = args.optString("query", "")
                        if (query.isNotBlank()) {
                            val targetUrl = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
                            val launched = withContext(Dispatchers.Main) {
                                app?.phoneControlManager?.openUrlsInChromeTabs(listOf(targetUrl)) ?: false
                            }
                            resultJson.put("success", launched)
                            resultJson.put("message", "Searching Google Chrome for '$query'")
                        } else {
                            isSuccess = false
                            errorMessage = "Missing search query"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        }
                    }

                    // ── 3. MEDIA & ENTERTAINMENT ───────────────────────────────
                    "search_youtube" -> {
                        val query = args.optString("query", "")
                        if (query.isNotBlank()) {
                            val launched = withContext(Dispatchers.Main) {
                                app?.phoneControlManager?.openApp("youtube") ?: false
                            }
                            resultJson.put("success", launched != null)
                            resultJson.put("message", "Searching YouTube for '$query'")
                        } else {
                            isSuccess = false
                            errorMessage = "Missing YouTube query"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        }
                    }

                    // ── 4. MESSAGING & CALLING ─────────────────────────────────
                    "send_whatsapp_message" -> {
                        val recipient = args.optString("recipient", "")
                            .ifBlank { args.optString("recipient_name", "") }
                            .ifBlank { args.optString("contact", "") }
                        val message = args.optString("message", "")
                        val appNumber = if (args.has("app_number")) args.optInt("app_number", 1) else null
                        val confirmed = args.optBoolean("confirmed", false)

                        if (recipient.isNotBlank() && message.isNotBlank()) {
                            val sendResult = withContext(Dispatchers.Main) {
                                WhatsAppMessenger.sendMessage(context, recipient, message, appNumber, confirmed)
                            }
                            when (sendResult) {
                                is WhatsAppMessenger.SendResult.Success -> {
                                    resultJson.put("success", true)
                                    resultJson.put("sent", true)
                                    resultJson.put("send_attempted", sendResult.sendAttempted)
                                    resultJson.put("delivery_verified", sendResult.deliveryVerified)
                                    resultJson.put("message", "WhatsApp send initiated for ${sendResult.contactName} (${sendResult.phoneNumber}) via ${sendResult.appName}. Note: Message delivery is unverified; check WhatsApp to confirm.")
                                }
                                is WhatsAppMessenger.SendResult.RequiresConfirmation -> {
                                    resultJson.put("success", false)
                                    resultJson.put("requires_confirmation", true)
                                    resultJson.put("draft_prepared", true)
                                    resultJson.put("recipient", sendResult.contactName)
                                    resultJson.put("message", "Draft ready for ${sendResult.contactName} (${sendResult.phoneNumber}): \"${sendResult.message}\". Please confirm before sending.")
                                }
                                is WhatsAppMessenger.SendResult.MultipleContactsFound -> {
                                    resultJson.put("success", false)
                                    resultJson.put("multiple_contacts", true)
                                    resultJson.put("matches", JSONArray(sendResult.matches))
                                    resultJson.put("message", "Multiple contacts matched '${sendResult.name}': ${sendResult.matches.joinToString()}. Which contact would you like to message?")
                                }
                                is WhatsAppMessenger.SendResult.ContactNotFound -> {
                                    resultJson.put("success", false)
                                    resultJson.put("message", "Could not find contact '${sendResult.name}' in phone contacts. Please verify the name or specify a phone number.")
                                }
                                is WhatsAppMessenger.SendResult.MissingPermission -> {
                                    resultJson.put("success", false)
                                    resultJson.put("permission_denied", true)
                                    resultJson.put("message", "Contacts permission is required to find contacts for WhatsApp. Please grant Contacts permission in Settings.")
                                }
                                is WhatsAppMessenger.SendResult.MultipleAppsFound -> {
                                    resultJson.put("success", false)
                                    resultJson.put("message", "Multiple WhatsApp installations found (${sendResult.count} apps). Please specify WhatsApp 1 or WhatsApp 2.")
                                }
                                is WhatsAppMessenger.SendResult.Error -> {
                                    resultJson.put("success", false)
                                    resultJson.put("message", sendResult.reason)
                                }
                            }
                            isSuccess = resultJson.optBoolean("success", false)
                            if (!isSuccess) errorMessage = resultJson.optString("message")
                        } else {
                            isSuccess = false
                            errorMessage = "Missing recipient or message argument"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        }
                    }

                    "call_contact" -> {
                        val nameArg = args.optString("name", "").ifBlank { args.optString("contact", "") }
                        if (nameArg.isNotBlank()) {
                            val callResult = withContext(Dispatchers.Main) {
                                ContactCaller.callContact(context, nameArg)
                            }
                            val success = callResult is ContactCaller.CallResult.Success
                            resultJson.put("success", success)
                            resultJson.put("message", "Call status: $callResult")
                        } else {
                            isSuccess = false
                            errorMessage = "Missing contact name"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        }
                    }

                    // ── 5. ACCESSIBILITY UI AUTOMATION ─────────────────────────
                    "click_ui_element" -> {
                        val precondition = com.jarvis.assistant.automation.agent.TaskExecutionPolicy.evaluatePreconditions(context, name)
                        if (!precondition.canExecute) {
                            if (precondition.requiredAction == "UNLOCK_DEVICE") {
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.setState(com.jarvis.assistant.automation.agent.AgentTaskEngine.State.WAITING_FOR_USER)
                            } else if (precondition.requiredAction == "ENABLE_ACCESSIBILITY") {
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.setState(com.jarvis.assistant.automation.agent.AgentTaskEngine.State.WAITING_FOR_PERMISSION)
                            }
                            isSuccess = false
                            errorMessage = precondition.reason
                            resultJson.put("success", false)
                            resultJson.put("precondition_failed", true)
                            resultJson.put("required_action", precondition.requiredAction)
                            resultJson.put("message", precondition.reason)
                        } else {
                            val a11y = JarvisAccessibilityService.instance
                            if (a11y != null) {
                                val text = args.optString("text", "")
                                val x = args.optInt("x", -1)
                                val y = args.optInt("y", -1)

                                val clicked = when {
                                    text.isNotBlank() -> a11y.clickNodeWithText(text)
                                    x >= 0 && y >= 0 -> a11y.clickAt(x.toFloat(), y.toFloat())
                                    else -> false
                                }
                                resultJson.put("success", clicked)
                                resultJson.put("message", if (clicked) "Clicked UI element" else "UI element not found")
                            } else {
                                isSuccess = false
                                errorMessage = "Accessibility service is disabled. Please enable Jarvis Accessibility."
                                resultJson.put("success", false)
                                resultJson.put("message", errorMessage)
                            }
                        }
                    }

                    "type_text" -> {
                        val precondition = com.jarvis.assistant.automation.agent.TaskExecutionPolicy.evaluatePreconditions(context, name)
                        if (!precondition.canExecute) {
                            if (precondition.requiredAction == "UNLOCK_DEVICE") {
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.setState(com.jarvis.assistant.automation.agent.AgentTaskEngine.State.WAITING_FOR_USER)
                            } else if (precondition.requiredAction == "ENABLE_ACCESSIBILITY") {
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.setState(com.jarvis.assistant.automation.agent.AgentTaskEngine.State.WAITING_FOR_PERMISSION)
                            }
                            isSuccess = false
                            errorMessage = precondition.reason
                            resultJson.put("success", false)
                            resultJson.put("precondition_failed", true)
                            resultJson.put("required_action", precondition.requiredAction)
                            resultJson.put("message", precondition.reason)
                        } else {
                            val a11y = JarvisAccessibilityService.instance
                            val text = args.optString("text", "")
                            if (a11y != null && text.isNotBlank()) {
                                val typed = a11y.typeText(text)
                                resultJson.put("success", typed)
                                resultJson.put("message", if (typed) "Typed text: $text" else "No input field in focus")
                            } else {
                                isSuccess = false
                                errorMessage = if (a11y == null) "Accessibility disabled" else "Missing text argument"
                                resultJson.put("success", false)
                                resultJson.put("message", errorMessage)
                            }
                        }
                    }

                    "scroll_screen" -> {
                        val precondition = com.jarvis.assistant.automation.agent.TaskExecutionPolicy.evaluatePreconditions(context, name)
                        if (!precondition.canExecute) {
                            if (precondition.requiredAction == "UNLOCK_DEVICE") {
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.setState(com.jarvis.assistant.automation.agent.AgentTaskEngine.State.WAITING_FOR_USER)
                            } else if (precondition.requiredAction == "ENABLE_ACCESSIBILITY") {
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.setState(com.jarvis.assistant.automation.agent.AgentTaskEngine.State.WAITING_FOR_PERMISSION)
                            }
                            isSuccess = false
                            errorMessage = precondition.reason
                            resultJson.put("success", false)
                            resultJson.put("precondition_failed", true)
                            resultJson.put("required_action", precondition.requiredAction)
                            resultJson.put("message", precondition.reason)
                        } else {
                            val a11y = JarvisAccessibilityService.instance
                            val direction = args.optString("direction", "down")
                            if (a11y != null) {
                                val scrolled = a11y.performSmartScroll(direction)
                                resultJson.put("success", scrolled)
                                resultJson.put("message", "Scrolled $direction")
                            } else {
                                isSuccess = false
                                errorMessage = "Accessibility service disabled"
                                resultJson.put("success", false)
                                resultJson.put("message", errorMessage)
                            }
                        }
                    }

                    "press_key" -> {
                        val a11y = JarvisAccessibilityService.instance
                        val key = args.optString("key", "back")
                        if (a11y != null) {
                            val actionCode = when (key.lowercase(java.util.Locale.ROOT)) {
                                "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                                else -> AccessibilityService.GLOBAL_ACTION_BACK
                            }
                            a11y.performGlobalAction(actionCode)
                            resultJson.put("success", true)
                            resultJson.put("message", "Pressed $key")
                        } else {
                            isSuccess = false
                            errorMessage = "Accessibility service disabled"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        }
                    }

                    // ── 6. SURVEILLANCE & GODS EYE MCP ─────────────────────────
                    "gods_eye_select_camera" -> {
                        val cameraResult = com.jarvis.assistant.ui.godseye.GodsEyeMcpClient.executeTool(name, args)
                        resultJson.put("success", true)
                        resultJson.put("data", cameraResult)
                        resultJson.put("message", "Gods Eye camera selection executed.")
                    }

                    // ── 7. PRACTICAL DEVICE INFORMATION QUERIES ───────────────
                    "get_contact_count" -> {
                        val countResult = com.jarvis.assistant.automation.query.DeviceInfoQueryHelper.getPhoneContactsCount(context)
                        when (countResult) {
                            is com.jarvis.assistant.automation.query.DeviceInfoQueryHelper.ContactCountResult.Success -> {
                                resultJson.put("success", true)
                                resultJson.put("count", countResult.count)
                                resultJson.put("message", countResult.message)
                            }
                            is com.jarvis.assistant.automation.query.DeviceInfoQueryHelper.ContactCountResult.PermissionDenied -> {
                                isSuccess = false
                                errorMessage = "Contacts permission is required to count contacts. Please grant Contacts permission in Settings."
                                resultJson.put("success", false)
                                resultJson.put("permission_denied", true)
                                resultJson.put("message", errorMessage)
                            }
                            is com.jarvis.assistant.automation.query.DeviceInfoQueryHelper.ContactCountResult.Error -> {
                                isSuccess = false
                                errorMessage = "Error querying contacts: ${countResult.reason}"
                                resultJson.put("success", false)
                                resultJson.put("message", errorMessage)
                            }
                        }
                    }

                    "get_whatsapp_contact_count" -> {
                        val waResult = com.jarvis.assistant.automation.query.DeviceInfoQueryHelper.getWhatsAppContactsCount(context)
                        when (waResult) {
                            is com.jarvis.assistant.automation.query.DeviceInfoQueryHelper.WhatsAppContactCountResult.Success -> {
                                resultJson.put("success", true)
                                resultJson.put("count", waResult.count)
                                resultJson.put("message", waResult.explanation)
                            }
                            is com.jarvis.assistant.automation.query.DeviceInfoQueryHelper.WhatsAppContactCountResult.PermissionDenied -> {
                                isSuccess = false
                                errorMessage = "Contacts permission is required to query WhatsApp contacts. Please grant Contacts permission in Settings."
                                resultJson.put("success", false)
                                resultJson.put("permission_denied", true)
                                resultJson.put("message", errorMessage)
                            }
                            is com.jarvis.assistant.automation.query.DeviceInfoQueryHelper.WhatsAppContactCountResult.Error -> {
                                isSuccess = false
                                errorMessage = "Error querying WhatsApp contacts: ${waResult.reason}"
                                resultJson.put("success", false)
                                resultJson.put("message", errorMessage)
                            }
                        }
                    }

                    "get_app_usage_stats" -> {
                        val appName = args.optString("app_name", "whatsapp")
                            .ifBlank { args.optString("app", "whatsapp") }
                        val usageResult = com.jarvis.assistant.automation.query.DeviceInfoQueryHelper.getAppUsageDuration(context, appName)
                        when (usageResult) {
                            is com.jarvis.assistant.automation.query.DeviceInfoQueryHelper.AppUsageResult.Success -> {
                                resultJson.put("success", true)
                                resultJson.put("app_name", usageResult.appName)
                                resultJson.put("package_name", usageResult.packageName)
                                resultJson.put("duration_ms", usageResult.durationMs)
                                resultJson.put("formatted_duration", usageResult.formattedDuration)
                                resultJson.put("message", usageResult.summary)
                            }
                            is com.jarvis.assistant.automation.query.DeviceInfoQueryHelper.AppUsageResult.PermissionDenied -> {
                                isSuccess = false
                                errorMessage = usageResult.message
                                resultJson.put("success", false)
                                resultJson.put("permission_denied", true)
                                resultJson.put("settings_action", usageResult.settingsAction)
                                resultJson.put("message", errorMessage)
                            }
                            is com.jarvis.assistant.automation.query.DeviceInfoQueryHelper.AppUsageResult.AppNotFound -> {
                                isSuccess = false
                                errorMessage = usageResult.message
                                resultJson.put("success", false)
                                resultJson.put("message", errorMessage)
                            }
                            is com.jarvis.assistant.automation.query.DeviceInfoQueryHelper.AppUsageResult.Error -> {
                                isSuccess = false
                                errorMessage = usageResult.reason
                                resultJson.put("success", false)
                                resultJson.put("message", errorMessage)
                            }
                        }
                    }

                    // ── 8. TASK LIFECYCLE & MONITORING ────────────────────────
                    "task_completed" -> {
                        val summary = args.optString("summary", "Task completed.")
                        resultJson.put("success", true)
                        resultJson.put("message", summary)
                    }

                    "get_task_status" -> {
                        val taskId = args.optString("task_id", "").takeIf { it.isNotBlank() }
                        val summary = com.jarvis.assistant.automation.agent.AgentTaskEngine.getTaskStatusSummary(taskId)
                        resultJson.put("success", true)
                        resultJson.put("message", summary)
                    }

                    "get_active_task" -> {
                        val summary = com.jarvis.assistant.automation.agent.AgentTaskEngine.getActiveTaskSummary()
                        resultJson.put("success", true)
                        resultJson.put("message", summary)
                    }

                    "get_failed_tasks" -> {
                        val limit = args.optInt("limit", 5)
                        val summary = com.jarvis.assistant.automation.agent.AgentTaskEngine.getFailedTasksSummary(limit)
                        resultJson.put("success", true)
                        resultJson.put("message", summary)
                    }

                    "cancel_active_task" -> {
                        val reason = args.optString("reason", "User requested cancellation")
                        val summary = com.jarvis.assistant.automation.agent.AgentTaskEngine.cancelActiveTask(reason)
                        resultJson.put("success", true)
                        resultJson.put("message", summary)
                    }

                    "get_last_failure_reason" -> {
                        val summary = com.jarvis.assistant.automation.agent.AgentTaskEngine.getLastFailureReasonSummary()
                        resultJson.put("success", true)
                        resultJson.put("message", summary)
                    }

                    "resume_task" -> {
                        val taskId = args.optString("task_id", "").takeIf { it.isNotBlank() }
                        val summary = com.jarvis.assistant.automation.agent.AgentTaskEngine.resumeActiveTask(taskId)
                        val isBlocked = summary.contains("blocked", true) || summary.contains("cannot", true)
                        resultJson.put("success", !isBlocked)
                        resultJson.put("message", summary)
                    }

                    "control_smart_home" -> {
                        val target = args.optString("target", args.optString("device", ""))
                        val action = args.optString("action", args.optString("command", "toggle"))
                        val entityId = args.optString("entity_id", "")
                        val delaySec = args.optInt("delay_sec", 0)
                        val intervalSec = args.optInt("interval_sec", 0)
                        val smartHome = app?.smartHomeManager ?: com.jarvis.assistant.automation.smarthome.SmartHomeManager(context)
                        val response = smartHome.controlDevice(
                            target = if (entityId.isNotBlank()) entityId else target,
                            action = action,
                            delaySec = delaySec,
                            intervalSec = intervalSec
                        )
                        val isOk = !response.contains("Error", ignoreCase = true) && !response.contains("Failed", ignoreCase = true)
                        resultJson.put("success", isOk)
                        resultJson.put("message", response)
                    }

                    "get_smart_home_devices" -> {
                        val smartHome = app?.smartHomeManager ?: com.jarvis.assistant.automation.smarthome.SmartHomeManager(context)
                        val response = smartHome.getDeviceStates()
                        resultJson.put("success", true)
                        resultJson.put("message", response)
                    }

                    else -> {
                        isSuccess = false
                        errorMessage = "Unsupported tool action: '$name'"
                        resultJson.put("success", false)
                        resultJson.put("message", errorMessage)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception executing tool '$name': ${e.message}", e)
                isSuccess = false
                errorMessage = e.message ?: "Execution error"
                resultJson.put("success", false)
                resultJson.put("message", "Failed to execute tool $name: ${e.message}")
            }

            val finalResult = AiToolResult(
                callId = callId,
                functionName = name,
                isSuccess = isSuccess,
                resultJson = resultJson,
                errorMessage = errorMessage
            )

            if (name in nonIdempotentTools) {
                synchronized(idempotencyCache) {
                    idempotencyCache[actionKey] = finalResult
                }
            }

            finalResult
        }
    }

    /**
     * Returns standard tool definitions in OpenAI / Hermes JSON Schema format.
     */
    fun getAvailableToolSchemas(): List<JSONObject> {
        val tools = mutableListOf<JSONObject>()

        fun tool(name: String, description: String, properties: JSONObject, required: List<String>) {
            tools.add(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", name)
                    put("description", description)
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", properties)
                        put("required", JSONArray(required))
                    })
                })
            })
        }

        tool(
            name = "set_flashlight",
            description = "Turns the phone flashlight / torch LED on or off.",
            properties = JSONObject().apply {
                put("enabled", JSONObject().apply {
                    put("type", "boolean")
                    put("description", "true to turn on, false to turn off")
                })
            },
            required = listOf("enabled")
        )

        tool(
            name = "set_volume",
            description = "Adjusts device media volume (level percentage 0-100, or up/down/mute action).",
            properties = JSONObject().apply {
                put("level", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Target volume percentage from 0 to 100")
                })
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(listOf("up", "down", "mute", "check")))
                    put("description", "Relative action if level is omitted")
                })
            },
            required = emptyList()
        )

        tool(
            name = "get_telemetry",
            description = "Retrieves battery percentage, charging state, and current network status.",
            properties = JSONObject(),
            required = emptyList()
        )

        tool(
            name = "open_app",
            description = "Launches an installed application by name (e.g. 'camera', 'whatsapp', 'youtube', 'settings').",
            properties = JSONObject().apply {
                put("app_name", JSONObject().apply {
                    put("type", "string")
                    put("description", "Name of the app to launch")
                })
            },
            required = listOf("app_name")
        )

        tool(
            name = "execute_connected_app_action",
            description = "Executes deep autonomous actions (searching files/content, deleting items, uploading/saving documents, reading screen data, navigating) inside ANY connected application on the user's phone (e.g. Google Drive, Files, WhatsApp, Gmail, Photos, Instagram, Settings). When an app is connected, you have 100% full autonomous access. NEVER refuse or say you cannot access it.",
            properties = JSONObject().apply {
                put("app_name", JSONObject().apply {
                    put("type", "string")
                    put("description", "Name of the app (e.g. 'Drive', 'Files', 'WhatsApp', 'Gmail', 'Photos', 'Instagram')")
                })
                put("action_type", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(listOf("search", "delete", "upload", "create", "read_content", "navigate", "custom")))
                    put("description", "Type of action to execute inside the app")
                })
                put("target_query", JSONObject().apply {
                    put("type", "string")
                    put("description", "Target file, document, search query, or item name")
                })
                put("user_goal", JSONObject().apply {
                    put("type", "string")
                    put("description", "Full natural language description of what to accomplish in the app")
                })
            },
            required = listOf("app_name", "action_type")
        )

        tool(
            name = "open_website",
            description = "Opens a target URL directly in the browser.",
            properties = JSONObject().apply {
                put("url", JSONObject().apply {
                    put("type", "string")
                    put("description", "The website URL to navigate to")
                })
            },
            required = listOf("url")
        )

        tool(
            name = "search_in_chrome",
            description = "Performs a Google Search query in Chrome browser.",
            properties = JSONObject().apply {
                put("query", JSONObject().apply {
                    put("type", "string")
                    put("description", "Search query")
                })
            },
            required = listOf("query")
        )

        tool(
            name = "send_whatsapp_message",
            description = "Sends a WhatsApp chat message to a contact name or phone number. Requires confirmation before dispatch unless confirmed is explicitly set.",
            properties = JSONObject().apply {
                put("recipient_name", JSONObject().apply {
                    put("type", "string")
                    put("description", "Name of the contact as spoken by the user (or recipient)")
                })
                put("recipient", JSONObject().apply {
                    put("type", "string")
                    put("description", "Contact name or phone number")
                })
                put("message", JSONObject().apply {
                    put("type", "string")
                    put("description", "Message text content to send")
                })
                put("confirmed", JSONObject().apply {
                    put("type", "boolean")
                    put("description", "Set to true if user explicitly confirmed sending the message")
                })
            },
            required = listOf("message")
        )

        tool(
            name = "get_contact_count",
            description = "Queries the total number of contacts saved in the phone address book using the Android Contacts Provider.",
            properties = JSONObject(),
            required = emptyList()
        )

        tool(
            name = "get_whatsapp_contact_count",
            description = "Queries the count of contacts in the phone address book synced with WhatsApp, explaining data boundaries honestly.",
            properties = JSONObject(),
            required = emptyList()
        )

        tool(
            name = "get_app_usage_stats",
            description = "Queries actual app usage duration for today (e.g. 'whatsapp', 'youtube') using Android UsageStatsManager.",
            properties = JSONObject().apply {
                put("app_name", JSONObject().apply {
                    put("type", "string")
                    put("description", "Name of the app to check usage for (default is 'whatsapp')")
                })
            },
            required = emptyList()
        )

        tool(
            name = "call_contact",
            description = "Dials a contact or phone number.",
            properties = JSONObject().apply {
                put("name", JSONObject().apply {
                    put("type", "string")
                    put("description", "Name of the contact or digits of phone number to call")
                })
            },
            required = listOf("name")
        )

        tool(
            name = "click_ui_element",
            description = "Clicks a UI button or element on the Android screen via accessibility service.",
            properties = JSONObject().apply {
                put("text", JSONObject().apply {
                    put("type", "string")
                    put("description", "Visible text or content description of the element")
                })
            },
            required = emptyList()
        )

        tool(
            name = "type_text",
            description = "Types text into the currently active/focused input field.",
            properties = JSONObject().apply {
                put("text", JSONObject().apply {
                    put("type", "string")
                    put("description", "The text to type")
                })
            },
            required = listOf("text")
        )

        tool(
            name = "scroll_screen",
            description = "Scrolls the current Android view up or down.",
            properties = JSONObject().apply {
                put("direction", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(listOf("up", "down")))
                    put("description", "Direction to scroll")
                })
            },
            required = listOf("direction")
        )

        tool(
            name = "task_completed",
            description = "Signals that an autonomous multi-step instruction has been successfully finished.",
            properties = JSONObject().apply {
                put("summary", JSONObject().apply {
                    put("type", "string")
                    put("description", "Brief spoken summary of the completed task")
                })
            },
            required = listOf("summary")
        )

        tool(
            name = "get_task_status",
            description = "Retrieves current status, step progress, or verification result of an active or recent automation task.",
            properties = JSONObject().apply {
                put("task_id", JSONObject().apply {
                    put("type", "string")
                    put("description", "Optional unique task ID to query; if omitted, queries the active or most recent task")
                })
            },
            required = emptyList()
        )

        tool(
            name = "get_active_task",
            description = "Returns what Jarvis is currently doing right now, including step progress and last observation.",
            properties = JSONObject(),
            required = emptyList()
        )

        tool(
            name = "get_failed_tasks",
            description = "Queries recent failed tasks with their error causes and recovery evidence.",
            properties = JSONObject().apply {
                put("limit", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Maximum number of recent failed tasks to return (default 5)")
                })
            },
            required = emptyList()
        )

        tool(
            name = "cancel_active_task",
            description = "Cancels the active automation task cleanly and releases all resources.",
            properties = JSONObject().apply {
                put("reason", JSONObject().apply {
                    put("type", "string")
                    put("description", "Reason for cancellation")
                })
            },
            required = emptyList()
        )

        tool(
            name = "get_last_failure_reason",
            description = "Explains why the last task failed, what was observed, and whether user action is required.",
            properties = JSONObject(),
            required = emptyList()
        )

        tool(
            name = "resume_task",
            description = "Resumes an interrupted or paused task if safe (blocks automatic resumption for sensitive/non-idempotent actions).",
            properties = JSONObject().apply {
                put("task_id", JSONObject().apply {
                    put("type", "string")
                    put("description", "Optional task ID to resume")
                })
            },
            required = emptyList()
        )

        tool(
            name = "control_smart_home",
            description = "Controls smart home devices such as lights, switches, scenes, and climate via Home Assistant or Webhook.",
            properties = JSONObject().apply {
                put("target", JSONObject().apply {
                    put("type", "string")
                    put("description", "Device or group name, e.g. 'living room lights', 'bedroom lamp', 'all', or entity_id like 'light.living_room'")
                })
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("description", "Action to perform: 'on', 'off', 'toggle', 'blink', 'party', 'dim'")
                })
                put("delay_sec", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Optional delay in seconds before executing")
                })
            },
            required = listOf("target", "action")
        )

        tool(
            name = "get_smart_home_devices",
            description = "Queries connected smart home devices and their current states (on/off/temperature) from Home Assistant.",
            properties = JSONObject(),
            required = emptyList()
        )

        // Merge schemas from modular SkillRegistry (deduplicated by tool name)
        try {
            val registeredSkillSchemas = com.jarvis.assistant.automation.skills.SkillRegistry.getInstance(context).getRegisteredToolSchemas()
            val existingNames = tools.mapNotNull { it.optJSONObject("function")?.optString("name") }.toSet()
            for (schema in registeredSkillSchemas) {
                val sName = schema.optJSONObject("function")?.optString("name")
                if (sName != null && sName !in existingNames) {
                    tools.add(schema)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error merging skill schemas: ${e.message}")
        }

        return tools
    }
}
