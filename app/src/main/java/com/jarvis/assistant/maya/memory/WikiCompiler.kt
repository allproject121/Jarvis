package com.jarvis.assistant.maya.memory

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.AiTextSynthesizer
import com.jarvis.assistant.androllm.memory.Memory
import com.jarvis.assistant.data.memory.MemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One entry of `wiki/index.json`. */
data class WikiPageMeta(
    val title: String,
    val slug: String,
    val summary: String = "",
    val updatedAt: Long = 0L,
    val sources: Int = 0
)

/** A fixed topic page of the personal wiki (ported from Maya: About you, People, Preferences, Projects, Routine, Places). */
data class WikiTopic(val slug: String, val title: String, val keywords: List<String>, val categories: List<String> = emptyList())

/** Pure helpers: slugging, index codec/merge, LLM output parsing (unit-testable). */
object WikiIndex {

    val TOPICS: List<WikiTopic> = listOf(
        WikiTopic(
            "about-you", "About you",
            listOf("name", "naam", "birthday", "age", "umar", "born", "i am", "mera", "meri", "faith", "religion", "dharm", "health", "tabiyat", "job", "kaam", "study", "padhai", "college", "school", "language", "phone", "myself", "main"),
            listOf("Personal", "Identity", "Pinned Facts", "Skills", "Devices")
        ),
        WikiTopic(
            "people", "People",
            listOf("brother", "sister", "mom", "dad", "mother", "father", "wife", "husband", "friend", "dost", "bhai", "behen", "mummy", "papa", "uncle", "aunt", "girlfriend", "boyfriend", "boss", "colleague", "cousin", "son", "daughter", "beta", "beti", "contact", "call", "number", "family", "parivar", "team", "client"),
            listOf("Contacts")
        ),
        WikiTopic(
            "preferences", "Preferences",
            listOf("favorite", "favourite", "prefer", "like", "love", "pasand", "hate", "dislike", "nahi pasand", "song", "music", "gaana", "movie", "film", "food", "khana", "app", "color", "colour", "style", "tone", "reply", "jawab", "short", "language"),
            listOf("Preferences")
        ),
        WikiTopic(
            "projects", "Projects",
            listOf("project", "app", "build", "code", "github", "repo", "launch", "deadline", "client", "business", "startup", "website", "feature", "bug", "release", "kaam", "task", "goal", "plan", "exam"),
            listOf("Projects", "Goals", "Programming Languages", "Frameworks", "Developer Notes")
        ),
        WikiTopic(
            "routine", "Routine",
            listOf("routine", "daily", "roz", "morning", "subah", "night", "raat", "wake", "uthta", "sleep", "sota", "gym", "workout", "office", "every day", "weekday", "weekend", "schedule", "alarm", "reminder", "habit", "am", "pm", "baje"),
            listOf("Routines")
        ),
        WikiTopic(
            "places", "Places",
            listOf("live in", "rehta", "rehti", "city", "shehar", "home", "ghar", "office", "address", "location", "delhi", "mumbai", "pune", "bangalore", "bengaluru", "hyderabad", "chennai", "kolkata", "village", "gaon", "trip", "travel", "visit", "jaana", "flight", "train", "station", "airport"),
            emptyList()
        )
    )

    fun slugify(title: String): String {
        val s = title.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return s.ifEmpty { "page" }.take(48).trimEnd('-')
    }

    fun topicFor(slug: String): WikiTopic? = TOPICS.firstOrNull { it.slug == slug }

    /** Picks the topic page a memory belongs to by category + keyword hits (defaults to About you). */
    fun classify(text: String, jarvisCategory: String? = null, androCategory: String? = null): WikiTopic {
        val lower = " " + text.lowercase(Locale.ROOT) + " "
        var best: WikiTopic = TOPICS.first()
        var bestScore = 0
        for (t in TOPICS) {
            var score = 0
            if (jarvisCategory != null && t.categories.any { it.equals(jarvisCategory, true) }) score += 3
            if (androCategory != null && t.categories.any { it.equals(androCategory, true) }) score += 3
            for (k in t.keywords) {
                if (lower.contains(" $k ") || lower.contains(" $k.") || lower.contains(" $k,")) score += 2
                else if (k.length > 3 && lower.contains(k)) score += 1
            }
            if (score > bestScore) {
                bestScore = score
                best = t
            }
        }
        return best
    }

