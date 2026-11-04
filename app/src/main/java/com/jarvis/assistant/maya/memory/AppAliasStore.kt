package com.jarvis.assistant.maya.memory

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Spoken-name -> package alias map taught through `remember_app_name` ("'Insta' matlab Instagram").
 * Persisted at `filesDir/maya_app_aliases.json`; `open_app`-style lookups call [resolve] first.
 */
object AppAliasStore {

    private const val TAG = "AppAliasStore"
    const val FILE_NAME = "maya_app_aliases.json"

    private val lock = Any()

    @Volatile
    private var cache: MutableMap<String, String>? = null

    fun normalize(name: String): String = name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun load(context: Context): MutableMap<String, String> = cache ?: synchronized(lock) {
        cache ?: parse(runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()).also { cache = it }
    }

    /** Pure parser (unit-testable). */
    fun parse(json: String?): MutableMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (json.isNullOrBlank()) return out
        runCatching {
            val root = JSONObject(json)
            val aliases = root.optJSONObject("aliases") ?: root
            val keys = aliases.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = aliases.optString(k)
                if (k.isNotBlank() && v.isNotBlank()) out[normalize(k)] = v.trim()
            }
        }
        return out
    }

    fun serialize(map: Map<String, String>): String = JSONObject().apply {
        put("version", 1)
        put("aliases", JSONObject().also { o -> map.forEach { (k, v) -> o.put(k, v) } })
    }.toString(2)

    private fun persist(context: Context, map: Map<String, String>) {
        synchronized(lock) {
            cache = LinkedHashMap(map)
            runCatching { file(context).writeText(serialize(map)) }
                .onFailure { Log.w(TAG, "Could not write aliases: ${it.message}") }
        }
    }

    fun all(context: Context): Map<String, String> = load(context).toMap()

    /** Package for a spoken name, or null when no alias was taught. */
    fun resolve(name: String): String? {
        val map = cache ?: return null
        return map[normalize(name)]
    }

    fun resolve(context: Context, name: String): String? = load(context)[normalize(name)]

    fun remember(context: Context, spokenName: String, packageName: String) {
        val key = normalize(spokenName)
        if (key.isEmpty() || packageName.isBlank()) return
        val next = LinkedHashMap(load(context))
        next[key] = packageName.trim()
        persist(context, next)
    }

    fun forget(context: Context, spokenName: String): String? {
        val key = normalize(spokenName)
        val next = LinkedHashMap(load(context))
        val removed = next.remove(key) ?: return null
        persist(context, next)
        return removed
    }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        false
    }

    /** Finds an installed launcher app whose label matches [label] (exact, then contains). */
    fun findPackageByLabel(context: Context, label: String): String? {
        val q = normalize(label)
        if (q.isEmpty()) return null
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(intent, 0).map { ri ->
                normalize(ri.loadLabel(pm).toString()) to ri.activityInfo.packageName
            }
            apps.firstOrNull { it.first == q }?.second
                ?: apps.firstOrNull { it.first.contains(q) || q.contains(it.first) }?.second
                ?: apps.firstOrNull { it.second.lowercase(Locale.ROOT).endsWith(".$q") }?.second
        } catch (e: Exception) {
            null
        }
    }

    fun labelOf(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }
}

/** `set_user_name` guard: names that belong to the assistant / its creator are rejected. */
object UserNames {

    private val RESERVED = setOf("jarvis", "maya", "boss", "assistant", "ai", "hunter", "system", "admin", "user")

    fun normalize(name: String): String = name.trim().trim('"', '\'', '.', ',').replace(Regex("\\s+"), " ")

    fun isReserved(name: String): Boolean {
        val n = normalize(name).lowercase(Locale.ROOT)
        if (n.isEmpty()) return true
        if (n in RESERVED) return true
        // "Jarvis bhai", "Boss ji" etc. are variants of a reserved name.
        val first = n.split(' ').first()
        return first in RESERVED && n.split(' ').size <= 2
    }

    fun isValid(name: String): Boolean {
        val n = normalize(name)
        return n.length in 1..40 && n.none { it.isDigit() } && !isReserved(n)
    }
}
