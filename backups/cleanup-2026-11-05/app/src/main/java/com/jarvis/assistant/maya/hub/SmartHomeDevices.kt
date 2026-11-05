package com.jarvis.assistant.maya.hub

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Maya Home": local ESP32 devices reached over plain HTTP on the LAN.
 *
 * The device model, firmware URL defaults and multi-target parsing are pure Kotlin
 * (unit-tested); [SmartHomeDeviceStore] persists to `filesDir/maya_smarthome.json`.
 */
object SmartHomeDevices {

    const val STORE_FILE = "maya_smarthome.json"

    enum class Kind(val label: String) {
        LIGHT("light"), PLUG("plug"), FAN("fan"), RELAY("relay");

        companion object {
            fun from(s: String?): Kind {
                val k = s.orEmpty().trim().lowercase()
                return when {
                    k.startsWith("light") || k == "bulb" || k == "lamp" || k == "led" || k == "batti" -> LIGHT
                    k.startsWith("plug") || k == "socket" || k == "charger" || k == "geyser" || k == "heater" -> PLUG
                    k.startsWith("fan") || k == "pankha" -> FAN
                    else -> RELAY
                }
            }
        }
    }

    /** Known ESP32 firmware families and the URL layout each one expects. */
    enum class Firmware(val label: String) {
        TASMOTA("tasmota"), ESPHOME("esphome"), SHELLY("shelly"), SIMPLE("simple"), CUSTOM("custom");

        companion object {
            fun from(s: String?): Firmware {
                val f = s.orEmpty().trim().lowercase()
                return when {
                    f.startsWith("tasmota") -> TASMOTA
                    f.startsWith("esphome") -> ESPHOME
                    f.startsWith("shelly") -> SHELLY
                    f.startsWith("custom") || f.startsWith("raw") || f.startsWith("url") -> CUSTOM
                    else -> SIMPLE
                }
            }
        }
    }

    data class Device(
        val id: String,
        val name: String,
        val room: String,
        val kind: Kind,
        val ip: String,
        val firmware: Firmware,
        /** Full URLs (http://ip/...) or empty when unsupported. */
        val onUrl: String,
        val offUrl: String,
        val toggleUrl: String,
        val statusUrl: String,
        /** ESPHome entity id / Tasmota relay index / Shelly relay index. */
        val channel: String = "",
        val addedAt: Long = System.currentTimeMillis()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("name", name); put("room", room); put("kind", kind.label); put("ip", ip)
            put("firmware", firmware.label); put("on_url", onUrl); put("off_url", offUrl)
            put("toggle_url", toggleUrl); put("status_url", statusUrl); put("channel", channel); put("added_at", addedAt)
        }

        fun urlFor(action: String): String = when (action) {
            "on" -> onUrl
            "off" -> offUrl
            "toggle" -> toggleUrl
            "status" -> statusUrl
            else -> ""
        }

