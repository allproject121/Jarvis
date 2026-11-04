package com.jarvis.assistant.maya.memory

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.androllm.chat.AndroLlmChatStore
import com.jarvis.assistant.androllm.chat.model.Conversation
import com.jarvis.assistant.androllm.chat.model.Message
import com.jarvis.assistant.androllm.chat.model.MessageOrigin
import com.jarvis.assistant.androllm.chat.model.MessageRole
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.data.memory.MemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure builder/reader for the `maya_memory.json` export format (unit-testable).
 *
 * Contents: remembered facts, chat transcripts, rules, episodes, wiki pages, notices and
 * favourite/SOS contacts. API keys and the voice print are NEVER included.
 */
object MayaBackupFormat {

    const val FORMAT = "maya_memory"
    const val VERSION = 1
    val EXCLUDED: List<String> = listOf("api_keys", "voice_print", "passwords")

    data class ChatExport(val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val messages: List<ChatMessageExport>)
    data class ChatMessageExport(val id: String, val role: String, val content: String, val ts: Long)
    data class Contacts(val sos: List<String> = emptyList(), val favourites: List<String> = emptyList())

    fun build(
        userName: String,
        memories: JSONObject?,
        chats: List<ChatExport>,
        rules: List<Rule>,
        episodes: List<Episode>,
        wikiIndex: List<WikiPageMeta>,
        wikiPages: Map<String, String>,
        notices: List<Notice>,
        contacts: Contacts,
        exportedAt: Long = System.currentTimeMillis()
    ): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("app", "jarvis")
        put("exportedAt", exportedAt)
        put("user", JSONObject().put("name", userName))
        put("memories", memories ?: JSONObject())
        put("chats", JSONArray().also { arr ->
            chats.forEach { c ->
                arr.put(JSONObject().apply {
                    put("id", c.id)
                    put("title", c.title)
                    put("createdAt", c.createdAt)
                    put("updatedAt", c.updatedAt)
                    put("messages", JSONArray().also { ma ->
                        c.messages.forEach { m ->
                            ma.put(JSONObject().put("id", m.id).put("role", m.role).put("content", m.content).put("ts", m.ts))
                        }
                    })
                })
            }
        })
        put("rules", RulesCodec.toJsonArray(rules))
        put("episodes", JSONArray().also { arr -> episodes.forEach { arr.put(EpisodeCodec.toJson(it)) } })
        put("wiki", JSONObject().apply {
            put("index", JSONArray().also { arr ->
                wikiIndex.forEach { p ->
                    arr.put(JSONObject().put("title", p.title).put("slug", p.slug).put("summary", p.summary).put("updatedAt", p.updatedAt).put("sources", p.sources))
                }
            })
            put("pages", JSONObject().also { o -> wikiPages.forEach { (slug, md) -> o.put(slug, md) } })
        })
        put("notices", NoticesCodec.toJsonArray(notices))
        put("contacts", JSONObject().apply {
            put("sos", JSONArray(contacts.sos))
            put("favourites", JSONArray(contacts.favourites))
        })
        put("excluded", JSONArray(EXCLUDED))
    }

    fun isMayaBackup(root: JSONObject): Boolean = root.optString("format") == FORMAT

    fun readRules(root: JSONObject): List<Rule> {
        val arr = root.optJSONArray("rules") ?: return emptyList()
        return RulesCodec.parse(JSONObject().put("rules", arr).toString()).rules
    }

    fun readEpisodes(root: JSONObject): List<Episode> {
        val arr = root.optJSONArray("episodes") ?: return emptyList()
        val out = ArrayList<Episode>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { EpisodeCodec.fromJson(it) }?.let { out += it }
        return out
    }

    fun readNotices(root: JSONObject): List<Notice> {
        val arr = root.optJSONArray("notices") ?: return emptyList()
        val out = ArrayList<Notice>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { NoticesCodec.fromJson(it) }?.let { out += it }
        return out
    }

    fun readWiki(root: JSONObject): Pair<List<WikiPageMeta>, Map<String, String>> {
        val wiki = root.optJSONObject("wiki") ?: return emptyList<WikiPageMeta>() to emptyMap()
        val index = WikiIndex.parseIndex(JSONObject().put("pages", wiki.optJSONArray("index") ?: JSONArray()).toString())
        val pages = LinkedHashMap<String, String>()
        wiki.optJSONObject("pages")?.let { o ->
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = o.optString(k)
                if (v.isNotBlank()) pages[k] = v
            }
        }
        return index to pages
    }

    fun readChats(root: JSONObject): List<ChatExport> {
        val arr = root.optJSONArray("chats") ?: return emptyList()
        val out = ArrayList<ChatExport>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val msgs = ArrayList<ChatMessageExport>()
            val ma = c.optJSONArray("messages") ?: JSONArray()
            for (j in 0 until ma.length()) {
                val m = ma.optJSONObject(j) ?: continue
                val content = m.optString("content")
                if (content.isBlank()) continue
                msgs += ChatMessageExport(m.optString("id").ifBlank { "${c.optString("id")}_$j" }, m.optString("role", "user"), content, m.optLong("ts", 0L))
            }
            out += ChatExport(c.optString("id"), c.optString("title").ifBlank { "Imported chat" }, c.optLong("createdAt", 0L), c.optLong("updatedAt", 0L), msgs)
        }
        return out
    }

    fun readContacts(root: JSONObject): Contacts {
        val c = root.optJSONObject("contacts") ?: return Contacts()
        fun list(key: String): List<String> {
            val a = c.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
        }
        return Contacts(list("sos"), list("favourites"))
    }

    fun splitContacts(raw: String): List<String> = raw.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Export everything-the-user-shared into one JSON file (cacheDir) and open the share sheet;
 * restore merges a file back. Mirrors Maya's "Back up everything the user has shared".
 */
