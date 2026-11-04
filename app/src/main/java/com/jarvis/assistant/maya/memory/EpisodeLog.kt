package com.jarvis.assistant.maya.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/** One exchange in the episodic log: who said what, when, which tools ran, which app was open. */
data class Episode(
    val ts: Long,
    val user: String,
    val assistant: String,
    val tools: List<String> = emptyList(),
    val summary: String = EpisodeCodec.summarize(user, assistant),
    /** Package name of the foreground app when the turn happened, if known. */
    val app: String? = null
) {
    val dateKey: String get() = EpisodeCodec.dateKey(ts)
}

/** Pure JSON-lines codec (unit-testable, no Android). */
object EpisodeCodec {

    const val SUMMARY_MAX = 160

    fun summarize(user: String, assistant: String): String {
        val u = user.replace(Regex("\\s+"), " ").trim()
        val a = assistant.replace(Regex("\\s+"), " ").trim()
        val joined = when {
            u.isEmpty() -> "Jarvis: $a"
            a.isEmpty() -> "User: $u"
            else -> "User: $u | Jarvis: $a"
        }
        return if (joined.length <= SUMMARY_MAX) joined else joined.take(SUMMARY_MAX - 1).trimEnd() + "…"
    }

    fun toJson(e: Episode): JSONObject = JSONObject().apply {
        put("ts", e.ts)
        put("user", e.user)
        put("assistant", e.assistant)
        if (e.tools.isNotEmpty()) put("tools", JSONArray(e.tools))
        put("summary", e.summary)
        if (!e.app.isNullOrBlank()) put("app", e.app)
    }

    fun toLine(e: Episode): String = toJson(e).toString()

    fun fromJson(o: JSONObject): Episode? {
        val ts = o.optLong("ts", 0L)
        val user = o.optString("user")
        val assistant = o.optString("assistant")
        if (ts <= 0L || (user.isBlank() && assistant.isBlank())) return null
        val toolsArr = o.optJSONArray("tools")
        val tools = ArrayList<String>()
        if (toolsArr != null) for (i in 0 until toolsArr.length()) toolsArr.optString(i).takeIf { it.isNotBlank() }?.let { tools += it }
        val summary = o.optString("summary").ifBlank { summarize(user, assistant) }
        val app = o.optString("app").takeIf { it.isNotBlank() }
        return Episode(ts, user, assistant, tools, summary, app)
    }

    fun fromLine(line: String): Episode? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return try {
            fromJson(JSONObject(trimmed))
        } catch (e: Exception) {
            null
        }
    }

    fun dateKey(ts: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))
}

/**
 * Keyword scoring + date filtering for `recall_episodes` (pure Kotlin).
 */
object EpisodeScoring {

    private val STOP = setOf(
        "the", "and", "for", "with", "that", "this", "what", "when", "did", "was", "were", "have", "had",
        "you", "your", "about", "kya", "tha", "the", "thi", "hai", "hain", "mera", "meri", "mujhe", "kab",
        "kaun", "kis", "din", "wo", "woh", "usne", "maine", "kiya", "kaise", "hua", "koi"
    )

    fun tokenize(text: String): List<String> =
        text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in STOP }

    /** Keyword overlap score: exact token hit = 3, prefix/substring hit = 1, tool/app hit = 2. */
    fun score(episode: Episode, queryTokens: List<String>): Int {
        if (queryTokens.isEmpty()) return 0
        val hay = (episode.user + " " + episode.assistant).lowercase(Locale.ROOT)
        val hayTokens = tokenize(hay).toSet()
        var score = 0
        for (q in queryTokens) {
            when {
                q in hayTokens -> score += 3
                hay.contains(q) -> score += 1
            }
            if (episode.tools.any { it.lowercase(Locale.ROOT).contains(q) }) score += 2
            if (episode.app?.lowercase(Locale.ROOT)?.contains(q) == true) score += 2
        }
        return score
    }

    /**
     * Resolves a date filter into an inclusive [from, to] range in epoch millis.
     * Accepts `yyyy-MM-dd`, `dd/MM/yyyy`, `today|aaj`, `yesterday|kal`, `this week|is hafte`,
     * `last week|pichhle hafte`, `yyyy-MM` (whole month). Returns null when unparseable.
     */
    fun dateRange(date: String?, now: Long = System.currentTimeMillis()): LongRange? {
        val d = date?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (d.isEmpty()) return null
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        fun dayRange(c: Calendar): LongRange {
            val start = (c.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1); add(Calendar.MILLISECOND, -1) }
            return start.timeInMillis..end.timeInMillis
        }
        when (d) {
            "today", "aaj" -> return dayRange(cal)
            "yesterday", "kal" -> return dayRange(cal.apply { add(Calendar.DAY_OF_MONTH, -1) })
            "this week", "is hafte", "week" -> {
                val end = dayRange(cal).last
                val start = dayRange((cal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -6) }).first
                return start..end
            }
            "last week", "pichhle hafte", "pichle hafte" -> {
                val end = dayRange((cal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -7) }).last
                val start = dayRange((cal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -13) }).first
                return start..end
            }
        }
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(d)?.let { m ->
            val (y, mo, da) = m.destructured
            val c = Calendar.getInstance().apply { clear(); set(y.toInt(), mo.toInt() - 1, da.toInt()) }
            return dayRange(c)
        }
        Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$").find(d)?.let { m ->
            val (da, mo, y) = m.destructured
            val c = Calendar.getInstance().apply { clear(); set(y.toInt(), mo.toInt() - 1, da.toInt()) }
            return dayRange(c)
        }
        Regex("^(\\d{4})-(\\d{2})$").find(d)?.let { m ->
            val (y, mo) = m.destructured
            val start = Calendar.getInstance().apply { clear(); set(y.toInt(), mo.toInt() - 1, 1) }
            val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1); add(Calendar.MILLISECOND, -1) }
            return start.timeInMillis..end.timeInMillis
        }
        return null
    }

    /**
     * Returns the best-matching episodes, newest first among equal scores. With no query, the
     * newest episodes (optionally inside [date]) are returned.
     */
    fun recall(
        episodes: List<Episode>,
        query: String?,
        date: String?,
        limit: Int = 8,
        now: Long = System.currentTimeMillis()
    ): List<Episode> {
        val range = dateRange(date, now)
        val pool = if (range != null) episodes.filter { it.ts in range } else episodes
        val tokens = tokenize(query.orEmpty())
        if (tokens.isEmpty()) return pool.sortedByDescending { it.ts }.take(limit)
        return pool.map { it to score(it, tokens) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Episode, Int>> { it.second }.thenByDescending { it.first.ts })
            .map { it.first }
            .take(limit)
    }
}