    fun parseIndex(json: String?): List<WikiPageMeta> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("pages") ?: JSONArray()
            val out = ArrayList<WikiPageMeta>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val slug = o.optString("slug").ifBlank { slugify(o.optString("title")) }
                if (slug.isBlank()) continue
                out += WikiPageMeta(
                    title = o.optString("title").ifBlank { slug },
                    slug = slug,
                    summary = o.optString("summary"),
                    updatedAt = o.optLong("updatedAt", 0L),
                    sources = o.optInt("sources", 0)
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun serializeIndex(pages: List<WikiPageMeta>): String = JSONObject().apply {
        put("version", 1)
        put("pages", JSONArray().also { arr ->
            pages.forEach { p ->
                arr.put(JSONObject().apply {
                    put("title", p.title)
                    put("slug", p.slug)
                    put("summary", p.summary)
                    put("updatedAt", p.updatedAt)
                    put("sources", p.sources)
                })
            }
        })
    }.toString(2)

    /** Replaces/adds [updated] by slug; fixed topics keep their canonical order, extras go after, alphabetically. */
    fun merge(existing: List<WikiPageMeta>, updated: WikiPageMeta): List<WikiPageMeta> {
        val bySlug = LinkedHashMap<String, WikiPageMeta>()
        existing.forEach { bySlug[it.slug] = it }
        bySlug[updated.slug] = updated
        val order = TOPICS.map { it.slug }
        return bySlug.values.sortedWith(
            compareBy<WikiPageMeta> { order.indexOf(it.slug).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.title.lowercase(Locale.ROOT) }
        )
    }

    /** Parses the writer's "SUMMARY: ...\n---\n<page>" output. Returns (summary, page) or null. */
    fun parseWriterOutput(raw: String): Pair<String, String>? {
        val text = raw.trim().removePrefix("```markdown").removePrefix("```md").removePrefix("```").removeSuffix("```").trim()
        if (text.isEmpty()) return null
        val lines = text.lines()
        val summaryLine = lines.firstOrNull { it.trim().uppercase(Locale.ROOT).startsWith("SUMMARY:") }
        val summary = summaryLine?.substringAfter(":")?.trim()?.take(100).orEmpty()
        val sepIdx = lines.indexOfFirst { it.trim() == "---" }
        val body = when {
            sepIdx >= 0 -> lines.drop(sepIdx + 1).joinToString("\n").trim()
            summaryLine != null -> lines.filterNot { it === summaryLine }.joinToString("\n").trim()
            else -> text
        }
        if (body.isEmpty()) return null
        return summary.ifBlank { body.lines().firstOrNull { it.isNotBlank() }?.trim('#', ' ', '-')?.take(100).orEmpty() } to body
    }
}

/**
 * Compiles memories + episodes into topic pages written by the LLM. Pages live at
 * `filesDir/wiki/<slug>.md`, the index at `filesDir/wiki/index.json`. Skips quietly when no
 * model/key is configured.
 */
object WikiCompiler {

    private const val TAG = "WikiCompiler"
    const val DIR_NAME = "wiki"
    const val INDEX_FILE = "index.json"
    const val PAGE_MAX_CHARS = 1200
    private const val MAX_MEMORIES_PER_PAGE = 40
    private const val MAX_EPISODES_PER_PAGE = 15

    /** System prompt ported from Maya's wiki writer (shortened page budget: 1200 chars). */
    val WRITER_SYSTEM_PROMPT: String = """
You maintain a small personal wiki about ONE person, on their own phone. You are the
assistant (Jarvis) who serves them; these pages are your own notes, for your own use later.

Write ONE page. Follow every rule.

FORMAT - exactly this, nothing before or after:
SUMMARY: <one line, max 100 chars, what this page contains>
---
<the page, in markdown, short bullet lines>

CONTENT RULES
1. REWRITE THE PAGE IN FULL. You are given the current page; produce its replacement,
   not an addition to it. DELETE anything the new evidence contradicts, and anything
   that is no longer true. A page that only ever grows is useless.
2. BE SPECIFIC. "Nikalta hai 6:45 ke aas paas weekdays pe" is worth ten lines of "has a
   morning routine". Times, names, apps, numbers. If you cannot be specific about
   something, leave it out rather than padding.
3. SEPARATE STATED FROM OBSERVED. Something he told you: write it plainly. Something
   you worked out from his activity: say so - "lagta hai", "shayad", "maine notice kiya
   ki". NEVER write an inference as though he said it.
4. MARK LOW CONFIDENCE. If the evidence is thin, say the line is a guess. A confident
   wrong line is much worse than a hedged right one.
5. NO INVENTING. Everything must trace to the evidence given. If the evidence is too
   thin for a real page, return a short honest page - that is a correct answer.
6. HIS WORDS. He may speak Hinglish; write in Hinglish where that is how he would say it.
   Use his vocabulary for people and things, not formal English translations.
7. NO PREAMBLE, no "here is the page", no code fences, no closing remarks.
8. Keep the whole page under $PAGE_MAX_CHARS characters. Use "## " headings and "- " bullets only.
""".trimIndent()

