package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Best-effort Wi-Fi toggle. `setWifiEnabled` is deprecated and blocked on
 * Android 10+ (API 29); there the tool opens the Wi-Fi quick panel (Jarvis'
 * set_wifi behaviour, falling back to the full Wi-Fi settings) and reports
 * the state honestly.
 */
class WifiTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "set_wifi",
        description = "Turn Wi-Fi on or off. On newer Android versions the OS requires opening the Wi-Fi panel instead.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("enabled") {
                    put("type", "boolean")
                    put("description", "true to enable, false to disable")
                }
            }
            putJsonArray("required") { add("enabled") }
        },
        permission = ToolPermission.WIFI,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val enabled = ToolArgs.bool(arguments, "enabled", true)
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return ToolResult.Failure("This device has no Wi-Fi.")
        val result = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching {
                @Suppress("DEPRECATION")
                if (wifi.isWifiEnabled != enabled) wifi.setWifiEnabled(enabled) else true
            }.getOrDefault(false)
        } else {
            false // blocked by the OS on API 29+
        }

        if (result) {
            return ToolResult.Success(
                summary = if (enabled) "Wi-Fi enabled." else "Wi-Fi disabled.",
                data = buildJsonObject {
                    put("enabled", enabled)
                    put("status", if (enabled) "on" else "off")
                }
            )
        }
        val opened = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ToolIntents.launch(context, Intent(Settings.Panel.ACTION_WIFI))) ||
            ToolIntents.launch(context, Intent(Settings.ACTION_WIFI_SETTINGS))
        return if (opened) {
            ToolResult.Success(
                summary = "This Android version blocks apps from toggling Wi-Fi — I opened the Wi-Fi panel so you can turn it ${if (enabled) "on" else "off"}.",
                data = buildJsonObject {
                    put("enabled", enabled)
                    put("status", "settings-opened")
                }
            )
        } else {
            ToolResult.Failure("Could not change Wi-Fi — toggle it manually in Android settings.")
        }
    }
}
