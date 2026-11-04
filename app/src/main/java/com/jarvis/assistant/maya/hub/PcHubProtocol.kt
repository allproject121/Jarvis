package com.jarvis.assistant.maya.hub

import org.json.JSONObject
import java.security.SecureRandom

/**
 * Wire format of the Phone <-> PC hub (JSON text frames over a WebSocket at `ws://<pc>:<port>/jarvis`).
 * Mirrored by `companion/server.js` (`attachJarvisHub`). Pure Kotlin + org.json, unit-tested.
 *
 * Envelope: `{"v":1,"type":"ask","id":"m_...","ts":1730000000000,"reply_to":"m_...","payload":{...}}`
 */
object PcHubProtocol {

    const val VERSION = 1
    const val WS_PATH = "/jarvis"

    // phone -> PC
    const val T_HELLO = "hello"
    const val T_PAIR = "pair"
    const val T_ASK = "ask"
    const val T_CONTROL = "control"
    const val T_CALL_START = "call_start"
    const val T_CALL_CHUNK = "call_chunk"
    const val T_CALL_END = "call_end"
    const val T_MEMORY_PUSH = "memory_push"
    const val T_MEMORY_PULL = "memory_pull"
    const val T_PING = "ping"

    // PC -> phone
    const val T_WELCOME = "welcome"
    const val T_PAIRED = "paired"
    const val T_PAIR_FAILED = "pair_failed"
    const val T_REPLY = "reply"
    const val T_RESULT = "result"
    const val T_CALL_STARTED = "call_started"
    const val T_CALL_REPLY = "call_reply"
    const val T_CALL_ENDED = "call_ended"
    const val T_MEMORY_ACK = "memory_ack"
    const val T_MEMORY_DATA = "memory_data"
    const val T_PONG = "pong"
    const val T_ERROR = "error"
    /** Unsolicited event from the PC (e.g. its own system notices) - injected as [PC] when live. */
    const val T_EVENT = "event"

    data class Message(
        val type: String,
        val id: String,
        val payload: JSONObject = JSONObject(),
        val replyTo: String? = null,
        val ts: Long = System.currentTimeMillis(),
        val version: Int = VERSION
    ) {
        fun encode(): String = JSONObject().apply {
            put("v", version)
            put("type", type)
            put("id", id)
            put("ts", ts)
            if (replyTo != null) put("reply_to", replyTo)
            put("payload", payload)
        }.toString()

        fun text(): String = payload.optString("text", "")
        fun isReply(): Boolean = replyTo != null
    }

    private val random = SecureRandom()

    fun newId(prefix: String = "m"): String {
        val n = random.nextLong() and Long.MAX_VALUE
        return prefix + "_" + System.currentTimeMillis().toString(36) + "_" + n.toString(36).take(6)
    }

    fun build(type: String, payload: JSONObject = JSONObject(), replyTo: String? = null, id: String = newId()): Message =
        Message(type = type, id = id, payload = payload, replyTo = replyTo)

    fun text(type: String, text: String, replyTo: String? = null): Message =
        build(type, JSONObject().put("text", text), replyTo)

    /** Decodes a frame; returns null for anything that is not a well-formed envelope. */
    fun decode(raw: String): Message? {
        val o = try { JSONObject(raw) } catch (e: Exception) { return null }
        val type = o.optString("type", "")
        if (type.isBlank()) return null
        val id = o.optString("id", "").ifBlank { newId("x") }
        val payload = o.optJSONObject("payload") ?: JSONObject()
        val replyTo = if (o.has("reply_to") && !o.isNull("reply_to")) o.optString("reply_to") else null
        return Message(
            type = type,
            id = id,
            payload = payload,
            replyTo = replyTo?.takeIf { it.isNotBlank() },
            ts = o.optLong("ts", System.currentTimeMillis()),
            version = o.optInt("v", VERSION)
        )
    }

    fun helloPayload(deviceName: String, appVersion: String, phoneJoinCode: String, token: String): JSONObject =
        JSONObject().apply {
            put("device", deviceName)
            put("app", "jarvis-android")
            put("version", appVersion)
            put("phone_code", phoneJoinCode)
            if (token.isNotBlank()) put("token", token)
        }

    fun wsUrl(host: String, port: Int, token: String = ""): String {
        val h = host.trim().removePrefix("http://").removePrefix("https://").removePrefix("ws://").removePrefix("wss://").trimEnd('/')
        val hostOnly = if (h.contains(':') && !h.startsWith("[")) h.substringBefore(':') else h
        val base = "ws://$hostOnly:$port$WS_PATH"
        return if (token.isBlank()) base else "$base?token=$token"
    }
}

/**
 * 6-digit join codes shown on both sides (PC console / phone screen) to pair the hub.
 */
object PcPairing {
    const val CODE_LENGTH = 6
    private val random = SecureRandom()

    fun generateCode(): String {
        val n = random.nextInt(1_000_000)
        return n.toString().padStart(CODE_LENGTH, '0')
    }

    /** Keeps only digits; returns null unless exactly six remain. */
    fun normalize(input: String?): String? {
        val digits = input.orEmpty().filter { it.isDigit() }
        return if (digits.length == CODE_LENGTH) digits else null
    }

    fun matches(entered: String?, expected: String?): Boolean {
        val a = normalize(entered) ?: return false
        val b = normalize(expected) ?: return false
        return a == b
    }

    /** "123 456" style display grouping. */
    fun pretty(code: String): String {
        val c = normalize(code) ?: return code
        return c.substring(0, 3) + " " + c.substring(3)
    }

    /** Parses "192.168.1.5:8765" / "ws://pc:8765/jarvis" into host + port. */
    fun parseHostPort(input: String, defaultPort: Int = HubPrefs.DEFAULT_PC_PORT): Pair<String, Int>? {
        var s = input.trim()
        if (s.isEmpty()) return null
        s = s.removePrefix("http://").removePrefix("https://").removePrefix("ws://").removePrefix("wss://")
        s = s.substringBefore('/')
        val host: String
        val port: Int
        if (s.startsWith("[")) {
            host = s.substringBefore(']').removePrefix("[")
            port = s.substringAfter("]:", "").toIntOrNull() ?: defaultPort
        } else if (s.count { it == ':' } == 1) {
            host = s.substringBefore(':')
            port = s.substringAfter(':').toIntOrNull() ?: defaultPort
        } else {
            host = s
            port = defaultPort
        }
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
    }
}