        companion object {
            fun fromJson(o: JSONObject): Device = Device(
                id = o.optString("id"),
                name = o.optString("name"),
                room = o.optString("room"),
                kind = Kind.from(o.optString("kind")),
                ip = o.optString("ip"),
                firmware = Firmware.from(o.optString("firmware")),
                onUrl = o.optString("on_url"),
                offUrl = o.optString("off_url"),
                toggleUrl = o.optString("toggle_url"),
                statusUrl = o.optString("status_url"),
                channel = o.optString("channel"),
                addedAt = o.optLong("added_at", System.currentTimeMillis())
            )
        }
    }

    /** Normalises "192.168.1.20", "http://192.168.1.20/", "esp-light.local" into a base URL. */
    fun baseUrl(ipOrHost: String): String {
        var h = ipOrHost.trim()
        if (h.isEmpty()) return ""
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "http://$h"
        return h.trimEnd('/')
    }

    /** Default endpoints for the common ESP32 firmware families. */
    fun defaultUrls(firmware: Firmware, ipOrHost: String, kind: Kind, channel: String = ""): Map<String, String> {
        val base = baseUrl(ipOrHost)
        if (base.isEmpty()) return emptyMap()
        return when (firmware) {
            Firmware.TASMOTA -> {
                val idx = channel.toIntOrNull()?.let { if (it > 1) it.toString() else "" } ?: ""
                mapOf(
                    "on" to "$base/cm?cmnd=Power${idx}%20On",
                    "off" to "$base/cm?cmnd=Power${idx}%20Off",
                    "toggle" to "$base/cm?cmnd=Power${idx}%20Toggle",
                    "status" to "$base/cm?cmnd=Power$idx"
                )
            }
            Firmware.ESPHOME -> {
                val domain = if (kind == Kind.LIGHT) "light" else if (kind == Kind.FAN) "fan" else "switch"
                val id = channel.ifBlank { kind.label }
                mapOf(
                    "on" to "$base/$domain/$id/turn_on",
                    "off" to "$base/$domain/$id/turn_off",
                    "toggle" to "$base/$domain/$id/toggle",
                    "status" to "$base/$domain/$id"
                )
            }
            Firmware.SHELLY -> {
                val idx = channel.ifBlank { "0" }
                mapOf(
                    "on" to "$base/relay/$idx?turn=on",
                    "off" to "$base/relay/$idx?turn=off",
                    "toggle" to "$base/relay/$idx?turn=toggle",
                    "status" to "$base/relay/$idx"
                )
            }
            Firmware.SIMPLE -> mapOf(
                "on" to "$base/on",
                "off" to "$base/off",
                "toggle" to "$base/toggle",
                "status" to "$base/status"
            )
            Firmware.CUSTOM -> emptyMap()
        }
    }

    fun slug(name: String): String = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "device" }

    fun newDevice(
        name: String,
        room: String,
        kind: Kind,
        ipOrHost: String,
        firmware: Firmware,
        channel: String = "",
        customUrls: Map<String, String> = emptyMap()
    ): Device {
        val defaults = defaultUrls(firmware, ipOrHost, kind, channel)
        fun pick(k: String) = customUrls[k]?.trim()?.takeIf { it.isNotBlank() } ?: defaults[k].orEmpty()
        return Device(
            id = slug(name) + "_" + System.currentTimeMillis().toString(36).takeLast(4),
            name = name.trim(),
            room = room.trim(),
            kind = kind,
            ip = ipOrHost.trim(),
            firmware = firmware,
            onUrl = pick("on"), offUrl = pick("off"), toggleUrl = pick("toggle"), statusUrl = pick("status"),
            channel = channel
        )
    }

    // ── Multi-target parsing (ported from Maya's smart_home_control prompt rules) ──────────

    data class Resolution(
        val devices: List<Device>,
        /** Pieces of the target string that matched no local device (forwarded to Home Assistant). */
        val unknown: List<String>,
        val all: Boolean
    )

    private val allWords = setOf("all", "sab", "sabhi", "saari", "sari", "sare", "saare", "everything", "every", "har")
    private val kindWords = mapOf(
        "light" to Kind.LIGHT, "lights" to Kind.LIGHT, "batti" to Kind.LIGHT, "battiyan" to Kind.LIGHT, "bulb" to Kind.LIGHT, "bulbs" to Kind.LIGHT, "lamp" to Kind.LIGHT, "led" to Kind.LIGHT,
        "fan" to Kind.FAN, "fans" to Kind.FAN, "pankha" to Kind.FAN, "pankhe" to Kind.FAN,
        "plug" to Kind.PLUG, "plugs" to Kind.PLUG, "socket" to Kind.PLUG, "sockets" to Kind.PLUG,
        "relay" to Kind.RELAY, "relays" to Kind.RELAY, "switch" to Kind.RELAY, "switches" to Kind.RELAY
    )
    private val fillerWords = setOf("the", "ko", "ka", "ki", "ke", "wala", "wali", "waala", "waali", "my", "mera", "meri", "please", "device", "devices", "on", "off", "karo", "kar", "do")

    /** Splits "room light, blue light and fan" into pieces. */
    fun splitTargets(target: String): List<String> =
        target.split(Regex("""\s*(,|;|/|\band\b|\baur\b|&|\+|\bplus\b)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim().trim('.', '!') }
            .filter { it.isNotEmpty() }

    private fun tokens(s: String): List<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() && it !in fillerWords }

    /** Resolves one piece against the device list. Returns matches (possibly many for "all lights"/rooms). */
    fun resolvePiece(piece: String, devices: List<Device>): List<Device> {
        val toks = tokens(piece)
        if (toks.isEmpty()) return emptyList()
        val wantsAll = toks.any { it in allWords }
        val kindTok = toks.firstNotNullOfOrNull { kindWords[it] }
        val rest = toks.filter { it !in allWords && kindWords[it] == null }

        // "sab" / "saari lights" / "all fans" / "everything"
        if (wantsAll) {
            val byKind = if (kindTok != null) devices.filter { it.kind == kindTok } else devices
            val byRoom = if (rest.isNotEmpty()) byKind.filter { d -> rest.any { r -> d.room.lowercase().contains(r) } } else byKind
            return if (byRoom.isNotEmpty()) byRoom else byKind
        }

        // exact id / name
        devices.firstOrNull { it.id.equals(piece.trim(), true) || it.name.equals(piece.trim(), true) }?.let { return listOf(it) }

        // scored fuzzy match on name/room/kind tokens
        val scored = devices.map { d ->
            val nameToks = tokens(d.name).toSet()
            val roomToks = tokens(d.room).toSet()
            var score = 0
            for (t in rest) {
                if (t in nameToks) score += 3
                else if (nameToks.any { it.startsWith(t) || t.startsWith(it) }) score += 2
                if (t in roomToks) score += 2
            }
            if (kindTok != null && d.kind == kindTok) score += 1
            if (kindTok != null && d.kind != kindTok && rest.isEmpty()) score = 0
            d to score
        }.filter { it.second > 0 }
        if (scored.isEmpty()) {
            // "fan" alone with exactly one fan -> that fan; "lights" alone -> every light
            if (kindTok != null && rest.isEmpty()) {
                val ofKind = devices.filter { it.kind == kindTok }
                if (kindTok == Kind.LIGHT || ofKind.size == 1) return ofKind
            }
            return emptyList()
        }
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }.map { it.first }
        // Room-only phrase ("bedroom") with kind -> all matching devices in that room.
        return if (kindTok != null && rest.isNotEmpty() && top.size > 1) top else top.take(if (rest.isEmpty()) top.size else 1)
    }

    fun resolve(target: String, devices: List<Device>): Resolution {
        val pieces = splitTargets(target)
        if (pieces.isEmpty()) return Resolution(emptyList(), emptyList(), false)
        val found = LinkedHashMap<String, Device>()
        val unknown = ArrayList<String>()
        var all = false
        for (p in pieces) {
            val toks = tokens(p)
            val isAll = toks.isNotEmpty() && toks.all { it in allWords } || (toks.any { it in allWords } && toks.none { kindWords[it] != null })
            val matches = resolvePiece(p, devices)
            if (isAll && matches.size == devices.size) all = true
            if (matches.isEmpty()) unknown.add(p) else matches.forEach { found[it.id] = it }
        }
        return Resolution(found.values.toList(), unknown, all)
    }

    // ── Action parsing ────────────────────────────────────────────────────────

    fun normalizeAction(action: String?): String {
        val a = action.orEmpty().trim().lowercase()
        return when {
            a == "cancel" || a == "stop" || a == "roko" || a == "band karo pattern" -> "cancel"
            a.contains("blink") || a.contains("flash") || a.contains("strobe") || a.contains("jhapk") -> "blink"
            a.contains("chase") || a.contains("party") || a.contains("wave") || a.contains("sequence") -> "chase"
            a.contains("toggle") || a.contains("switch") || a.contains("flip") -> "toggle"
            a == "off" || a.startsWith("off") || a.contains("turn off") || a.contains("band") || a.contains("bujha") -> "off"
            a == "on" || a.startsWith("on") || a.contains("turn on") || a.contains("chalu") || a.contains("jala") || a.contains("start") -> "on"
            a.contains("status") || a.contains("state") || a.contains("query") -> "status"
            else -> a
        }
    }
}

/** JSON-file persistence for the device list. */
class SmartHomeDeviceStore(private val file: File) {

    @Volatile
    private var cache: List<SmartHomeDevices.Device>? = null

    @Synchronized
    fun all(): List<SmartHomeDevices.Device> {
        cache?.let { return it }
        val loaded = try {
            if (!file.exists()) emptyList() else {
                val root = JSONObject(file.readText())
                val arr = root.optJSONArray("devices") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { SmartHomeDevices.Device.fromJson(it) } }
            }
        } catch (e: Exception) {
            emptyList()
        }
        cache = loaded
        return loaded
    }

    @Synchronized
    fun save(devices: List<SmartHomeDevices.Device>) {
        val root = JSONObject().put("version", 1).put("devices", JSONArray().apply { devices.forEach { put(it.toJson()) } })
        file.parentFile?.mkdirs()
        file.writeText(root.toString(2))
        cache = devices
    }

    fun add(device: SmartHomeDevices.Device): SmartHomeDevices.Device {
        val existing = all().filter { !it.name.equals(device.name, true) }
        save(existing + device)
        return device
    }

    fun remove(nameOrId: String): SmartHomeDevices.Device? {
        val list = all()
        val victim = list.firstOrNull { it.id.equals(nameOrId.trim(), true) || it.name.equals(nameOrId.trim(), true) }
            ?: SmartHomeDevices.resolvePiece(nameOrId, list).singleOrNull()
            ?: return null
        save(list.filter { it.id != victim.id })
        return victim
    }

    fun find(nameOrId: String): SmartHomeDevices.Device? =
        all().firstOrNull { it.id.equals(nameOrId.trim(), true) || it.name.equals(nameOrId.trim(), true) }
}
