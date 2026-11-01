package com.jarvis.assistant.ui.godseye

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Live AIS vessel feed for God's Eye View.
 *
 * AISStream is a WebSocket-only, key-gated service that allows a single
 * connection per key, so the browser cannot talk to it directly (no CORS, and
 * the key must not reach the page). This holds one socket for the whole app,
 * accumulates the newest position per MMSI plus a thinned track, and serves
 * /api/vessels snapshots from that cache - mirroring the upstream
 * server/providers/vessels/ais-live.js.
 *
 * Without a key the feed reports `missing-key` honestly: no placeholder ships.
 */
object GodsEyeAisStream {

    private const val TAG = "GodsEyeAisStream"
    private const val AISSTREAM_URL = "wss://stream.aisstream.io/v0/stream"

    /** Positions older than this are dropped from the snapshot. */
    private const val STALE_MS = 30 * 60 * 1000L
    /** Hard cap on cached vessels so a worldwide subscription cannot grow forever. */
    private const val CACHE_MAX = 6000
    /** Feed reads as silent (not live) after this long without a message. */
    private const val SILENCE_MS = 120_000L
    /** Reconnect backoff ladder; the last entry repeats. */
    private val BACKOFF_MS = longArrayOf(5_000, 15_000, 60_000, 300_000)
    /** A rejected key cannot be fixed by retrying - probe at most hourly. */
    private const val AUTH_PROBE_MS = 3_600_000L
    private const val TRACK_MAX_SAMPLES = 240
    private const val TRACK_MIN_GAP_SEC = 20L

    data class Vessel(
        val mmsi: String,
        var lat: Double,
        var lon: Double,
        var name: String,
        var imo: String,
        var type: String,
        var destination: String,
        var speed: Double,
        var course: Double,
        var heading: Int,
        var lastPositionEpoch: Long,
        var lastPositionUtc: String,
        var updatedAt: Long
    )

    data class StaticInfo(val name: String, val type: String, val destination: String, val imo: String)

    private val vessels = ConcurrentHashMap<String, Vessel>()
    private val statics = ConcurrentHashMap<String, StaticInfo>()
    private val tracks = ConcurrentHashMap<String, MutableList<DoubleArray>>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // a quiet WebSocket must not time out
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var connecting = false
    @Volatile private var lastMessageAt = 0L
    @Volatile private var lastAttemptAt = 0L
    @Volatile private var failureCount = 0
    @Volatile private var authFailed = false
    @Volatile private var lastError: String? = null
    @Volatile private var keyFingerprint: String? = null

    private fun isoFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

