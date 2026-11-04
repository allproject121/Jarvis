package com.jarvis.assistant.maya.business

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A saved board (plan, research report, analysis...). Stored in reports.json / research.json /
 * trade_analysis.json depending on [kind] so the file names mirror Maya's.
 */
data class BoardRecord(
    val id: String = UUID.randomUUID().toString().take(8),
    val kind: String,          // marketing_plan | ads_plan | landing_page | competitor | keywords | trends | research | review_themes | lead_generation | analysis | watchlist_scan | portfolio_review | weekly_summary
    val title: String,
    val text: String = "",
    val status: String = "running", // running | done | failed | cancelled
    val query: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0L,
    val progress: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind); put("title", title); put("text", text); put("status", status)
        put("query", query); put("createdAt", createdAt); put("finishedAt", finishedAt); put("progress", progress)
    }

    companion object {
        fun fromJson(o: JSONObject) = BoardRecord(
            id = o.optString("id"), kind = o.optString("kind"), title = o.optString("title"), text = o.optString("text"),
            status = o.optString("status", "done"), query = o.optString("query"), createdAt = o.optLong("createdAt"),
            finishedAt = o.optLong("finishedAt"), progress = o.optString("progress")
        )

        fun fileFor(kind: String): String = when (kind) {
            "research" -> "research.json"
            "analysis", "watchlist_scan", "portfolio_review" -> "trade_analysis.json"
            else -> "reports.json"
        }

        /** Which status/cancel tool family a kind belongs to. */
        fun groupOf(kind: String): String = when (kind) {
            "research" -> "research"
            "analysis", "watchlist_scan", "portfolio_review" -> "market"
            else -> "marketing"
        }
    }
}

object BoardStore {
    private const val MAX_PER_FILE = 60

    fun save(context: Context, r: BoardRecord) {
        JsonFiles.updateArray(context, BoardRecord.fileFor(r.kind)) { arr ->
            val out = JSONArray()
            out.put(r.toJson())
            var n = 1
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id") == r.id) continue
                if (n++ >= MAX_PER_FILE) break
                out.put(o)
            }
            out
        }
    }

    fun list(context: Context, file: String): List<BoardRecord> =
        JsonFiles.toList(JsonFiles.readArray(context, file)).map { BoardRecord.fromJson(it) }

    fun all(context: Context): List<BoardRecord> =
        (list(context, "reports.json") + list(context, "research.json") + list(context, "trade_analysis.json"))
            .sortedByDescending { it.createdAt }

    fun byGroup(context: Context, group: String): List<BoardRecord> =
        all(context).filter { BoardRecord.groupOf(it.kind) == group }

    fun get(context: Context, id: String): BoardRecord? = all(context).firstOrNull { it.id.equals(id, true) }

    fun delete(context: Context, id: String): Boolean {
        var removed = false
        for (f in listOf("reports.json", "research.json", "trade_analysis.json")) {
            JsonFiles.updateArray(context, f) { arr ->
                val out = JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("id").equals(id, true)) removed = true else out.put(o)
                }
                out
            }
        }
        return removed
    }
}

/**
 * Background job runner for business boards. One job per group at a time (Maya: "Marketing ka ek
 * kaam pehle se chal raha hai - dobara start nahi kiya"). On completion the owner gets a notification
 * and the live session gets a [SYSTEM EVENT].
 */
object BusinessJobs {
    private const val TAG = "BusinessJobs"
    private val jobs = ConcurrentHashMap<String, Pair<String, Job>>() // group -> (recordId, job)

    fun running(group: String): BoardRecord? {
        val entry = jobs[group] ?: return null
        return if (entry.second.isActive) RunningCache.get(entry.first) else null
    }

    fun isRunning(group: String): Boolean = jobs[group]?.second?.isActive == true

    /** Small in-memory cache of running records so status queries see live progress. */
    private object RunningCache {
        val map = ConcurrentHashMap<String, BoardRecord>()
        fun get(id: String) = map[id]
    }

    fun progress(context: Context, record: BoardRecord, text: String): BoardRecord {
        val r = record.copy(progress = text)
        RunningCache.map[r.id] = r
        BoardStore.save(context, r)
        return r
    }

