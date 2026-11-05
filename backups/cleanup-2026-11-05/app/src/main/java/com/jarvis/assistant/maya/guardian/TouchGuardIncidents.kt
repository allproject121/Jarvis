package com.jarvis.assistant.maya.guardian

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One Touch Guard / Watchman incident (Maya's "Who tried to get in" list).
 * [verdict] is a [WatchmanFaceMonitor.Verdict] name or "pending" while the photo is analysed.
 */
data class TouchGuardIncident(
    val id: String,
    val at: Long,
    val source: String,          // touch_guard | watchman | face_lock
    val motionDelta: Float,
    val photoPath: String?,
    val verdict: String,
    val names: List<String>,
    val note: String
) {
    fun timeText(): String = SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault()).format(Date(at))

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("at", at)
        put("source", source)
        put("motion_delta", motionDelta.toDouble())
        if (photoPath != null) put("photo", photoPath)
        put("verdict", verdict)
        put("names", JSONArray(names))
        put("note", note)
    }

    companion object {
        fun fromJson(o: JSONObject): TouchGuardIncident {
            val names = ArrayList<String>()
            o.optJSONArray("names")?.let { a -> for (i in 0 until a.length()) names.add(a.optString(i)) }
            return TouchGuardIncident(
                id = o.optString("id"),
                at = o.optLong("at", 0L),
                source = o.optString("source", "touch_guard"),
                motionDelta = o.optDouble("motion_delta", 0.0).toFloat(),
                photoPath = o.optString("photo", null),
                verdict = o.optString("verdict", "pending"),
                names = names,
                note = o.optString("note", "")
            )
        }
    }
}

/** filesDir/touch_guard_incidents.json — newest first, capped at [MAX]. */
class TouchGuardIncidents(baseDir: File) {

    companion object {
        const val MAX = 200
        fun of(context: Context) = TouchGuardIncidents(context.applicationContext.filesDir)
    }

    private val file = File(baseDir, "touch_guard_incidents.json")
    private val lock = Any()

    fun list(): List<TouchGuardIncident> = synchronized(lock) { load() }

    private fun load(): MutableList<TouchGuardIncident> = try {
        if (!file.exists()) mutableListOf()
        else {
            val arr = JSONObject(file.readText()).optJSONArray("incidents") ?: JSONArray()
            MutableList(arr.length()) { i -> TouchGuardIncident.fromJson(arr.getJSONObject(i)) }
        }
    } catch (_: Exception) {
        mutableListOf()
    }

    private fun persist(list: List<TouchGuardIncident>) {
        try {
            val json = JSONObject().put("version", 1).put("incidents", JSONArray().apply { list.forEach { put(it.toJson()) } })
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) { file.writeText(json.toString()); tmp.delete() }
        } catch (_: Exception) {}
    }

    fun record(incident: TouchGuardIncident): TouchGuardIncident = synchronized(lock) {
        val list = load()
        list.add(0, incident)
        while (list.size > MAX) list.removeAt(list.size - 1)
        persist(list)
        incident
    }

    fun update(id: String, transform: (TouchGuardIncident) -> TouchGuardIncident) = synchronized(lock) {
        val list = load()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = transform(list[idx])
            persist(list)
        }
    }

    /** Incidents since [sinceMs] (0 = all). */
    fun since(sinceMs: Long): List<TouchGuardIncident> = list().filter { it.at >= sinceMs }

    fun clear() = synchronized(lock) { persist(emptyList()) }

    /** Human report used by the touch_guard_report tool. */
    fun report(sinceMs: Long = 0L): String {
        val items = since(sinceMs)
        if (items.isEmpty()) return "Nobody touched the phone — no Touch Guard incidents recorded."
        val photos = items.count { it.photoPath != null }
        val unknown = items.count { it.verdict == WatchmanFaceMonitor.Verdict.UNKNOWN.name }
        val known = items.filter { it.names.isNotEmpty() }.flatMap { it.names }.distinct()
        val sb = StringBuilder()
        sb.append("${items.size} incident(s), last at ${items.first().timeText()}, $photos photo(s) taken.")
        if (unknown > 0) sb.append(" $unknown with an unknown face.")
        if (known.isNotEmpty()) sb.append(" Recognised: ${known.joinToString()}.")
        sb.append("\nLatest:")
        items.take(5).forEach { i ->
            sb.append("\n- ${i.timeText()} · ${i.source} · motion ${"%.1f".format(Locale.US, i.motionDelta)} · ${i.verdict.lowercase(Locale.ROOT)}")
            if (i.names.isNotEmpty()) sb.append(" (${i.names.joinToString()})")
            if (i.photoPath != null) sb.append(" · photo")
        }
        return sb.toString()
    }
}