    data class CompileReport(val pagesWritten: Int, val pagesSkipped: Int, val skippedReason: String? = null) {
        fun describe(): String = skippedReason ?: "wrote $pagesWritten page(s), skipped $pagesSkipped"
    }

    fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR_NAME).also { it.mkdirs() }

    fun pageFile(context: Context, slug: String): File = File(dir(context), WikiIndex.slugify(slug) + ".md")

    fun readIndex(context: Context): List<WikiPageMeta> =
        WikiIndex.parseIndex(runCatching { File(dir(context), INDEX_FILE).takeIf { it.exists() }?.readText() }.getOrNull())

    fun writeIndex(context: Context, pages: List<WikiPageMeta>) {
        runCatching { File(dir(context), INDEX_FILE).writeText(WikiIndex.serializeIndex(pages)) }
            .onFailure { Log.w(TAG, "Could not write wiki index: ${it.message}") }
    }

    fun readPage(context: Context, slug: String): String? =
        runCatching { pageFile(context, slug).takeIf { it.exists() }?.readText() }.getOrNull()

    /** Writes a page + merges the index entry (also used by backup restore). */
    fun writePage(context: Context, meta: WikiPageMeta, markdown: String) {
        runCatching { pageFile(context, meta.slug).writeText(markdown) }
            .onFailure { Log.w(TAG, "Could not write page ${meta.slug}: ${it.message}") }
        writeIndex(context, WikiIndex.merge(readIndex(context), meta))
    }

    /** All pages as slug -> markdown (for backup). */
    fun allPages(context: Context): Map<String, String> =
        readIndex(context).mapNotNull { m -> readPage(context, m.slug)?.let { m.slug to it } }.toMap()

    /** One-line-per-page index summary for the system prompt ("read_wiki_page(slug) for depth"). */
    fun promptIndex(context: Context, maxChars: Int = 300): String {
        val pages = readIndex(context).filter { it.summary.isNotBlank() }
        if (pages.isEmpty()) return ""
        val sb = StringBuilder("[WIKI] (read_wiki_page(slug) for detail)\n")
        for (p in pages) {
            val line = "- ${p.slug}: ${p.summary}\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
        }
        return sb.toString().trimEnd()
    }

    /** True when any text model is reachable (cloud key or a local endpoint). */
    fun hasLlm(context: Context): Boolean = try {
        val p = ((context.applicationContext as? JarvisApp) ?: JarvisApp.instance).preferences
        listOf(p.apiKey, p.openAiApiKey, p.claudeApiKey, p.deepSeekApiKey, p.grokApiKey, p.openRouterApiKey, p.customEndpoint)
            .any { it.isNotBlank() }
    } catch (e: Throwable) {
        false
    }

