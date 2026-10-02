package com.example.myapplication

import android.os.Handler
import android.os.Looper
import android.util.Log

class JarvisAutomation(private val bridge: AndroidBridge) {

    private val handler = Handler(Looper.getMainLooper())

    private val taskQueue = mutableListOf<JarvisTask>()
    private var isExecuting = false
    private var currentWorkflow: String = ""
    private var workflowStep = 0
    private var isPaused = false

    data class JarvisTask(
        val type: String,
        val params: Map<String, String> = emptyMap(),
        val delay: Long = 1000,
        val priority: Int = 0,
        val tag: String = ""
    )

    fun enqueueTask(task: JarvisTask) {
        val index = taskQueue.indexOfFirst { it.priority < task.priority }
        if (index >= 0) {
            taskQueue.add(index, task)
        } else {
            taskQueue.add(task)
        }
        if (!isExecuting) processNextTask()
    }

    fun enqueueTasks(tasks: List<JarvisTask>) {
        taskQueue.addAll(tasks)
        if (!isExecuting) processNextTask()
    }

    private fun processNextTask() {
        if (isPaused) return
        if (taskQueue.isEmpty()) {
            isExecuting = false
            Log.d("JarvisAutomation", "All tasks completed")
            return
        }
        isExecuting = true
        val task = taskQueue.removeAt(0)

        handler.postDelayed({
            executeTask(task)
            processNextTask()
        }, task.delay)
    }

    fun pauseQueue() {
        isPaused = true
    }

    fun resumeQueue() {
        isPaused = false
        if (!isExecuting) processNextTask()
    }

    fun clearQueue() {
        taskQueue.clear()
        isExecuting = false
    }

    fun getQueueSize(): Int = taskQueue.size

