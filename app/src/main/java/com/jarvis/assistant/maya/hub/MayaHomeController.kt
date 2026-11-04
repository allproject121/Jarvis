package com.jarvis.assistant.maya.hub

import android.content.Context
import android.util.Log
import com.jarvis.assistant.automation.smarthome.SmartHomeManager
import com.jarvis.assistant.background.JarvisConversationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * "Maya Home" controller: local ESP32 devices over HTTP + pattern scheduling, with fallback
 * to the existing [SmartHomeManager] (Home Assistant / webhook) for targets that are not local
 * devices. The tool schedules all timing itself (blink/chase/interval/delay) and returns at once.
 */
class MayaHomeController private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MayaHome"

        @Volatile
        private var instance: MayaHomeController? = null

        fun get(context: Context): MayaHomeController =
            instance ?: synchronized(this) {
                instance ?: MayaHomeController(context.applicationContext).also { instance = it }
            }

        const val MSG_NO_DEVICES =
            "No smart-home device is linked to Jarvis yet - add your ESP32s in Maya Home (HUB -> Maya Home -> Add device, or smart_home_add_device), then I can switch them."
    }

    data class Outcome(val success: Boolean, val message: String, val json: JSONObject = JSONObject())

    val store = SmartHomeDeviceStore(File(context.filesDir, SmartHomeDevices.STORE_FILE))
    val scheduler = SmartHomeScheduler()
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()
    private val fallback: SmartHomeManager by lazy { SmartHomeManager(context) }

    /** Last known state per device id (from the last successful command / status poll). */
    private val lastState = HashMap<String, String>()

    fun devices(): List<SmartHomeDevices.Device> = store.all()

    // ── CRUD ───────────────────────────────────────────────────────────────

    fun addDevice(
        name: String,
        room: String,
        kind: String,
        ip: String,
        firmware: String,
        channel: String = "",
        onUrl: String = "",
        offUrl: String = "",
        toggleUrl: String = "",
        statusUrl: String = ""
    ): Outcome {
        if (name.isBlank()) return Outcome(false, "Device name is required.")
        val custom = mapOf("on" to onUrl, "off" to offUrl, "toggle" to toggleUrl, "status" to statusUrl)
        val fw = if (firmware.isBlank() && custom.values.any { it.isNotBlank() }) SmartHomeDevices.Firmware.CUSTOM
        else SmartHomeDevices.Firmware.from(firmware)
        if (ip.isBlank() && fw != SmartHomeDevices.Firmware.CUSTOM) return Outcome(false, "Device IP/host is required (e.g. 192.168.1.42).")
        val d = SmartHomeDevices.newDevice(name, room, SmartHomeDevices.Kind.from(kind), ip, fw, channel, custom)
        if (d.onUrl.isBlank() && d.offUrl.isBlank() && d.toggleUrl.isBlank()) {
            return Outcome(false, "No control URLs: give an IP with a firmware (tasmota/esphome/shelly/simple) or explicit on/off URLs.")
        }
        store.add(d)
        return Outcome(true, "Added '${d.name}'${if (d.room.isNotBlank()) " in ${d.room}" else ""} (${d.kind.label}, ${d.firmware.label}, ${d.ip.ifBlank { "custom URLs" }}). on=${d.onUrl}", d.toJson())
    }

    fun removeDevice(nameOrId: String): Outcome {
        val removed = store.remove(nameOrId) ?: return Outcome(false, "No device named '$nameOrId'. ${listSummary()}")
        scheduler.cancelForDevices(listOf(removed.id))
        return Outcome(true, "Removed '${removed.name}'.", removed.toJson())
    }

    fun listSummary(): String {
        val all = devices()
        if (all.isEmpty()) return MSG_NO_DEVICES
        return "Maya Home devices (${all.size}): " + all.joinToString("; ") { d ->
            "${d.name}${if (d.room.isNotBlank()) " [${d.room}]" else ""} (${d.kind.label}, ${d.ip})" +
                (lastState[d.id]?.let { " - $it" } ?: "")
        }
    }

    fun listJson(): JSONObject {
        val arr = JSONArray()
        devices().forEach { d -> arr.put(d.toJson().put("state", lastState[d.id] ?: "unknown")) }
        val running = JSONArray()
        scheduler.active().forEach { r ->
            running.put(JSONObject().put("label", r.label).put("devices", JSONArray(r.deviceIds)).put("ends_in_sec", ((r.endsAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)))
        }
        return JSONObject().put("devices", arr).put("running_patterns", running)
    }

    // ── Control ─────────────────────────────────────────────────────────────

    /**
     * One call does the whole request: multi-target, on/off/toggle/blink/chase/cancel,
     * interval_sec (gap between devices / blink edges), delay_sec (start later), count.
     */
    suspend fun control(
        target: String,
        action: String,
        intervalSec: Double? = null,
        delaySec: Double? = null,
        count: Int? = null
    ): Outcome = withContext(Dispatchers.IO) {
        val act = SmartHomeDevices.normalizeAction(action)
        val all = devices()
        val res = SmartHomeDevices.resolve(target, all)

        if (act == "cancel") {
            val n = if (res.all || res.devices.isEmpty()) scheduler.cancelAll() else scheduler.cancelForDevices(res.devices.map { it.id })
            return@withContext Outcome(true, if (n > 0) "Stopped $n running pattern/timer(s)." else "Nothing was running.", JSONObject().put("cancelled", n))
        }
        if (act == "status") return@withContext query(target)

        if (res.devices.isEmpty()) {
            // Not a local ESP32 - hand over to Home Assistant / webhook.
            if (all.isEmpty() && !fallbackConfigured()) return@withContext Outcome(false, MSG_NO_DEVICES)
            val unknown = res.unknown.ifEmpty { listOf(target) }
            return@withContext fallbackControl(unknown, act, delaySec, intervalSec)
        }

        val delayMs = PatternMath.clampDelayMs(delaySec)
        val ids = res.devices.map { it.id }
        val names = res.devices.joinToString(", ") { it.name }
        val targets = res.devices

        val steps: List<PatternMath.Step>
        val label: String
        when (act) {
            "on", "off" -> {
                val on = act == "on"
                val gapGiven = intervalSec != null && intervalSec > 0 && targets.size > 1
                if (delayMs == 0L && !gapGiven) {
                    // Immediate: do it now, report real results.
                    val results = targets.map { d -> d to switchDevice(d, on) }
                    val ok = results.count { it.second == null }
                    val failed = results.filter { it.second != null }
                    val msg = buildString {
                        append(if (ok == targets.size) "Done: $names ${if (on) "on" else "off"}." else "$ok/${targets.size} switched ${if (on) "on" else "off"}.")
                        if (failed.isNotEmpty()) append(" Failed: " + failed.joinToString("; ") { "${it.first.name} (${it.second})" })
                    }
                    return@withContext Outcome(failed.isEmpty(), msg, JSONObject().put("ok", ok).put("failed", failed.size).put("devices", JSONArray(ids)))
                }
                steps = PatternMath.delayed(PatternMath.staggered(targets.size, intervalSec, on), delayMs)
                label = (if (on) "on" else "off") + (if (gapGiven) " staggered" else "") + (if (delayMs > 0) " after ${delayMs / 1000}s" else "")
            }
            "toggle" -> {
                if (delayMs == 0L) {
                    val results = targets.map { d -> d to toggleDevice(d) }
                    val failed = results.filter { it.second != null }
                    return@withContext Outcome(failed.isEmpty(), if (failed.isEmpty()) "Toggled $names." else "Toggle failed for " + failed.joinToString { "${it.first.name} (${it.second})" })
                }
                steps = targets.indices.map { PatternMath.Step(delayMs, it, true) } // placeholder flag, toggled in executor
                label = "toggle after ${delayMs / 1000}s"
            }
            "blink" -> {
                steps = PatternMath.delayed(PatternMath.blink(targets.size, intervalSec, count), delayMs)
                label = "blink x${count ?: PatternMath.DEFAULT_BLINK_COUNT}"
            }
            "chase" -> {
                steps = PatternMath.delayed(PatternMath.chase(targets.size, intervalSec, count), delayMs)
                label = "chase x${count ?: PatternMath.DEFAULT_CHASE_ROUNDS}"
            }
            else -> return@withContext Outcome(false, "Unknown action '$action'. Use on, off, toggle, blink, chase or cancel.")
        }

        val isToggle = act == "toggle"
        val running = scheduler.schedule(label, ids, steps, onFinished = {
            notifyLive("Maya Home: '$label' on $names finished.")
        }) { index, on ->
            if (index < 0) targets.forEach { d -> if (isToggle) toggleDevice(d) else switchDevice(d, on) }
            else targets.getOrNull(index)?.let { d -> if (isToggle) toggleDevice(d) else switchDevice(d, on) }
        }
        val total = PatternMath.totalDurationMs(steps) / 1000.0
        val msg = buildString {
            append("Started '$label' on $names")
            if (delayMs > 0) append(" (begins in ${delayMs / 1000}s)")
            append(" - runs for ${"%.0f".format(total)}s, ${steps.size} switch events. ")
            append("Do not say 'done' yet; I will report when it finishes. To stop it: smart_home_control action='cancel'.")
        }
        Outcome(true, msg, JSONObject().put("pattern", running.key).put("label", label).put("devices", JSONArray(ids)).put("duration_sec", total).put("steps", steps.size))
    }

    /** Polls the status URL (or reports the last known state) for each matched device. */
    suspend fun query(target: String): Outcome = withContext(Dispatchers.IO) {
        val all = devices()
        val res = if (target.isBlank()) SmartHomeDevices.Resolution(all, emptyList(), true) else SmartHomeDevices.resolve(target, all)
        if (res.devices.isEmpty()) {
            if (fallbackConfigured()) return@withContext Outcome(true, fallback.getDeviceStates())
            return@withContext Outcome(false, if (all.isEmpty()) MSG_NO_DEVICES else "No device matches '$target'. ${listSummary()}")
        }
        val arr = JSONArray()
        val lines = res.devices.map { d ->
            val st = readStatus(d)
            arr.put(JSONObject().put("name", d.name).put("state", st))
            "${d.name}: $st"
        }
        val patterns = scheduler.active().filter { r -> r.deviceIds.any { id -> res.devices.any { it.id == id } } }
        val extra = if (patterns.isNotEmpty()) " Running: " + patterns.joinToString { "${it.label} (${((it.endsAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)}s left)" } else ""
        Outcome(true, lines.joinToString("; ") + extra, JSONObject().put("states", arr))
    }

    // ── HTTP ───────────────────────────────────────────────────────────────

    /** Returns null on success, else an error string. */
    suspend fun switchDevice(d: SmartHomeDevices.Device, on: Boolean): String? {
        val url = if (on) d.onUrl else d.offUrl
        if (url.isBlank()) return "no ${if (on) "on" else "off"} URL"
        val err = hit(d, url)
        if (err == null) lastState[d.id] = if (on) "on" else "off"
        return err
    }

    suspend fun toggleDevice(d: SmartHomeDevices.Device): String? {
        if (d.toggleUrl.isNotBlank()) {
            val err = hit(d, d.toggleUrl)
            if (err == null) lastState[d.id] = when (lastState[d.id]) { "on" -> "off"; "off" -> "on"; else -> "toggled" }
            return err
        }
        val next = lastState[d.id] != "on"
        return switchDevice(d, next)
    }

    suspend fun readStatus(d: SmartHomeDevices.Device): String {
        if (d.statusUrl.isBlank()) return lastState[d.id] ?: "unknown (no status URL)"
        return try {
            http.newCall(Request.Builder().url(d.statusUrl).get().build()).execute().use { r ->
                val body = r.body?.string().orEmpty().trim()
                if (!r.isSuccessful) return "unreachable (HTTP ${r.code})"
                val parsed = parseState(body)
                if (parsed != null) lastState[d.id] = parsed
                parsed ?: body.take(60).ifBlank { "online" }
            }
        } catch (e: Exception) {
            "offline (${e.javaClass.simpleName})"
        }
    }

    private fun parseState(body: String): String? {
        val b = body.trim()
        // Tasmota {"POWER":"ON"}, ESPHome {"state":"ON"}, Shelly {"ison":true}, simple "on"/"1"
        try {
            val o = JSONObject(b)
            o.keys().forEach { k ->
                val lk = k.lowercase()
                if (lk.startsWith("power") || lk == "state" || lk == "status") {
                    val v = o.optString(k).lowercase()
                    if (v == "on" || v == "off") return v
                }
                if (lk == "ison" || lk == "output") return if (o.optBoolean(k)) "on" else "off"
            }
        } catch (ignored: Exception) { }
        val lb = b.lowercase()
        return when {
            lb == "on" || lb == "1" || lb == "true" -> "on"
            lb == "off" || lb == "0" || lb == "false" -> "off"
            else -> null
        }
    }

    private suspend fun hit(d: SmartHomeDevices.Device, url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).let { b ->
                // ESPHome expects POST for actions; everything else is happy with GET.
                if (d.firmware == SmartHomeDevices.Firmware.ESPHOME && (url.contains("/turn_") || url.endsWith("/toggle")))
                    b.post(ByteArray(0).toRequestBody(null))
                else b.get()
            }.build()
            http.newCall(req).execute().use { r ->
                if (r.isSuccessful) null else "HTTP ${r.code}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Device ${d.name} unreachable at $url: ${e.message}")
            e.message?.take(60) ?: e.javaClass.simpleName
        }
    }

    // ── Fallback to Home Assistant / webhook ─────────────────────────────────

    private fun fallbackConfigured(): Boolean = try {
        val p = (context.applicationContext as? com.jarvis.assistant.JarvisApp)?.preferences
            ?: com.jarvis.assistant.data.preferences.AppPreferences(context)
        p.homeAssistantUrl.isNotBlank() || p.smartHomeWebhookUrl.isNotBlank()
    } catch (e: Exception) {
        false
    }

    private suspend fun fallbackControl(targets: List<String>, action: String, delaySec: Double?, intervalSec: Double?): Outcome {
        if (!fallbackConfigured()) {
            return Outcome(false, "No local device matches '${targets.joinToString(", ")}' and Home Assistant/webhook is not configured. ${listSummary()}")
        }
        val delayMs = PatternMath.clampDelayMs(delaySec)
        if (delayMs > 0) {
            scheduler.schedule("HA $action after ${delayMs / 1000}s", targets.map { "ha:$it" }, listOf(PatternMath.Step(delayMs, -1, action == "on")), onFinished = {
                notifyLive("Smart home: '$action' sent to ${targets.joinToString()} via Home Assistant.")
            }) { _, _ ->
                targets.forEach { t -> fallback.controlDevice(t, action, 0, intervalSec?.toInt() ?: 0) }
            }
            return Outcome(true, "Scheduled '$action' for ${targets.joinToString(", ")} via Home Assistant in ${delayMs / 1000}s.")
        }
        val results = targets.map { t -> fallback.controlDevice(t, action, 0, intervalSec?.toInt() ?: 0) }
        return Outcome(results.none { it.contains("Error", true) || it.contains("Failed", true) || it.contains("required", true) }, results.joinToString("\n"))
    }

    private fun notifyLive(text: String) {
        try {
            if (JarvisConversationService.isSessionOn.value) JarvisConversationService.sendTextMessage("[SYSTEM EVENT] $text")
        } catch (e: Exception) {
            Log.w(TAG, "notifyLive failed: ${e.message}")
        }
    }
}
