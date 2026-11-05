package com.jarvis.assistant.data.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.maya.memory.MayaBackup
import com.jarvis.assistant.maya.memory.MayaBackupFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * JARVIS Backup & Restore engine - the ONE backup flow of the app (2026-11-05).
 *
 * Used by Settings → System → Backup & restore, Memory → Wiki → Backup tab and the
 * `export_backup` AI tool. One JSON file (`format = jarvis_full_backup`) holds:
 *  - `settings`: every app SharedPreferences file (AppPreferences `jarvis_prefs` + the other
 *    jarvis / androllm / maya prefs), with typed values so they restore exactly;
 *  - `data`: memories (MemoryManager export = jarvis_memory.db rows + androllm_memory_meta
 *    projects / relationships / summaries), AndroLLM chats (androllm_chat.db), rules, episodes,
 *    wiki, notices (built by [MayaBackup]);
 *  - `files`: small user lists from filesDir (custom macros, watches, app aliases, smart-home
 *    devices, CRM leads, drips).
 *
 * NEVER exported: API keys, tokens, passwords, secrets (encrypted prefs files are skipped and
 * any key / value that looks like a credential is dropped), voice print, model weights.
 *
 * Restore accepts this format, the old `Jarvis_Backup_*.json` (metadata + memories + maya)
 * and the Maya `maya_memory` export. Settings and the listed files are replaced; memories,
 * chats, rules, episodes, wiki and notices are merged. The app should be restarted afterwards.
 */
object BackupManager {
    private const val TAG = "BackupManager"

    const val FORMAT = "jarvis_full_backup"
    const val FORMAT_VERSION = 2
    private const val SHARE_DIR = "maya_backup" // shared via the maya memory FileProvider (cache-path)
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024

    /** Small user-data files in filesDir that are worth carrying to a new phone. */
    private val DATA_FILES = listOf(
        "custom_macros.json",   // automation/macro/MacroRepository
        "maya_watches.json",    // maya/screen/WatcherEngine
        "maya_app_aliases.json",// maya/memory/AppAliasStore
        "maya_smarthome.json",  // maya/hub/SmartHomeDevices
        "crm_leads.json",       // automation/crm/LeadCrmManager
        "drips.json"            // automation/crm/LeadFollowupScheduler
    )

    /** Prefs files that only hold device-local state (download ids, temp paths) or system data. */
    private val EXCLUDED_PREF_FILES = setOf(
        "androllm_model_downloads",
        "androllm_litert_downloads",
        "jarvis_secure_api_prefs",
        "maya_hub_secure"
    )
    private val EXCLUDED_PREF_FILE_PREFIXES = listOf("com.google.", "androidx.", "WebView", "org.chromium", "_", "Firebase", "com.crashlytics")
    private val SECRET_FILE_NAME = Regex("(?i)(secure|secret|encrypt|keystore|credential|password|token)")
    private val SECRET_KEY_NAME = Regex(
        "(?i)(api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|auth[_-]?token|id[_-]?token|bearer|" +
            "client[_-]?secret|secret[_-]?key|_secret$|password|passwd|passphrase|credential|private[_-]?key|" +
            "cookie|_token$|^token$|token_|_pat$|oauth)"
    )
    private val SECRET_VALUE = Regex("^(AIza[0-9A-Za-z_-]{20,}|sk-[A-Za-z0-9_-]{16,}|ghp_[A-Za-z0-9]{20,}|gsk_[A-Za-z0-9]{20,}|xox[abp]-[A-Za-z0-9-]{10,})")

    data class ExportSummary(
        val fileName: String? = null,
        val bytes: Long = 0,
        val prefFiles: Int = 0,
        val prefKeys: Int = 0,
        val secretsSkipped: Int = 0,
        val memories: Int = 0,
        val chats: Int = 0,
        val legacyChatTurns: Int = 0,
        val rules: Int = 0,
        val files: Int = 0
    ) {
        fun describe(): String = buildString {
            if (fileName != null) append("Saved $fileName (${(bytes / 1024).coerceAtLeast(1)} KB).\n\n")
            append("Included: $prefKeys settings in $prefFiles groups, $memories memories, $chats chats")
            if (legacyChatTurns > 0) append(" + $legacyChatTurns voice-chat turns")
            append(", $rules rules, $files saved lists (macros, watches...).\n\n")
            append("Not included: API keys, tokens and passwords")
            if (secretsSkipped > 0) append(" ($secretsSkipped skipped)")
            append(".")
        }
    }

