package com.jarvis.assistant.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.jarvis.assistant.ui.compose.navigation.ComposeRoutes
import com.jarvis.assistant.ui.settings.AndroLlmPageActivity

/**
 * DEBUG BUILD ONLY. Opens a Settings page exactly like its Settings row does, from a PC:
 *
 *   adb shell am start -n com.jarvis.assistant/.debug.DebugOpenActivity --es section VOICE
 *       (sections: VOICE AGENT AUTOMATION PERMISSIONS ENGINES MODELS CHAT MEMORY MCP)
 *   adb shell am start -n com.jarvis.assistant/.debug.DebugOpenActivity --es route tool_debug
 *       (routes: tool_debug developer models voice prompts cloud profile — or TOOL_DEBUG, DEVELOPER …)
 *
 * Starts the same AndroLlmPageActivity.sectionIntent / routeIntent the rows start, then closes.
 * Result goes to logcat tag "JarvisDebugOpen".
 */
class DebugOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sectionArg = intent.getStringExtra("section")?.trim()
        val routeArg = intent.getStringExtra("route")?.trim()
        try {
            when {
                !sectionArg.isNullOrEmpty() -> {
                    val section = AndroLlmPageActivity.Section.entries.firstOrNull { it.name.equals(sectionArg, ignoreCase = true) }
                    if (section == null) {
                        Log.e(TAG, "Unknown section '$sectionArg'. Use one of ${AndroLlmPageActivity.Section.entries.joinToString { it.name }}")
                    } else {
                        startActivity(AndroLlmPageActivity.sectionIntent(this, section))
                        Log.i(TAG, "Opened section $section")
                    }
                }
                !routeArg.isNullOrEmpty() -> {
                    val route = resolveRoute(routeArg)
                    startActivity(AndroLlmPageActivity.routeIntent(this, route))
                    Log.i(TAG, "Opened route $route")
                }
                else -> Log.e(TAG, "Pass --es section <NAME> or --es route <route>")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not open page: ${e.message}", e)
        }
        finish()
    }

    private fun resolveRoute(arg: String): String = when (arg.uppercase()) {
        "TOOL_DEBUG" -> ComposeRoutes.TOOL_DEBUG
        "DEVELOPER" -> ComposeRoutes.DEVELOPER
        "MODELS" -> ComposeRoutes.MODELS
        "VOICE" -> ComposeRoutes.VOICE
        "PROMPTS" -> ComposeRoutes.PROMPTS
        "CLOUD" -> ComposeRoutes.CLOUD
        "PROFILE" -> ComposeRoutes.PROFILE
        else -> arg
    }

    private companion object {
        const val TAG = "JarvisDebugOpen"
    }
}