object MayaBackup {

    private const val TAG = "MayaBackup"
    private const val DIR = "maya_backup"

    data class RestoreReport(
        val memories: Int = 0,
        val chats: Int = 0,
        val rules: Int = 0,
        val episodes: Int = 0,
        val wikiPages: Int = 0,
        val notices: Int = 0,
        val contacts: Boolean = false
    ) {
        fun describe(): String =
            "memories=$memories, chats=$chats, rules=$rules, episodes=$episodes, wiki=$wikiPages, notices=$notices" +
                (if (contacts) ", SOS contacts restored" else "")
    }

    fun authority(context: Context): String = "${context.packageName}.maya.memory.fileprovider"

    /** Builds the export JSON (no file). */
    suspend fun buildExportJson(context: Context): JSONObject = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val jarvis = (app as? JarvisApp) ?: JarvisApp.instance
        val userName = runCatching { jarvis.preferences.userName }.getOrDefault("")

        val memories: JSONObject? = runCatching {
            MemoryManager.getInstance(app).exportMemories().getOrNull()?.let { f ->
                val obj = JSONObject(f.readText())
                runCatching { f.delete() }
                obj
            }
        }.getOrNull() ?: runCatching {
            // Fallback: plain rows from the repository.
            JSONObject().put("version", 0).put("memories", JSONArray().also { arr ->
                jarvis.memoryRepository.getAllMemories().forEach { e ->
                    arr.put(JSONObject().put("id", e.id).put("category", e.category).put("key", e.key).put("value", e.value).put("content", e.rawText).put("timestamp", e.timestamp))
                }
            })
        }.getOrNull()

