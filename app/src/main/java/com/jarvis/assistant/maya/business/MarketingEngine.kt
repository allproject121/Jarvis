package com.jarvis.assistant.maya.business

import android.content.Context
import com.jarvis.assistant.automation.crm.LeadCrmManager
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/** Marketing plans + web research boards (all run as background [BusinessJobs]). */
object MarketingEngine {

    private fun ensureNote(text: String, note: String): String {
        if (text.isBlank()) return text
        return if (text.contains("## Note", ignoreCase = true) || text.contains("## Ye advice", ignoreCase = true)) text
        else text.trimEnd() + "\n---\n## Note\n- " + note
    }

    /** kind: marketing | ads | landing_page */
    fun startPlan(context: Context, goal: String, budget: String, platform: String, kind: String, days: Int): BoardRecord? {
        val k = when (kind.lowercase()) { "ads", "ads_plan", "campaign_ads" -> "ads_plan"; "landing_page", "landing" -> "landing_page"; else -> "marketing_plan" }
        val format = when (k) { "ads_plan" -> BusinessPrompts.ADS_PLAN_FORMAT; "landing_page" -> BusinessPrompts.LANDING_PAGE_FORMAT; else -> BusinessPrompts.MARKETING_PLAN_FORMAT }
        val title = when (k) { "ads_plan" -> "Ads plan: $goal"; "landing_page" -> "Landing page: $goal"; else -> "Marketing plan: $goal" }
        return BusinessJobs.start(context, k, title, goal, { r ->
            "Marketing board ready: '${r.title}' (id ${r.id}). User ko 3-4 line me ZUBAANI batao - Hinglish me, sabse kaam ki baat pehle. " +
                "Poora content Business > Marketing screen pe hai. Yaad dilao: ye AI suggestion hai, numbers andaaze hain, measured data nahi."
        }) { rec ->
            val profile = BusinessProfileStore(context).load()
            BusinessJobs.progress(context, rec, "Plan bana rahi hoon…")
            val prompt = buildString {
                append(profile.promptBlock()).append("\n\n")
                append("GOAL: ").append(goal).append('\n')
                if (budget.isNotBlank()) append("BUDGET: ").append(budget).append('\n')
                if (platform.isNotBlank()) append("PLATFORM: ").append(platform).append('\n')
                append("PLAN LENGTH: ").append(days.coerceIn(7, 90)).append(" days\n\n")
                append(format)
            }
            val out = BusinessRuntime.llm(context, BusinessPrompts.SYSTEM_MARKETER, prompt, 0.6, 2200)
            ensureNote(out, "Ye suggestions aur andaaze hain, measured data nahi. Kuch bhi post/pay owner khud karega.")
        }
    }

    /** Generic search -> LLM board. [queries] are run one by one (cancellable). */
    private suspend fun searchAndSummarise(
        context: Context,
        rec: BoardRecord,
        queries: List<String>,
        task: String,
        format: String = BusinessPrompts.RESEARCH_FORMAT
    ): String {
        val profile = BusinessProfileStore(context).load()
        val ctx = StringBuilder()
        queries.forEachIndexed { i, q ->
            currentCoroutineContext().ensureActive()
            BusinessJobs.progress(context, rec, "Search ${i + 1}/${queries.size}: $q")
            val res = BusinessRuntime.searchWeb(context, q)
            ctx.append(res.asContext(1800)).append('\n')
        }
        currentCoroutineContext().ensureActive()
        BusinessJobs.progress(context, rec, "Report likh rahi hoon…")
        val prompt = buildString {
            append(profile.promptBlock()).append("\n\nTASK: ").append(task).append("\n\nSEARCH RESULTS:\n")
            append(ctx.toString().take(9000)).append("\n\n").append(format)
        }
        val out = BusinessRuntime.llm(context, BusinessPrompts.SYSTEM_MARKETER, prompt, 0.4, 2200)
        return ensureNote(out, "Search results ka AI summary hai - andaaza hai, measured data nahi.")
    }