    data class RestoreReport(
        val format: String,
        val prefFiles: Int = 0,
        val prefKeys: Int = 0,
        val files: Int = 0,
        val legacyChatTurns: Int = 0,
        val data: MayaBackup.RestoreReport? = null
    ) {
        fun describe(): String = buildString {
            append("Backup type: $format\n")
            if (prefKeys > 0) append("Settings restored: $prefKeys in $prefFiles groups\n")
            if (files > 0) append("Saved lists restored: $files\n")
            if (legacyChatTurns > 0) append("Voice-chat turns restored: $legacyChatTurns\n")
            data?.let {
                append("Memories: ${it.memories}, chats: ${it.chats}, rules: ${it.rules}, episodes: ${it.episodes}, wiki pages: ${it.wikiPages}, notices: ${it.notices}")
            }
        }.trim()
    }

    fun suggestedFileName(): String =
        "Jarvis_Backup_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".json"

    // ------------------------------------------------------------------ export

    /** Builds the whole backup document. */
    suspend fun buildBackupJson(context: Context): Pair<JSONObject, ExportSummary> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val jarvis = (app as? JarvisApp) ?: JarvisApp.instance
        val prefs = jarvis.preferences
        var summary = ExportSummary()

        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", FORMAT_VERSION)
        root.put("app", app.packageName)
        root.put("appVersion", runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "")
        root.put("exportedAt", System.currentTimeMillis())
        root.put("metadata", JSONObject().apply {
            put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("user", runCatching { prefs.userName }.getOrDefault(""))
            put("model", runCatching { prefs.aiModel }.getOrDefault(""))
            put("persona", runCatching { prefs.personality }.getOrDefault(""))
            put("voice", runCatching { prefs.voice }.getOrDefault(""))
        })

        // 1) Settings (all relevant SharedPreferences files, typed, secrets removed)
        val settings = JSONObject()
        var keyCount = 0
        var skipped = 0
        for (name in listPrefFiles(app)) {
            if (isExcludedPrefFile(name)) continue
            val all = runCatching { app.getSharedPreferences(name, Context.MODE_PRIVATE).all }.getOrNull() ?: continue
            val obj = JSONObject()
            for ((key, value) in all) {
                if (value == null) continue
                if (isSecret(key, value)) { skipped++; continue }
                val typed = encodeTyped(value) ?: continue
                obj.put(key, typed)
                keyCount++
            }
            if (obj.length() > 0) settings.put(name, obj)
        }
        root.put("settings", settings)
        summary = summary.copy(prefFiles = settings.length(), prefKeys = keyCount, secretsSkipped = skipped)
        summary = summary.copy(legacyChatTurns = runCatching { prefs.loadChatHistory().size }.getOrDefault(0))

        // 2) Memories, chats, rules, episodes, wiki, notices
        try {
            val data = MayaBackup.buildExportJson(app)
            root.put("data", data)
            summary = summary.copy(
                memories = data.optJSONObject("memories")?.optJSONArray("memories")?.length() ?: 0,
                chats = data.optJSONArray("chats")?.length() ?: 0,
                rules = data.optJSONArray("rules")?.length() ?: 0
            )
        } catch (e: Exception) {
            Log.w(TAG, "Memory/chat section skipped: ${e.message}", e)
        }

        // 3) Small user-data files
        val files = JSONObject()
        for (fileName in DATA_FILES) {
            val f = File(app.filesDir, fileName)
            if (f.isFile && f.length() in 1..MAX_FILE_BYTES) {
                runCatching { files.put(fileName, f.readText(Charsets.UTF_8)) }
            }
        }
        root.put("files", files)
        summary = summary.copy(files = files.length())

