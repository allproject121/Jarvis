package com.jarvis.assistant.automation.macro

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.agent.SerializedActionExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MacroEngine(
    private val context: Context,
    private val macroRepository: MacroRepository
) {
    companion object {
        private const val TAG = "MacroEngine"
    }

    suspend fun executeMacroByName(name: String, params: Map<String, String> = emptyMap()): MacroExecutionResult {
        val macro = macroRepository.findMacroByName(name)
            ?: return MacroExecutionResult.Failure("Macro '$name' not found", -1)
        return executeMacro(macro, params)
    }

    suspend fun executeMacro(macro: Macro, params: Map<String, String>): MacroExecutionResult = withContext(Dispatchers.Default) {
        Log.i(TAG, "Starting macro '${macro.name}' with ${macro.steps.size} steps. Params: $params")
        var lastExtractedText = ""

        for ((index, step) in macro.steps.withIndex()) {
            val stepSuccess = SerializedActionExecutor.execute("MacroStep_${index}_${step.tool}") {
                executeStep(step, params)
            } ?: false

            if (!stepSuccess) {
                if (step.isOptional) {
                    Log.w(TAG, "Optional step ${index + 1} (${step.tool}) failed/skipped, continuing.")
                } else {
                    val errMsg = "Step ${index + 1} (${step.tool}) failed."
                    Log.e(TAG, errMsg)
                    (context.applicationContext as? JarvisApp)?.museExperienceEngine?.recordTrajectory(
                        goal = macro.name,
                        stepsTaken = macro.steps.take(index + 1).map { it.tool },
                        wasSuccessful = false,
                        feedback = errMsg
                    )
                    return@withContext MacroExecutionResult.Failure(errMsg, index)
                }
            }
        }

        (context.applicationContext as? JarvisApp)?.museExperienceEngine?.recordTrajectory(
            goal = macro.name,
            stepsTaken = macro.steps.map { it.tool },
            wasSuccessful = true
        )
        MacroExecutionResult.Success("Macro '${macro.name}' executed successfully.", lastExtractedText.ifBlank { null })
    }

    private suspend fun executeStep(step: MacroStep, params: Map<String, String>): Boolean {
        val service = JarvisAccessibilityService.instance
        return when (step.tool) {
            "open_app_by_name" -> {
                val appName = step.args["name"]?.toString() ?: return false
                openAppByName(appName)
            }
            "wait" -> {
                val seconds = (step.args["seconds"] as? Number)?.toLong() ?: 2L
                delay(seconds * 1000L)
                true
            }
            "tap_text" -> {
                val text = step.args["text"]?.toString() ?: return false
                tapText(service, text)
            }
            "type_text" -> {
                var text = step.args["text"]?.toString() ?: return false
                for ((k, v) in params) {
                    text = text.replace("{$k}", v).replace("{${k.replace(" ", "_")}}", v)
                }
                typeText(service, text)
            }
            "wait_for_screen_text" -> {
                val target = step.args["text"]?.toString() ?: return false
                val mode = step.args["mode"]?.toString() ?: "appear"
                val timeoutSec = (step.args["timeout_seconds"] as? Number)?.toLong() ?: 60L
                waitForScreenText(service, target, mode == "appear", timeoutSec)
            }
            "go_back" -> {
                service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
            }
            "read_screen_text" -> {
                val collected = readAllScreenText(service)
                Log.i(TAG, "Screen text read: ${collected.take(100)}...")
                true
            }
            else -> {
                Log.w(TAG, "Unknown macro tool: ${step.tool}")
                false
            }
        }
    }

    private fun openAppByName(appName: String): Boolean {
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val matched = packages.firstOrNull {
                val label = pm.getApplicationLabel(it).toString()
                label.equals(appName, ignoreCase = true) || label.contains(appName, ignoreCase = true)
            }

            if (matched != null) {
                val launchIntent = pm.getLaunchIntentForPackage(matched.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return true
                }
            }

            // Fallback for well known packages
            val fallbackPackage = when (appName.lowercase()) {
                "chatgpt" -> "com.openai.chatgpt"
                "gemini" -> "com.google.android.apps.bard"
                "whatsapp" -> "com.whatsapp"
                "youtube" -> "com.google.android.youtube"
                else -> null
            }
            if (fallbackPackage != null) {
                val launchIntent = pm.getLaunchIntentForPackage(fallbackPackage)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app '$appName': ${e.message}")
        }
        return false
    }

    private fun tapText(service: JarvisAccessibilityService?, text: String): Boolean {
        val root = service?.rootInActiveWindow ?: return false
        val lowerTarget = text.lowercase().trim()
        val node = findNodeMatchingText(root, lowerTarget) ?: return false
        return service.clickNodeAndTap(node)
    }

    private fun findNodeMatchingText(node: AccessibilityNodeInfo, lowerTarget: String): AccessibilityNodeInfo? {
        val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").lowercase()
        if (label.contains(lowerTarget)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeMatchingText(child, lowerTarget)
            if (found != null) return found
        }
        return null
    }

    private fun typeText(service: JarvisAccessibilityService?, text: String): Boolean {
        val root = service?.rootInActiveWindow ?: return false
        val editNode = findFocusedOrFirstEditText(root) ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val res = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (!res) {
            service.clickNodeAndTap(editNode)
            editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return true
    }

    private fun findFocusedOrFirstEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && (node.className?.contains("EditText") == true || node.isEditable)) {
            return node
        }
        if (node.className?.contains("EditText") == true || node.isEditable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedOrFirstEditText(child)
            if (found != null) return found
        }
        return null
    }

    private suspend fun waitForScreenText(
        service: JarvisAccessibilityService?,
        text: String,
        shouldAppear: Boolean,
        timeoutSec: Long
    ): Boolean {
        val lowerTarget = text.lowercase().trim()
        val deadline = System.currentTimeMillis() + (timeoutSec * 1000L)

        while (System.currentTimeMillis() < deadline) {
            val root = service?.rootInActiveWindow
            val contains = root?.let { containsText(it, lowerTarget) } ?: false
            if (shouldAppear && contains) return true
            if (!shouldAppear && !contains) return true
            delay(1000L)
        }
        return false
    }

    private fun containsText(node: AccessibilityNodeInfo, lowerTarget: String): Boolean {
        val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").lowercase()
        if (label.contains(lowerTarget)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsText(child, lowerTarget)) return true
        }
        return false
    }

    private fun readAllScreenText(service: JarvisAccessibilityService?): String {
        val root = service?.rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        collectAllText(root, sb)
        return sb.toString().trim()
    }

    private fun collectAllText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val label = node.text?.toString() ?: node.contentDescription?.toString()
        if (!label.isNullOrBlank()) {
            sb.append(label).append("\n")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllText(child, sb)
        }
    }
}