/**
 * Append-only episodic log at `filesDir/maya_episodes.jsonl`. Writes are serialized on IO;
 * the file is trimmed to the newest [KEEP_LINES] entries once it exceeds [MAX_LINES].
 */
class EpisodeLog(private val file: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    /** Tools used since the last logged turn (filled by the tool pack, drained per episode). */
    private val pendingTools = ConcurrentLinkedQueue<String>()

    fun noteToolUse(name: String) {
        if (name.isNotBlank()) pendingTools.add(name)
    }

    fun drainTools(): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val t = pendingTools.poll() ?: break
            if (t !in out) out += t
        }
        return out
    }

    fun append(episode: Episode) {
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(EpisodeCodec.toLine(episode) + "\n")
            }.onFailure { Log.w(TAG, "Could not append episode: ${it.message}") }
            maybeTrim()
        }
    }

    fun appendAsync(episode: Episode) {
        scope.launch { append(episode) }
    }

    /** Every stored episode, oldest first. */
    fun readAll(): List<Episode> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        runCatching { file.readLines().mapNotNull(EpisodeCodec::fromLine) }.getOrDefault(emptyList())
    }

    fun recent(limit: Int): List<Episode> = readAll().takeLast(limit).asReversed()

    fun recall(query: String?, date: String?, limit: Int = 8): List<Episode> =
        EpisodeScoring.recall(readAll(), query, date, limit)

    /** Short summaries of the newest [n] episodes, newest first. */
    fun recentSummaries(n: Int): List<String> = recent(n).map { it.summary }

    fun count(): Int = readAll().size

    fun clear() = synchronized(lock) { runCatching { file.delete() } }

    /** Merge episodes from a backup (dedupe on ts + user text). Returns the number added. */
    fun merge(incoming: List<Episode>): Int = synchronized(lock) {
        val existing = readAll().map { "${it.ts}|${it.user}" }.toHashSet()
        var added = 0
        val sb = StringBuilder()
        for (e in incoming.sortedBy { it.ts }) {
            val key = "${e.ts}|${e.user}"
            if (key in existing) continue
            existing += key
            sb.append(EpisodeCodec.toLine(e)).append('\n')
            added++
        }
        if (added > 0) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(sb.toString())
            }.onFailure { Log.w(TAG, "Could not merge episodes: ${it.message}") }
            maybeTrim()
        }
        added
    }

    private fun maybeTrim() {
        runCatching {
            if (!file.exists() || file.length() < TRIM_CHECK_BYTES) return
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                file.writeText(lines.takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"))
            }
        }
    }

    companion object {
        private const val TAG = "EpisodeLog"
        const val FILE_NAME = "maya_episodes.jsonl"
        private const val MAX_LINES = 3000
        private const val KEEP_LINES = 2000
        private const val TRIM_CHECK_BYTES = 1_500_000L

        @Volatile
        private var instance: EpisodeLog? = null

        fun get(context: Context): EpisodeLog = instance ?: synchronized(this) {
            instance ?: EpisodeLog(File(context.applicationContext.filesDir, FILE_NAME)).also { instance = it }
        }

        /** Package name of the foreground app, when the accessibility service can tell. */
        fun foregroundPackage(): String? = try {
            com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                ?.getActiveRootNode()?.packageName?.toString()?.takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            null
        }
    }
}

/**
 * Hook called from `ChatRepository.addTurn` (additive, wrapped in try/catch there). Respects
 * incognito and skips placeholder halves.
 */
object MayaEpisodeHook {

    private const val PLACEHOLDER_REPLY = "(Audio response)"
    private const val PLACEHOLDER_QUERY = "(Audio query)"
    private const val SPOKEN_QUERY = "Spoken user query"

    fun onTurn(context: Context, userText: String, jarvisText: String) {
        val user = userText.trim()
        val jarvis = jarvisText.trim()
        if (jarvis.isBlank() || jarvis == PLACEHOLDER_REPLY) return
        if (user == PLACEHOLDER_QUERY || user == SPOKEN_QUERY) return
        if (user.isBlank() && jarvis.isBlank()) return
        if (!MemoryPrefs.canStore(context)) return
        val log = EpisodeLog.get(context)
        val episode = Episode(
            ts = System.currentTimeMillis(),
            user = user,
            assistant = jarvis,
            tools = log.drainTools(),
            app = EpisodeLog.foregroundPackage()?.takeIf { it != context.packageName }
        )
        log.appendAsync(episode)
    }
}