        root to summary
    }

    /** Writes the backup into a user-picked document (ACTION_CREATE_DOCUMENT). */
    suspend fun exportToUri(context: Context, uri: Uri): Result<ExportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val (root, summary) = buildBackupJson(context)
            val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("Could not open the chosen file for writing")
            Log.i(TAG, "Exported backup to $uri (${bytes.size} bytes)")
            summary.copy(fileName = displayName(context, uri) ?: "backup", bytes = bytes.size.toLong())
        }.onFailure { Log.e(TAG, "Export failed: ${it.message}", it) }
    }

    /** Writes the backup into cacheDir/maya_backup (shareable through the maya memory FileProvider). */
    suspend fun exportToShareableFile(context: Context): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val (root, _) = buildBackupJson(context)
            val dir = File(context.applicationContext.cacheDir, SHARE_DIR).apply { mkdirs() }
            dir.listFiles()?.filter { it.name.startsWith("Jarvis_Backup_") || it.name.startsWith("maya_memory_") }
                ?.forEach { runCatching { it.delete() } }
            val file = File(dir, suggestedFileName())
            file.writeText(root.toString(2), Charsets.UTF_8)
            file
        }.onFailure { Log.e(TAG, "Export (share) failed: ${it.message}", it) }
    }

    /** Export + system share sheet (used by the `export_backup` AI tool). */
    suspend fun exportAndShare(context: Context): Result<File> {
        val result = exportToShareableFile(context)
        result.getOrNull()?.let { file -> withContext(Dispatchers.Main) { MayaBackup.share(context, file) } }
        return result
    }

    // ------------------------------------------------------------------ restore

    suspend fun restoreFromUri(context: Context, uri: Uri): Result<RestoreReport> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { it.reader(Charsets.UTF_8).readText() }
                ?: error("Could not open the selected file")
            restoreFromJson(context, text)
        }.onFailure { Log.e(TAG, "Restore failed: ${it.message}", it) }
    }

    /** Validates [text] and writes it back. Throws IllegalArgumentException for files that are not Jarvis backups. */
    suspend fun restoreFromJson(context: Context, text: String): RestoreReport = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val root = try {
            JSONObject(text.trim().removePrefix("﻿"))
        } catch (e: Exception) {
            throw IllegalArgumentException("This file is not a Jarvis backup (it is not valid JSON).")
        }

        when {
            root.optString("format") == FORMAT -> {
                val version = root.optInt("version", 0)
                if (version > FORMAT_VERSION) Log.w(TAG, "Backup version $version is newer than $FORMAT_VERSION; restoring known sections")
                val (prefFiles, prefKeys) = restoreSettings(app, root.optJSONObject("settings"))
                val files = restoreFiles(app, root.optJSONObject("files"))
                val data = root.optJSONObject("data")?.let { MayaBackup.restoreFromJson(app, it.toString()) }
                RestoreReport("Jarvis full backup v$version", prefFiles, prefKeys, files, 0, data)
            }
            MayaBackupFormat.isMayaBackup(root) -> {
                RestoreReport("Memory backup (maya_memory)", data = MayaBackup.restoreFromJson(app, text))
            }
            root.has("metadata") && (root.has("memories") || root.has("chat_history") || root.has("maya")) -> {
                // Old Jarvis_Backup_*.json from the previous BackupManager.
                val memories = if (root.has("memories")) MayaBackup.restoreFromJson(app, JSONObject().put("memories", root.get("memories")).toString()) else null
                val maya = root.optJSONObject("maya")?.let { MayaBackup.restoreFromJson(app, it.toString()) }
                val turns = restoreLegacyChatHistory(root.optJSONArray("chat_history"))
                val merged = when {
                    memories == null -> maya
                    maya == null -> memories
                    else -> maya.copy(memories = maya.memories + memories.memories)
                }
                RestoreReport("Old Jarvis backup", legacyChatTurns = turns, data = merged)
            }
            root.has("memories") && root.has("version") -> {
                RestoreReport("Memory export", data = MayaBackup.restoreFromJson(app, text))
            }
            else -> throw IllegalArgumentException("This file is not a Jarvis backup.")
        }
    }

    /** Relaunches the app so every singleton / screen reloads the restored data. */
    fun restartApp(activity: Activity) {
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) activity.startActivity(intent)
        activity.finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    // ------------------------------------------------------------------ helpers

    private fun listPrefFiles(context: Context): List<String> {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        return dir.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.map { it.name.removeSuffix(".xml") }?.sorted()
            ?: emptyList()
    }

    private fun isExcludedPrefFile(name: String): Boolean =
        name in EXCLUDED_PREF_FILES ||
            EXCLUDED_PREF_FILE_PREFIXES.any { name.startsWith(it) } ||
            SECRET_FILE_NAME.containsMatchIn(name)

    private fun isSecret(key: String, value: Any): Boolean =
        SECRET_KEY_NAME.containsMatchIn(key) || (value is String && SECRET_VALUE.containsMatchIn(value.trim()))

    private fun encodeTyped(value: Any): JSONObject? = when (value) {
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is String -> JSONObject().put("t", "s").put("v", value)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.filterIsInstance<String>()))
        else -> null
    }

    private fun restoreSettings(context: Context, settings: JSONObject?): Pair<Int, Int> {
        if (settings == null) return 0 to 0
        var files = 0
        var keys = 0
        val names = settings.keys()
        while (names.hasNext()) {
            val name = names.next()
            if (isExcludedPrefFile(name) || name.contains('/') || name.contains("..")) continue
            val obj = settings.optJSONObject(name) ?: continue
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            var wrote = 0
            val ks = obj.keys()
            while (ks.hasNext()) {
                val key = ks.next()
                val typed = obj.optJSONObject(key) ?: continue
                try {
                    when (typed.optString("t")) {
                        "b" -> editor.putBoolean(key, typed.getBoolean("v"))
                        "i" -> editor.putInt(key, typed.getInt("v"))
                        "l" -> editor.putLong(key, typed.getLong("v"))
                        "f" -> editor.putFloat(key, typed.getDouble("v").toFloat())
                        "s" -> {
                            val v = typed.getString("v")
                            if (isSecret(key, v)) continue
                            editor.putString(key, v)
                        }
                        "ss" -> {
                            val arr = typed.optJSONArray("v") ?: JSONArray()
                            editor.putStringSet(key, (0 until arr.length()).map { arr.optString(it) }.toSet())
                        }
                        else -> continue
                    }
                    wrote++
                } catch (e: Exception) {
                    Log.w(TAG, "Skipped setting $name/$key: ${e.message}")
                }
            }
            if (wrote > 0 && editor.commit()) {
                files++
                keys += wrote
            }
        }
        return files to keys
    }

    private fun restoreFiles(context: Context, files: JSONObject?): Int {
        if (files == null) return 0
        var count = 0
        for (fileName in DATA_FILES) {
            if (!files.has(fileName)) continue
            val content = files.optString(fileName)
            if (content.isBlank()) continue
            runCatching {
                // Light validation: must still be JSON (object or array) before replacing the local file.
                val t = content.trim()
                if (t.startsWith("{")) JSONObject(t) else if (t.startsWith("[")) JSONArray(t) else error("not JSON")
                val target = File(context.filesDir, fileName)
                val tmp = File(context.filesDir, "$fileName.restore_tmp")
                tmp.writeText(content, Charsets.UTF_8)
                if (!tmp.renameTo(target)) {
                    target.writeText(content, Charsets.UTF_8)
                    tmp.delete()
                }
            }.onSuccess { count++ }.onFailure { Log.w(TAG, "Skipped file $fileName: ${it.message}") }
        }
        return count
    }

    private fun restoreLegacyChatHistory(arr: JSONArray?): Int {
        if (arr == null || arr.length() == 0) return 0
        return runCatching {
            val prefs = JarvisApp.instance.preferences
            if (prefs.loadChatHistory().isNotEmpty()) return 0
            val type = object : TypeToken<List<ChatTurn>>() {}.type
            val turns: List<ChatTurn> = Gson().fromJson(arr.toString(), type) ?: emptyList()
            if (turns.isNotEmpty()) prefs.saveChatHistory(turns)
            turns.size
        }.getOrDefault(0)
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
