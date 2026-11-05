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
import com.jarvis.assistant.automation.dynamic.DynamicTool
import com.jarvis.assistant.automation.dynamic.DynamicToolRegistry
import com.jarvis.assistant.automation.dynamic.DynamicScriptExecutor
import com.jarvis.assistant.automation.dynamic.SelfHealingAgent

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
    private var loopGuard = com.jarvis.assistant.automation.guard.ToolLoopGuard()

    /** Shared accessibility controller / executor for the ui_* tools (AndroLLM automation upgrade). */
    private val uiController by lazy { com.jarvis.assistant.automation.accessibility.AccessibilityController(context) }
    private val uiExecutor by lazy { com.jarvis.assistant.automation.accessibility.AutomationExecutor(context, uiController) }

    /**
     * Resets idempotency cache when a new user turn starts.
     * [liveSession] relaxes the loop caps: one Gemini Live turn can legitimately chain many
     * screen-automation steps (same tool back-to-back), which the default caps would cut off.
     */
    fun startNewTurn(turnId: String, liveSession: Boolean = false) {
        synchronized(idempotencyCache) {
            lastTurnId = turnId
            idempotencyCache.clear()
            loopGuard = if (liveSession) {
                com.jarvis.assistant.automation.guard.ToolLoopGuard(maxTotalCalls = 40, maxConsecutiveSameTool = 10, blockIdenticalRepeats = false)
            } else {
                com.jarvis.assistant.automation.guard.ToolLoopGuard()
            }
            com.jarvis.assistant.automation.agent.AgentVariableStore.beginTurn()
        }
    }

    /**
     * Returns a block message when Voice Guard is armed, [name] is risky, the owner's voice wasn't
     * verified in the last 60 s, and the user doesn't approve the confirmation prompt; null = allowed.
     */
    private suspend fun enforceVoiceGuard(name: String, args: JSONObject): String? =
        // Shared with the Gemini Live handlers in JarvisConversationService.
        com.jarvis.assistant.security.guardian.VoiceGuardGate.check(context, name, args)

    // ── Batch-4 device helpers ──────────────────────────────────────────────

    /** Result of a Do Not Disturb change: [ok] false means nothing changed. */
    private data class DndOutcome(val ok: Boolean, val message: String, val settingsOpened: Boolean = false)

    private fun applyDnd(enabled: Boolean, mode: String): DndOutcome {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return DndOutcome(false, "Notification service unavailable, so I couldn't change Do Not Disturb.")
        if (!nm.isNotificationPolicyAccessGranted) {
            val opened = try {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't open DND access settings: ${e.message}")
                false
            }
            return DndOutcome(false, "Allow Do Not Disturb access for Jarvis, then ask again", settingsOpened = opened)
        }
        val filter = if (!enabled) android.app.NotificationManager.INTERRUPTION_FILTER_ALL else when (mode) {
            "alarms" -> android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
            "total_silence" -> android.app.NotificationManager.INTERRUPTION_FILTER_NONE
            else -> android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
        }
        return try {
            nm.setInterruptionFilter(filter)
            val now = nm.currentInterruptionFilter
            val applied = now == filter
            val label = mode.replace('_', ' ')
            when {
                !applied -> DndOutcome(false, "Android didn't apply the Do Not Disturb change (current filter: $now).")
                enabled -> DndOutcome(true, "Do Not Disturb is ON ($label).")
                else -> DndOutcome(true, "Do Not Disturb is OFF.")
            }
        } catch (e: Exception) {
            DndOutcome(false, "Couldn't change Do Not Disturb: ${e.message}")
        }
    }

    /** Starts a Clock-app countdown (no UI). Returns null on success, otherwise the error. */
    private fun startClockTimer(seconds: Int, label: String): String? {
        val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86_400))
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            null
        } catch (e: android.content.ActivityNotFoundException) {
            "No clock app on this phone can take a timer."
        } catch (e: SecurityException) {
            "The clock app refused the timer (permission): ${e.message}"
        } catch (e: Exception) {
            "Could not set timer: ${e.message}"
        }
    }

    private fun dirSize(dir: java.io.File?, depth: Int = 0): Long {
        if (dir == null || !dir.exists() || depth > 12) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { dirSize(it, depth + 1) } ?: 0L
    }

    /**
     * Dispatches and executes an AI Tool Call safely across threads.
     */
    suspend fun dispatchTool(toolCall: AiToolCall): AiToolResult = executionMutex.withLock {
        withContext(Dispatchers.IO) {
            val callId = toolCall.callId
            val name = toolCall.functionName
            val args = toolCall.arguments

            // Loop Guard & Agent safety check
            if (!loopGuard.canExecute(name, args)) {
                val stopMsg = loopGuard.stopReason() ?: "Tool execution blocked by safety loop guard."
                Log.w(TAG, "ToolLoopGuard blocked execution of '$name': $stopMsg")
                val blockedJson = JSONObject().apply {
                    put("success", false)
                    put("message", stopMsg)
                }
                return@withContext AiToolResult(
                    callId = callId,
                    functionName = name,
                    isSuccess = false,
                    resultJson = blockedJson,
                    errorMessage = stopMsg
                )
            }

            // Per-tool toggle (Settings → Agent tools). Default: nothing disabled → no-op.
            if (com.jarvis.assistant.androllm.agent.bridge.JarvisDispatcherHooks.isToolDisabled(context, name)) {
                val disabledMsg = "The '$name' tool is disabled in Settings → Agent tools."
                Log.i(TAG, "Tool '$name' blocked by per-tool toggle")
                return@withContext AiToolResult(
                    callId = callId,
                    functionName = name,
                    isSuccess = false,
                    resultJson = JSONObject().apply {
                        put("success", false)
                        put("message", disabledMsg)
                    },
                    errorMessage = disabledMsg
                )
            }

            // Idempotency check: Prevent duplicate side-effects (e.g. sending SMS/WhatsApp or Calling twice)
            val nonIdempotentTools = setOf("send_whatsapp_message", "call_contact", "whatsapp_call", "send_sms")
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

            // Voice Guard: while armed, risky tools (calls, messages, shell, deletes, dynamic tools...)
            // need the owner — a recent owner-voice verification, or an explicit confirmation.
            val guardBlock = enforceVoiceGuard(name, args)
            if (guardBlock != null) {
                Log.w(TAG, "Voice Guard blocked '$name'")
                return@withContext AiToolResult(
                    callId = callId,
                    functionName = name,
                    isSuccess = false,
                    resultJson = JSONObject().apply {
                        put("success", false)
                        put("blocked_by_voice_guard", true)
                        put("message", guardBlock)
                    },
                    errorMessage = guardBlock
                )
            }

            Log.i(TAG, "Executing tool '$name' (callId=$callId) with args: $args")
            val app = context.applicationContext as? JarvisApp

            // ── 0a. MAYA FEATURE TOOL PACKS (screen verb loop, memory/rules, connectors, business,
            //        guardian, Termux/PC hub, media ...) — see MayaToolRegistry / MayaToolPacks.
            MayaToolRegistry.ensureInstalled(context)
            if (MayaToolRegistry.has(name)) {
                val outcome = MayaToolRegistry.dispatch(context, name, args)
                val outJson = JSONObject(outcome.json.toString()).apply {
                    put("success", outcome.success)
                    if (!has("message")) put("message", outcome.message)
                }
                val packResult = AiToolResult(
                    callId = callId,
                    functionName = name,
                    isSuccess = outcome.success,
                    resultJson = outJson,
                    errorMessage = if (outcome.success) null else outcome.message
                )
                loopGuard.record(name, args, packResult)
                return@withContext packResult
            }

            // ── 0. DYNAMIC SELF-HEALING TOOL RUNTIME DISPATCH ────────────────────
            try {
                val dynamicRegistry = DynamicToolRegistry.getInstance(context)
                val dynamicTool = dynamicRegistry.getTool(name)
                if (dynamicTool != null) {
                    Log.i(TAG, "Dispatching dynamic tool '$name' (v${dynamicTool.version}) to DynamicScriptExecutor")
                    val executor = DynamicScriptExecutor(context)
                    val execResult = executor.execute(dynamicTool, args)
                    dynamicRegistry.recordExecution(name, execResult.isSuccess, execResult.errorMessage)

                    if (!execResult.isSuccess && execResult.errorMessage != null) {
                        SelfHealingAgent.getInstance(context)
                            .interceptFailure(name, args, execResult.errorMessage)
                    }

                    val finalResult = AiToolResult(
                        callId = callId,
                        functionName = name,
                        isSuccess = execResult.isSuccess,
                        resultJson = execResult.outputJson,
                        errorMessage = execResult.errorMessage
                    )
                    return@withContext finalResult
                }
            } catch (e: Exception) {
                Log.w(TAG, "Dynamic tool execution error for '$name': ${e.message}")
            }

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
                    // ── DYNAMIC SELF-HEALING & TOOL CREATION META-TOOLS ─────────
                    "create_dynamic_tool" -> {
                        val dynamicRegistry = DynamicToolRegistry.getInstance(context)
                        val toolName = args.getString("name")
                        val desc = args.optString("description", "Dynamic tool authored by Jarvis")
                        val paramsSchema = args.optString("parameters_schema_json", "{\"type\":\"object\",\"properties\":{}}")
                        val scriptTypeStr = args.optString("script_type", "JAVASCRIPT")
                        val scriptCode = args.getString("script_code")
                        val scriptType = runCatching {
                            DynamicTool.ScriptType.valueOf(scriptTypeStr.uppercase())
                        }.getOrDefault(DynamicTool.ScriptType.JAVASCRIPT)

                        val res = dynamicRegistry.createTool(toolName, desc, paramsSchema, scriptType, scriptCode)
                        res.onSuccess { created ->
                            resultJson.put("success", true)
                            resultJson.put("message", "Dynamic tool '${created.name}' created successfully (version 1). It is now live in Jarvis.")
                            resultJson.put("tool", created.toJson())
                        }.onFailure { err ->
                            isSuccess = false
                            errorMessage = err.message
                            resultJson.put("success", false)
                            resultJson.put("error", err.message)
                        }
                    }

                    "edit_dynamic_tool" -> {
                        val dynamicRegistry = DynamicToolRegistry.getInstance(context)
                        val toolName = args.getString("name")
                        val updatedCode = if (args.has("updated_script_code")) args.getString("updated_script_code") else null
                        val updatedDesc = if (args.has("updated_description")) args.getString("updated_description") else null
                        val updatedSchema = if (args.has("updated_parameters_schema_json")) args.getString("updated_parameters_schema_json") else null

                        val res = dynamicRegistry.editTool(toolName, updatedCode, updatedDesc, updatedSchema)
                        res.onSuccess { updated ->
                            resultJson.put("success", true)
                            resultJson.put("message", "Dynamic tool '${updated.name}' patched to version ${updated.version}.")
                            resultJson.put("tool", updated.toJson())
                            SelfHealingAgent.getInstance(context)
                                .recordPatchSuccess(toolName, "Code/schema patched to version ${updated.version}")
                        }.onFailure { err ->
                            isSuccess = false
                            errorMessage = err.message
                            resultJson.put("success", false)
                            resultJson.put("error", err.message)
                        }
                    }

                    "list_dynamic_tools" -> {
                        val dynamicRegistry = DynamicToolRegistry.getInstance(context)
                        val tools = dynamicRegistry.getAllTools()
                        val array = JSONArray()
                        tools.forEach { array.put(it.toJson()) }
                        resultJson.put("success", true)
                        resultJson.put("total_tools", tools.size)
                        resultJson.put("tools", array)
                    }

                    "delete_dynamic_tool" -> {
                        val dynamicRegistry = DynamicToolRegistry.getInstance(context)
                        val toolName = args.getString("name")
                        val deleted = dynamicRegistry.deleteTool(toolName)
                        resultJson.put("success", deleted)
                        resultJson.put("message", if (deleted) "Dynamic tool '$toolName' deleted." else "Dynamic tool '$toolName' not found.")
                    }

                    // ── 1. DEVICE HARDWARE CONTROLS ────────────────────────────
                    "set_flashlight" -> {
                        val enabled = args.optBoolean("enabled", true)
                        val msg = withContext(Dispatchers.Main) {
                            try {
                                app?.phoneControlManager?.setFlashlight(enabled)
                            } catch (e: Exception) {
                                Log.w(TAG, "set_flashlight failed: ${e.message}")
                                null
                            }
                        }
                        // setFlashlight returns an error sentence (no hardware / camera busy) instead of throwing.
                        val toggled = DeviceToolMessages.isFlashlightSuccess(msg)
                        isSuccess = toggled
                        val finalMsg = when {
                            toggled -> msg.orEmpty()
                            msg.isNullOrBlank() -> "Couldn't control the flashlight - the phone control service isn't available."
                            else -> "Couldn't turn the flashlight ${if (enabled) "on" else "off"}: $msg"
                        }
                        if (!toggled) errorMessage = finalMsg
                        resultJson.put("success", toggled)
                        resultJson.put("message", finalMsg)
                        if (toggled) resultJson.put("flashlight_enabled", enabled)
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
                                        // Unmute first (a muted stream ignores setStreamVolume) and report
                                        // the level the phone really has, not the one that was asked for.
                                        if (level > 0 && audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                                            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                                        }
                                        val target = Math.round(level / 100f * maxVol)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                                        val actual = if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) 0
                                            else Math.round(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / maxVol.coerceAtLeast(1))
                                        resultJson.put("message", "Volume set to $actual%")
                                    }
                                    action == "up" -> {
                                        if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                                            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                                        }
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

                    "set_driving_mode" -> {
                        val rawMode = args.optString("call_mode", "").trim()
                        val callMode = com.jarvis.assistant.core.DrivingCallPolicy.normalizeCallMode(rawMode)
                        if (!args.has("enabled")) {
                            isSuccess = false
                            errorMessage = "Missing 'enabled' argument for set_driving_mode"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        } else if (rawMode.isNotEmpty() && callMode == null) {
                            isSuccess = false
                            errorMessage = "Unknown call_mode '$rawMode' - use 'announce' or 'auto_reject_sms'."
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        } else {
                            val enabled = args.optBoolean("enabled", false)
                            val manager = com.jarvis.assistant.core.JarvisDrivingModeManager.getInstance(context)
                            if (callMode != null) manager.drivingCallMode = callMode
                            manager.setDrivingMode(enabled)
                            val actualEnabled = manager.isDrivingMode.value
                            val actualMode = manager.drivingCallMode
                            var msg = com.jarvis.assistant.core.DrivingCallPolicy.statusMessage(actualEnabled, actualMode)
                            if (actualEnabled && actualMode == com.jarvis.assistant.data.preferences.AppPreferences.DRIVING_CALL_MODE_AUTO_REJECT_SMS) {
                                val canReject = context.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                                val canSms = context.checkSelfPermission(android.Manifest.permission.SEND_SMS) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (!canReject) msg += " Note: the phone-call permission isn't granted, so I'll announce calls instead of rejecting them."
                                else if (!canSms) msg += " Note: SMS permission isn't granted, so calls will be rejected without the auto-reply text."
                            }
                            isSuccess = actualEnabled == enabled
                            if (!isSuccess) errorMessage = msg
                            resultJson.put("success", isSuccess)
                            resultJson.put("driving_mode_enabled", actualEnabled)
                            resultJson.put("call_mode", actualMode.lowercase(java.util.Locale.ROOT))
                            resultJson.put("message", msg)
                        }
                    }

                    "get_battery_status" -> {
                        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                        val sticky = try {
                            context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                        } catch (e: Exception) {
                            Log.w(TAG, "Battery sticky intent unavailable: ${e.message}")
                            null
                        }
                        if (bm == null && sticky == null) {
                            isSuccess = false
                            errorMessage = "Battery information is unavailable on this device."
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        } else {
                            val level = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                            val scale = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                            val percent = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                                ?.takeIf { it in 0..100 }
                                ?: if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                            val status = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || (bm?.isCharging == true)
                            val full = status == android.os.BatteryManager.BATTERY_STATUS_FULL
                            val plug = DeviceStatusFormat.plugLabel(sticky?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0)
                            val tempC = DeviceStatusFormat.temperatureCelsius(sticky?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE)
                            val health = DeviceStatusFormat.healthLabel(sticky?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, 1) ?: 1)
                            // computeChargeTimeRemaining (API 28+, minSdk is 28) returns -1 when unknown.
                            val toFullMs = if (charging) bm?.computeChargeTimeRemaining()?.takeIf { it > 0 } else null
                            val msg = DeviceStatusFormat.batterySummary(percent, charging, full, plug, tempC, health, toFullMs)
                            resultJson.put("success", true)
                            resultJson.put("battery_percent", percent)
                            resultJson.put("is_charging", charging)
                            resultJson.put("is_full", full)
                            resultJson.put("charge_source", plug ?: "none")
                            if (tempC != null) resultJson.put("temperature_c", tempC)
                            resultJson.put("health", health)
                            if (toFullMs != null) resultJson.put("time_to_full_minutes", toFullMs / 60_000L)
                            resultJson.put("message", msg)
                        }
                    }

                    "get_storage_stats" -> {
                        try {
                            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                            val total = stat.totalBytes
                            val free = stat.availableBytes
                            val cache = dirSize(context.cacheDir) + dirSize(context.externalCacheDir)
                            resultJson.put("success", true)
                            resultJson.put("total_bytes", total)
                            resultJson.put("free_bytes", free)
                            resultJson.put("used_bytes", (total - free).coerceAtLeast(0))
                            resultJson.put("used_percent", DeviceStatusFormat.usedPercent(total, free))
                            resultJson.put("total", DeviceStatusFormat.formatBytes(total))
                            resultJson.put("free", DeviceStatusFormat.formatBytes(free))
                            resultJson.put("app_cache_bytes", cache)
                            resultJson.put("app_cache", DeviceStatusFormat.formatBytes(cache))
                            resultJson.put("message", DeviceStatusFormat.storageSummary(total, free, cache))
                        } catch (e: Exception) {
                            isSuccess = false
                            errorMessage = "Couldn't read storage stats: ${e.message}"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        }
                    }

                    "whatsapp_read_messages" -> {
                        val listener = com.jarvis.assistant.automation.notification.JarvisNotificationListener.Companion
                        if (!listener.isNotificationServiceEnabled(context)) {
                            isSuccess = false
                            errorMessage = "Notification access is off — enable it in Settings"
                            resultJson.put("success", false)
                            resultJson.put("permission_denied", true)
                            resultJson.put("message", errorMessage)
                        } else {
                            val contact = args.optString("contact", "").ifBlank { args.optString("sender", "") }.trim()
                            val limit = if (args.has("limit")) args.optInt("limit", 5) else 5
                            val items = listener.recentNotifications.value.map {
                                DeviceStatusFormat.MessageItem(it.packageName, it.sender, it.text, it.timestamp)
                            }
                            val msgs = DeviceStatusFormat.filterWhatsApp(items, contact.ifBlank { null }, limit)
                            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                            resultJson.put("success", true)
                            resultJson.put("count", msgs.size)
                            resultJson.put("messages", JSONArray().apply {
                                msgs.forEach { m ->
                                    put(JSONObject().apply {
                                        put("sender", m.sender)
                                        put("text", m.text)
                                        put("time", fmt.format(java.util.Date(m.timestamp)))
                                        put("app", if (m.packageName == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp")
                                    })
                                }
                            })
                            val scope = if (contact.isNotBlank()) " from '$contact'" else ""
                            val connectedNote = if (listener.instance == null) " (the notification listener isn't connected right now, so new messages may be missing)" else ""
                            resultJson.put(
                                "message",
                                if (msgs.isEmpty()) "No recent WhatsApp messages$scope in notifications. I can only see messages that arrived as notifications while Jarvis was running$connectedNote."
                                else "Recent WhatsApp messages$scope$connectedNote:\n" + msgs.joinToString("\n") {
                                    "- ${it.sender} (${fmt.format(java.util.Date(it.timestamp))}): ${it.text.take(200)}"
                                }
                            )
                        }
                    }

                    "get_scheduled_tasks" -> {
                        val upcoming = com.jarvis.assistant.automation.schedule.JarvisScheduleStore.upcoming(context)
                        resultJson.put("success", true)
                        resultJson.put("count", upcoming.size)
                        resultJson.put("tasks", JSONArray().apply { upcoming.forEach { put(it.toJson()) } })
                        resultJson.put(
                            "message",
                            com.jarvis.assistant.automation.schedule.ScheduledTaskFormat.describe(upcoming, System.currentTimeMillis())
                        )
                    }

                    "set_dnd" -> {
                        val rawMode = args.optString("mode", "")
                        val mode = DeviceStatusFormat.normalizeDndMode(rawMode)
                        if (!args.has("enabled")) {
                            isSuccess = false
                            errorMessage = "Missing 'enabled' argument for set_dnd"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        } else if (mode == null) {
                            isSuccess = false
                            errorMessage = "Unknown DND mode '$rawMode' - use priority, alarms or total_silence."
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        } else {
                            val enabled = args.optBoolean("enabled", true)
                            val outcome = applyDnd(enabled, mode)
                            isSuccess = outcome.ok
                            if (!outcome.ok) errorMessage = outcome.message
                            resultJson.put("success", outcome.ok)
                            resultJson.put("dnd_enabled", outcome.ok && enabled)
                            if (enabled) resultJson.put("mode", mode)
                            if (outcome.settingsOpened) resultJson.put("settings_opened", true)
                            resultJson.put("message", outcome.message)
                        }
                    }

                    "start_focus_timer" -> {
                        val minutes = args.optInt("minutes", 0)
                        if (minutes <= 0) {
                            isSuccess = false
                            errorMessage = "How many minutes should the focus timer run?"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        } else if (minutes > 600) {
                            isSuccess = false
                            errorMessage = "A focus timer can be at most 600 minutes (10 hours)."
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        } else {
                            val timerError = withContext(Dispatchers.Main) { startClockTimer(minutes * 60, "Focus time") }
                            if (timerError != null) {
                                isSuccess = false
                                errorMessage = timerError
                                resultJson.put("success", false)
                                resultJson.put("timer_set", false)
                                resultJson.put("message", timerError)
                            } else {
                                val endAt = System.currentTimeMillis() + minutes * 60_000L
                                com.jarvis.assistant.automation.schedule.JarvisScheduleStore.record(
                                    context, "focus_timer", "Focus time", endAt, "start_focus_timer"
                                )
                                resultJson.put("timer_set", true)
                                resultJson.put("ends_at", endAt)
                                var msg = "Focus timer set for $minutes minutes."
                                if (args.optBoolean("dnd", false)) {
                                    val dnd = applyDnd(true, "priority")
                                    resultJson.put("dnd_enabled", dnd.ok)
                                    if (dnd.ok) {
                                        msg += " Do Not Disturb is on (priority) — it stays on until you turn it off."
                                    } else {
                                        // Timer is running; only the DND part failed. Say so plainly so it isn't retried.
                                        isSuccess = false
                                        msg += " The timer IS running (don't set it again), but Do Not Disturb wasn't turned on: ${dnd.message}"
                                        errorMessage = msg
                                        if (dnd.settingsOpened) resultJson.put("settings_opened", true)
                                    }
                                }
                                resultJson.put("success", isSuccess)
                                resultJson.put("message", msg)
                            }
                        }
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
                                // Maya parity: names taught with remember_app_name win over fuzzy lookup.
                                val aliasPkg = try { com.jarvis.assistant.maya.memory.AppAliasStore.resolve(context, appName) } catch (_: Exception) { null }
                                val aliasIntent = aliasPkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
                                val opened = if (aliasIntent != null) {
                                    aliasIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    withContext(Dispatchers.Main) { context.startActivity(aliasIntent) }
                                    Unit
                                } else withContext(Dispatchers.Main) {
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

                            isSuccess = execResult.success
                            if (!execResult.success) errorMessage = execResult.message
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
                            val searchResult = withContext(Dispatchers.Main) {
                                com.jarvis.assistant.automation.youtube.YouTubeController.searchYouTube(context, query)
                            }
                            val msg = searchResult.message
                                ?: if (searchResult.success) "Showing YouTube search results for '$query'." else "Couldn't open YouTube search for '$query'."
                            isSuccess = searchResult.success
                            if (!searchResult.success) errorMessage = msg
                            resultJson.put("success", searchResult.success)
                            resultJson.put("message", msg)
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
                            resultJson.put(
                                "message",
                                when (callResult) {
                                    is ContactCaller.CallResult.Success ->
                                        "Calling ${callResult.contact.name} (${callResult.contact.label.ifBlank { "saved contact" }})."
                                    is ContactCaller.CallResult.MultipleMatches ->
                                        "Ambiguous: ${callResult.candidates.size} contacts named '$nameArg'. " +
                                            callResult.candidates.joinToString("; ") {
                                                "${it.contact.name} (${it.contact.label.ifBlank { "saved" }}, ends ${it.contact.last4}" +
                                                    (if (it.affinityCount > 0) ", called ${it.affinityCount}x" else "") + ")"
                                            }
                                    is ContactCaller.CallResult.NoMatch -> "No saved contact matched '$nameArg'."
                                    is ContactCaller.CallResult.MissingContactsPermission -> "Contacts permission not granted."
                                    is ContactCaller.CallResult.MissingCallPermission -> "Phone call permission not granted."
                                    is ContactCaller.CallResult.CallFailed -> "Could not place the call."
                                }
                            )
                            if (callResult is ContactCaller.CallResult.MultipleMatches) {
                                resultJson.put("candidates", org.json.JSONArray().apply {
                                    callResult.candidates.forEach { cand ->
                                        put(org.json.JSONObject().apply {
                                            put("name", cand.contact.name)
                                            put("number", cand.contact.number)
                                            put("last4", cand.contact.last4)
                                            put("label", cand.contact.label)
                                            put("times_called", cand.affinityCount)
                                            put("why", cand.reason)
                                        })
                                    }
                                })
                            }
                        } else {
                            isSuccess = false
                            errorMessage = "Missing contact name"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        }
                    }

                    "find_contacts", "search_contacts" -> {
                        val query = args.optString("query", "").ifBlank { args.optString("name", "") }
                        val canReadContacts = context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (query.isNotBlank() && !canReadContacts) {
                            // searchContacts() silently returns nothing without the permission - say why instead.
                            isSuccess = false
                            errorMessage = "Contacts permission is off - allow Contacts for Jarvis in Settings, then ask again."
                            resultJson.put("success", false)
                            resultJson.put("permission_denied", true)
                            resultJson.put("message", errorMessage)
                        } else if (query.isNotBlank()) {
                            val limit = if (args.has("limit")) args.optInt("limit", 8).coerceIn(1, 20) else 8
                            val candidates = withContext(Dispatchers.Main) {
                                ContactCaller.searchContacts(context, query, limit)
                            }
                            resultJson.put("success", candidates.isNotEmpty())
                            resultJson.put("count", candidates.size)
                            resultJson.put("contacts", org.json.JSONArray().apply {
                                candidates.forEach { cand ->
                                    put(org.json.JSONObject().apply {
                                        put("name", cand.contact.name)
                                        put("number", cand.contact.number)
                                        put("last4", cand.contact.last4)
                                        put("label", cand.contact.label)
                                        put("times_called", cand.affinityCount)
                                        put("why", cand.reason)
                                    })
                                }
                            })
                            resultJson.put(
                                "message",
                                if (candidates.isEmpty()) "No saved contact matched '$query'."
                                else "Found ${candidates.size} contact(s) matching '$query': " +
                                    candidates.joinToString("; ") {
                                        "${it.contact.name} (${it.contact.label.ifBlank { "saved" }}, ends ${it.contact.last4}" +
                                            (if (it.affinityCount > 0) ", called ${it.affinityCount}x" else "") + ")"
                                    }
                            )
                        } else {
                            isSuccess = false
                            errorMessage = "Missing contact query"
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
                        val key = args.optString("key", "back").ifBlank { "back" }.trim().lowercase(java.util.Locale.ROOT)
                        val actionCode = when (key) {
                            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                            "recents", "recent_apps" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                            "lock_screen", "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                            else -> null
                        }
                        if (actionCode == null) {
                            isSuccess = false
                            errorMessage = "Unsupported key '$key'. Supported: ${DeviceToolSchemas.PRESS_KEYS.joinToString()}."
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        } else if (a11y != null) {
                            val pressed = a11y.performGlobalAction(actionCode)
                            isSuccess = pressed
                            if (!pressed) errorMessage = "Android refused the '$key' key press."
                            resultJson.put("success", pressed)
                            resultJson.put("message", if (pressed) "Pressed $key" else errorMessage)
                        } else {
                            isSuccess = false
                            errorMessage = "Accessibility service disabled"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        }
                    }

                    // ── 6. SURVEILLANCE & GODS EYE MCP ─────────────────────────
                    // Same name set the conversation service routes (offline / Hermes reach them here).
                    "open_gods_eye_view",
                    "gods_eye_show_view",
                    "gods_eye_situation_brief",
                    "gods_eye_get_earthquakes",
                    "gods_eye_get_active_fires",
                    "gods_eye_aircraft_in_area",
                    "gods_eye_satellites_overhead",
                    "gods_eye_get_recent_launches",
                    "gods_eye_military_awareness",
                    "gods_eye_find_cctv_cameras",
                    "gods_eye_inspect_globe",
                    "gods_eye_control_layers",
                    "gods_eye_get_space_weather",
                    "gods_eye_get_subsea_cables",
                    "gods_eye_get_public_transit",
                    "gods_eye_start_auto_tour",
                    "gods_eye_select_camera" -> {
                        val mcpResult = com.jarvis.assistant.ui.godseye.GodsEyeMcpClient.executeTool(name, args)
                        val ok = mcpResult.optBoolean("success", false)
                        val viewUrl = mcpResult.optString("view_url", "")
                        val actionJson = mcpResult.optString("action_json", "")
                        val shouldShowGlobe = ok && (args.optBoolean("show_on_globe", false) ||
                            name == "open_gods_eye_view" ||
                            name == "gods_eye_show_view" ||
                            name == "gods_eye_select_camera" ||
                            (name != "gods_eye_inspect_globe" && (viewUrl.isNotBlank() || actionJson.isNotBlank())))
                        if (shouldShowGlobe) {
                            withContext(Dispatchers.Main) {
                                com.jarvis.assistant.ui.godseye.GodsEyeMcpClient.launchOrUpdateGodsEye(
                                    context,
                                    viewUrl.takeIf { it.isNotBlank() },
                                    actionJson.takeIf { it.isNotBlank() }
                                )
                            }
                        }
                        val gKeys = mcpResult.keys()
                        while (gKeys.hasNext()) {
                            val k = gKeys.next()
                            resultJson.put(k, mcpResult.get(k))
                        }
                        isSuccess = ok
                        resultJson.put("success", ok)
                        if (!resultJson.has("message")) {
                            resultJson.put(
                                "message",
                                mcpResult.optString("summary", "").ifBlank {
                                    if (ok) "God's Eye tool '$name' finished." else "God's Eye tool '$name' failed."
                                }
                            )
                        }
                        if (!ok) errorMessage = resultJson.optString("message")
                    }

                    "open_gods_eye_radar" -> {
                        val radarRes = com.jarvis.assistant.network.RealLifeDataHub.openGodsEyeRadar(
                            context = context,
                            radarType = args.optString("radar_type", "all"),
                            location = args.optString("location", "").takeIf { it.isNotBlank() }
                        )
                        val rKeys = radarRes.keys()
                        while (rKeys.hasNext()) {
                            val k = rKeys.next()
                            resultJson.put(k, radarRes.get(k))
                        }
                        val ok = radarRes.optBoolean("success", false)
                        isSuccess = ok
                        resultJson.put("success", ok)
                        if (!resultJson.has("message")) resultJson.put("message", if (ok) "God's Eye radar opened." else "Couldn't open the God's Eye radar.")
                        if (!ok) errorMessage = resultJson.optString("message")
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

                    // ── 7. DEEP SYSTEM TOOLS & UTILITIES ──────────────────────────────────
                    "get_wifi_status" -> {
                        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                        val isEnabled = wifiManager?.isWifiEnabled ?: false
                        @Suppress("DEPRECATION")
                        val info = wifiManager?.connectionInfo
                        // ConnectivityManager knows about the Wi-Fi link even when the SSID is
                        // redacted (no location permission -> "<unknown ssid>", networkId -1).
                        val onWifi = try {
                            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                            cm?.getNetworkCapabilities(cm.activeNetwork)
                                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
                        } catch (_: Exception) { false }
                        val connected = isEnabled && (onWifi || (info != null && info.networkId != -1))
                        val ssid = DeviceToolMessages.cleanSsid(info?.ssid)
                        resultJson.put("success", true)
                        resultJson.put("wifi_enabled", isEnabled)
                        resultJson.put("connected", connected)
                        if (connected) {
                            resultJson.put("ssid", ssid ?: "")
                            if (ssid == null) resultJson.put("ssid_hidden", true)
                        }
                        resultJson.put("message", DeviceToolMessages.wifiStatusMessage(isEnabled, info?.ssid, connected))
                    }

                    "set_wifi" -> {
                        val enabled = args.optBoolean("enabled", true)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            // Android 10+ blocks apps from toggling Wi-Fi: open the panel and say so honestly.
                            val panelOpened = try {
                                context.startActivity(android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                                true
                            } catch (e: Exception) {
                                Log.w(TAG, "Wi-Fi panel unavailable, trying Wi-Fi settings: ${e.message}")
                                try {
                                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                    true
                                } catch (_: Exception) { false }
                            }
                            isSuccess = false
                            errorMessage = if (panelOpened) DeviceToolMessages.WIFI_PANEL_MESSAGE
                            else "Android doesn't let apps switch Wi-Fi directly, and I couldn't open the Wi-Fi settings either."
                            resultJson.put("success", false)
                            resultJson.put("panel_opened", panelOpened)
                            resultJson.put("requires_user_action", true)
                            resultJson.put("message", errorMessage)
                        } else {
                            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                            @Suppress("DEPRECATION")
                            val toggled = wifiManager?.setWifiEnabled(enabled) == true
                            isSuccess = toggled
                            val msg = when {
                                toggled -> if (enabled) "Wi-Fi enabled." else "Wi-Fi disabled."
                                else -> "Couldn't turn Wi-Fi ${if (enabled) "on" else "off"}."
                            }
                            if (!toggled) errorMessage = msg
                            resultJson.put("success", toggled)
                            resultJson.put("message", msg)
                        }
                    }

                    "get_bluetooth_status" -> {
                        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                        if (adapter != null) {
                            val isEnabled = adapter.isEnabled
                            val paired = try { adapter.bondedDevices?.size ?: 0 } catch (_: Exception) { 0 }
                            resultJson.put("success", true)
                            resultJson.put("bluetooth_enabled", isEnabled)
                            resultJson.put("paired_count", paired)
                            resultJson.put("message", if (isEnabled) "Bluetooth is ON with $paired paired devices." else "Bluetooth is currently OFF.")
                        } else {
                            resultJson.put("success", false)
                            resultJson.put("message", "Bluetooth hardware not detected.")
                        }
                    }

                    "read_clipboard" -> {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        val text = withContext(Dispatchers.Main) {
                            if (clipboard?.hasPrimaryClip() == true) {
                                clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                            } else ""
                        }
                        resultJson.put("success", true)
                        resultJson.put("clipboard_text", text)
                        resultJson.put("message", if (text.isNotBlank()) "Clipboard: $text" else "Clipboard is currently empty.")
                    }

                    "copy_to_clipboard" -> {
                        val text = args.optString("text", "")
                        if (text.isBlank()) {
                            isSuccess = false
                            errorMessage = "Nothing to copy - no text was given."
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        } else {
                            val copied = withContext(Dispatchers.Main) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Jarvis", text)
                                if (clipboard != null) {
                                    clipboard.setPrimaryClip(clip)
                                    true
                                } else false
                            }
                            isSuccess = copied
                            if (!copied) errorMessage = "Clipboard service unavailable."
                            resultJson.put("success", copied)
                            resultJson.put("message", if (copied) "Copied to clipboard." else errorMessage)
                        }
                    }

                    "take_screenshot" -> {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            val accService = JarvisAccessibilityService.instance
                            if (accService != null) {
                                val ok = accService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                                resultJson.put("success", ok)
                                resultJson.put("message", if (ok) "Screenshot captured successfully." else "Screenshot capture failed.")
                            } else {
                                resultJson.put("success", false)
                                resultJson.put("message", "Accessibility permission required to take screenshot.")
                            }
                        } else {
                            resultJson.put("success", false)
                            resultJson.put("message", "Screenshot requires Android 9.0+.")
                        }
                    }

                    "share_text" -> {
                        val text = args.optString("text", "").ifBlank { args.optString("content", "") }
                        if (text.isNotBlank()) {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, text)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            val chooser = android.content.Intent.createChooser(intent, "Share").apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(chooser)
                            resultJson.put("success", true)
                            resultJson.put("message", "Sharing dialog opened.")
                        } else {
                            resultJson.put("success", false)
                            resultJson.put("message", "No text provided to share.")
                        }
                    }

                    "get_weather" -> {
                        val loc = args.optString("location", "").ifBlank { "auto" }
                        // AndroLLM upgrade: named locations get Open-Meteo current
                        // conditions + 3-day forecast; "auto" (IP-based) and any
                        // Open-Meteo failure keep the original wttr.in one-liner.
                        val openMeteo = if (!loc.equals("auto", ignoreCase = true)) {
                            com.jarvis.assistant.androllm.tools.impl.WeatherTool().lookup(loc)
                                as? com.jarvis.assistant.androllm.agent.core.ToolResult.Success
                        } else null
                        if (openMeteo != null) {
                            resultJson.put("success", true)
                            resultJson.put("weather", openMeteo.summary)
                            resultJson.put("message", openMeteo.summary)
                            runCatching { resultJson.put("details", JSONObject(openMeteo.data.toString())) }
                        } else {
                        val client = okhttp3.OkHttpClient()
                        val req = okhttp3.Request.Builder()
                            .url("https://wttr.in/${java.net.URLEncoder.encode(loc, "UTF-8")}?format=3")
                            .header("User-Agent", "curl/7.68.0")
                            .build()
                        val weatherData: String? = try {
                            client.newCall(req).execute().use { resp ->
                                val body = resp.body?.string()?.trim()
                                if (resp.isSuccessful && DeviceToolMessages.isUsableWeatherText(body)) body else null
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "wttr.in weather fallback failed: ${e.message}")
                            null
                        }
                        if (weatherData != null) {
                            resultJson.put("success", true)
                            resultJson.put("weather", weatherData)
                            resultJson.put("message", weatherData)
                        } else {
                            // Both Open-Meteo (named locations) and the wttr.in fallback failed.
                            isSuccess = false
                            errorMessage = "Couldn't get the weather for ${if (loc.equals("auto", ignoreCase = true)) "your location" else "'$loc'"} right now - the weather services didn't respond."
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        }
                        }
                    }

                    "search_web" -> {
                        val q = args.optString("query", "").trim()
                        // AndroLLM upgrade: DDG Instant Answer → DDG HTML → Bing
                        // provider chain returns real results to the model; only
                        // when every provider is empty does the original path run.
                        val webResults = if (q.isNotBlank()) {
                            runCatching { com.jarvis.assistant.androllm.tools.impl.WebSearchTool().search(q) }.getOrNull()
                        } else null
                        if (webResults != null && webResults.results.isNotEmpty()) {
                            val top = webResults.results.take(6)
                            val summary = com.jarvis.assistant.androllm.tools.impl.WebSearchTool.summarize(q, top)
                            resultJson.put("success", true)
                            resultJson.put("answer", summary)
                            resultJson.put("message", summary)
                            resultJson.put("provider", webResults.provider)
                            resultJson.put("results", org.json.JSONArray().apply {
                                top.forEach { r ->
                                    put(JSONObject().put("title", r.title).put("snippet", r.snippet).put("url", r.url))
                                }
                            })
                        } else if (q.isNotBlank()) {
                            val client = okhttp3.OkHttpClient()
                            val encoded = java.net.URLEncoder.encode(q, "UTF-8")
                            val req = okhttp3.Request.Builder()
                                .url("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
                                .build()
                            val answer = try {
                                client.newCall(req).execute().use { resp ->
                                    val body = resp.body?.string().orEmpty()
                                    val json = JSONObject(body)
                                    val abs = json.optString("AbstractText", "")
                                    val ans = json.optString("Answer", "")
                                    if (abs.isNotBlank()) abs else if (ans.isNotBlank()) ans else {
                                        json.optJSONArray("RelatedTopics")?.optJSONObject(0)?.optString("Text", "") ?: ""
                                    }
                                }
                            } catch (e: Exception) { "" }

                            if (answer.isNotBlank()) {
                                resultJson.put("success", true)
                                resultJson.put("answer", answer)
                                resultJson.put("message", answer)
                            } else {
                                withContext(Dispatchers.Main) {
                                    app?.phoneControlManager?.searchWeb(q)
                                }
                                resultJson.put("success", true)
                                resultJson.put("message", "Opened Chrome search for '$q'.")
                            }
                        } else {
                            resultJson.put("success", false)
                            resultJson.put("message", "Search query is empty.")
                        }
                    }

                    "calculate" -> {
                        val expr = args.optString("expression", "")
                        try {
                            if (expr.isBlank()) throw IllegalArgumentException("expression is empty")
                            val num = com.jarvis.assistant.automation.tools.MathEvaluator.evaluate(expr)
                            if (num.isNaN() || num.isInfinite()) throw ArithmeticException("result is not a finite number")
                            val formatted = if (num % 1.0 == 0.0) num.toLong().toString() else String.format(java.util.Locale.US, "%.4f", num).trimEnd('0').trimEnd('.')
                            resultJson.put("success", true)
                            resultJson.put("result", num)
                            resultJson.put("message", "$expr = $formatted")
                        } catch (e: Exception) {
                            isSuccess = false
                            errorMessage = "Calculation error: ${e.message}"
                            resultJson.put("success", false)
                            resultJson.put("message", "Calculation error: ${e.message}")
                        }
                    }

                    "add_calendar_event" -> {
                        val title = args.optString("title", "Event").ifBlank { "Event" }
                        val description = args.optString("description", "")
                        val rawStart = args.optString("start_time", "").ifBlank { args.optString("start", "") }.trim()
                        val startMs = DeviceStatusFormat.parseEventStart(rawStart)
                        val durationMin = args.optInt("duration_minutes", 60).let { if (it > 0) it.coerceAtMost(7 * 24 * 60) else 60 }
                        val endMs = startMs?.plus(durationMin * 60_000L)
                        val intent = android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
                            data = android.provider.CalendarContract.Events.CONTENT_URI
                            putExtra(android.provider.CalendarContract.Events.TITLE, title)
                            if (description.isNotBlank()) putExtra(android.provider.CalendarContract.Events.DESCRIPTION, description)
                            if (startMs != null && endMs != null) {
                                putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                                putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                            }
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                            val whenText = if (startMs != null) {
                                java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(startMs)) +
                                    " for $durationMin min"
                            } else null
                            resultJson.put("success", true)
                            resultJson.put("event_screen_opened", true)
                            if (startMs != null) {
                                resultJson.put("start_time_ms", startMs)
                                resultJson.put("end_time_ms", endMs)
                            }
                            resultJson.put(
                                "message",
                                when {
                                    whenText != null -> "Opened the calendar's new-event screen for '$title' on $whenText — tap Save to add it."
                                    rawStart.isNotBlank() -> "Opened the calendar's new-event screen for '$title', but I couldn't understand the time '$rawStart', so no time was filled in."
                                    else -> "Opened the calendar's new-event screen for '$title' without a time — set the time and tap Save."
                                }
                            )
                        } catch (e: Exception) {
                            isSuccess = false
                            errorMessage = "Couldn't open a calendar app: ${e.message}"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        }
                    }

                    "create_note" -> {
                        val noteText = args.optString("text", "").ifBlank { args.optString("content", "") }
                        val noteTitle = args.optString("title", "Note")
                        if (noteText.isNotBlank()) {
                            app?.memoryRepository?.saveMemory(
                                rawText = if (noteTitle.isNotBlank()) "$noteTitle: $noteText" else noteText,
                                category = com.jarvis.assistant.data.memory.MemoryEntity.CATEGORY_NOTES,
                                key = noteTitle,
                                value = noteText
                            )
                            resultJson.put("success", true)
                            resultJson.put("message", "Note saved: $noteTitle")
                        } else {
                            resultJson.put("success", false)
                            resultJson.put("message", "Note content cannot be empty.")
                        }
                    }

                    "list_notes" -> {
                        val notes = app?.memoryRepository?.getAllMemories()?.filter {
                            it.category == com.jarvis.assistant.data.memory.MemoryEntity.CATEGORY_NOTES
                        } ?: emptyList()
                        val summary = if (notes.isEmpty()) "You have no saved notes." else notes.take(5).joinToString("; ") { it.rawText }
                        resultJson.put("success", true)
                        resultJson.put("notes_count", notes.size)
                        resultJson.put("message", summary)
                    }

                    "set_reminder" -> {
                        val message = args.optString("message", "Reminder").ifBlank { "Reminder" }
                        val minutes = args.optInt("minutes", 0)
                        if (minutes <= 0) {
                            isSuccess = false
                            errorMessage = "How many minutes from now?"
                            resultJson.put("success", false)
                            resultJson.put("needs_minutes", true)
                            resultJson.put("message", errorMessage)
                        } else if (minutes > 1440) {
                            isSuccess = false
                            errorMessage = "Clock timers can run at most 24 hours (1440 minutes) - use create_reminder for a specific date/time."
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        } else {
                            val timerError = withContext(Dispatchers.Main) { startClockTimer(minutes * 60, message) }
                            if (timerError == null) {
                                com.jarvis.assistant.automation.schedule.JarvisScheduleStore.record(
                                    context, "timer", message, System.currentTimeMillis() + minutes * 60_000L, "set_reminder"
                                )
                                resultJson.put("success", true)
                                resultJson.put("message", "Reminder timer set for $minutes minutes: $message")
                            } else {
                                isSuccess = false
                                errorMessage = timerError
                                resultJson.put("success", false)
                                resultJson.put("message", timerError)
                            }
                        }
                    }

                    // 13. UI ACCESSIBILITY & PHONE SCREEN TOOLS
                    "ui_read_screen" -> {
                        val controller = uiController
                        if (!controller.isConnected) {
                            isSuccess = false
                            resultJson.put("success", false)
                            resultJson.put("message", "Accessibility Service not enabled. Please enable Jarvis in Accessibility Settings.")
                        } else {
                            val limit = args.optInt("limit", 40).coerceIn(5, 150)
                            val snapshot = controller.readScreen()
                            val summary = snapshot.describe(limit)
                            resultJson.put("success", true)
                            resultJson.put("app", snapshot.packageName)
                            resultJson.put("fields", snapshot.textFields.size)
                            resultJson.put("buttons", snapshot.buttons.size)
                            resultJson.put("loading", snapshot.loading)
                            resultJson.put("screen_summary", summary)
                            resultJson.put(
                                "message",
                                if (snapshot.isEmpty) "The screen has no readable elements (app: ${snapshot.packageName.ifBlank { "unknown" }}). Try ocr_screen for image-based content.\n$summary"
                                else summary
                            )
                        }
                    }

                    "ui_click" -> {
                        val label = args.optString("label", "").ifBlank { args.optString("target", "") }.ifBlank { args.optString("text", "") }
                        val index = args.optInt("index", 0).coerceAtLeast(0)
                        if (label.isBlank()) {
                            isSuccess = false
                            resultJson.put("success", false)
                            resultJson.put("message", "Label is required for ui_click")
                        } else {
                            val res = uiController.click(com.jarvis.assistant.automation.accessibility.UiSelector(textContains = label, index = index))
                            isSuccess = res.success
                            resultJson.put("success", res.success)
                            resultJson.put("message", res.message)
                        }
                    }

                    "ui_type" -> {
                        val text = args.optString("text", "")
                        val into = args.optString("into", "").ifBlank { args.optString("field", "") }.ifBlank { null }
                        if (text.isEmpty()) {
                            isSuccess = false
                            resultJson.put("success", false)
                            resultJson.put("message", "Text is required for ui_type")
                        } else {
                            val res = uiController.type(text, into)
                            isSuccess = res.success
                            resultJson.put("success", res.success)
                            resultJson.put("message", res.message)
                        }
                    }

                    "ui_scroll" -> {
                        val direction = args.optString("direction", "down")
                        val res = uiController.scroll(direction)
                        isSuccess = res.success
                        resultJson.put("success", res.success)
                        resultJson.put("message", res.message)
                    }

                    "ui_swipe" -> {
                        val direction = args.optString("direction", "")
                        val res = uiController.swipe(direction)
                        isSuccess = res.success
                        resultJson.put("success", res.success)
                        resultJson.put("message", res.message)
                    }

                    "ui_navigate" -> {
                        val action = args.optString("action", "")
                        val res = uiController.navigate(action)
                        isSuccess = res.success
                        resultJson.put("success", res.success)
                        resultJson.put("message", res.message)
                    }

                    "ui_gesture" -> {
                        val action = args.optString("action", "")
                        val label = args.optString("label", "").ifBlank { null }
                        val dx = args.optDouble("dx", 0.0).toFloat()
                        val dy = args.optDouble("dy", 0.0).toFloat()
                        val res = uiController.gesture(action, label, dx, dy)
                        isSuccess = res.success
                        resultJson.put("success", res.success)
                        resultJson.put("message", res.message)
                    }

                    "ui_launch_app" -> {
                        val appName = args.optString("app", "").ifBlank { args.optString("label", "") }.ifBlank { args.optString("name", "") }
                        val res = uiController.launchApp(appName)
                        isSuccess = res.success
                        resultJson.put("success", res.success)
                        resultJson.put("message", res.message)
                    }

                    "ui_run" -> {
                        val goal = args.optString("goal", "").trim()
                        if (goal.isBlank()) {
                            isSuccess = false
                            resultJson.put("success", false)
                            resultJson.put("message", "Goal is required for ui_run")
                        } else {
                            val targetApp = args.optString("app", "").trim()
                            // An explicit target app is launched first by the planner (LaunchApp -> Wait -> act).
                            val fullGoal = if (targetApp.isNotEmpty() &&
                                com.jarvis.assistant.androllm.accessibility.planner.HeuristicActionPlanner.appLabelFromGoal(goal) == null
                            ) "open $targetApp and $goal" else goal
                            val maxSteps = if (args.has("max_steps")) args.optInt("max_steps", 0).takeIf { it > 0 } else null
                            val res = uiExecutor.run(
                                goal = fullGoal,
                                maxSteps = maxSteps,
                                preApproved = args.optBoolean("confirmed", false)
                            )
                            isSuccess = res.success
                            resultJson.put("success", res.success)
                            resultJson.put("steps_taken", res.steps)
                            resultJson.put("message", res.summary)
                        }
                    }

                    "resolve_contact" -> {
                        val contactName = args.optString("name", "").trim()
                        if (contactName.isBlank()) {
                            isSuccess = false
                            resultJson.put("success", false)
                            resultJson.put("message", "A contact name is required for resolve_contact.")
                        } else {
                            val number = com.jarvis.assistant.automation.tools.ContactResolver.resolveByName(context, contactName)
                            if (number != null) {
                                resultJson.put("success", true)
                                resultJson.put("contact_name", contactName)
                                resultJson.put("phone_number", number)
                                resultJson.put("message", "Found contact $contactName: $number")
                            } else {
                                isSuccess = false
                                resultJson.put("success", false)
                                resultJson.put("message", "No phone number found for contact '$contactName' or permission denied.")
                            }
                        }
                    }

                    "search_apps" -> {
                        val query = args.optString("query", "").trim()
                        if (query.isBlank()) {
                            isSuccess = false
                            resultJson.put("success", false)
                            resultJson.put("message", "A query is required for search_apps.")
                        } else {
                            val searchRes = com.jarvis.assistant.automation.tools.AppIndex.search(context, query)
                            val appList = org.json.JSONArray()
                            searchRes.matches.forEach { entry ->
                                appList.put(JSONObject().apply {
                                    put("label", entry.label)
                                    put("package_name", entry.packageName)
                                    put("can_launch", entry.hasLauncher)
                                })
                            }
                            isSuccess = searchRes.matches.isNotEmpty()
                            resultJson.put("success", isSuccess)
                            resultJson.put("query", query)
                            resultJson.put("matches", appList)
                            resultJson.put("ambiguous", searchRes.ambiguous)
                            resultJson.put(
                                "message",
                                if (searchRes.matches.isEmpty()) "No installed app matches '$query'."
                                else "Found ${searchRes.matches.size} matching applications: " +
                                    searchRes.matches.joinToString { it.label } +
                                    (if (searchRes.ambiguous) " (ambiguous - ask the user which one)." else ".")
                            )
                        }
                    }

                    // ── Code Studio (AI coding IDE) — confined to the Code Studio projects root ──
                    in com.jarvis.assistant.codestudio.CodeStudioTools.NAMES -> {
                        isSuccess = com.jarvis.assistant.codestudio.CodeStudioTools.execute(context, name, args, resultJson)
                        if (!isSuccess) errorMessage = resultJson.optString("message")
                    }

                    // ── Code Studio runtime: shell (risky → ToolConfirmationManager), AI generator, live preview ──
                    in com.jarvis.assistant.codestudio.tools.CodeStudioRuntimeTools.NAMES -> {
                        isSuccess = com.jarvis.assistant.codestudio.tools.CodeStudioRuntimeTools.execute(context, name, args, resultJson)
                        if (!isSuccess) errorMessage = resultJson.optString("message")
                    }

                    // ── Web info tools: get_news (RSS), get_public_ip, wikipedia_search ──
                    in com.jarvis.assistant.automation.tools.JarvisWebInfoTools.NAMES -> {
                        val webOut = com.jarvis.assistant.automation.tools.JarvisWebInfoTools.execute(context, name, args)
                        val webKeys = webOut.keys()
                        while (webKeys.hasNext()) {
                            val k = webKeys.next()
                            resultJson.put(k, webOut.get(k))
                        }
                        isSuccess = webOut.optBoolean("success", false)
                        if (!isSuccess) errorMessage = webOut.optString("message")
                    }

                    in com.jarvis.assistant.androllm.tools.AndroLlmTools.NAMES -> {
                        val outcome = com.jarvis.assistant.androllm.tools.AndroLlmTools.execute(context, name, args)
                        if (outcome == null) {
                            isSuccess = false
                            errorMessage = "Unsupported tool action: '$name'"
                            resultJson.put("success", false)
                            resultJson.put("message", errorMessage)
                        } else {
                            outcome.writeTo(resultJson)
                            isSuccess = outcome.success
                            if (!outcome.success) errorMessage = outcome.message
                        }
                    }

                    else -> {
                        val mcpManager = com.jarvis.assistant.mcp.McpManager.getInstance(context)
                        if (mcpManager.isMcpTool(name)) {
                            val (ok, output) = mcpManager.executeMcpTool(name, args)
                            isSuccess = ok
                            resultJson.put("success", ok)
                            resultJson.put("message", output)
                        } else {
                            // AndroLLM-native registry tool (agent framework), run through its gated executor.
                            val agentOutcome = com.jarvis.assistant.androllm.agent.bridge.JarvisDispatcherHooks
                                .executeRegistryTool(context, name, args)
                            if (agentOutcome != null) {
                                agentOutcome.writeTo(resultJson)
                                isSuccess = agentOutcome.success
                                if (!agentOutcome.success) errorMessage = agentOutcome.message
                            } else {
                                isSuccess = false
                                errorMessage = "Unsupported tool action: '$name'"
                                resultJson.put("success", false)
                                resultJson.put("message", errorMessage)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception executing tool '$name': ${e.message}", e)
                isSuccess = false
                errorMessage = e.message ?: "Execution error"
                resultJson.put("success", false)
                resultJson.put("message", "Failed to execute tool $name: ${e.message}")
            }

            val err = errorMessage
            if (!isSuccess && err != null) {
                try {
                    SelfHealingAgent.getInstance(context).interceptFailure(name, args, err)
                } catch (_: Exception) {}
            }

            val finalResult = AiToolResult(
                callId = callId,
                functionName = name,
                isSuccess = isSuccess,
                resultJson = resultJson,
                errorMessage = errorMessage
            )

            loopGuard.record(name, args, finalResult)

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
     *
     * [includeDisabled] = true returns the raw list (used by the agent
     * framework's registry adapters); the default hides tools the user
     * disabled in Settings → Agent tools (none by default).
     */
    fun getAvailableToolSchemas(includeDisabled: Boolean = false): List<JSONObject> {
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
            name = "search_youtube",
            description = "Opens YouTube search results for a query (does not auto-play a video).",
            properties = JSONObject().apply {
                put("query", JSONObject().apply {
                    put("type", "string")
                    put("description", "What to search for on YouTube")
                })
            },
            required = listOf("query")
        )

        tool(
            name = "set_driving_mode",
            description = "Turns Jarvis driving mode on or off. While driving, incoming calls are either announced aloud or auto-rejected with an SMS reply.",
            properties = JSONObject().apply {
                put("enabled", JSONObject().apply {
                    put("type", "boolean")
                    put("description", "true to turn driving mode on, false to turn it off")
                })
                put("call_mode", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(listOf("announce", "auto_reject_sms")))
                    put("description", "Optional: how to handle incoming calls while driving (announce the caller, or reject and send an auto-reply SMS)")
                })
            },
            required = listOf("enabled")
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

        tool(
            name = "get_wifi_status",
            description = "Checks whether Wi-Fi is enabled, current SSID, and connectivity status.",
            properties = JSONObject(),
            required = emptyList()
        )

        tool(
            name = "set_wifi",
            description = "Enables or disables Wi-Fi, or opens the Wi-Fi quick settings panel.",
            properties = JSONObject().apply {
                put("enabled", JSONObject().apply {
                    put("type", "boolean")
                    put("description", "true to enable Wi-Fi, false to disable")
                })
            },
            required = listOf("enabled")
        )

        tool(
            name = "get_bluetooth_status",
            description = "Checks Bluetooth state and count of paired/connected devices.",
            properties = JSONObject(),
            required = emptyList()
        )

        tool(
            name = "read_clipboard",
            description = "Reads current plain text copied on the Android system clipboard.",
            properties = JSONObject(),
            required = emptyList()
        )

        tool(
            name = "copy_to_clipboard",
            description = "Copies specified text to the Android system clipboard.",
            properties = JSONObject().apply {
                put("text", JSONObject().apply {
                    put("type", "string")
                    put("description", "The text content to copy to clipboard")
                })
            },
            required = listOf("text")
        )

        tool(
            name = "take_screenshot",
            description = "Captures an instant screenshot of the current Android screen.",
            properties = JSONObject(),
            required = emptyList()
        )

        tool(
            name = "share_text",
            description = "Shares text, notes, or links via the Android system share sheet.",
            properties = JSONObject().apply {
                put("text", JSONObject().apply {
                    put("type", "string")
                    put("description", "Text or link to share")
                })
                put("title", JSONObject().apply {
                    put("type", "string")
                    put("description", "Optional dialog title")
                })
            },
            required = listOf("text")
        )

        tool(
            name = "get_weather",
            description = "Retrieves live weather, temperature, and sky conditions for a specified location or current city.",
            properties = JSONObject().apply {
                put("location", JSONObject().apply {
                    put("type", "string")
                    put("description", "City name, or 'auto' for current location")
                })
            },
            required = emptyList()
        )

        tool(
            name = "search_web",
            description = "Performs an instant web search and returns direct answers, facts, and knowledge summaries.",
            properties = JSONObject().apply {
                put("query", JSONObject().apply {
                    put("type", "string")
                    put("description", "The question or search query")
                })
            },
            required = listOf("query")
        )

        tool(
            name = "calculate",
            description = "Evaluates math expressions (arithmetic, percentages, powers, square roots).",
            properties = JSONObject().apply {
                put("expression", JSONObject().apply {
                    put("type", "string")
                    put("description", "Math expression e.g. '(150 * 18) / 100' or 'sqrt(144)'")
                })
            },
            required = listOf("expression")
        )

        tool(
            name = "add_calendar_event",
            description = "Opens the calendar's new-event screen pre-filled with title, details and (when given) start time and duration; the user taps Save.",
            properties = JSONObject().apply {
                put("title", JSONObject().apply {
                    put("type", "string")
                    put("description", "Title of the calendar event")
                })
                put("description", JSONObject().apply {
                    put("type", "string")
                    put("description", "Optional description or details")
                })
                put("start_time", JSONObject().apply {
                    put("type", "string")
                    put("description", "Optional start, ISO-8601 (e.g. '2026-11-05T15:30:00+05:30') or 'YYYY-MM-DD HH:mm' in local time")
                })
                put("duration_minutes", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Optional duration in minutes (default 60)")
                })
            },
            required = listOf("title")
        )

        tool(
            name = "create_note",
            description = "Saves a quick note or memo into Jarvis persistent memory.",
            properties = JSONObject().apply {
                put("title", JSONObject().apply {
                    put("type", "string")
                    put("description", "Title or topic of the note")
                })
                put("text", JSONObject().apply {
                    put("type", "string")
                    put("description", "Content of the note")
                })
            },
            required = listOf("text")
        )

        tool(
            name = "list_notes",
            description = "Lists recent notes and memos saved in Jarvis memory.",
            properties = JSONObject(),
            required = emptyList()
        )

        tool(
            name = "set_reminder",
            description = "Sets a countdown timer/reminder in the Clock app that goes off N minutes from now (max 1440). For a specific date/time use create_reminder.",
            properties = JSONObject().apply {
                put("message", JSONObject().apply {
                    put("type", "string")
                    put("description", "Reminder label or task name")
                })
                put("minutes", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Minutes from now (required, 1-1440) - ask the user if they didn't say")
                })
            },
            required = listOf("message", "minutes")
        )

        // Batch-4 device tools (battery, storage, WhatsApp reading, contacts search, press_key, DND, focus timer, schedule)
        try {
            val existing = tools.mapNotNull { it.optJSONObject("function")?.optString("name") }.toSet()
            DeviceToolSchemas.schemas().forEach { schema ->
                val sName = schema.optJSONObject("function")?.optString("name")
                if (sName != null && sName !in existing) tools.add(schema)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error adding device tool schemas: ${e.message}")
        }

        // 13. UI ACCESSIBILITY & APP CONTROLS
        tool(
            name = "ui_read_screen",
            description = "Reads active screen text, visible buttons, and text fields in foreground app using Accessibility.",
            properties = JSONObject().apply {
                put("limit", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Maximum elements to return (default 40)")
                })
            },
            required = emptyList()
        )

        tool(
            name = "ui_click",
            description = "Taps an element on the screen matching the given text or description.",
            properties = JSONObject().apply {
                put("label", JSONObject().apply {
                    put("type", "string")
                    put("description", "Visible text or description of the button/element to tap")
                })
                put("index", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Match index if multiple elements share the same text (default 0)")
                })
            },
            required = listOf("label")
        )

        tool(
            name = "ui_type",
            description = "Types text into the focused text field or the field specified by 'into'.",
            properties = JSONObject().apply {
                put("text", JSONObject().apply {
                    put("type", "string")
                    put("description", "Text to type into the field")
                })
                put("into", JSONObject().apply {
                    put("type", "string")
                    put("description", "Optional label or hint of the target text field")
                })
            },
            required = listOf("text")
        )

        tool(
            name = "ui_scroll",
            description = "Scrolls the active scrollable view (falls back to a swipe when the screen has no scrollable list).",
            properties = JSONObject().apply {
                put("direction", JSONObject().apply {
                    put("type", "string")
                    put("enum", org.json.JSONArray(listOf("up", "down", "left", "right", "forward", "backward")))
                })
            },
            required = listOf("direction")
        )

        tool(
            name = "ui_swipe",
            description = "Swipes the screen in the specified direction.",
            properties = JSONObject().apply {
                put("direction", JSONObject().apply {
                    put("type", "string")
                    put("enum", org.json.JSONArray(listOf("up", "down", "left", "right")))
                })
            },
            required = listOf("direction")
        )

        tool(
            name = "ui_navigate",
            description = "Performs global navigation action (back, home, recents, notifications, quick_settings, split_screen).",
            properties = JSONObject().apply {
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("enum", org.json.JSONArray(listOf("back", "home", "recents", "notifications", "quick_settings", "split_screen")))
                })
            },
            required = listOf("action")
        )

        tool(
            name = "ui_gesture",
            description = "Dispatches advanced gestures (double_tap, long_press, drag, pinch_in, pinch_out).",
            properties = JSONObject().apply {
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("enum", org.json.JSONArray(listOf("double_tap", "long_press", "drag", "pinch_in", "pinch_out")))
                })
                put("label", JSONObject().apply {
                    put("type", "string")
                    put("description", "Element label for double_tap or long_press")
                })
                put("dx", JSONObject().apply { put("type", "number"); put("description", "Horizontal drag distance") })
                put("dy", JSONObject().apply { put("type", "number"); put("description", "Vertical drag distance") })
            },
            required = listOf("action")
        )

        tool(
            name = "ui_run",
            description = "Executes an autonomous multi-step mobile task inside another app: launches the app, then reads the screen and acts step by step (e.g., 'open YouTube and search for tutorials', 'open WhatsApp and tell Mom I am late'). Sensitive taps (send, pay, delete...) need confirmation.",
            properties = JSONObject().apply {
                put("goal", JSONObject().apply {
                    put("type", "string")
                    put("description", "Plain language description of the goal to accomplish")
                })
                put("app", JSONObject().apply {
                    put("type", "string")
                    put("description", "Optional app to open first (e.g. 'YouTube') when the goal doesn't name it")
                })
                put("confirmed", JSONObject().apply {
                    put("type", "boolean")
                    put("description", "True only after the user explicitly confirmed sensitive steps of this goal")
                })
                put("max_steps", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Optional step budget override (default from automation settings)")
                })
            },
            required = listOf("goal")
        )

        tool(
            name = "ui_launch_app",
            description = "Launches an installed app by its visible name through the accessibility automation engine (best match, cached app index).",
            properties = JSONObject().apply {
                put("app", JSONObject().apply {
                    put("type", "string")
                    put("description", "App name, e.g. 'WhatsApp' or 'yt'")
                })
            },
            required = listOf("app")
        )

        tool(
            name = "resolve_contact",
            description = "Resolves a contact name ('Mom', 'Alice') to a phone number from the device contacts.",
            properties = JSONObject().apply {
                put("name", JSONObject().apply {
                    put("type", "string")
                    put("description", "Name of the contact to resolve")
                })
            },
            required = listOf("name")
        )

        tool(
            name = "search_apps",
            description = "Searches installed Android applications by name or common alias ('yt', 'wa', 'ig').",
            properties = JSONObject().apply {
                put("query", JSONObject().apply {
                    put("type", "string")
                    put("description", "App name or alias to search")
                })
            },
            required = listOf("query")
        )

        // Code Studio tools (projects / files / open IDE)
        try {
            val existing = tools.mapNotNull { it.optJSONObject("function")?.optString("name") }.toSet()
            com.jarvis.assistant.codestudio.CodeStudioTools.schemas().forEach { schema ->
                val sName = schema.optJSONObject("function")?.optString("name")
                if (sName != null && sName !in existing) tools.add(schema)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error adding Code Studio tool schemas: ${e.message}")
        }

        // Code Studio runtime tools (run_shell_command, generate_project, start_preview)
        try {
            val existing = tools.mapNotNull { it.optJSONObject("function")?.optString("name") }.toSet()
            com.jarvis.assistant.codestudio.tools.CodeStudioRuntimeTools.schemas().forEach { schema ->
                val sName = schema.optJSONObject("function")?.optString("name")
                if (sName != null && sName !in existing) tools.add(schema)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error adding Code Studio runtime tool schemas: ${e.message}")
        }

        // Web info tools (get_news, get_public_ip, wikipedia_search) - deduplicated by name
        try {
            val existingWeb = tools.mapNotNull { it.optJSONObject("function")?.optString("name") }.toSet()
            com.jarvis.assistant.automation.tools.JarvisWebInfoTools.schemas().forEach { schema ->
                val sName = schema.optJSONObject("function")?.optString("name")
                if (sName != null && sName !in existingWeb) tools.add(schema)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error adding web info tool schemas: ${e.message}")
        }

        // AndroLLM-ported tools (scan_qr, ocr_screen, converters, maps, music, email, sms...)
        try {
            val existing = tools.mapNotNull { it.optJSONObject("function")?.optString("name") }.toSet()
            com.jarvis.assistant.androllm.tools.AndroLlmTools.schemas().forEach { schema ->
                val sName = schema.optJSONObject("function")?.optString("name")
                if (sName != null && sName !in existing) tools.add(schema)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error adding AndroLLM tool schemas: ${e.message}")
        }

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

        // Dynamic (Jarvis-authored) tools: already in the same {"type":"function","function":{...}}
        // shape (DynamicTool.toGeminiSchema). Built-in names win on collision; dispatchTool still
        // routes a dynamic tool by name first.
        try {
            val dynamicSchemas = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(500L) {
                    DynamicToolRegistry.getInstance(context).toGeminiSchemas()
                }
            } ?: emptyList()
            val existing = tools.mapNotNull { it.optJSONObject("function")?.optString("name") }.toMutableSet()
            for (schema in dynamicSchemas) {
                val sName = schema.optJSONObject("function")?.optString("name")
                if (!sName.isNullOrBlank() && existing.add(sName)) tools.add(schema)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error adding dynamic tool schemas: ${e.message}")
        }

        // Merge imported tools from connected Model Context Protocol (MCP) servers
        try {
            val mcpManager = com.jarvis.assistant.mcp.McpManager.getInstance(context)
            tools.addAll(mcpManager.getMcpToolSchemas())
        } catch (_: Exception) {}

        // Maya feature tool packs: only the CORE set is shown (built-in names win on collision).
        // The other ~250 stay reachable through find_tool + run_tool; showing them all overloaded
        // Gemini's tool choice and blew the offline model's 2K context.
        try {
            MayaToolRegistry.ensureInstalled(context)
            val existing = tools.mapNotNull { it.optJSONObject("function")?.optString("name") }.toMutableSet()
            for (schema in MayaToolRegistry.coreSchemas()) {
                val sName = schema.optJSONObject("function")?.optString("name")
                if (!sName.isNullOrBlank() && existing.add(sName)) tools.add(schema)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error adding Maya tool schemas: ${e.message}")
        }

        if (includeDisabled) return tools
        // Per-tool toggles + (only with the AGENT_PIPELINE flag on) AndroLLM registry tools.
        return com.jarvis.assistant.androllm.agent.bridge.JarvisDispatcherHooks.filterSchemas(context, tools)
    }

    /**
     * Tool list for the on-device (offline) brain. Its context is ~2K tokens (~4.4K chars), so it
     * gets only the everyday phone actions, each compacted to name + one short line + param
     * names/types. The full list (100+ tools, ~170K chars) used to overflow it, and the model
     * recited the tool list back instead of acting.
     */
    fun getOfflineToolSchemas(): List<JSONObject> {
        val wanted = listOf(
            "set_flashlight", "set_volume", "open_app", "call_contact", "send_whatsapp_message",
            "whatsapp_delete_message", "whatsapp_delete_sent_message", "set_reminder",
            "get_battery_status", "set_wifi", "get_weather", "search_web"
        )
        val byName = getAvailableToolSchemas(includeDisabled = false)
            .associateBy { it.optJSONObject("function")?.optString("name").orEmpty() }
        return wanted.mapNotNull { byName[it] }.map { compactSchema(it) }
    }

    private fun compactSchema(schema: JSONObject): JSONObject {
        val fn = schema.optJSONObject("function") ?: return schema
        val desc = fn.optString("description").split(Regex("(?<=[.!?])\\s")).firstOrNull().orEmpty().take(70)
        val params = fn.optJSONObject("parameters")
        val outProps = JSONObject()
        params?.optJSONObject("properties")?.let { p ->
            val keys = p.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val type = p.optJSONObject(k)?.optString("type").orEmpty().ifBlank { "string" }
                outProps.put(k, JSONObject().put("type", type))
            }
        }
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", fn.optString("name"))
                put("description", desc)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", outProps)
                    put("required", params?.optJSONArray("required") ?: JSONArray())
                })
            })
        }
    }
}
