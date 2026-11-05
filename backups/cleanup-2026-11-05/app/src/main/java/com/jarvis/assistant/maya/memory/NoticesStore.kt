package com.jarvis.assistant.maya.memory

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.AiTextSynthesizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** A local announcement shown on Home / in the Wiki screen ("Backup ready", "Price alert", daily quote). */
data class Notice(
    val id: String,
    val title: String,
    val body: String,
    /** `info` | `alert` | `quote`. */
    val kind: String = NoticesStore.KIND_INFO,
    val ts: Long,
    val read: Boolean = false
)

/** Pure JSON codec for `maya_notices.json`. */
object NoticesCodec {

    fun parse(json: String?): List<Notice> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("notices") ?: JSONArray()
            val out = ArrayList<Notice>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                fromJson(o)?.let { out += it }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun fromJson(o: JSONObject): Notice? {
        val title = o.optString("title").trim()
        val body = o.optString("body").trim()
        if (title.isEmpty() && body.isEmpty()) return null
        return Notice(
            id = o.optString("id").ifBlank { newId() },
            title = title,
            body = body,
            kind = NoticesStore.normalizeKind(o.optString("kind")),
            ts = o.optLong("ts", 0L),
            read = o.optBoolean("read", false)
        )
    }

    fun toJson(n: Notice): JSONObject = JSONObject().apply {
        put("id", n.id)
        put("title", n.title)
        put("body", n.body)
        put("kind", n.kind)
        put("ts", n.ts)
        put("read", n.read)
    }

    fun toJsonArray(list: List<Notice>): JSONArray = JSONArray().also { arr -> list.forEach { arr.put(toJson(it)) } }

    fun serialize(list: List<Notice>): String = JSONObject().apply {
        put("version", 1)
        put("notices", toJsonArray(list))
    }.toString(2)

    fun newId(): String = "n" + UUID.randomUUID().toString().replace("-", "").take(10)
}

/**
 * Local notices list persisted at `filesDir/maya_notices.json`. Other packs call
 * [post]; Home / WikiActivity observe [noticesFlow] and [unreadFlow].
 */
object NoticesStore {

    private const val TAG = "NoticesStore"
    const val FILE_NAME = "maya_notices.json"
    const val KIND_INFO = "info"
    const val KIND_ALERT = "alert"
    const val KIND_QUOTE = "quote"
    private const val MAX_NOTICES = 200

    private val lock = Any()

    @Volatile
    private var cache: List<Notice>? = null

    private val _noticesFlow = MutableStateFlow<List<Notice>>(emptyList())
    private val _unreadFlow = MutableStateFlow(0)

    /** Every notice, newest first. */
    val noticesFlow: StateFlow<List<Notice>> get() = _noticesFlow

    /** Number of unread notices. */
    val unreadFlow: StateFlow<Int> get() = _unreadFlow

    fun normalizeKind(raw: String?): String = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        KIND_ALERT, "warning", "warn", "error" -> KIND_ALERT
        KIND_QUOTE -> KIND_QUOTE
        else -> KIND_INFO
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun load(context: Context): List<Notice> = cache ?: synchronized(lock) {
        cache ?: NoticesCodec.parse(runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull())
            .sortedByDescending { it.ts }
            .also { cache = it; publish(it) }
    }

    private fun persist(context: Context, list: List<Notice>) {
        synchronized(lock) {
            val sorted = list.sortedByDescending { it.ts }.take(MAX_NOTICES)
            cache = sorted
            runCatching { file(context).writeText(NoticesCodec.serialize(sorted)) }
                .onFailure { Log.w(TAG, "Could not write notices: ${it.message}") }
            publish(sorted)
        }
    }

    private fun publish(list: List<Notice>) {
        _noticesFlow.value = list
        _unreadFlow.value = list.count { !it.read }
    }

    /** Loads from disk (if needed) and refreshes the flows. Safe to call from UI. */
    fun refresh(context: Context): List<Notice> = load(context)

    fun list(context: Context): List<Notice> = load(context)

    fun unreadCount(context: Context): Int = load(context).count { !it.read }