    fun startCompetitorResearch(context: Context, competitor: String): BoardRecord? {
        val loc = BusinessProfileStore(context).load().location
        return BusinessJobs.start(context, "competitor", "Competitor research: $competitor", competitor, { r ->
            "Competitor research ready: '${r.title}' (id ${r.id}). 3-4 line me gist do; ye unke pages pe likhi baaton se hai, prices/reviews ka andaaza nahi lagaya."
        }) { rec ->
            searchAndSummarise(
                context, rec,
                listOf("$competitor $loc", "$competitor reviews", "$competitor price offers services", "$competitor instagram facebook"),
                "Competitor research on '$competitor': what they offer, how they position/price (only if stated), what customers praise/complain about, " +
                    "their social presence, and 3-5 gaps our business can use. Sections: Overview, Offer and pricing (stated only), Positioning, " +
                    "Customers kya bolte hain, Social presence, Hamare liye mauke, Sources."
            )
        }
    }

    fun startKeywordResearch(context: Context, topic: String): BoardRecord? {
        val loc = BusinessProfileStore(context).load().location
        return BusinessJobs.start(context, "keywords", "Keyword research: $topic", topic, { r ->
            "Keyword research ready: '${r.title}' (id ${r.id}). Gist do. ${BusinessPrompts.KEYWORD_NOTE}"
        }) { rec ->
            BusinessJobs.progress(context, rec, "Log kya search karte hain, dekh rahi hoon…")
            val seeds = listOf(topic, "$topic $loc".trim(), "$topic near me", "best $topic", "$topic price")
            val suggestions = LinkedHashSet<String>()
            val engine = com.jarvis.assistant.marketing.SeoAuditEngine()
            for (s in seeds) {
                currentCoroutineContext().ensureActive()
                runCatching { engine.fetchKeywords(s) }.getOrNull()?.let { suggestions.addAll(it) }
            }
            val profile = BusinessProfileStore(context).load()
            val prompt = buildString {
                append(profile.promptBlock()).append("\n\nTASK: group these REAL autocomplete queries for '").append(topic)
                append("' by intent (hire/buy, local, questions, compare). Suggest a primary keyword, supporting keywords, a service-page outline ")
                append("(H2s) and 4-6 local FAQs. There is NO search-volume data - never quote volume, difficulty or ranking promises.\n\n")
                append("QUERIES:\n").append(suggestions.take(80).joinToString("\n")).append("\n\n")
                append(BusinessPrompts.RESEARCH_FORMAT.replace("End with '## Sources' (one '- title - url' per line) then", "End with"))
            }
            val out = BusinessRuntime.llm(context, BusinessPrompts.SYSTEM_MARKETER, prompt, 0.4, 1800)
            ensureNote(out, BusinessPrompts.KEYWORD_NOTE)
        }
    }

    fun startTrendResearch(context: Context, niche: String): BoardRecord? {
        val y = Calendar.getInstance().get(Calendar.YEAR)
        return BusinessJobs.start(context, "trends", "Trend research: $niche", niche, { r ->
            "Trend research ready: '${r.title}' (id ${r.id}). 3-4 trend ideas zubaani batao - ye andaaza hai."
        }) { rec ->
            searchAndSummarise(
                context, rec,
                listOf("$niche trends $y", "$niche instagram reels trend", "$niche latest news India", "$niche content ideas"),
                "Trend research for the niche '$niche': what is trending now, content angles that fit our business, 5 post/reel ideas " +
                    "(one line each), and what to avoid. Sections: Abhi kya chal raha hai, Hamare business ke liye angles, Content ideas, Avoid, Sources."
            )
        }
    }

    fun startSeoResearch(context: Context, topic: String, site: String): BoardRecord? =
        BusinessJobs.start(context, "seo", "SEO research: $topic", topic, { r ->
            "SEO research ready: '${r.title}' (id ${r.id}). Gist do - koi ranking promise mat karo."
        }) { rec ->
            val audit = if (site.isNotBlank()) runCatching { com.jarvis.assistant.marketing.SeoAuditEngine().runPageSpeedAudit(site) }.getOrDefault("") else ""
            searchAndSummarise(
                context, rec,
                listOf(topic, "$topic guide", "$topic faq"),
                "SEO research for '$topic'. Who ranks (from results), what their pages cover, content gaps, a page outline for us." +
                    (if (audit.isNotBlank()) "\nOur site audit:\n${audit.take(1500)}" else "")
            )
        }