    private fun executeTask(task: JarvisTask) {
        Log.d("JarvisAutomation", "Executing: ${task.type} with params ${task.params}")
        when (task.type) {
            "OPEN_APP" -> bridge.openApp(task.params["appName"] ?: "")
            "OPEN_APP_PKG" -> bridge.openAppPackage(task.params["package"] ?: "")
            "SEARCH_YT" -> executeYouTubeSearch(task.params["query"] ?: "")
            "SEND_WA" -> executeWhatsAppMessage(task.params["contact"] ?: "", task.params["message"] ?: "")
            "BACK" -> JarvisAccessibilityService.instance?.performBack()
            "HOME" -> JarvisAccessibilityService.instance?.performHome()
            "RECENTS" -> JarvisAccessibilityService.instance?.performRecents()
            "CLICK" -> {
                val coords = task.params["coords"]?.split(",")
                if (coords?.size == 2) {
                    JarvisAccessibilityService.instance?.performTap(coords[0].toFloat(), coords[1].toFloat())
                }
            }
            "CLICK_TEXT" -> JarvisAccessibilityService.instance?.findAndClickText(task.params["text"] ?: "")
            "TYPE_TEXT" -> JarvisAccessibilityService.instance?.findAndTypeTextFuzzy(task.params["text"] ?: "")
            "SCROLL_DOWN" -> JarvisAccessibilityService.instance?.performScrollDown()
            "SCROLL_UP" -> JarvisAccessibilityService.instance?.performScrollUp()
            "SWIPE_LEFT" -> JarvisAccessibilityService.instance?.performSwipeLeft()
            "SWIPE_RIGHT" -> JarvisAccessibilityService.instance?.performSwipeRight()
            "SWIPE" -> {
                val fromX = task.params["fromX"]?.toFloatOrNull() ?: 0f
                val fromY = task.params["fromY"]?.toFloatOrNull() ?: 0f
                val toX = task.params["toX"]?.toFloatOrNull() ?: 0f
                val toY = task.params["toY"]?.toFloatOrNull() ?: 0f
                JarvisAccessibilityService.instance?.performSwipe(fromX, fromY, toX, toY)
            }
            "LONG_PRESS" -> {
                val x = task.params["x"]?.toFloatOrNull() ?: 0f
                val y = task.params["y"]?.toFloatOrNull() ?: 0f
                JarvisAccessibilityService.instance?.performLongPress(x, y)
            }
            "DOUBLE_TAP" -> {
                val x = task.params["x"]?.toFloatOrNull() ?: 0f
                val y = task.params["y"]?.toFloatOrNull() ?: 0f
                JarvisAccessibilityService.instance?.performDoubleTap(x, y)
            }
            "NOTIFICATIONS" -> JarvisAccessibilityService.instance?.performNotifications()
            "QUICK_SETTINGS" -> JarvisAccessibilityService.instance?.performQuickSettings()
            "LOCK_SCREEN" -> JarvisAccessibilityService.instance?.performLockScreen()
            "TAKE_SCREENSHOT" -> JarvisAccessibilityService.instance?.performTakeScreenshot()
            "SPLIT_SCREEN" -> JarvisAccessibilityService.instance?.performSplitScreen()
            "SPEAK" -> bridge.speak(task.params["text"] ?: "")
            "WAIT" -> {
                // Delay already handled by task.delay
            }
            "SET_VOLUME" -> {
                val level = task.params["level"]?.toIntOrNull() ?: 50
                bridge.setVolume(task.params["stream"] ?: "media", level)
            }
            "TOGGLE_WIFI" -> {
                val enable = task.params["enable"]?.toBoolean()
                if (enable != null) bridge.setWifiEnabled(enable)
                else bridge.toggleWifi()
            }
            "TOGGLE_BLUETOOTH" -> {
                val enable = task.params["enable"]?.toBoolean()
                if (enable != null) bridge.setBluetoothEnabled(enable)
                else bridge.setBluetoothEnabled(!bridge.isBluetoothEnabled())
            }
            "TOGGLE_FLASHLIGHT" -> {
                val enable = task.params["enable"]?.toBoolean() ?: false
                bridge.toggleFlashlight(enable)
            }
            "SET_BRIGHTNESS" -> {
                val level = task.params["level"]?.toIntOrNull() ?: 128
                bridge.setBrightness(level)
            }
            "OPEN_URL" -> bridge.openUrl(task.params["url"] ?: "")
            "WEB_SEARCH" -> bridge.webSearch(task.params["query"] ?: "")
            "YOUTUBE_SEARCH" -> bridge.youtubeSearch(task.params["query"] ?: "")
            "SEND_SMS" -> bridge.sendSMS(task.params["number"] ?: "", task.params["message"] ?: "")
            "MAKE_CALL" -> bridge.makeCall(task.params["number"] ?: "")
            "SET_ALARM" -> {
                val hour = task.params["hour"]?.toIntOrNull() ?: 7
                val minute = task.params["minute"]?.toIntOrNull() ?: 0
                bridge.setAlarm(hour, minute, task.params["label"] ?: "Alarm")
            }
            "SET_TIMER" -> {
                val seconds = task.params["seconds"]?.toIntOrNull() ?: 60
                bridge.setTimer(seconds, task.params["label"] ?: "Timer")
            }
            "SET_RINGER" -> bridge.setRingerMode(task.params["mode"] ?: "normal")
            "DND" -> {
                if (task.params["enable"]?.toBoolean() == true) bridge.startDndMode()
                else bridge.stopDndMode()
            }
            "VIBRATE" -> bridge.vibrate(task.params["millis"]?.toLongOrNull() ?: 500)
            "LAUNCH_MAPS" -> bridge.openMaps(task.params["query"] ?: "")
            "NAVIGATE" -> {
                val lat = task.params["lat"]?.toDoubleOrNull() ?: 0.0
                val lng = task.params["lng"]?.toDoubleOrNull() ?: 0.0
                bridge.getDirection(lat, lng)
            }
        }
    }

    fun executeWhatsAppMessage(contactName: String, message: String) {
        enqueueTask(JarvisTask("OPEN_APP", mapOf("appName" to "WhatsApp"), 500))
        enqueueTask(JarvisTask("WAIT", delay = 3000))
        enqueueTask(JarvisTask("CLICK_TEXT", mapOf("text" to "Search"), 2000))
        enqueueTask(JarvisTask("TYPE_TEXT", mapOf("text" to contactName), 1000))
        enqueueTask(JarvisTask("WAIT", delay = 2000))
        enqueueTask(JarvisTask("CLICK_TEXT", mapOf("text" to contactName), 1500))
        enqueueTask(JarvisTask("WAIT", delay = 2000))
        enqueueTask(JarvisTask("TYPE_TEXT", mapOf("text" to message), 1000))
        enqueueTask(JarvisTask("CLICK_TEXT", mapOf("text" to "Send"), 1500))
    }