    /** Posts a notice. Other packs call this ("Backup ready", "Price alert", ...). */
    fun post(context: Context, title: String, body: String, kind: String = KIND_INFO): Notice {
        val notice = Notice(
            id = NoticesCodec.newId(),
            title = title.trim().ifBlank { "Jarvis" },
            body = body.trim(),
            kind = normalizeKind(kind),
            ts = System.currentTimeMillis(),
            read = false
        )
        persist(context, listOf(notice) + load(context))
        return notice
    }

    /** Replaces today's quote notice (keeps only one `quote` per day). */
    fun postDailyQuote(context: Context, quote: String): Notice {
        val today = DailyQuote.dateKey()
        val current = load(context).filterNot { it.kind == KIND_QUOTE && DailyQuote.dateKey(it.ts) == today }
        val notice = Notice(NoticesCodec.newId(), "Aaj ka quote", quote.trim(), KIND_QUOTE, System.currentTimeMillis(), false)
        persist(context, listOf(notice) + current)
        return notice
    }

    fun markRead(context: Context, id: String) {
        val current = load(context)
        if (current.none { it.id == id && !it.read }) return
        persist(context, current.map { if (it.id == id) it.copy(read = true) else it })
    }

    fun markAllRead(context: Context) {
        val current = load(context)
        if (current.none { !it.read }) return
        persist(context, current.map { it.copy(read = true) })
    }

    fun delete(context: Context, id: String) {
        val current = load(context)
        if (current.none { it.id == id }) return
        persist(context, current.filterNot { it.id == id })
    }

    fun clear(context: Context) = persist(context, emptyList())

    /** Merge notices from a backup (dedupe on id). Returns the number added. */
    fun merge(context: Context, incoming: List<Notice>): Int {
        val current = load(context)
        val ids = current.map { it.id }.toHashSet()
        val added = incoming.filter { it.id !in ids }
        if (added.isNotEmpty()) persist(context, current + added)
        return added.size
    }
}

/**
 * Daily quote generated once per day by the LLM (cached in [MemoryPrefs]); posted as a
 * `quote` notice. Falls back to a small built-in list when no model answers.
 */
object DailyQuote {

    private const val TAG = "DailyQuote"

    private val FALLBACK = listOf(
        "Chhote kadam bhi aage hi le jaate hain. — Jarvis",
        "Kal ka kaam aaj, aaj ka abhi.",
        "Discipline is choosing between what you want now and what you want most.",
        "Jo focus karta hai, wahi finish karta hai.",
        "Progress, not perfection.",
        "Well begun is half done. — Aristotle",
        "The best way to predict the future is to create it. — Peter Drucker"
    )

    fun dateKey(ts: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))

    /** Today's cached quote, if one was already generated. */
    fun cached(context: Context): String? = MemoryPrefs.cachedQuote(context, dateKey())

    /**
     * Returns today's quote, generating it (and posting the notice) the first time it is asked
     * for on a given day.
     */
    suspend fun today(context: Context): String {
        cached(context)?.let { return it }
        val name = runCatching { JarvisApp.instance.preferences.userName }.getOrDefault("Boss")
        val rules = runCatching { RulesStore.get(context).list().take(3).joinToString("; ") { it.text } }.getOrDefault("")
        val text = try {
            AiTextSynthesizer.synthesizeText(
                context = context,
                systemInstruction = "You are Jarvis, a personal assistant. Write ONE short motivational or thoughtful quote " +
                    "of the day for the user (max 140 characters). Hinglish or English, matching how the user talks. " +
                    "Output only the quote (attribution optional), no preamble, no quotes marks.",
                prompt = "User's name: $name. Date: ${dateKey()}." + (if (rules.isNotBlank()) " Standing rules: $rules." else ""),
                temperature = 0.9,
                maxTokens = 80,
                fallback = { "" }
            ).trim().trim('"', '“', '”')
        } catch (e: Exception) {
            Log.w(TAG, "Quote generation failed: ${e.message}")
            ""
        }
        val quote = text.takeIf { it.isNotBlank() && it.length <= 240 } ?: FALLBACK[(dateKey().hashCode() and 0x7fffffff) % FALLBACK.size]
        MemoryPrefs.cacheQuote(context, dateKey(), quote)
        runCatching { NoticesStore.postDailyQuote(context, quote) }
        return quote
    }
}
