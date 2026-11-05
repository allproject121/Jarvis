package com.jarvis.assistant.androllm.tools.impl

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import com.jarvis.assistant.androllm.permissions.Feature
import com.jarvis.assistant.androllm.permissions.PermissionManager
import com.jarvis.assistant.automation.phone.PhoneControlManager
import com.jarvis.assistant.automation.tools.AppIndex
import com.jarvis.assistant.util.PermissionHelper
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Jarvis' shared [PhoneControlManager] (null only before JarvisApp.onCreate finished). */
private fun phoneControl(context: Context): PhoneControlManager? =
    runCatching { (context.applicationContext as? JarvisApp)?.phoneControlManager }.getOrNull()

/**
 * Reads the battery state: level, charging status, technology, temperature.
 * No permission needed (sticky broadcast). Richer than Jarvis' get_telemetry
 * (which reports battery + network only).
 */
class BatteryTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "get_battery",
        description = "Read the current battery level (%), charging status, health and temperature.",
        permission = ToolPermission.DEVICE,
        category = ToolCategory.DEVICE,
        supportedTasks = listOf(
            "battery", "battery percentage", "charging", "charge level",
            "battery health", "battery temperature"
        ),
        cacheable = true
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return ToolResult.Failure("Could not read the battery state.")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return ToolResult.Failure("Could not read the battery state.")
        val pct = (level * 100 / scale.coerceAtLeast(1)).coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "battery"
        }
        val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
            else -> "unknown"
        }
        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0
        val tech = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
        val data = buildJsonObject {
            put("level", pct)
            put("charging", charging)
            put("plugged", source)
            put("health", health)
            put("temperature_c", tempC)
            put("technology", tech ?: "unknown")
        }
        val summary = "Battery at $pct%" +
            (if (charging) " (charging via $source)" else " (not charging)") +
            (if (tempC > 0) ", ${String.format(Locale.US, "%.1f", tempC)}°C" else "") +
            (if (health != "unknown") ", health $health" else "")
        return ToolResult.Success(summary, data)
    }
}

/**
 * Sets the media / ring / alarm volume (0..100). Media volume goes through
 * Jarvis' [PhoneControlManager.setVolumePercent] (same path as Jarvis'
 * set_volume, shows the system volume UI); ring and alarm use AudioManager
 * directly (AndroLLM behaviour — Jarvis had no per-stream control).
 */
class VolumeTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "set_volume",
        description = "Set the media volume to a percentage (0–100). Also accepts a stream: media, ring or alarm.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("percent") { put("type", "integer"); put("description", "0–100") }
                putJsonObject("stream") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("media", "ring", "alarm").forEach { add(it) } }
                }
            }
            putJsonArray("required") { add("percent") }
        },
        permission = ToolPermission.SYSTEM,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val raw = ToolArgs.int(arguments, "percent", ToolArgs.int(arguments, "level", -1))
        if (raw < 0) return ToolResult.Failure("Missing required argument: percent")
        val percent = raw.coerceIn(0, 100)
        val streamName = when (ToolArgs.str(arguments, "stream")?.lowercase()) {
            "ring" -> "ring"
            "alarm" -> "alarm"
            else -> "media"
        }
        if (streamName == "media") {
            val pcm = phoneControl(context)
            if (pcm != null) {
                val msg = withContext(Dispatchers.Main) { pcm.setVolumePercent(percent) }
                return if (msg.startsWith("Could not")) {
                    ToolResult.Failure(msg)
                } else {
                    ToolResult.Success(msg, buildJsonObject { put("stream", "media"); put("percent", percent) })
                }
            }
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (streamName) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(stream)
        if (max <= 0) return ToolResult.Failure("This stream has no volume control.")
        val target = (percent * max / 100).coerceIn(0, max)
        val ok = runCatching { am.setStreamVolume(stream, target, 0); true }.getOrDefault(false)
        return if (ok) {
            ToolResult.Success(
                "Set $streamName volume to $percent%.",
                buildJsonObject { put("stream", streamName); put("percent", percent) }
            )
        } else {
            ToolResult.Failure("Could not change the $streamName volume (Do Not Disturb may block ring changes).")
        }
    }
}

/**
 * Device information: model, Android version, SDK, screen, RAM, storage.
 */
class DeviceInfoTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "get_device_info",
        description = "Return device information: model, manufacturer, Android version, screen resolution, RAM and free storage.",
        permission = ToolPermission.DEVICE,
        category = ToolCategory.INFORMATION,
        supportedTasks = listOf(
            "device info", "device information", "device model", "ram",
            "storage", "android version", "phone specs", "my phone"
        )
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val dm = context.resources.displayMetrics
        // Device RAM (AndroLLM reported the VM heap limit here; Jarvis reports
        // the real total/available memory).
        val mem = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        }.getOrNull()
        val totalRamMb = (mem?.totalMem ?: 0L) / (1024L * 1024L)
        val availRamMb = (mem?.availMem ?: 0L) / (1024L * 1024L)
        val freeGb = runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
        }.getOrDefault(0.0)
        val data = buildJsonObject {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("screen", "${dm.widthPixels}x${dm.heightPixels}")
            put("dpi", dm.densityDpi)
            put("ram_mb", totalRamMb)
            put("ram_available_mb", availRamMb)
            put("free_storage_gb", freeGb)
        }
        return ToolResult.Success(
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "screen ${dm.widthPixels}x${dm.heightPixels}, RAM $totalRamMb MB ($availRamMb MB free), " +
                "${String.format(Locale.US, "%.1f", freeGb)} GB storage free",
            data
        )
    }
}

/**
 * Lists installed apps (visible labels + package names), read from Jarvis'
 * shared [AppIndex] (QUERY_ALL_PACKAGES is declared, so third-party apps show up).
 */
class PackageManagerTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "list_apps",
        description = "List installed apps (names + package names). Optionally filter by keyword (e.g. 'whats').",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") { put("type", "string"); put("description", "Optional filter keyword") }
            }
        },
        permission = ToolPermission.APPS,
        category = ToolCategory.MEDIA
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = ToolArgs.str(arguments, "query", "filter")?.lowercase()
        val apps = withContext(Dispatchers.IO) {
            runCatching { AppIndex.all(context).map { it.packageName to it.label } }.getOrNull()
        } ?: return ToolResult.Failure("Could not read installed apps.")
        val filtered = if (query != null) apps.filter {
            it.first.contains(query) || it.second.contains(query, ignoreCase = true)
        } else apps
        if (filtered.isEmpty()) {
            return ToolResult.Success("No installed app matches '${query ?: ""}'.")
        }
        val data = buildJsonObject {
            put("count", filtered.size)
            putJsonArray("apps") {
                filtered.forEach { (pkg, label) ->
                    add(buildJsonObject { put("name", label); put("package", pkg) })
                }
            }
        }
        return ToolResult.Success(
            filtered.joinToString("; ", limit = 20, truncated = "…") { (pkg, label) -> "$label ($pkg)" },
            data
        )
    }
}

/**
 * Recently used apps via UsageStatsManager. Needs the "Usage access" special
 * permission; without it the tool opens that settings screen and reports a
 * clear, actionable failure.
 */
class RunningAppsTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "get_running_apps",
        description = "Return apps used in the last 10 minutes (foreground first). Needs the Usage access permission in system settings.",
        permission = ToolPermission.DEVICE,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.packageName
            )
        }
        if (mode != AppOpsManager.MODE_ALLOWED) {
            ToolIntents.launch(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return ToolResult.Failure(
                "Usage access is not granted. I opened Settings → Usage access — enable it for Jarvis, then try again.",
                data = buildJsonObject { put("usage_access", false) },
                retryable = false
            )
        }
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = withContext(Dispatchers.IO) {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 10 * 60 * 1000L, now)
        } ?: emptyList()
        val top = stats.filter { it.lastTimeUsed >= now - 10 * 60 * 1000L }
            .sortedByDescending { it.lastTimeUsed }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .take(10)
        if (top.isEmpty()) return ToolResult.Success("No recent apps found.")
        val pm = context.packageManager
        fun labelOf(pkg: String): String = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val data = buildJsonObject {
            putJsonArray("apps") {
                top.forEach { s ->
                    add(buildJsonObject {
                        put("name", labelOf(s.packageName))
                        put("package", s.packageName)
                        put("last_used_minutes_ago", (now - s.lastTimeUsed) / 60000L)
                    })
                }
            }
        }
        return ToolResult.Success(top.joinToString(", ") { labelOf(it.packageName) }, data)
    }
}