    /**
     * Starts [work] in the background. [work] returns the outRec board text (or throws).
     * Returns the created record, or null if a job of the same group is already running.
     */
    fun start(
        context: Context,
        kind: String,
        title: String,
        query: String,
        doneEvent: (BoardRecord) -> String,
        work: suspend (BoardRecord) -> String
    ): BoardRecord? {
        val app = context.applicationContext
        val group = BoardRecord.groupOf(kind)
        if (isRunning(group)) return null
        val record = BoardRecord(kind = kind, title = title, query = query, progress = "shuru…")
        BoardStore.save(app, record)
        RunningCache.map[record.id] = record
        val job = BusinessRuntime.scope.launch {
            var outRec: BoardRecord
            try {
                val text = work(record)
                outRec = (RunningCache.map[record.id] ?: record).copy(
                    text = text, status = if (text.isBlank()) "failed" else "done",
                    finishedAt = System.currentTimeMillis(), progress = ""
                )
                val parsedTitle = BoardScriptParser.parse(text).title
                if (parsedTitle.isNotBlank()) outRec = outRec.copy(title = parsedTitle)
            } catch (e: CancellationException) {
                outRec = (RunningCache.map[record.id] ?: record).copy(status = "cancelled", finishedAt = System.currentTimeMillis())
                BoardStore.save(app, outRec)
                RunningCache.map.remove(record.id)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "job $kind failed: ${e.message}", e)
                outRec = (RunningCache.map[record.id] ?: record).copy(
                    status = "failed", text = "Kaam beech me ruk gaya: ${e.message}", finishedAt = System.currentTimeMillis()
                )
            }
            BoardStore.save(app, outRec)
            RunningCache.map.remove(record.id)
            val tab = if (group == "market") BusinessActivity.TAB_FINANCE else BusinessActivity.TAB_MARKETING
            if (outRec.status == "done") {
                BusinessEvents.notifyAndInject(
                    app, 0x5200 + (outRec.id.hashCode() and 0xFF), "Ready: ${outRec.title}",
                    outRec.text.lineSequence().filter { it.startsWith("- ") }.take(3).joinToString("\n").ifBlank { outRec.title },
                    doneEvent(outRec), tab
                )
            } else {
                BusinessEvents.notifyAndInject(
                    app, 0x5200 + (outRec.id.hashCode() and 0xFF), "Nahi ho paaya: ${outRec.title}", outRec.text.take(200),
                    "Background kaam '${outRec.title}' poora nahi ho paaya: ${outRec.text.take(160)}. User ko sach bata do.", tab
                )
            }
        }
        jobs[group] = record.id to job
        job.invokeOnCompletion { jobs.remove(group, record.id to job) }
        return record
    }

    fun cancel(group: String): Boolean {
        val entry = jobs[group] ?: return false
        if (!entry.second.isActive) return false
        entry.second.cancel()
        return true
    }

    /** Status text for `marketing_status` / `research_status`. */
    fun statusText(context: Context, group: String, id: String? = null): String {
        if (!id.isNullOrBlank()) {
            val r = BoardStore.get(context, id) ?: return "Report '$id' nahi mili."
            return describe(r)
        }
        val runningId = jobs[group]?.takeIf { it.second.isActive }?.first
        if (runningId != null) {
            val r = RunningCache.get(runningId) ?: BoardStore.get(context, runningId)
            if (r != null) return "Chal raha hai: \"${r.title}\" - ${r.progress.ifBlank { "kaam chal raha hai" }} (${(System.currentTimeMillis() - r.createdAt) / 1000}s)."
        }
        val last = BoardStore.byGroup(context, group).firstOrNull { it.status != "running" }
            ?: return "Abhi kuch nahi chal raha aur koi purani report nahi hai."
        return "Abhi kuch nahi chal raha. Pichhli report:\n" + describe(last)
    }

    private fun describe(r: BoardRecord): String =
        "[${r.id}] ${r.title} (${r.kind}, ${r.status}, ${DateTimeParse.formatDateTime(r.createdAt)})\n" + r.text.take(3500)
}