    fun startDeepResearch(context: Context, question: String): BoardRecord? =
        BusinessJobs.start(context, "research", "Research: $question", question, { r ->
            "Research ho gayi: '${r.title}' (id ${r.id}). Ab user ko 3-4 line me ZUBAANI gist do - sirf sabse kaam ki baatein, Hinglish me. " +
                "Poori report Business > Marketing board pe hai, isliye wo mat padho. URLs kabhi mat bolo. Jo nahi mila usko honestly bolo. " +
                "Detail maange to research_status se poori report padh sakti ho."
        }) { rec ->
            BusinessJobs.progress(context, rec, "Research plan bana rahi hoon…")
            val planRaw = BusinessRuntime.llm(
                context, "You split research questions into web search queries. Output ONLY a JSON array of 4-6 short search queries.",
                "Question: $question", 0.3, 300
            )
            val queries = parseStringArray(planRaw).ifEmpty { listOf(question, "$question latest", "$question explained", "$question data") }.take(6)
            val ctx = StringBuilder()
            queries.forEachIndexed { i, q ->
                currentCoroutineContext().ensureActive()
                BusinessJobs.progress(context, rec, "Search ${i + 1}/${queries.size}: $q")
                ctx.append(BusinessRuntime.searchWeb(context, q).asContext(1600)).append('\n')
            }
            currentCoroutineContext().ensureActive()
            BusinessJobs.progress(context, rec, "Report likh rahi hoon…")
            val out = BusinessRuntime.llm(
                context,
                "You are a careful researcher. Use only the search results; mark anything uncertain as 'andaaza'. Write in simple Hinglish (Roman script).",
                "QUESTION: $question\n\nSEARCH RESULTS:\n${ctx.toString().take(11000)}\n\n" +
                    "Write a sourced report with sub-sections per sub-question, a 'Seedha jawab' section first, and a 'Kya pakka nahi hai' section.\n" +
                    BusinessPrompts.RESEARCH_FORMAT,
                0.3, 2600
            )
            ensureNote(out, "Search results ka AI summary hai - andaaza hai, measured data nahi.")
        }

    fun startLeadGeneration(context: Context, criteria: String, location: String): BoardRecord? {
        val loc = location.ifBlank { BusinessProfileStore(context).load().location }
        return BusinessJobs.start(context, "lead_generation", "Lead generation: $criteria", criteria, { r ->
            "Lead list ready: '${r.title}' (id ${r.id}). Jinke phone number mile wo Leads me NEW status ke saath save hain (source ke saath). " +
                "Kisi ko message mat bhejo jab tak user na bole - send_lead_message chat kholta hai, send user dabata hai."
        }) { rec ->
            val queries = listOf("$criteria $loc contact number", "$criteria in $loc", "$criteria $loc justdial", "$criteria $loc phone")
            val ctx = StringBuilder()
            queries.forEachIndexed { i, q ->
                currentCoroutineContext().ensureActive()
                BusinessJobs.progress(context, rec, "Search ${i + 1}/${queries.size}: $q")
                ctx.append(BusinessRuntime.searchWeb(context, q).asContext(1800)).append('\n')
            }
            BusinessJobs.progress(context, rec, "List bana rahi hoon…")
            val raw = BusinessRuntime.llm(
                context,
                "Extract prospective business leads ONLY from the given search results. Never invent a name or a phone number. Output ONLY JSON.",
                "Criteria: $criteria, location: $loc\nRESULTS:\n${ctx.toString().take(9000)}\n\n" +
                    "Return a JSON array of objects {\"name\":..,\"phone\":..(digits as written in the result, or empty),\"company\":..,\"source\":..(url),\"why\":..}. Max 15.",
                0.1, 1500
            )
            val leads = parseObjectArray(raw)
            val crm = LeadCrmManager.getInstance(context)
            val sb = StringBuilder("# Leads - $criteria ($loc)\n## Mile hue leads\n")
            var saved = 0
            for (o in leads) {
                val name = o.optString("name").trim()
                if (name.isBlank()) continue
                val phone = o.optString("phone").filter { it.isDigit() || it == '+' }
                val source = o.optString("source")
                if (phone.filter { it.isDigit() }.length >= 8) {
                    crm.addLead(name, phone, o.optString("company").ifBlank { null }, "source: lead_generation ${source.take(120)}; ${o.optString("why").take(120)}", 0.0, "NEW")
                    saved++
                    sb.append("- ").append(name).append(" | ").append(phone).append(" | saved NEW\n")
                } else {
                    sb.append("- ").append(name).append(" | number nahi mila | ").append(source.take(80)).append('\n')
                }
            }
            if (leads.isEmpty()) sb.append("- Search results me koi saaf lead nahi mila.\n")
            sb.append("---\n## Note\n- ").append(saved).append(" leads phone ke saath save hue. Ye public search results se hain - verify karke hi contact karo.")
            sb.toString()
        }
    }