    fun executeYouTubeSearch(query: String) {
        enqueueTask(JarvisTask("OPEN_APP", mapOf("appName" to "YouTube"), 500))
        enqueueTask(JarvisTask("WAIT", delay = 3000))
        enqueueTask(JarvisTask("CLICK_TEXT", mapOf("text" to "Search"), 2000))
        enqueueTask(JarvisTask("TYPE_TEXT", mapOf("text" to query), 1000))
        enqueueTask(JarvisTask("WAIT", delay = 2000))
        enqueueTask(JarvisTask("CLICK_TEXT", mapOf("text" to "Search"), 1500))
        enqueueTask(JarvisTask("WAIT", delay = 2000))
        enqueueTask(JarvisTask("SCROLL_DOWN", delay = 1000))
    }

    fun executeAppChain(apps: List<String>) {
        for (app in apps) {
            enqueueTask(JarvisTask("OPEN_APP", mapOf("appName" to app), 1500))
            enqueueTask(JarvisTask("WAIT", delay = 2000))
        }
    }

    fun chainWorkflow(steps: List<JarvisTask>) {
        steps.forEach { enqueueTask(it) }
    }

    fun createRoutine(name: String, steps: List<JarvisTask>) {
        currentWorkflow = name
        workflowStep = 0
        chainWorkflow(steps)
        Log.d("JarvisAutomation", "Routine '$name' started with ${steps.size} steps")
    }

    fun goodNightRoutine() {
        createRoutine("Good Night", listOf(
            JarvisTask("SPEAK", mapOf("text" to "Good Night Sir. Aaram kijiye."), 500),
            JarvisTask("SET_RINGER", mapOf("mode" to "silent"), 2000),
            JarvisTask("DND", mapOf("enable" to "true"), 1000),
            JarvisTask("SET_BRIGHTNESS", mapOf("level" to "30"), 1000),
            JarvisTask("HOME", delay = 1000)
        ))
    }

    fun goodMorningRoutine() {
        createRoutine("Good Morning", listOf(
            JarvisTask("SPEAK", mapOf("text" to "Good Morning Sir! Aapka swagat hai."), 500),
            JarvisTask("SET_RINGER", mapOf("mode" to "normal"), 2000),
            JarvisTask("DND", mapOf("enable" to "false"), 1000),
            JarvisTask("SET_BRIGHTNESS", mapOf("level" to "180"), 1000),
            JarvisTask("TOGGLE_WIFI", mapOf("enable" to "true"), 1000)
        ))
    }

    fun workModeRoutine() {
        createRoutine("Work Mode", listOf(
            JarvisTask("SPEAK", mapOf("text" to "Work mode activate kar raha hoon Sir."), 500),
            JarvisTask("SET_RINGER", mapOf("mode" to "vibrate"), 2000),
            JarvisTask("OPEN_APP", mapOf("appName" to "Gmail"), 2000),
            JarvisTask("OPEN_APP", mapOf("appName" to "Chrome"), 2000)
        ))
    }

    fun drivingModeRoutine() {
        createRoutine("Driving Mode", listOf(
            JarvisTask("SPEAK", mapOf("text" to "Driving mode activate. Safe journey Sir."), 500),
            JarvisTask("DND", mapOf("enable" to "true"), 1000),
            JarvisTask("TOGGLE_WIFI", mapOf("enable" to "false"), 1000),
            JarvisTask("SET_BRIGHTNESS", mapOf("level" to "255"), 1000),
            JarvisTask("OPEN_APP", mapOf("appName" to "Maps"), 2000)
        ))
    }

    fun meetingModeRoutine() {
        createRoutine("Meeting Mode", listOf(
            JarvisTask("SPEAK", mapOf("text" to "Meeting mode activate. Main chup ho jata hoon."), 500),
            JarvisTask("SET_RINGER", mapOf("mode" to "silent"), 1000),
            JarvisTask("DND", mapOf("enable" to "true"), 1000),
            JarvisTask("SET_VOLUME", mapOf("level" to "0", "stream" to "ring"), 500)
        ))
    }

    fun executeMultiAppWorkflow(workflow: List<Pair<String, String>>) {
        for ((action, target) in workflow) {
            enqueueTask(JarvisTask(action, mapOf("target" to target), 2000))
        }
    }

    fun executeScheduledAction(action: String, params: Map<String, String>, delayMillis: Long) {
        handler.postDelayed({
            enqueueTask(JarvisTask(action, params))
        }, delayMillis)
    }

    fun cancelAllScheduled() {
        handler.removeCallbacksAndMessages(null)
        clearQueue()
    }

    fun getTaskStatus(): Map<String, Any> {
        return mapOf(
            "queueSize" to taskQueue.size,
            "isExecuting" to isExecuting,
            "isPaused" to isPaused,
            "currentWorkflow" to currentWorkflow,
            "workflowStep" to workflowStep
        )
    }
}