/**
 * Vibrates the phone for a short burst. Delegates to Jarvis' existing vibrate
 * tool (AndroLlmTools — same AndroLLM logic with the API 31+ VibratorManager).
 */
class VibrateTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "vibrate",
        description = "Vibrate the phone for a short burst (default ~500ms).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("duration_ms") {
                    put("type", "integer")
                    put("description", "Vibration length in milliseconds (default 500)")
                }
            }
        },
        permission = ToolPermission.SYSTEM,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult =
        JarvisToolBridge.runAndroLlmTool(context, "vibrate", arguments)
}

/**
 * Sets the screen brightness (0–100). Requires the special 'Modify system
 * settings' permission (WRITE_SETTINGS).
 *
 * Jarvis-first: uses Jarvis' [PhoneControlManager.setBrightnessPercent]
 * (forces manual mode and writes screen_brightness_float, which modern
 * Android 13–15 skins require — strictly better than AndroLLM's 0–255 write).
 */
class BrightnessTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "set_brightness",
        description = "Set the screen brightness to a percentage (0–100). Requires the special 'Modify system settings' permission.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("percent") { put("type", "integer"); put("description", "0–100") }
            }
            putJsonArray("required") { add("percent") }
        },
        permission = ToolPermission.SYSTEM,
        requiresConfirmation = true,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val raw = ToolArgs.int(arguments, "percent", ToolArgs.int(arguments, "percentage", -1))
        if (raw < 0) return ToolResult.Failure("Missing required argument: percent")
        val percent = raw.coerceIn(0, 100)
        if (!PermissionHelper.hasWriteSettingsPermission(context)) {
            // Opens Settings → Modify system settings for Jarvis.
            PermissionHelper.requestWriteSettings(context)
            return ToolResult.Failure(
                "Screen brightness needs the 'Modify system settings' permission. " +
                    "I opened that settings screen — enable it for Jarvis, then try again.",
                retryable = false
            )
        }
        val pcm = phoneControl(context)
            ?: return ToolResult.Failure("Phone controls are not ready yet — try again in a moment.")
        val msg = withContext(Dispatchers.Main) { pcm.setBrightnessPercent(percent) }
        return if (msg.startsWith("Screen brightness set")) {
            ToolResult.Success(msg, buildJsonObject { put("percent", percent) })
        } else {
            ToolResult.Failure(msg)
        }
    }
}

/**
 * Opens the system clock / alarms app (no permission needed).
 */
class ClockTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "open_clock",
        description = "Open the system clock and alarms app.",
        permission = ToolPermission.APPS,
        category = ToolCategory.MEDIA
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val showAlarms = Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching {
            if (showAlarms.resolveActivity(context.packageManager) != null) {
                context.startActivity(showAlarms)
            } else {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory("android.intent.category.APP_CLOCK")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            true
        }.getOrDefault(false)
        return if (ok) {
            ToolResult.Success("Opened the clock app.")
        } else {
            ToolResult.Failure("Could not open the clock app.")
        }
    }
}

/**
 * Reads the device's last known location (GPS or network). Location is
 * requested lazily through [PermissionManager] when missing.
 */
class LocationTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "get_location",
        description = "Read the device's last known location (latitude, longitude, accuracy). Requires location permission.",
        permission = ToolPermission.LOCATION,
        requiresConfirmation = true,
        category = ToolCategory.INFORMATION
    )

    @Suppress("MissingPermission")
    override suspend fun execute(arguments: JsonObject): ToolResult {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return ToolResult.Failure("Location services are unavailable on this device.")
        if (!PermissionHelper.hasLocationPermission(context)) {
            return ToolResult.Failure(
                PermissionManager.getInstance(context).missingMessage(
                    Feature.WEATHER_LOCATION, "Reading your location needs location access."
                ),
                retryable = false
            )
        }
        val location = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider ->
                runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?: return ToolResult.Failure(
                "No recent location is available — open any maps app once to warm up the last-known location, then try again."
            )
        val data = buildJsonObject {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy_m", location.accuracy.toDouble())
            put("age_s", (System.currentTimeMillis() - location.time) / 1000L)
        }
        return ToolResult.Success(
            "Last known location: ${String.format(Locale.US, "%.5f", location.latitude)}, " +
                "${String.format(Locale.US, "%.5f", location.longitude)} (±${location.accuracy.toInt()}m)",
            data
        )
    }
}
