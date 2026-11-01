package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import com.jarvis.assistant.automation.tools.AppIndex
import com.jarvis.assistant.automation.tools.AppSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Launches an installed app by display label or package name ("open Discord",
 * "open Spotify"). Resolution reuses Jarvis' [AppIndex] / [AppSearch] (the
 * earlier AndroLLM port) with fuzzy matching ("YT" → YouTube):
 *
 * 1. Exact package name, then exact/partial/fuzzy label match.
 * 2. Multiple close matches → lists the candidates and asks instead of guessing.
 * 3. Installed but no launcher activity → explains why, never claims "not installed".
 * 4. Not found at all → suggests a Play Store search.
 */
class AppLauncherTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "launch_app",
        description = "Launch an installed app by its name (e.g. 'Spotify', 'Settings', 'WhatsApp') or package name. Matches partial and fuzzy names ('YT' for YouTube).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("app") {
                    put("type", "string")
                    put("description", "App name or package name")
                }
            }
            putJsonArray("required") { add("app") }
        },
        permission = ToolPermission.APPS,
        category = ToolCategory.MEDIA
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val app = ToolArgs.str(arguments, "app", "name", "package")
            ?: return ToolResult.Failure("Missing required argument: app")

        // "settings" is a super-common request with no launcher activity.
        if (app.equals("settings", ignoreCase = true)) {
            val opened = ToolIntents.launch(context, Intent(android.provider.Settings.ACTION_SETTINGS))
            return if (opened) ToolResult.Success("Opened Settings.")
            else ToolResult.Failure("Could not open Settings.")
        }

        val entries = withContext(Dispatchers.IO) { AppIndex.all(context) }
        if (entries.isEmpty()) {
            return ToolResult.Failure("I could not read the list of installed apps on this device.")
        }

        val result = AppSearch.search(entries, app)
        Log.i(TAG, "'$app' → ${result.matches.size} match(es), ambiguous=${result.ambiguous}")

        if (result.matches.isEmpty()) {
            // Never falsely claim it isn't installed; offer the Play Store.
            return ToolResult.Failure(
                "I couldn't find \"$app\" installed on this device. Would you like me to search the Play Store for it?",
                data = buildJsonObject {
                    put("app", app)
                    put("status", "not_found")
                }
            )
        }

        if (result.ambiguous) {
            val candidates = result.matches.joinToString("; ") {
                "\"${it.label}\" (${it.packageName})"
            }
            return ToolResult.Failure(
                "Several apps match \"$app\": $candidates. Which one do you mean?",
                data = buildJsonObject {
                    put("app", app)
                    put("status", "ambiguous")
                    putJsonArray("candidates") { result.matches.forEach { add(it.packageName) } }
                }
            )
        }

        val entry = result.matches.first()
        val pm = context.packageManager
        // Verify the launch intent; never claim missing on null.
        val intent = entry.launchIntent?.let { Intent(it) }
            ?: runCatching { pm.getLaunchIntentForPackage(entry.packageName) }.getOrNull()
        if (intent == null) {
            return ToolResult.Failure(
                "\"${entry.label}\" is installed but has no launcher activity — it may be a settings pane or widget-only app, so I can't open it directly.",
                data = buildJsonObject {
                    put("app", app)
                    put("package", entry.packageName)
                    put("status", "no_launcher")
                }
            )
        }

        val launched = ToolIntents.launch(context, intent)
        return if (launched) {
            ToolResult.Success(
                summary = "Opened ${entry.label}.",
                data = buildJsonObject {
                    put("app", app)
                    put("label", entry.label)
                    put("package", entry.packageName)
                    put("status", "launched")
                }
            )
        } else {
            ToolResult.Failure(
                "Could not open \"${entry.label}\" — the system blocked the launch. Try opening it from the app drawer.",
                data = buildJsonObject {
                    put("app", app)
                    put("package", entry.packageName)
                    put("status", "launch_blocked")
                }
            )
        }
    }

    private companion object {
        const val TAG = "AppLauncherTool"
    }
}
