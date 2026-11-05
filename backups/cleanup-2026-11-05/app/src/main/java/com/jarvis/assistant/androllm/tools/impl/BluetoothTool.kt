package com.jarvis.assistant.androllm.tools.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
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
 * Best-effort Bluetooth toggle. Programmatic enable/disable only works on
 * older Android (the classic API is deprecated/blocked on modern releases);
 * on blocked devices the tool falls back to opening the Bluetooth settings
 * panel and reports the state honestly. (Jarvis' get_bluetooth_status covers
 * reading the state; this adds toggling.)
 */
class BluetoothTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "set_bluetooth",
        description = "Turn Bluetooth on or off. On newer Android versions the OS may require opening the settings panel instead.",
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
        permission = ToolPermission.BLUETOOTH,
        category = ToolCategory.DEVICE
    )

    @Suppress("DEPRECATION", "MissingPermission")
    override suspend fun execute(arguments: JsonObject): ToolResult {
        val enabled = ToolArgs.bool(arguments, "enabled")
        val adapter: BluetoothAdapter? = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: BluetoothAdapter.getDefaultAdapter()
        }.getOrNull()
        if (adapter == null) {
            return ToolResult.Failure("This device has no Bluetooth.")
        }
        val result = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                when {
                    enabled && !adapter.isEnabled -> adapter.enable()
                    !enabled && adapter.isEnabled -> adapter.disable()
                    else -> true
                }
            } else {
                false // blocked by the OS on API 33+
            }
        }.getOrDefault(false)

        if (result) {
            return ToolResult.Success(
                summary = if (enabled) "Bluetooth enabled." else "Bluetooth disabled.",
                data = buildJsonObject {
                    put("enabled", enabled)
                    put("status", if (enabled) "on" else "off")
                }
            )
        }
        // Modern Android blocks programmatic toggling → open the settings panel.
        val opened = ToolIntents.launch(context, Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        return if (opened) {
            ToolResult.Success(
                summary = "This Android version blocks apps from toggling Bluetooth — I opened the Bluetooth settings for you.",
                data = buildJsonObject {
                    put("enabled", enabled)
                    put("status", "settings-opened")
                }
            )
        } else {
            ToolResult.Failure("Could not change Bluetooth — toggle it manually in Android settings.")
        }
    }
}