    suspend fun reviewThemes(context: Context, reviews: String): BoardRecord {
        val profile = BusinessProfileStore(context).load()
        val out = BusinessRuntime.llm(
            context, BusinessPrompts.SYSTEM_MARKETER,
            profile.promptBlock() + "\n\nTASK: summarise these customer reviews into recurring PRAISE and COMPLAINT themes with rough counts " +
                "(count only what is in the text), 3 quick fixes and 2 reply angles. Flag any review mentioning injury, legal threat, fraud, " +
                "harassment or privacy under '## Owner khud dekhe' - do not draft replies for those.\n" +
                "Board script: '# Review themes', '## Tareef', '## Shikayatein', '## Quick fixes', '## Reply angles', '## Owner khud dekhe'.\n\nREVIEWS:\n" +
                reviews.take(9000),
            0.3, 1600
        )
        val text = if (out.isBlank()) "" else out.trimEnd() + "\n" + BusinessPrompts.REVIEW_THEMES_NOTE
        val rec = BoardRecord(kind = "review_themes", title = "Review themes", text = text, status = if (text.isBlank()) "failed" else "done",
            query = reviews.take(80), finishedAt = System.currentTimeMillis())
        BoardStore.save(context, rec)
        return rec
    }

    fun parseStringArray(raw: String): List<String> {
        val s = raw.substringAfter('[', "").substringBeforeLast(']', "")
        if (s.isBlank()) return emptyList()
        return try {
            val arr = JSONArray("[$s]")
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    fun parseObjectArray(raw: String): List<JSONObject> {
        val s = raw.substringAfter('[', "").substringBeforeLast(']', "")
        if (s.isBlank()) return emptyList()
        return try { JsonFiles.toList(JSONArray("[$s]")) } catch (_: Exception) { emptyList() }
    }
}

/** kpi.json: numbers the owner logs (leads, spend, sales, followers...). */
object KpiStore {
    const val FILE = "kpi.json"

    data class KpiEntry(val id: String, val metric: String, val value: Double, val date: String, val note: String, val createdAt: Long)

    fun log(context: Context, metric: String, value: Double, date: String, note: String): KpiEntry {
        val e = KpiEntry(UUID.randomUUID().toString().take(8), normalizeMetric(metric), value, date, note, System.currentTimeMillis())
        JsonFiles.updateArray(context, FILE) { arr ->
            arr.put(JSONObject().put("id", e.id).put("metric", e.metric).put("value", e.value).put("date", e.date)
                .put("note", e.note).put("createdAt", e.createdAt))
            arr
        }
        return e
    }

    fun all(context: Context): List<KpiEntry> = JsonFiles.toList(JsonFiles.readArray(context, FILE)).map {
        KpiEntry(it.optString("id"), it.optString("metric"), it.optDouble("value", 0.0), it.optString("date"), it.optString("note"), it.optLong("createdAt"))
    }

    fun normalizeMetric(m: String): String = m.trim().lowercase().replace(' ', '_').let {
        when (it) { "lead" -> "leads"; "sale" -> "sales"; "ad_spend", "spent", "cost" -> "spend"; "follower" -> "followers"; "revenue", "income" -> "revenue"; else -> it }
    }

    /** period: today | week | month | all | N (days). Returns (fromDateInclusive). */
    fun periodStart(period: String): String? {
        val cal = Calendar.getInstance()
        val p = period.trim().lowercase()
        val days = when {
            p == "all" -> return null
            p == "today" || p == "aaj" -> 0
            p == "week" || p == "weekly" || p == "hafta" || p.isBlank() -> 6
            p == "month" || p == "monthly" || p == "mahina" -> 29
            p.toIntOrNull() != null -> (p.toInt() - 1).coerceAtLeast(0)
            else -> 6
        }
        cal.add(Calendar.DAY_OF_YEAR, -days)
        return DateTimeParse.formatDate(cal.timeInMillis)
    }

    fun summary(context: Context, period: String): String {
        val from = periodStart(period)
        val entries = all(context).filter { from == null || it.date >= from }
        if (entries.isEmpty()) return "Is period (${period.ifBlank { "week" }}) me koi KPI log nahi hua. 'kpi_log' se number daalo, jaise leads=12, spend=2000."
        val byMetric = entries.groupBy { it.metric }
        val sb = StringBuilder("# KPI_SUMMARY (${period.ifBlank { "week" }}${if (from != null) ", $from se" else ""})\n## Numbers\n")
        val totals = HashMap<String, Double>()
        for ((m, list) in byMetric.toSortedMap()) {
            val sorted = list.sortedBy { it.date }
            val total = sorted.sumOf { it.value }
            totals[m] = total
            val cumulative = m in setOf("followers", "subscribers", "rating")
            if (cumulative) sb.append("- ").append(m).append(": latest ").append(fmt(sorted.last().value))
                .append(" (pehle ").append(fmt(sorted.first().value)).append(", badlav ").append(fmt(sorted.last().value - sorted.first().value)).append(")\n")
            else sb.append("- ").append(m).append(": total ").append(fmt(total)).append(" (").append(sorted.size).append(" entries, avg ").append(fmt(total / sorted.size)).append(")\n")
        }
        val leads = totals["leads"]; val spend = totals["spend"]; val sales = totals["sales"]; val revenue = totals["revenue"]
        sb.append("## Calculated\n")
        var any = false
        if (leads != null && spend != null && leads > 0) { sb.append("- Cost per lead: ").append(fmt(spend / leads)).append('\n'); any = true }
        if (sales != null && leads != null && leads > 0) { sb.append("- Lead to sale: ").append(fmt(sales / leads * 100)).append("%\n"); any = true }
        if (revenue != null && spend != null && spend > 0) { sb.append("- Revenue / spend (ROAS): ").append(fmt(revenue / spend)).append("x\n"); any = true }
        if (!any) sb.append("- Leads + spend log karo to cost per lead nikal dungi.\n")
        sb.append("---\n## Note\n- Ye sirf aapke logged numbers hain - measured tracking nahi.")
        return sb.toString()
    }

    fun fmt(v: Double): String = if (v == Math.floor(v) && Math.abs(v) < 1e12) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)
}

/** Saved report formats (Maya: "Daily Report {date}\nShift: {shift}..."), report_formats.json. */
object ReportFormats {
    const val FILE = "report_formats.json"