    /** Groups memories/episodes by topic and asks the LLM to (re)write each page. */
    suspend fun compileNow(context: Context, force: Boolean = false): CompileReport = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!hasLlm(app)) return@withContext CompileReport(0, WikiIndex.TOPICS.size, "no LLM configured; wiki compile skipped")
        if (!MemoryPrefs.canStore(app)) return@withContext CompileReport(0, WikiIndex.TOPICS.size, "incognito; wiki compile skipped")

        val memories: List<Memory> = runCatching { MemoryManager.getInstance(app).getMemories() }.getOrDefault(emptyList())
            .filterNot { it.isArchived }
        val episodes = runCatching { EpisodeLog.get(app).recent(120) }.getOrDefault(emptyList())
        if (memories.isEmpty() && episodes.isEmpty()) return@withContext CompileReport(0, WikiIndex.TOPICS.size, "nothing to compile yet")

        val memByTopic = HashMap<String, MutableList<Memory>>()
        for (m in memories) {
            val t = WikiIndex.classify(m.content, m.jarvisCategory, m.category.label)
            memByTopic.getOrPut(t.slug) { ArrayList() } += m
        }
        val epByTopic = HashMap<String, MutableList<Episode>>()
        for (e in episodes) {
            val t = WikiIndex.classify(e.user + " " + e.assistant.take(200), null, null)
            epByTopic.getOrPut(t.slug) { ArrayList() } += e
        }

        val lastRun = MemoryPrefs.wikiLastRun(app)
        var written = 0
        var skipped = 0
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        for (topic in WikiIndex.TOPICS) {
            val mems = memByTopic[topic.slug].orEmpty().sortedByDescending { it.updatedAt }.take(MAX_MEMORIES_PER_PAGE)
            val eps = epByTopic[topic.slug].orEmpty().sortedByDescending { it.ts }.take(MAX_EPISODES_PER_PAGE)
            val sources = mems.size + eps.size
            if (sources == 0) { skipped++; continue }
            val changed = force || lastRun == 0L || mems.any { it.updatedAt > lastRun } || eps.any { it.ts > lastRun }
            if (!changed) { skipped++; continue }

            val current = readPage(app, topic.slug).orEmpty().ifBlank { "(empty - first version)" }
            val evidence = StringBuilder()
            evidence.append("PAGE: ${topic.title} (slug ${topic.slug})\n\nCURRENT PAGE:\n").append(current.take(PAGE_MAX_CHARS + 200)).append("\n\n")
            evidence.append("EVIDENCE - FACTS THE USER STATED OR THAT WERE EXTRACTED (newest first):\n")
            mems.forEach { m -> evidence.append("- [${dateFmt.format(Date(m.updatedAt))}] [${m.jarvisCategory}] ${m.content.take(300)}\n") }
            if (eps.isNotEmpty()) {
                evidence.append("\nEVIDENCE - OBSERVED FROM RECENT CONVERSATIONS (what actually happened; inferences only):\n")
                eps.forEach { e -> evidence.append("- [${dateFmt.format(Date(e.ts))}]${e.app?.let { " (app $it)" } ?: ""} ${e.summary}\n") }
            }
            evidence.append("\nNow write the replacement page.")

            val raw = try {
                AiTextSynthesizer.synthesizeText(
                    context = app,
                    systemInstruction = WRITER_SYSTEM_PROMPT,
                    prompt = evidence.toString(),
                    temperature = 0.3,
                    maxTokens = 700,
                    fallback = { "" }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Writer failed for ${topic.slug}: ${e.message}")
                ""
            }
            val parsed = WikiIndex.parseWriterOutput(raw)
            if (parsed == null) { skipped++; continue }
            val (summary, body) = parsed
            val page = "# ${topic.title}\n\n" + body.take(PAGE_MAX_CHARS)
            writePage(app, WikiPageMeta(topic.title, topic.slug, summary, System.currentTimeMillis(), sources), page)
            written++
        }
        MemoryPrefs.setWikiLastRun(app, System.currentTimeMillis())
        Log.i(TAG, "WIKI compiled: $written written, $skipped skipped")
        if (written > 0) runCatching { NoticesStore.post(app, "Wiki updated", "$written page(s) refreshed from your memories.", NoticesStore.KIND_INFO) }
        CompileReport(written, skipped)
    }
}

/** WorkManager job: periodic (12 h, unique "maya_wiki_compile") and on-demand ("maya_wiki_compile_now"). */
class WikiWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        return try {
            val report = WikiCompiler.compileNow(applicationContext, force)
            Log.i(TAG, "WIKI worker: ${report.describe()}")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "WIKI worker failed: ${e.message}")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "WikiWorker"
        const val KEY_FORCE = "force"
    }
}

object WikiScheduler {

    private const val TAG = "WikiScheduler"
    const val UNIQUE_PERIODIC = "maya_wiki_compile"
    const val UNIQUE_NOW = "maya_wiki_compile_now"
    private const val PERIOD_HOURS = 12L

    fun schedule(context: Context) {
        try {
            val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
            val periodic = PeriodicWorkRequestBuilder<WikiWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
            Log.i(TAG, "Wiki compile scheduled every ${PERIOD_HOURS}h")
        } catch (e: Throwable) {
            Log.w(TAG, "Could not schedule wiki compile: ${e.message}")
        }
    }

    fun runNow(context: Context, force: Boolean = true) {
        try {
            val once = OneTimeWorkRequestBuilder<WikiWorker>()
                .setInputData(androidx.work.workDataOf(WikiWorker.KEY_FORCE to force))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.REPLACE, once)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not start wiki compile: ${e.message}")
        }
    }

    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_PERIODIC) }
    }
}