        val chats = runCatching {
            val convRepo = AndroLlmChatStore.conversationRepository(app)
            val msgRepo = AndroLlmChatStore.messageRepository(app)
            convRepo.getAll().first().map { c ->
                MayaBackupFormat.ChatExport(
                    id = c.id, title = c.title, createdAt = c.createdAt, updatedAt = c.updatedAt,
                    messages = msgRepo.getByConversationId(c.id).map { m ->
                        MayaBackupFormat.ChatMessageExport(m.id, m.role.name.lowercase(Locale.ROOT), m.content, m.timestamp)
                    }
                )
            }
        }.getOrDefault(emptyList())

        val contacts = MayaBackupFormat.Contacts(
            sos = runCatching { MayaBackupFormat.splitContacts(jarvis.preferences.emergencySosContacts) }.getOrDefault(emptyList()),
            favourites = emptyList()
        )

        MayaBackupFormat.build(
            userName = userName,
            memories = memories,
            chats = chats,
            rules = RulesStore.get(app).list(),
            episodes = EpisodeLog.get(app).readAll(),
            wikiIndex = WikiCompiler.readIndex(app),
            wikiPages = WikiCompiler.allPages(app),
            notices = NoticesStore.list(app),
            contacts = contacts
        )
    }

    /** Writes `maya_memory_<stamp>.json` into cacheDir/maya_backup and returns it. */
    suspend fun export(context: Context): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val json = buildExportJson(context)
            val dir = File(context.applicationContext.cacheDir, DIR).apply { mkdirs() }
            dir.listFiles()?.filter { it.name.startsWith("maya_memory_") }?.forEach { runCatching { it.delete() } }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "maya_memory_$stamp.json")
            file.writeText(json.toString(2))
            Log.i(TAG, "Exported Maya backup to ${file.absolutePath} (${file.length()} bytes)")
            file
        }.onFailure { Log.e(TAG, "Export failed: ${it.message}", it) }
    }

    /** Opens the system share sheet for [file]. Returns false when the FileProvider is unavailable. */
    fun share(context: Context, file: File): Boolean = try {
        val uri: Uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Jarvis memory backup")
            putExtra(Intent.EXTRA_TEXT, "Jarvis memories, chats, rules and wiki (no API keys or voice print).")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share Jarvis backup").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Share failed: ${e.message}")
        false
    }

    /** Export + share in one step (used by the tool and the Backup tab). */
    suspend fun exportAndShare(context: Context): Result<File> {
        val result = export(context)
        result.getOrNull()?.let { file ->
            withContext(Dispatchers.Main) { share(context, file) }
            runCatching { NoticesStore.post(context, "Backup ready", "Shared ${file.name} (${file.length() / 1024} KB).", NoticesStore.KIND_INFO) }
        }
        return result
    }

    suspend fun restore(context: Context, uri: Uri): Result<RestoreReport> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { it.reader(Charsets.UTF_8).readText() }
                ?: error("Could not open backup")
            restoreFromJson(context, text)
        }.onFailure { Log.e(TAG, "Restore failed: ${it.message}", it) }
    }

    /** Merges a Maya/Jarvis backup JSON; also accepts a BackupManager file and a bare memory export. */
    suspend fun restoreFromJson(context: Context, text: String): RestoreReport = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val jarvis = (app as? JarvisApp) ?: JarvisApp.instance
        val root = JSONObject(text)
        var report = RestoreReport()

        // Memories: MemoryManager export object (v1/v2) or BackupManager style rows.
        val memObj = root.optJSONObject("memories")
        val memArr = root.optJSONArray("memories")
        if (memObj != null && memObj.has("memories")) {
            val imported = runCatching { MemoryManager.getInstance(app).importMemories(memObj.toString()) }.getOrNull()
            report = report.copy(memories = imported?.let { it.inserted + it.updated } ?: importRows(jarvis, memObj.optJSONArray("memories")))
        } else if (memArr != null) {
            report = report.copy(memories = importRows(jarvis, memArr))
        } else if (root.has("memories") && root.optJSONObject("memories") == null && root.has("version")) {
            // A bare MemoryManager export file.
            runCatching { MemoryManager.getInstance(app).importMemories(text) }.getOrNull()?.let { report = report.copy(memories = it.inserted + it.updated) }
        }

        // Chats (idempotent upserts keyed on the exported ids).
        val chats = MayaBackupFormat.readChats(root)
        if (chats.isNotEmpty()) {
            val convRepo = AndroLlmChatStore.conversationRepository(app)
            val msgRepo = AndroLlmChatStore.messageRepository(app)
            var n = 0
            for (c in chats) {
                if (c.messages.isEmpty()) continue
                val id = c.id.ifBlank { "maya_import_${c.createdAt}" }
                val now = System.currentTimeMillis()
                if (convRepo.findById(id) == null) {
                    convRepo.upsert(Conversation(id = id, title = c.title, createdAt = c.createdAt.takeIf { it > 0 } ?: now, updatedAt = c.updatedAt.takeIf { it > 0 } ?: now))
                }
                val existing = msgRepo.getByConversationId(id).map { it.id }.toHashSet()
                val fresh = c.messages.filter { it.id !in existing }.map { m ->
                    Message(
                        id = m.id,
                        conversationId = id,
                        role = when (m.role.lowercase(Locale.ROOT)) { "assistant", "model", "jarvis", "maya" -> MessageRole.ASSISTANT; else -> MessageRole.USER },
                        content = m.content,
                        timestamp = m.ts.takeIf { it > 0 } ?: now,
                        origin = MessageOrigin.TYPED
                    )
                }
                if (fresh.isNotEmpty()) {
                    msgRepo.upsertAll(fresh)
                    n++
                }
            }
            report = report.copy(chats = n)
        }

        report = report.copy(rules = RulesStore.get(app).merge(MayaBackupFormat.readRules(root)))
        report = report.copy(episodes = EpisodeLog.get(app).merge(MayaBackupFormat.readEpisodes(root)))

        val (index, pages) = MayaBackupFormat.readWiki(root)
        var wikiCount = 0
        val currentIndex = WikiCompiler.readIndex(app).associateBy { it.slug }
        for (meta in index) {
            val md = pages[meta.slug] ?: continue
            val local = currentIndex[meta.slug]
            if (local == null || local.updatedAt < meta.updatedAt) {
                WikiCompiler.writePage(app, meta, md)
                wikiCount++
            }
        }
        report = report.copy(wikiPages = wikiCount)

        report = report.copy(notices = NoticesStore.merge(app, MayaBackupFormat.readNotices(root)))

        val contacts = MayaBackupFormat.readContacts(root)
        if (contacts.sos.isNotEmpty()) {
            runCatching {
                val current = MayaBackupFormat.splitContacts(jarvis.preferences.emergencySosContacts)
                val merged = (current + contacts.sos).distinct()
                if (merged != current) {
                    jarvis.preferences.emergencySosContacts = merged.joinToString(", ")
                    report = report.copy(contacts = true)
                }
            }
        }

        val name = root.optJSONObject("user")?.optString("name").orEmpty()
        if (name.isNotBlank() && UserNames.isValid(name)) {
            runCatching { if (jarvis.preferences.userName.isBlank()) jarvis.preferences.userName = name }
        }

        Log.i(TAG, "Restored backup: ${report.describe()}")
        runCatching { NoticesStore.post(app, "Backup restored", report.describe(), NoticesStore.KIND_INFO) }
        report
    }

    private suspend fun importRows(jarvis: JarvisApp, arr: JSONArray?): Int {
        if (arr == null) return 0
        var count = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val content = o.optString("rawText").ifBlank { o.optString("content") }.ifBlank { o.optString("value") }
            if (content.isBlank()) continue
            val category = o.optString("category").ifBlank { MemoryEntity.CATEGORY_NOTES }
            runCatching { jarvis.memoryRepository.saveMemory(rawText = content, category = category) }.onSuccess { count++ }
        }
        return count
    }
}