    fun all(context: Context): List<Pair<String, String>> =
        JsonFiles.toList(JsonFiles.readArray(context, FILE)).map { it.optString("name") to it.optString("template") }

    fun save(context: Context, name: String, template: String) {
        JsonFiles.updateArray(context, FILE) { arr ->
            val out = JSONArray()
            for (i in 0 until arr.length()) { val o = arr.optJSONObject(i) ?: continue; if (!o.optString("name").equals(name, true)) out.put(o) }
            out.put(JSONObject().put("name", name).put("template", template).put("savedAt", System.currentTimeMillis()))
            out
        }
    }

    fun delete(context: Context, name: String): Boolean {
        var removed = false
        JsonFiles.updateArray(context, FILE) { arr ->
            val out = JSONArray()
            for (i in 0 until arr.length()) { val o = arr.optJSONObject(i) ?: continue; if (o.optString("name").equals(name, true)) removed = true else out.put(o) }
            out
        }
        return removed
    }

    fun find(context: Context, name: String): String? = all(context).firstOrNull { it.first.equals(name, true) }?.second
        ?: all(context).firstOrNull { it.first.contains(name, true) }?.second

    /** Fills {placeholders} from [values]; {date} auto-fills. Unfilled ones stay visible as {field}. */
    fun fill(template: String, values: Map<String, String>): String {
        var out = template.replace("{date}", DateTimeParse.formatDate(System.currentTimeMillis()))
        for ((k, v) in values) out = out.replace("{$k}", v)
        return out
    }

    fun missing(text: String): List<String> = Regex("\\{([a-zA-Z_]+)\\}").findAll(text).map { it.groupValues[1] }.distinct().toList()
}