    /**
     * Open or re-open the socket if the key allows it and the backoff has
     * elapsed. Safe to call on every /api/vessels request.
     */
    @Synchronized
    fun ensureConnected(apiKey: String) {
        if (apiKey.isBlank()) {
            shutdown("missing key")
            return
        }
        val fingerprint = apiKey.hashCode().toString()
        if (keyFingerprint != null && keyFingerprint != fingerprint) {
            // A changed key clears the terminal auth-failed state.
            authFailed = false
            failureCount = 0
            shutdown("key changed")
        }
        keyFingerprint = fingerprint

        if (socket != null || connecting) {
            // Recycle a socket that is connected but has gone silent for far
            // longer than the report threshold.
            val silentFor = System.currentTimeMillis() - lastMessageAt
            if (socket != null && lastMessageAt > 0L && silentFor > SILENCE_MS * 2.5) {
                Log.w(TAG, "AIS socket silent for ${silentFor}ms; recycling")
                shutdown("silent")
            } else {
                return
            }
        }

        val now = System.currentTimeMillis()
        val waitMs = if (authFailed) {
            AUTH_PROBE_MS
        } else {
            BACKOFF_MS[minOf(failureCount, BACKOFF_MS.size - 1)]
        }
        if (failureCount > 0 && now - lastAttemptAt < waitMs) return
        lastAttemptAt = now
        connecting = true

        val request = Request.Builder().url(AISSTREAM_URL).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connecting = false
                failureCount = 0
                lastError = null
                val worldBox = JSONArray()
                    .put(JSONArray().put(-90.0).put(-180.0))
                    .put(JSONArray().put(90.0).put(180.0))
                val subscription = JSONObject().apply {
                    put("APIKey", apiKey)
                    put("BoundingBoxes", JSONArray().put(worldBox))
                    put("FilterMessageTypes", JSONArray(listOf(
                        "PositionReport",
                        "StandardClassBPositionReport",
                        "ExtendedClassBPositionReport",
                        "ShipStaticData",
                        "StaticDataReport"
                    )))
                }
                webSocket.send(subscription.toString())
                Log.i(TAG, "AISStream subscribed (worldwide)")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    ingest(JSONObject(text))
                } catch (e: Exception) {
                    Log.d(TAG, "AIS envelope parse failed: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connecting = false
                socket = null
                failureCount++
                lastError = t.message ?: "AIS stream error"
                if (response?.code == 401 || response?.code == 403) {
                    authFailed = true
                    lastError = "AISStream rejected the API key"
                }
                Log.w(TAG, "AISStream failure (#$failureCount): $lastError")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connecting = false
                socket = null
                Log.i(TAG, "AISStream closed: $code $reason")
            }
        })
    }

    @Synchronized
    private fun shutdown(reason: String) {
        socket?.cancel()
        socket = null
        connecting = false
        if (reason == "missing key" || reason == "key changed") {
            vessels.clear()
            statics.clear()
            tracks.clear()
            lastMessageAt = 0L
        }
    }

    private fun ingest(envelope: JSONObject) {
        lastMessageAt = System.currentTimeMillis()
        val messageType = envelope.optString("MessageType")
        if (messageType.isBlank()) {
            // AISStream reports a bad key as a plain {"error": "..."} frame.
            val err = envelope.optString("error", envelope.optString("Error"))
            if (err.isNotBlank()) {
                lastError = err
                if (err.contains("api key", true) || err.contains("invalid", true)) authFailed = true
            }
            return
        }
        val message = envelope.optJSONObject("Message")?.optJSONObject(messageType) ?: JSONObject()
        val metadata = envelope.optJSONObject("MetaData") ?: envelope.optJSONObject("Metadata") ?: JSONObject()

        val mmsi = firstString(metadata.opt("MMSI"), metadata.opt("mmsi"), message.opt("UserID"))
        if (mmsi.isBlank()) return

        if (messageType == "ShipStaticData" || messageType == "StaticDataReport") {
            val prior = statics[mmsi]
            val merged = StaticInfo(
                name = shipName(metadata, message, prior),
                type = shipType(message, prior),
                destination = firstString(message.opt("Destination"), prior?.destination),
                imo = firstString(message.opt("ImoNumber"), message.opt("IMO"), prior?.imo)
            )
            statics[mmsi] = merged
            vessels[mmsi]?.let { existing ->
                if (merged.name.isNotBlank() && (existing.name.isBlank() || existing.name == "MMSI $mmsi")) {
                    existing.name = merged.name
                }
                if (merged.type.isNotBlank()) existing.type = merged.type
                if (merged.destination.isNotBlank()) existing.destination = merged.destination
                if (merged.imo.isNotBlank()) existing.imo = merged.imo
            }
            return
        }

        val lat = firstDouble(metadata.opt("latitude"), metadata.opt("Latitude"), message.opt("Latitude"))
        val lon = firstDouble(metadata.opt("longitude"), metadata.opt("Longitude"), message.opt("Longitude"))
        // A positionless but well-formed record still proves the feed is alive.
        if (lat.isNaN() || lon.isNaN()) return

        val staticInfo = statics[mmsi]
        val timeUtc = firstString(metadata.opt("time_utc"), metadata.opt("TimeUtc"))
        val epochSec = epochSeconds(timeUtc)
        val resolvedName = shipName(metadata, message, staticInfo).ifBlank { "MMSI $mmsi" }

        vessels[mmsi] = Vessel(
            mmsi = mmsi,
            lat = lat,
            lon = lon,
            name = resolvedName,
            imo = firstString(message.opt("ImoNumber"), message.opt("IMO"), staticInfo?.imo),
            type = shipType(message, staticInfo),
            destination = firstString(message.opt("Destination"), staticInfo?.destination),
            speed = normalizedSpeed(firstDouble(message.opt("Sog"), message.opt("SOG"))),
            course = normalizedCourse(firstDouble(message.opt("Cog"), message.opt("COG"))),
            heading = normalizedHeading(firstDouble(message.opt("TrueHeading"), message.opt("Heading"))),
            lastPositionEpoch = epochSec,
            lastPositionUtc = timeUtc.ifBlank { isoFormat().format(Date(epochSec * 1000)) },
            updatedAt = System.currentTimeMillis()
        )

        appendTrack(mmsi, lat, lon, epochSec)
        prune()
    }

    private fun appendTrack(mmsi: String, lat: Double, lon: Double, epochSec: Long) {
        val samples = tracks.getOrPut(mmsi) { Collections.synchronizedList(ArrayList()) }
        synchronized(samples) {
            val last = samples.lastOrNull()
            // Anchored vessels collapse to a single point instead of a dense blob.
            if (last != null && epochSec - last[2].toLong() < TRACK_MIN_GAP_SEC) return
            samples.add(doubleArrayOf(lat, lon, epochSec.toDouble()))
            while (samples.size > TRACK_MAX_SAMPLES) samples.removeAt(0)
        }
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - STALE_MS
        if (vessels.size > CACHE_MAX) {
            vessels.entries
                .sortedBy { it.value.updatedAt }
                .take(vessels.size - CACHE_MAX)
                .forEach {
                    vessels.remove(it.key)
                    tracks.remove(it.key)
                    statics.remove(it.key)
                }
        }
        vessels.entries.filter { it.value.updatedAt < cutoff }.forEach {
            vessels.remove(it.key)
            tracks.remove(it.key)
        }
    }

    /** `live` once messages are flowing, `missing-key`/`idle`/`down` otherwise. */
    private fun feedStatus(apiKey: String): String = when {
        apiKey.isBlank() -> "missing-key"
        authFailed -> "auth-failed"
        lastMessageAt > 0L && System.currentTimeMillis() - lastMessageAt < SILENCE_MS -> "live"
        lastMessageAt > 0L -> "silent"
        failureCount >= BACKOFF_MS.size -> "down"
        else -> "idle"
    }

    private fun statusError(status: String): String? = when (status) {
        "missing-key" -> "AISSTREAM_API_KEY is not set - add it in Jarvis > Settings > API Connections"
        "auth-failed" -> lastError ?: "AISStream rejected the API key"
        "silent" -> "AISStream has sent no traffic for over two minutes"
        "down" -> lastError ?: "AISStream is unreachable"
        "idle" -> "Connecting to AISStream..."
        else -> null
    }

    /** HTTP status the /api/vessels response should carry. */
    fun httpStatusFor(apiKey: String): Int = if (apiKey.isBlank()) 503 else 200

    fun snapshot(apiKey: String, maxRows: Int = 3000): JSONObject {
        ensureConnected(apiKey)
        val status = feedStatus(apiKey)
        val cutoff = System.currentTimeMillis() - STALE_MS
        val rows = JSONArray()
        var newestEpoch = 0L
        vessels.values
            .asSequence()
            .filter { it.updatedAt >= cutoff }
            .sortedByDescending { it.updatedAt }
            .take(maxRows)
            .forEach { v ->
                if (v.lastPositionEpoch > newestEpoch) newestEpoch = v.lastPositionEpoch
                rows.put(JSONObject().apply {
                    put("mmsi", v.mmsi)
                    put("name", v.name)
                    put("type", v.type)
                    put("imo", v.imo)
                    put("destination", v.destination)
                    put("lat", v.lat)
                    put("lon", v.lon)
                    put("speed", v.speed)
                    put("course", v.course)
                    put("heading", v.heading)
                    put("last_position_epoch", v.lastPositionEpoch)
                    put("last_position_UTC", v.lastPositionUtc)
                })
            }

        return JSONObject().apply {
            put("rows", rows)
            put("source", "AISStream")
            put("status", status)
            put("error", statusError(status) ?: JSONObject.NULL)
            put("refreshing", status != "live")
            put("newestPositionAt", if (newestEpoch > 0L) isoFormat().format(Date(newestEpoch * 1000)) else JSONObject.NULL)
            put("lastMessageAt", if (lastMessageAt > 0L) lastMessageAt else JSONObject.NULL)
            put("watchdog", if (socket != null) "armed" else "idle")
        }
    }

    fun track(mmsi: String): JSONObject {
        val samples = JSONArray()
        tracks[mmsi]?.let { list ->
            synchronized(list) {
                list.forEach { s ->
                    samples.put(JSONObject().apply {
                        put("lat", s[0])
                        put("lon", s[1])
                        put("epoch", s[2].toLong())
                    })
                }
            }
        }
        return JSONObject().apply {
            put("mmsi", mmsi)
            put("samples", samples)
            put("source", "AISStream (accumulated since app start)")
        }
    }

    // --- value helpers -----------------------------------------------------

    private fun firstString(vararg candidates: Any?): String {
        for (c in candidates) {
            if (c == null || c == JSONObject.NULL) continue
            val s = c.toString().trim()
            if (s.isNotEmpty() && s != "null") return s
        }
        return ""
    }

    private fun firstDouble(vararg candidates: Any?): Double {
        for (c in candidates) {
            if (c == null || c == JSONObject.NULL) continue
            val d = (c as? Number)?.toDouble() ?: c.toString().toDoubleOrNull() ?: continue
            if (!d.isNaN()) return d
        }
        return Double.NaN
    }

    private fun shipName(metadata: JSONObject, message: JSONObject, prior: StaticInfo?): String =
        firstString(message.opt("Name"), metadata.opt("ShipName"), metadata.opt("shipname"), prior?.name)

    private fun shipType(message: JSONObject, prior: StaticInfo?): String {
        val code = firstDouble(message.opt("Type"), message.opt("ShipType"))
        if (!code.isNaN()) {
            val label = aisTypeLabel(code.toInt())
            if (label.isNotBlank()) return label
        }
        return prior?.type.orEmpty()
    }

    /** ITU-R M.1371 ship-and-cargo type codes, collapsed to display names. */
    private fun aisTypeLabel(code: Int): String = when (code) {
        in 20..29 -> "Wing in Ground"
        30 -> "Fishing"
        31, 32 -> "Towing"
        33 -> "Dredger"
        34 -> "Diving Vessel"
        35 -> "Military"
        36 -> "Sailing"
        37 -> "Pleasure Craft"
        in 40..49 -> "High Speed Craft"
        50 -> "Pilot Vessel"
        51 -> "Search and Rescue"
        52 -> "Tug"
        53 -> "Port Tender"
        55 -> "Law Enforcement"
        58 -> "Medical Transport"
        in 60..69 -> "Passenger"
        in 70..79 -> "Cargo"
        in 80..89 -> "Tanker"
        in 90..99 -> "Other"
        else -> ""
    }

    private fun normalizedSpeed(sog: Double): Double =
        if (sog.isNaN() || sog < 0 || sog >= 102.3) 0.0 else sog

    private fun normalizedCourse(cog: Double): Double =
        if (cog.isNaN() || cog < 0 || cog >= 360.0) 0.0 else cog

    private fun normalizedHeading(heading: Double): Int =
        if (heading.isNaN() || heading < 0 || heading >= 360.0) 511 else heading.toInt()

    private fun epochSeconds(timeUtc: String): Long {
        if (timeUtc.isNotBlank()) {
            // AISStream emits e.g. "2026-11-01 09:12:03.123456789 +0000 UTC".
            val cleaned = timeUtc.substringBefore('.').trim()
            for (pattern in arrayOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss")) {
                try {
                    val fmt = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    return fmt.parse(cleaned)!!.time / 1000
                } catch (_: Exception) {}
            }
        }
        return System.currentTimeMillis() / 1000
    }
}
