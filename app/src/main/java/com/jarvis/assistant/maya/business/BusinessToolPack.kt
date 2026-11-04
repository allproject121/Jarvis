package com.jarvis.assistant.maya.business

import android.content.Context
import android.content.Intent
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.crm.LeadCrmManager
import com.jarvis.assistant.automation.crm.LeadFollowupScheduler
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry.ToolOutcome
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry.prop
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry.props
import com.jarvis.assistant.util.ActivityLauncherHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * WP4 - Maya's BUSINESS / MARKETING / CRM / FINANCE tools.
 *
 * Install: `BusinessToolPack.install(context)` (registered in MayaToolPacks).
 * Families: "business" and "finance". Maya's rule throughout: the assistant prepares, the OWNER
 * approves, posts, pays and presses send. Nothing here publishes or messages on its own.
 */
object BusinessToolPack {

    private const val BIZ = "business"
    private const val FIN = "finance"

    // ---------------------------------------------------------------- arg helpers
    private fun JSONObject.s(vararg keys: String): String {
        for (k in keys) { val v = optString(k, "").trim(); if (v.isNotEmpty() && v != "null") return v }
        return ""
    }

    private fun JSONObject.i(key: String, def: Int): Int = when (val v = opt(key)) {
        is Number -> v.toInt()
        is String -> v.trim().toDoubleOrNull()?.toInt() ?: def
        else -> def
    }

    private fun JSONObject.d(vararg keys: String): Double? {
        for (k in keys) {
            when (val v = opt(k)) {
                is Number -> return v.toDouble()
                is String -> v.replace(",", "").replace("₹", "").replace("rs", "", true).trim().toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.b(key: String): Boolean = when (val v = opt(key)) {
        is Boolean -> v
        is String -> v.equals("true", true) || v.equals("yes", true) || v == "1" || v.equals("haan", true)
        is Number -> v.toInt() != 0
        else -> false
    }

    private fun JSONObject.list(key: String): List<String> = when (val v = opt(key)) {
        is JSONArray -> (0 until v.length()).map { v.optString(it).trim() }.filter { it.isNotEmpty() }
        is String -> v.split(',', '|', '/', ';').map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    private fun str(desc: String, enum: List<String>? = null) = prop("string", desc, enum)
    private fun num(desc: String) = prop("number", desc)
    private fun bool(desc: String) = prop("boolean", desc)

    private fun reg(name: String, desc: String, p: JSONObject, req: List<String>, family: String = BIZ, risky: Boolean = false,
                    h: suspend (Context, JSONObject) -> ToolOutcome) =
        MayaToolRegistry.register(name, desc, p, req, family, risky, h)

    private fun ok(msg: String, extra: JSONObject = JSONObject()) = ToolOutcome.ok(msg, extra)
    private fun fail(msg: String) = ToolOutcome.fail(msg)

    private fun busyMsg(group: String, ctx: Context): String =
        (if (group == "research") "Research pehle se chal rahi hai - dobara start nahi ki. " else "Marketing ka ek kaam pehle se chal raha hai — dobara start nahi kiya. ") +
            BusinessJobs.statusText(ctx, group)

    private fun findLead(ctx: Context, q: String): LeadCrmManager.LeadRecord? {
        if (q.isBlank()) return null
        val crm = LeadCrmManager.getInstance(ctx)
        crm.listLeads(null, 2000).firstOrNull { it.id.equals(q, true) }?.let { return it }
        val hits = crm.searchLeads(q)
        return hits.firstOrNull { it.name.equals(q, true) } ?: hits.firstOrNull()
    }

    fun install(context: Context) {
        installProfileAndContent()
        installMarketing()
        installCrmAndReports()
        installMissions()
        installFinance()
        // Re-arm scheduled post reminders and the alert worker after process start / reboot.
        try { PostReminderReceiver.rearmAll(context) } catch (_: Exception) { }
        try { FinanceEngine.syncAlertWorker(context) } catch (_: Exception) { }
    }

    // ================================================================= profile + content
    private fun installProfileAndContent() {
        reg("business_profile",
            "The owner's BUSINESS PROFILE - approved facts every marketing/content tool uses (name, what you sell, audience, location, tone, platforms, " +
                "claims allowed, phone, website, hours, offers, review link). action: show | new | update | delete. For new/update pass named fields or " +
                "fields='type=...; location=...'. Never invent services, prices or offers beyond the profile.",
            props(
                "action" to str("show | new | update | delete", listOf("show", "new", "update", "delete")),
                "name" to str("Business name"), "type" to str("What the business sells / does"), "audience" to str("Target audience"),
                "location" to str("City / area"), "tone" to str("Brand tone"), "platforms" to str("Social platforms, comma separated"),
                "claims" to str("Claims the owner allows in ads/copy"), "phone" to str("Business phone"), "website" to str("Website"),
                "hours" to str("Opening hours"), "language" to str("Content language (default Hinglish)"), "offers" to str("Current offers"),
                "review_link" to str("Google review link"), "fields" to str("Extra 'key=value; key=value' pairs")
            ), listOf("action")) { ctx, a ->
            val store = BusinessProfileStore(ctx)
            when (a.s("action").lowercase().ifBlank { "show" }) {
                "show", "get" -> {
                    val p = store.load()
                    if (p.isEmpty()) ok(BusinessPrompts.NO_PROFILE_HINT) else ok(p.render(), JSONObject().put("profile", p.toJson()))
                }
                "delete", "clear" -> { store.clear(); ok("Business profile hata diya.") }
                else -> {
                    val fields = LinkedHashMap<String, String>()
                    fields.putAll(store.parseFieldString(a.s("fields")))
                    for (k in BusinessProfile.FIELDS) a.s(k).takeIf { it.isNotBlank() }?.let { fields[k] = it }
                    val base = if (a.s("action").equals("new", true)) BusinessProfile() else store.load()
                    val p = store.applyFields(base, fields)
                    if (p.isEmpty()) return@reg fail("Kam se kam naam aur kaam/type chahiye. ${BusinessPrompts.NO_PROFILE_HINT}")
                    store.save(p)
                    ok("Business profile save ho gaya.\n" + p.render(), JSONObject().put("profile", p.toJson()))
                }
            }
        }

        reg("create_content",
            "WRITE social media content for the active business and save it as a DRAFT: a post, a reel script (15-60 s: hook, scene-by-scene, on-screen text, " +
                "voiceover, caption, hashtags), a carousel (slide 1 hook ... last slide CTA), a story, or a blog_brief. Platforms: instagram, facebook, linkedin, x, " +
                "whatsapp, youtube. Use for 'ek post likho', 'reel script banao', 'carousel banao', 'caption aur hashtags do'. This WRITES text only. " +
                "Read the draft back to the user; it is an AI suggestion until they approve it.",
            props(
                "kinds" to str("Comma list: post, reel_script, carousel, story, blog_brief"),
                "platforms" to str("Comma list: instagram, facebook, linkedin, x, whatsapp, youtube"),
                "count" to num("How many items (1-12, default 1)"), "topic" to str("Topic / brief"), "pillar" to str("Content pillar"),
                "repurpose_text" to str("Optional blog/video transcript to repurpose")
            ), emptyList()) { ctx, a ->
            val kinds = a.list("kinds").ifEmpty { a.list("kind") }
            val repurpose = a.s("repurpose_text")
            val items = ContentEngine.createContent(ctx, kinds, a.list("platforms").ifEmpty { a.list("platform") }, a.i("count", if (repurpose.isNotBlank()) 9 else 1),
                a.s("topic", "item", "brief"), a.s("pillar"),
                if (repurpose.isNotBlank()) "REPURPOSE this text into 5 posts + 3 reel_scripts + 1 carousel:\n${repurpose.take(5000)}" else "")
            if (items.isEmpty()) return@reg fail("Content nahi ban paaya - AI model se jawab nahi aaya. Thodi der baad dobara bolo.")
            val profileNote = if (BusinessProfileStore(ctx).load().isEmpty()) "\n(Business profile saved nahi hai - generic likha hai. business_profile se banao.)" else ""
            ok("${items.size} bane, sab Content tab me Draft me hain.\n\n" + items.joinToString("\n\n") { it.render() } +
                "\n\n${BusinessRuntime.SUGGESTION_NOTE}$profileNote",
                JSONObject().put("ids", JSONArray(items.map { it.id })))
        }

        reg("content_calendar",
            "Content calendar of drafts / approved / scheduled / published items. action: show (optionally status filter) | approve | schedule (needs datetime) | " +
                "unschedule | delete | published (user said 'ye post ho gaya') | read (full item). Only the OWNER approves.",
            props(
                "action" to str("show | read | approve | schedule | unschedule | delete | published", listOf("show", "read", "approve", "schedule", "unschedule", "delete", "published")),
                "id" to str("Content item id (or title)"), "datetime" to str("When to schedule: '2026-11-05 18:00', 'kal 10 baje', 'in 2 hours'"),
                "status" to str("Filter for show: Draft | Approved | Scheduled | Published")
            ), listOf("action")) { ctx, a ->
            val id = a.s("id", "item")
            when (a.s("action").lowercase()) {
                "show", "list", "" -> {
                    val filter = a.s("status")
                    val items = ContentStore(ctx).all().filter { filter.isBlank() || it.status.equals(filter, true) }
                    if (items.isEmpty()) return@reg ok("Content calendar khaali hai. create_content se likhwao.")
                    ok(items.take(30).joinToString("\n") {
                        "- [${it.id}] ${it.status} | ${it.kind}/${it.platform} | ${it.title}" + if (it.scheduledAt > 0) " | ${DateTimeParse.formatDateTime(it.scheduledAt)}" else ""
                    })
                }
                "read" -> ContentStore(ctx).get(id)?.let { ok(it.render()) } ?: fail("Item '$id' nahi mila.")
                "approve" -> ContentEngine.approve(ctx, id)?.let { ok("'${it.title}' Approved. Ab schedule ya publish_content kar sakte ho.") } ?: fail("Item '$id' nahi mila.")
                "schedule" -> {
                    val at = DateTimeParse.parseDateTime(a.s("datetime", "when", "time")) ?: return@reg fail("Kab schedule karna hai? datetime do, jaise 'kal 10 baje'.")
                    val item = ContentStore(ctx).get(id) ?: return@reg fail("Item '$id' nahi mila.")
                    if (item.status == ContentItem.STATUS_DRAFT) return@reg fail("'${item.title}' abhi Draft hai - pehle owner approve kare. ${BusinessRuntime.SUGGESTION_NOTE}")
                    val s = ContentEngine.schedule(ctx, item.id, at)!!
                    ok("'${s.title}' ${DateTimeParse.formatDateTime(at)} ke liye schedule. Us waqt main post TAIYAAR karke notify karungi - khud publish nahi karungi, post ka button aapko dabana hoga.")
                }
                "unschedule" -> ContentEngine.unschedule(ctx, id)?.let { ok("'${it.title}' ka schedule hata diya (Approved me wapas).") } ?: fail("Item '$id' nahi mila.")
                "delete" -> if (ContentStore(ctx).get(id)?.let { PostReminderReceiver.disarm(ctx, it); ContentStore(ctx).delete(it.id) } == true) ok("Item delete kar diya.") else fail("Item '$id' nahi mila.")
                "published", "posted" -> ContentEngine.markPublished(ctx, id)?.let { ok("'${it.title}' Published mark kar diya.") } ?: fail("Item '$id' nahi mila.")
                else -> fail("action samajh nahi aaya - show, read, approve, schedule, unschedule, delete ya published.")
            }
        }

        reg("schedule_post",
            "Schedule an APPROVED content item (or a picture + caption) to be posted later. At that time the assistant PREPARES it and notifies the user - it does " +
                "not publish unattended, because the phone is usually locked and a public post cannot be undone. Say that plainly when confirming.",
            props("id" to str("Content item id; empty = create one from caption"), "datetime" to str("When"), "caption" to str("Caption if no id"),
                "platform" to str("Platform if no id"), "image" to str("Optional image path")), listOf("datetime")) { ctx, a ->
            val at = DateTimeParse.parseDateTime(a.s("datetime", "when")) ?: return@reg fail("Time samajh nahi aaya - jaise 'kal subah 9 baje'.")
            var id = a.s("id", "item")
            if (id.isBlank()) {
                val caption = a.s("caption")
                if (caption.isBlank()) return@reg fail("Kaunsa post? id do ya caption.")
                val item = ContentItem(kind = if (a.s("image").isNotBlank()) "post" else "post", platform = a.s("platform").ifBlank { "instagram" },
                    title = caption.take(40), caption = caption, status = ContentItem.STATUS_APPROVED, body = a.s("image"))
                ContentStore(ctx).add(listOf(item)); id = item.id
            }
            val item = ContentStore(ctx).get(id) ?: return@reg fail("Item '$id' nahi mila.")
            if (item.status == ContentItem.STATUS_DRAFT) return@reg fail("'${item.title}' Draft hai - pehle approve karwao.")
            val s = ContentEngine.schedule(ctx, item.id, at)!!
            ok("'${s.title}' ${DateTimeParse.formatDateTime(at)} pe ready ho jayega - us waqt notify karungi aur caption copy kar dungi. Main khud post NAHI karungi.",
                JSONObject().put("id", s.id).put("at", at))
        }

        reg("cancel_scheduled_post", "Cancel a scheduled post. Omit 'which' to cancel the next one.",
            props("which" to str("Item id or title")), emptyList()) { ctx, a ->
            val which = a.s("which", "id")
            val target = if (which.isBlank()) ContentStore(ctx).scheduled().firstOrNull() else ContentStore(ctx).get(which)
            target ?: return@reg fail("Koi scheduled post nahi mila.")
            ContentEngine.unschedule(ctx, target.id)
            ok("'${target.title}' (${DateTimeParse.formatDateTime(target.scheduledAt)}) ka schedule cancel kar diya.")
        }

        reg("publish_content",
            "PUBLISH one APPROVED content item from the content calendar, now, with the user present. The caption is copied to the clipboard and the platform app " +
                "(Instagram/Facebook/LinkedIn/X/WhatsApp/YouTube) opens with it via the share screen; the user presses post. Report exactly that: never say 'post kar diya' for a hand-off.",
            props("id" to str("Content item id or title"), "image" to str("Optional image/video path to attach")), listOf("id")) { ctx, a ->
            val (success, msg) = ContentEngine.publish(ctx, a.s("id", "item"), a.s("image"))
            if (success) ok(msg) else fail(msg)
        }

        reg("write_video_script",
            "Write a ready-to-speak VIDEO script (HOOK + SCENE lines 'time | what we see | on-screen text | voiceover' + CAPTION + HASHTAGS + CTA) and save it to " +
                "the Content tab as a draft. BEFORE calling ask the user: (1) kitne second/minute ki video, (2) kiske liye / kaisa vibe.",
            props("topic" to str("Video topic"), "length_seconds" to num("Length in seconds"), "platform" to str("instagram | youtube | facebook ..."),
                "audience" to str("Who it is for / vibe")), listOf("topic")) { ctx, a ->
            val len = a.i("length_seconds", 0).takeIf { it > 0 } ?: (a.i("minutes", 0) * 60).takeIf { it > 0 }
            if (len == null || a.s("audience", "vibe").isBlank() && !a.has("length_seconds")) {
                return@reg ok("ASK_FIRST — abhi script mat likho. Pehle user se ek hi line me do cheez poochho: (1) video kitne second/minute ki chahiye, (2) kiske liye hai / kaisa vibe. Jawab mil jaye to write_video_script dobara call karna, dono answers ke saath.")
            }
            val item = ContentEngine.writeVideoScript(ctx, a.s("topic"), len, a.s("platform").ifBlank { if (len > 90) "youtube" else "instagram" }, a.s("audience", "vibe"))
                ?: return@reg fail("Script nahi ban paayi - AI model se jawab nahi aaya.")
            ok(item.render() + "\n\nContent tab me Draft me save hai. ${BusinessRuntime.SUGGESTION_NOTE}", JSONObject().put("id", item.id))
        }

        reg("suggest_story_idea", "Suggest today's Instagram / WhatsApp STORY ideas for the business (ideas only, nothing is posted).",
            props("topic" to str("Optional theme"), "count" to num("How many ideas (default 3)")), emptyList()) { ctx, a ->
            val out = ContentEngine.suggestStoryIdeas(ctx, a.s("topic"), a.i("count", 3))
            if (out.isBlank()) fail("Abhi idea nahi aaya - dobara try karo.") else ok(out)
        }

        reg("post_story",
            "Make a STORY and hand it to Instagram / Facebook / WhatsApp status: renders a text-quote poster on the phone (or uses an existing image) and opens " +
                "the app's story screen. RULE: tell the user the text first and call with confirmed=true only after a clear 'haan'. The user presses share.",
            props("text" to str("Story text / quote"), "platform" to str("instagram | facebook | whatsapp"), "image" to str("Optional existing image path"),
                "confirmed" to bool("true only after the user said yes to the text")), listOf("text")) { ctx, a ->
            val text = a.s("text", "caption")
            val platform = a.s("platform").ifBlank { "instagram" }
            if (!a.b("confirmed")) return@reg ok("CONFIRM_FIRST - Share dabane se PEHLE user ko batao story pe ye jayega: \"$text\" ($platform). Saaf 'haan' mile to post_story dobara confirmed=true ke saath.")
            val path = a.s("image").ifBlank { SocialMedia.renderStoryPoster(ctx, text, BusinessProfileStore(ctx).load().name) }
            SocialShare.copyToClipboard(ctx, "Story", text)
            val opened = SocialShare.shareImage(ctx, platform, path, text, story = true)
            if (opened.startsWith("FAILED")) fail("Story app nahi khul paaya: $opened. Poster yahan save hai: $path")
            else ok("HANDED_OFF - $platform ka story screen poster ke saath khul gaya, text clipboard me hai. Share USER ko dabana hai - maine post NAHI kiya.", JSONObject().put("path", path))
        }

        reg("post_photo_to_social",
            "Post an EXISTING PHOTO to Instagram, Facebook, LinkedIn, X and/or WhatsApp - story or feed - by handing it to the app's own share screen. " +
                "Empty image = the newest photo. Platforms go ONE AFTER ANOTHER (call again for the next). Tell the user the caption first; confirmed=true only after 'haan'. Never claim it was posted.",
            props("image" to str("Path / file name; empty = newest photo"), "platforms" to str("Comma list"), "caption" to str("Caption"),
                "as_story" to bool("Post as a story"), "confirmed" to bool("User approved the caption")), emptyList()) { ctx, a ->
            val path = SocialMedia.findMedia(ctx, a.s("image", "path"), video = false) ?: return@reg fail("Photo nahi mili - path ya naam do.")
            val platforms = a.list("platforms").ifEmpty { a.list("platform") }.ifEmpty { listOf("instagram") }
            val caption = a.s("caption")
            if (!a.b("confirmed")) return@reg ok("CONFIRM_FIRST - Share dabane se PEHLE user ko batao: photo $path, ${platforms.joinToString()} pe, caption: \"$caption\". 'haan' ke baad confirmed=true ke saath dobara bulao.")
            val p = platforms.first()
            SocialShare.copyToClipboard(ctx, "Caption", caption)
            val opened = SocialShare.shareImage(ctx, p, path, caption, story = a.b("as_story"))
            if (opened.startsWith("FAILED")) return@reg fail("App nahi khula: $opened")
            val rest = platforms.drop(1)
            ok("HANDED_OFF - $p photo ke saath khul gaya, caption clipboard me copy hai. Post ka button USER ko dabana hai - maine post NAHI kiya." +
                if (rest.isNotEmpty()) " Iske baad ${rest.joinToString()} ke liye post_photo_to_social(platforms='${rest.joinToString(",")}', image='$path', confirmed=true) bulao." else "")
        }

        reg("post_to_social",
            "Hand a VIDEO (reel) to Instagram / Facebook / YouTube's own share screen: empty video = newest video; a file name or path also works. The caption is " +
                "copied; the user must CONFIRM it before anything is posted and presses post themselves. confirmed=true only after the user said yes to the caption.",
            props("video" to str("Path / file name; empty = newest"), "platforms" to str("instagram, facebook, youtube"), "caption" to str("Caption"),
                "confirmed" to bool("User approved the caption")), emptyList()) { ctx, a ->
            val path = SocialMedia.findMedia(ctx, a.s("video", "path"), video = true) ?: return@reg fail("Video nahi mili - path ya naam do.")
            val platforms = a.list("platforms").ifEmpty { a.list("platform") }.ifEmpty { listOf("instagram") }
            val caption = a.s("caption")
            if (!a.b("confirmed")) return@reg ok("CONFIRM_FIRST - Share dabane se PEHLE user ko caption aur kya post hoga wo batao: video $path, ${platforms.joinToString()}, caption \"$caption\". Saaf 'haan' ke baad confirmed=true.")
            SocialShare.copyToClipboard(ctx, "Caption", caption)
            val opened = SocialShare.shareImage(ctx, platforms.first(), path, caption)
            if (opened.startsWith("FAILED")) fail("App nahi khula: $opened")
            else ok("HANDED_OFF - ${platforms.first()} video ke saath khul gaya, caption clipboard me hai. Post ka button USER ko dabana hai - maine post NAHI kiya. Ho jaaye to bolo 'ye post ho gaya'.")
        }
    }

    // ================================================================= marketing boards
    private fun installMarketing() {
        val planProps = props("goal" to str("Goal: leads, sales, followers, a festival campaign..."), "budget" to str("Budget (e.g. '5000/month')"),
            "platform" to str("Meta / Google / Instagram ..."), "days" to num("Plan length in days. Default 30"),
            "kind" to str("marketing | ads | landing_page", listOf("marketing", "ads", "landing_page")))

        val planHandler: suspend (Context, JSONObject, String) -> ToolOutcome = { ctx, a, kind ->
            val goal = a.s("goal", "topic", "objective")
            if (goal.isBlank()) fail("Plan kis goal ke liye chahiye - leads, sales, followers? Wo batao.")
            else {
                val rec = MarketingEngine.startPlan(ctx, goal, a.s("budget"), a.s("platform"), a.s("kind").ifBlank { kind }, a.i("days", 30))
                if (rec == null) fail(busyMsg("marketing", ctx))
                else ok("Plan bana rahi hoon — ~1 minute, background me. Ready hote hi bataungi; Business > Marketing board pe dikhega. Ye suggestion + andaaza hoga, measured data nahi.",
                    JSONObject().put("id", rec.id))
            }
        }
        reg("marketing_plan",
            "Write a MARKETING / CAMPAIGN PLAN for the active business: audience, offer, channels, campaign structure, week-by-week actions, creatives needed, " +
                "tracking checklist. kind='ads' = Meta/Google ADS plan; kind='landing_page' = landing page brief. Runs in the background ~1 minute, then shows on " +
                "the Marketing board. It is a SUGGESTION with estimates, not measured data - say so.", planProps, listOf("goal")) { c, a -> planHandler(c, a, "marketing") }
        reg("campaign_plan", "Plan one CAMPAIGN (festival, launch, offer) for the business - same board as marketing_plan. Background ~1 min; suggestion with estimates.",
            planProps, listOf("goal")) { c, a -> planHandler(c, a, "marketing") }
        reg("ads_plan",
            "Meta / Google ADS plan: audience, campaign structure, creative angles, ad copy variants (Headline | Primary text | CTA), creative brief, landing page, " +
                "budget split, tracking, stop/scale rules. The assistant never creates or pays for ads - the owner does in Ads Manager. Numbers are andaaza.",
            planProps, listOf("goal")) { c, a -> planHandler(c, a, "ads") }

        reg("marketing_status", "Check the running marketing / plan / competitor / keyword / trend job, or read back the last board (optionally by id).",
            props("id" to str("Optional board id")), emptyList()) { ctx, a -> ok(BusinessJobs.statusText(ctx, "marketing", a.s("id"))) }
        reg("cancel_marketing", "Stop the running marketing job.", props(), emptyList()) { _, _ ->
            if (BusinessJobs.cancel("marketing")) ok("Marketing ka kaam rok diya.") else ok("Abhi koi marketing kaam nahi chal raha.")
        }

        fun research(name: String, desc: String, argName: String, start: (Context, String, JSONObject) -> BoardRecord?) =
            reg(name, desc, props(argName to str("What to research"), "site" to str("Optional website for SEO audit"), "location" to str("Optional city")), listOf(argName)) { ctx, a ->
                val q = a.s(argName, "query", "topic")
                if (q.isBlank()) return@reg fail("Kis cheez pe? '$argName' do.")
                val rec = start(ctx, q, a) ?: return@reg fail(busyMsg("marketing", ctx))
                ok("Shuru kar diya — '${rec.title}', ~1 minute background me. Ready hote hi bataungi. Result andaaza hoga, search results se.", JSONObject().put("id", rec.id))
            }
        research("competitor_research", "Research a COMPETITOR from the web: offer, stated prices, positioning, what customers say, social presence and gaps we can use. Background ~1 min. Only what their pages say - no guessed prices/reviews.", "competitor") { c, q, _ -> MarketingEngine.startCompetitorResearch(c, q) }
        research("keyword_research", "Find the REAL search queries people type around a service in a city (autocomplete), group by intent, suggest primary/supporting keywords, a page outline and local FAQs. NO search-volume data - never quote a volume. Background ~1 min.", "topic") { c, q, _ -> MarketingEngine.startKeywordResearch(c, q) }
        research("trend_research", "What is trending in a NICHE right now and content angles/ideas for our business. Background ~1 min; ideas are andaaza.", "niche") { c, q, _ -> MarketingEngine.startTrendResearch(c, q) }
        research("seo_research", "SEO research for a topic (who ranks, content gaps, page outline) plus an optional PageSpeed audit of our site. Background ~1 min, no ranking promises.", "topic") { c, q, a -> MarketingEngine.startSeoResearch(c, q, a.s("site", "url")) }

        reg("deep_research",
            "Research a topic PROPERLY: split it into sub-questions, search each one and write a sourced report on the board. Takes a few minutes and runs in the " +
                "background — tell the user it's started and carry on; you'll be told when it's done. For a quick fact use search_web instead.",
            props("question" to str("The research question")), listOf("question")) { ctx, a ->
            val q = a.s("question", "query", "topic")
            if (q.isBlank()) return@reg fail("Kya research karna hai?")
            val rec = MarketingEngine.startDeepResearch(ctx, q) ?: return@reg fail(busyMsg("research", ctx))
            ok("Research shuru kar di — 3-8 minute lagenge, background me chalti rahegi. Tum baat karte raho, done hote hi main khud bata dungi. Beech me research_status se poochh sakti ho.",
                JSONObject().put("id", rec.id))
        }
        reg("research_status", "Check how the running research is going, or read back the last report. Use when the user asks 'ho gaya?' / 'kahan tak pahuncha?'.",
            props("id" to str("Optional report id")), emptyList()) { ctx, a -> ok(BusinessJobs.statusText(ctx, "research", a.s("id"))) }
        reg("cancel_research", "Stop the running deep research.", props(), emptyList()) { _, _ ->
            if (BusinessJobs.cancel("research")) ok("Research rok di.") else ok("Abhi koi research nahi chal rahi.")
        }

        reg("review_themes",
            "Summarise many customer reviews into recurring praise and complaints, quick fixes and reply angles (saved to the Marketing board). Reviews mentioning " +
                "injury, legal threats, fraud, harassment or privacy are flagged for the owner. For ONE reply use draft_review_reply.",
            props("reviews_text" to str("The reviews, pasted")), listOf("reviews_text")) { ctx, a ->
            val text = a.s("reviews_text", "reviews", "text")
            if (text.length < 20) return@reg fail("Reviews paste karo - kam se kam 2-3.")
            val rec = MarketingEngine.reviewThemes(ctx, text)
            if (rec.status == "done") ok(rec.text, JSONObject().put("id", rec.id)) else fail("Summary nahi ban paayi - dobara try karo.")
        }

        reg("review_request",
            "Draft a polite REVIEW REQUEST message for a happy customer with the business's review link, and (with confirmed=true) open WhatsApp/SMS with it " +
                "prefilled - the user presses send.",
            props("customer" to str("Customer / lead name or phone"), "channel" to str("whatsapp | sms", listOf("whatsapp", "sms")),
                "phone" to str("Phone if not a saved lead"), "confirmed" to bool("Open the chat now")), listOf("customer")) { ctx, a ->
            val profile = BusinessProfileStore(ctx).load()
            val lead = findLead(ctx, a.s("customer"))
            val name = lead?.name ?: a.s("customer")
            val phone = a.s("phone").ifBlank { lead?.phone.orEmpty() }
            val link = profile.reviewLink.ifBlank { "{review_link}" }
            val msg = "Namaste $name ji, ${profile.name.ifBlank { "hamare" }} ko chunne ke liye shukriya! Agar aapko hamari service achhi lagi ho to " +
                "2 minute nikaal ke ek review de dijiye, isse hume bahut madad milti hai: $link"
            if (link == "{review_link}") return@reg ok("Draft:\n$msg\n\nReview link profile me nahi hai - business_profile(action='update', review_link=...) se daalo, {review_link} aise mat bhejna.")
            if (!a.b("confirmed")) return@reg ok("Draft:\n$msg\n\n${BusinessPrompts.LEAD_MESSAGE_HINT}")
            if (phone.isBlank()) return@reg fail("$name ka number nahi mila - phone do.")
            val opened = if (a.s("channel").equals("sms", true)) {
                val i = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$phone")).putExtra("sms_body", msg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ActivityLauncherHelper.startActivitySafely(ctx, i)
            } else SocialShare.openWhatsAppChat(ctx, phone, msg)
            if (opened) ok("Chat khul gaya, message likha hua hai - send USER ko dabana hai.") else fail("Chat nahi khul paaya.")
        }
    }

    // ================================================================= CRM, KPI, reports
    private fun installCrmAndReports() {
        reg("lead_generation",
            "Find prospective LEADS matching criteria in a location from public web search; those with a phone number are saved to Leads as NEW with their source. " +
                "Background ~1-2 min. Nobody is contacted.",
            props("criteria" to str("Who to find, e.g. 'gyms', 'new restaurants'"), "location" to str("City / area")), listOf("criteria")) { ctx, a ->
            val c = a.s("criteria", "query")
            if (c.isBlank()) return@reg fail("Kaise leads chahiye?")
            val rec = MarketingEngine.startLeadGeneration(ctx, c, a.s("location", "city")) ?: return@reg fail(busyMsg("marketing", ctx))
            ok("Lead search shuru — background me, ready hote hi bataungi. Mile hue leads 'NEW' status me save honge.", JSONObject().put("id", rec.id))
        }

        reg("send_lead_message",
            "Message a saved LEAD on WhatsApp. Without 'message' it DRAFTS one for the user to hear first. With 'message' it OPENS the WhatsApp chat with the text " +
                "prefilled and the user presses send. Only confirmed=true (user explicitly said send it) lets the app press send.",
            props("lead" to str("Lead name, phone or id"), "message" to str("Exact text to send"), "purpose" to str("What the message is about, for drafting"),
                "confirmed" to bool("User explicitly asked to send without review")), listOf("lead"), risky = true) { ctx, a ->
            val lead = findLead(ctx, a.s("lead", "name", "phone")) ?: return@reg fail("Lead '${a.s("lead")}' nahi mila. manage_leads se dekho.")
            var message = a.s("message")
            if (message.isBlank()) {
                val profile = BusinessProfileStore(ctx).load()
                message = BusinessRuntime.llm(ctx, BusinessPrompts.SYSTEM_MARKETER,
                    profile.promptBlock() + "\n\nWrite ONE short, polite WhatsApp message (max 3 lines, ${profile.language}) to lead '${lead.name}'" +
                        (lead.company?.let { " from $it" } ?: "") + ". Stage: ${lead.status}. Notes: ${lead.notes.take(300)}. Purpose: ${a.s("purpose").ifBlank { "follow up" }}. " +
                        "Only facts from the profile. Output only the message.", 0.6, 250)
                if (message.isBlank()) return@reg fail("Draft nahi ban paaya - message khud bata do.")
                return@reg ok("Draft for ${lead.name}:\n$message\n\n${BusinessPrompts.LEAD_MESSAGE_HINT}", JSONObject().put("draft", message))
            }
            val opened = SocialShare.openWhatsAppChat(ctx, lead.phone, message)
            if (!opened) return@reg fail("WhatsApp nahi khul paaya.")
            LeadCrmManager.getInstance(ctx).updateLeadStatus(lead.id, if (lead.status == "NEW") "CONTACTED" else lead.status, "WhatsApp: ${message.take(80)}")
            if (a.b("confirmed")) {
                JarvisAccessibilityService.instance?.scheduleWhatsAppAutoSend()
                ok("${lead.name} ka chat khola aur send dabane ki koshish ki (confirmed). Delivery check WhatsApp me karo.")
            } else ok("${lead.name} ka WhatsApp chat khul gaya, message likha hua hai - send wo khud dabayenge. Maine bheja NAHI.")
        }

        reg("quotation_follow_up",
            "Draft a QUOTATION FOLLOW-UP for a lead who got a quote (amount/service optional), log it on the lead, and optionally set a reminder. Reads back the " +
                "draft; send it with send_lead_message.",
            props("lead" to str("Lead name, phone or id"), "amount" to str("Quoted amount"), "service" to str("Quoted service"),
                "remind_in_days" to num("Optional: remind again after N days")), listOf("lead")) { ctx, a ->
            val lead = findLead(ctx, a.s("lead")) ?: return@reg fail("Lead '${a.s("lead")}' nahi mila.")
            val profile = BusinessProfileStore(ctx).load()
            val draft = BusinessRuntime.llm(ctx, BusinessPrompts.SYSTEM_MARKETER,
                profile.promptBlock() + "\n\nWrite a short, warm quotation follow-up WhatsApp message (max 4 lines, ${profile.language}) to ${lead.name}. " +
                    "Service: ${a.s("service").ifBlank { "as quoted" }}. Amount: ${a.s("amount").ifBlank { "as quoted" }}. Ask if they have questions and offer a call. " +
                    "No pressure, no fake urgency, no new discounts. Output only the message.", 0.5, 250)
                .ifBlank { "Namaste ${lead.name} ji, humne jo quotation bheja tha${a.s("service").let { if (it.isBlank()) "" else " ($it)" }} uske baare me koi sawaal ho to batayiye - call pe samjha denge. ${profile.name}" }
            LeadCrmManager.getInstance(ctx).updateLeadStatus(lead.id, lead.status, "Quotation follow-up drafted ${a.s("amount")}")
            val days = a.i("remind_in_days", 0)
            if (days > 0) LeadFollowupScheduler.getInstance(ctx).scheduleFollowup(lead.phone, lead.name, "Quotation follow-up", days * 24 * 60)
            ok("Draft:\n$draft\n\n${BusinessPrompts.LEAD_MESSAGE_HINT}" + if (days > 0) "\n($days din baad yaad dila dungi.)" else "", JSONObject().put("draft", draft))
        }

        reg("set_drip",
            "Set a follow-up DRIP for a lead: reminders on the given days (e.g. '0, 2, 5'). Each reminder notifies the OWNER to follow up - nothing is sent to the lead automatically. days='cancel' removes it.",
            props("lead" to str("Lead name, phone or id"), "days" to str("Sequence like '0, 2, 5', or 'cancel'"), "message" to str("Reminder note")), listOf("lead", "days")) { ctx, a ->
            val lead = findLead(ctx, a.s("lead")) ?: return@reg fail("Lead '${a.s("lead")}' nahi mila.")
            val sched = LeadFollowupScheduler.getInstance(ctx)
            if (a.s("days").equals("cancel", true)) return@reg ok("${lead.name} ka drip band: ${sched.cancelDrip(lead.phone)} reminders hataye.")
            val days = a.list("days").mapNotNull { it.toIntOrNull() }.filter { it in 0..90 }
            if (days.isEmpty()) return@reg fail("Days samajh nahi aaye - jaise '0, 2, 5'.")
            val n = sched.scheduleDrip(lead.phone, lead.name, days, a.s("message"))
            ok("${lead.name} ke liye drip set: din ${days.joinToString(", ")} ($n reminders). Har reminder pe aapko yaad dilaungi - lead ko khud kuch nahi jayega.")
        }

        reg("kpi_log", "Log a business NUMBER (leads, spend, sales, revenue, followers, calls...) for a date. Several at once: values='leads=12; spend=2000'.",
            props("metric" to str("Metric name"), "value" to num("Value"), "date" to str("Date (default today)"), "values" to str("Several 'metric=value' pairs"),
                "note" to str("Optional note")), emptyList()) { ctx, a ->
            val date = DateTimeParse.parseDate(a.s("date"))
            val pairs = ArrayList<Pair<String, Double>>()
            a.s("metric").takeIf { it.isNotBlank() }?.let { m -> a.d("value")?.let { pairs.add(m to it) } }
            BusinessProfileStore(ctx).parseFieldString(a.s("values")).forEach { (k, v) -> v.replace(",", "").toDoubleOrNull()?.let { pairs.add(k to it) } }
            if (pairs.isEmpty()) return@reg fail("Kaunsa number? metric + value do, jaise leads=12.")
            val saved = pairs.map { (m, v) -> KpiStore.log(ctx, m, v, date, a.s("note")) }
            ok("Note kar liya ($date): " + saved.joinToString { "${it.metric}=${KpiStore.fmt(it.value)}" })
        }

        reg("kpi_summary", "Summarise logged KPIs for a period (today | week | month | all | N days) with cost per lead, conversion and ROAS when possible.",
            props("period" to str("today | week | month | all | number of days")), emptyList()) { ctx, a -> ok(KpiStore.summary(ctx, a.s("period").ifBlank { "week" })) }

        reg("weekly_summary", "This week's business summary: KPIs, new leads by stage, content made/published, marketing boards and missions. Saved to the Marketing board.",
            props(), emptyList()) { ctx, _ ->
            val text = weeklySummary(ctx)
            BoardStore.save(ctx, BoardRecord(kind = "weekly_summary", title = "Weekly summary", text = text, status = "done", finishedAt = System.currentTimeMillis()))
            ok(text)
        }

        reg("save_report_format", "Save a REPORT FORMAT with {placeholders} (e.g. 'Daily Report {date}\\nShift: {shift}\\nProduction: {production} MT'). {date} fills itself.",
            props("name" to str("Format name"), "template" to str("Template text with {placeholders}")), listOf("name", "template")) { ctx, a ->
            ReportFormats.save(ctx, a.s("name"), a.optString("template"))
            ok("Report format '${a.s("name")}' save ho gaya. Placeholders: ${ReportFormats.missing(a.optString("template")).joinToString().ifBlank { "none" }}")
        }
        reg("delete_report_format", "Delete a saved report format. Only when the user asks.", props("name" to str("Format name")), listOf("name")) { ctx, a ->
            if (ReportFormats.delete(ctx, a.s("name"))) ok("'${a.s("name")}' format delete kar diya.") else fail("'${a.s("name")}' naam ka format nahi mila.")
        }
        reg("list_report_formats", "List saved report formats and their placeholders.", props(), emptyList()) { ctx, _ ->
            val all = ReportFormats.all(ctx)
            if (all.isEmpty()) ok("Koi report format save nahi hai. User format bol de to main save_report_format se save kar dun.")
            else ok(all.joinToString("\n") { "- ${it.first}: ${ReportFormats.missing(it.second).joinToString().ifBlank { "no fields" }}" })
        }

        reg("send_report",
            "Compose a business REPORT (a saved format filled with values, or the KPI/weekly summary for a period) and hand it to WhatsApp, email or Telegram. " +
                "First call returns the text - read it to the user and ask 'bhej dun?'; after a clear 'haan' call again with the same values and confirmed=true. " +
                "The chat / email opens prefilled and the USER presses send.",
            props("channel" to str("whatsapp | email | telegram", listOf("whatsapp", "email", "telegram")), "period" to str("today | week | month"),
                "format" to str("Saved format name (optional)"), "values" to str("key=value per line for the format"), "to" to str("Phone / email (optional)"),
                "confirmed" to bool("User said yes to sending")), listOf("channel")) { ctx, a ->
            val formatName = a.s("format")
            val rawText: String = if (formatName.isNotBlank()) {
                val tpl = ReportFormats.find(ctx, formatName) ?: return@reg fail("'$formatName' format nahi mila. list_report_formats dekho.")
                val filled = ReportFormats.fill(tpl, BusinessProfileStore(ctx).parseFieldString(a.s("values")))
                val missing = ReportFormats.missing(filled)
                if (missing.isNotEmpty()) return@reg ok("Ye values chahiye: ${missing.joinToString()}. User se ek-ek karke natural tarike se poochho, phir `send_report` dobara call karo values me `key=value` har line pe. Date/time apne aap bhar jaata hai.")
                filled
            } else {
                val p = a.s("period").ifBlank { "week" }
                if (p.startsWith("week")) weeklySummary(ctx) else KpiStore.summary(ctx, p)
            }
            val text = plain(rawText)
            if (!a.b("confirmed")) return@reg ok("$text\n\nYe user ko sunao aur poochho bhej dun? Saaf 'haan' ke baad `send_report` dobara call karo — same values ke saath aur confirmed=true.")
            val to = a.s("to")
            val done = when (a.s("channel").lowercase()) {
                "email", "mail" -> SocialShare.openEmail(ctx, to, "Report - ${DateTimeParse.formatDate(System.currentTimeMillis())}", text)
                "telegram" -> !SocialShare.shareText(ctx, "telegram", text).startsWith("FAILED")
                else -> if (to.filter { it.isDigit() }.length >= 8) SocialShare.openWhatsAppChat(ctx, to, text) else !SocialShare.shareText(ctx, "whatsapp", text).startsWith("FAILED")
            }
            if (done) ok("Report ${a.s("channel")} me likh ke khol diya - send USER ko dabana hai.") else fail("${a.s("channel")} nahi khul paaya.")
        }
    }

    private fun plain(md: String): String = md.lines().joinToString("\n") { l ->
        when {
            l.startsWith("## ") -> l.removePrefix("## ").uppercase(Locale.getDefault())
            l.startsWith("# ") -> "*" + l.removePrefix("# ") + "*"
            l.trim() == "---" -> ""
            else -> l
        }
    }.trim()

    fun weeklySummary(ctx: Context): String {
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        val leads = LeadCrmManager.getInstance(ctx).listLeads(null, 5000)
        val newLeads = leads.filter { it.createdAt >= weekAgo }
        val byStatus = leads.filter { it.updatedAt >= weekAgo }.groupBy { it.status }.mapValues { it.value.size }
        val content = ContentStore(ctx).all()
        val made = content.count { it.createdAt >= weekAgo }
        val published = content.count { it.publishedAt >= weekAgo }
        val upcoming = content.count { it.scheduledAt > System.currentTimeMillis() }
        val boards = BoardStore.all(ctx).count { it.createdAt >= weekAgo && it.status == "done" }
        val missions = MissionRunner.all(ctx).count { it.createdAt >= weekAgo }
        val kpi = KpiStore.summary(ctx, "week").lines().filter { it.startsWith("- ") }
        val sb = StringBuilder("# Weekly summary (${DateTimeParse.formatDate(weekAgo)} - ${DateTimeParse.formatDate(System.currentTimeMillis())})\n")
        sb.append("## Leads\n- Naye leads: ").append(newLeads.size).append('\n')
        byStatus.forEach { (s, n) -> sb.append("- ").append(s).append(": ").append(n).append('\n') }
        sb.append("## Content\n- Likhe gaye: ").append(made).append("\n- Published: ").append(published).append("\n- Scheduled aage: ").append(upcoming).append('\n')
        sb.append("## KPI\n")
        if (kpi.isEmpty()) sb.append("- Is hafte koi number log nahi hua\n") else kpi.forEach { sb.append(it).append('\n') }
        sb.append("## Kaam\n- Marketing/research boards: ").append(boards).append("\n- Missions: ").append(missions).append('\n')
        sb.append("---\n## Note\n- Sirf app me save data se bana hai - measured tracking nahi.")
        return sb.toString()
    }

    // ================================================================= missions
    private fun installMissions() {
        reg("run_mission",
            "Hand a job that needs a SEQUENCE of 3+ tools (check X, then send Y, then do Z…) to the background mission agent. It plans the steps, runs the real " +
                "tools one by one reading each result, and reports back when done (max 25 steps / 10 min). Not for screen tap/type automation and not for 1-2 tool " +
                "jobs. NEVER call this twice for the same task - use mission_status instead.",
            props("goal" to str("The whole job in one clear sentence")), listOf("goal")) { ctx, a ->
            val goal = a.s("goal", "task")
            if (goal.isBlank()) return@reg fail("Mission ka goal batao.")
            val m = MissionRunner.start(ctx, goal)
            ok("Mission shuru kar diya — plan bana ke ek-ek karke tools chala rahi hoon, done hote hi batati hoon. Beech me progress chahiye to mission_status se poochh lena.",
                JSONObject().put("id", m.id))
        }
        reg("mission_status", "Progress / result of a mission (latest if no id), with its step log.", props("id" to str("Mission id")), emptyList()) { ctx, a ->
            val id = a.s("id")
            val m = if (id.isNotBlank()) MissionRunner.get(ctx, id) else MissionRunner.all(ctx).firstOrNull { it.kind == "mission" }
            m ?: return@reg ok("Koi mission nahi mila.")
            ok(m.log() + if (MissionRunner.isRunning(m.id)) "\n(abhi chal raha hai)" else "")
        }
        reg("cancel_mission", "Stop a running mission (latest if no id).", props("id" to str("Mission id")), emptyList()) { ctx, a ->
            val m = MissionRunner.cancel(ctx, a.s("id"))
            if (m != null) ok("Mission '${m.goal.take(60)}' rok diya.") else ok("Abhi koi mission nahi chal raha.")
        }
        reg("delegate_task", "Record a task delegated to a role (marketing, sales, accounts, team member...) as a decision entry on the Missions board. Nothing is executed.",
            props("role" to str("Who / which role"), "task" to str("The task")), listOf("role", "task")) { ctx, a ->
            val m = MissionRunner.delegate(ctx, a.s("role"), a.s("task"))
            ok("Note kar liya: '${a.s("task").take(80)}' -> ${a.s("role")}. Missions board pe dikhega (id ${m.id}).")
        }
        reg("open_business_board", "Open the Business screen (Leads, Content, Calendar, Marketing, Finance, Missions tabs).",
            props("tab" to str("leads | content | calendar | marketing | finance | missions")), emptyList()) { ctx, a ->
            val i = Intent(ctx, BusinessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(BusinessActivity.EXTRA_TAB, a.s("tab"))
            if (ActivityLauncherHelper.startActivitySafely(ctx, i)) ok("Business screen khol diya.") else fail("Screen nahi khul paayi.")
        }
    }

    // ================================================================= finance
    private fun installFinance() {
        val sym = props("symbol" to str("Company name or ticker (e.g. 'Tata Motors', 'TCS.NS', 'AAPL')"))

        reg("search_stock", "Find a stock's ticker symbol by company name (Indian NSE/BSE and global).", props("query" to str("Company name")), listOf("query"), FIN) { ctx, a ->
            val res = FinanceEngine.client(ctx).searchSymbols(a.s("query", "symbol", "name"), 8)
            if (res.isEmpty()) fail("Koi symbol nahi mila.") else ok(res.joinToString("\n") { "- ${it.symbol}: ${it.name} (${it.exchange} ${it.type})" })
        }
        reg("get_stock_quote", "Live price for a stock: price, day change %, day range, 52-week high/low, volume, market open or not. Accepts a NAME or a symbol (prefers NSE).",
            sym, listOf("symbol"), FIN) { ctx, a ->
            val s = FinanceEngine.resolve(ctx, a.s("symbol", "name"))
            val q = FinanceEngine.client(ctx).getQuoteDetail(s)
                ?: return@reg fail("'${a.s("symbol")}' ka data nahi mila. Naam thoda alag likh ke ya `search_stock` se sahi symbol nikaal ke dobara try karo.")
            ok(FinanceEngine.describeQuote(q), JSONObject().put("symbol", q.symbol).put("price", q.price).put("changePercent", q.changePercent))
        }
        reg("get_stock_history", "Past closing prices for a stock over a range (1mo|3mo|6mo|1y|5y): sampled closes plus the change across it.",
            props("symbol" to str("Name or ticker"), "range" to str("1mo | 3mo | 6mo | 1y | 2y | 5y")), listOf("symbol"), FIN) { ctx, a ->
            val s = FinanceEngine.resolve(ctx, a.s("symbol"))
            val range = a.s("range").ifBlank { "3mo" }
            val bars = FinanceEngine.client(ctx).getHistory(s, range, if (range == "5y" || range == "2y") "1wk" else "1d")
            if (bars.isEmpty()) return@reg fail("$s ki history nahi mili.")
            val step = maxOf(1, bars.size / 12)
            val sampled = bars.filterIndexed { i, _ -> i % step == 0 || i == bars.size - 1 }
            val chg = (bars.last().close - bars.first().close) / bars.first().close * 100
            ok("$s ($range): ${FinanceEngine.fmt(bars.first().close)} -> ${FinanceEngine.fmt(bars.last().close)} (${FinanceEngine.pct(chg)})\n" +
                sampled.joinToString("\n") { "- ${DateTimeParse.formatDate(it.timestamp)}: ${FinanceEngine.fmt(it.close)}" })
        }
        reg("get_stock_indicators", "The technical read for a stock, all computed on this phone: trend, RSI(14), MACD, SMA 20/50/200, Bollinger bands, support/resistance, 52-week range position, 1w/1m/3m changes.",
            sym, listOf("symbol"), FIN) { ctx, a ->
            val s = FinanceEngine.resolve(ctx, a.s("symbol"))
            val snap = FinanceEngine.indicators(ctx, s) ?: return@reg fail("$s ka chart data kam hai / nahi mila.")
            ok("$s: ${snap.describe()}\n(Ye calculations hain, advice nahi.)", snap.toJson())
        }
        reg("analyze_stock", "Analyse ONE stock and write a report: exact chart read, recent news, bull and bear case, levels. Runs in the BACKGROUND (~1 min) - say you've started; you'll be told. Information, not advice.",
            sym, listOf("symbol"), FIN) { ctx, a ->
            val rec = FinanceEngine.startAnalysis(ctx, a.s("symbol")) ?: return@reg fail("Ek market analysis pehle se chal raha hai. " + BusinessJobs.statusText(ctx, "market"))
            ok("Analysis shuru — background me, ready hote hi bataungi. Ye information hogi, advice nahi.", JSONObject().put("id", rec.id))
        }
        reg("market_analysis_status", "Check the running market analysis / watchlist scan / portfolio review, or read back the last report.",
            props("id" to str("Optional id")), emptyList(), FIN) { ctx, a -> ok(BusinessJobs.statusText(ctx, "market", a.s("id"))) }
        reg("compare_stocks", "Compare up to 5 stocks side by side: price, day change, 52-week range position, RSI and trend.",
            props("symbols" to str("Comma list of names/tickers")), listOf("symbols"), FIN) { ctx, a ->
            val list = a.list("symbols").take(5)
            if (list.size < 2) return@reg fail("Compare karne ke liye kam se kam 2 symbol do.")
            val sb = StringBuilder()
            for (raw in list) {
                val s = FinanceEngine.resolve(ctx, raw)
                val q = FinanceEngine.client(ctx).getQuoteDetail(s)
                val snap = FinanceEngine.indicators(ctx, s)
                sb.append("- ").append(s).append(": ").append(q?.let { "${FinanceEngine.fmt(it.price)} (${FinanceEngine.pct(it.changePercent)})" } ?: "n/a")
                    .append(" | 52w pos ").append(snap?.rangePosition?.let { String.format(Locale.US, "%.0f%%", it) } ?: "n/a")
                    .append(" | RSI ").append(snap?.rsi14?.let { String.format(Locale.US, "%.0f", it) } ?: "n/a")
                    .append(" | ").append(snap?.trend ?: "n/a").append(" | 3m ").append(FinanceEngine.pct(snap?.change3m)).append('\n')
            }
            ok(sb.toString().trimEnd() + "\n(Comparison hai, advice nahi.)")
        }
        reg("get_market_news", "Latest market headlines from Yahoo Finance, optionally for one symbol. Summarise them - don't read URLs aloud.",
            props("symbol" to str("Optional name/ticker")), emptyList(), FIN) { ctx, a ->
            val s = a.s("symbol").takeIf { it.isNotBlank() }?.let { FinanceEngine.resolve(ctx, it) }
            val news = FinanceEngine.client(ctx).getNewsHeadlines(s, 10)
            if (news.isEmpty()) fail("Headlines nahi aayi - internet check karo.") else ok(news.joinToString("\n") { "- ${it.title}" + if (it.pubDate.isNotBlank()) " (${it.pubDate.take(16)})" else "" })
        }
        reg("get_market_snapshot", "One-screen market snapshot: NIFTY 50, SENSEX, Nasdaq, Gold and USD/INR with day change.", props(), emptyList(), FIN) { ctx, _ ->
            ok(FinanceEngine.snapshotText(ctx))
        }
        reg("get_trending_stocks", "Today's trending tickers (India) with live price and change.", props(), emptyList(), FIN) { ctx, _ ->
            val syms = FinanceEngine.client(ctx).getTrending().take(10)
            if (syms.isEmpty()) return@reg fail("Trending list nahi aayi.")
            val prices = FinanceEngine.livePrices(ctx, syms)
            ok(syms.joinToString("\n") { "- $it: ${FinanceEngine.fmt(prices[it])}" })
        }
        reg("get_forex_rate", "Currency exchange rate, e.g. USD to INR, EUR to INR, and optional amount conversion.",
            props("from" to str("From currency (USD)"), "to" to str("To currency (INR)"), "amount" to num("Optional amount")), emptyList(), FIN) { ctx, a ->
            val from = a.s("from").ifBlank { "USD" }; val to = a.s("to").ifBlank { "INR" }
            val r = FinanceEngine.client(ctx).getForexRate(from, to) ?: return@reg fail("$from/$to rate nahi mila.")
            val amt = a.d("amount")
            ok("1 ${from.uppercase()} = ${String.format(Locale.US, "%.4f", r)} ${to.uppercase()}" + if (amt != null) " | ${KpiStore.fmt(amt)} ${from.uppercase()} = ${FinanceEngine.fmt(amt * r)} ${to.uppercase()}" else "",
                JSONObject().put("rate", r))
        }
        reg("add_to_watchlist", "Add a stock or crypto to the user's watchlist (Finance tab + scans). Accepts a NAME or a ticker.", sym, listOf("symbol"), FIN) { ctx, a ->
            val s = FinanceEngine.resolve(ctx, a.s("symbol")); FinanceEngine.client(ctx).addToWatchlist(s); ok("$s watchlist me add kar diya.")
        }
        reg("remove_from_watchlist", "Remove a symbol from the watchlist.", sym, listOf("symbol"), FIN) { ctx, a ->
            val c = FinanceEngine.client(ctx)
            val raw = a.s("symbol")
            val s = c.getWatchlist().firstOrNull { it.equals(c.resolveSymbol(raw), true) || it.startsWith(raw.uppercase()) } ?: FinanceEngine.resolve(ctx, raw)
            c.removeFromWatchlist(s); ok("$s watchlist se hata diya.")
        }
        reg("get_watchlist", "The watchlist with live prices and day change (instant).", props(), emptyList(), FIN) { ctx, _ ->
            val c = FinanceEngine.client(ctx)
            val list = c.getWatchlist().toList()
            if (list.isEmpty()) return@reg ok("Watchlist khaali hai.")
            val lines = coroutineScope {
                list.map { s -> async { s to c.getQuote(s) } }.map { it.await() }
            }.joinToString("\n") { (s, q) -> "- $s: " + (q?.let { "${FinanceEngine.fmt(it.price)} (${FinanceEngine.pct(it.changePercent)})" } ?: "n/a") }
            ok(lines)
        }
        reg("scan_watchlist", "Go through the whole watchlist and write up what stands out - who's moving, quiet, near a level, stretched. Background 1-3 min.",
            props(), emptyList(), FIN) { ctx, _ ->
            val rec = FinanceEngine.startWatchlistScan(ctx) ?: return@reg fail("Ek market kaam pehle se chal raha hai.")
            ok("Watchlist scan shuru — poori list ka data le kar batati hoon kis pe nazar rakhni hai. 1-3 minute lagenge, background me.", JSONObject().put("id", rec.id))
        }
        reg("add_holding", "Record a position the user holds: symbol, quantity, average buy price (adds to an existing position at weighted average).",
            props("symbol" to str("Name or ticker"), "quantity" to num("Shares / units"), "price" to num("Average buy price"), "note" to str("Optional note")),
            listOf("symbol", "quantity", "price"), FIN) { ctx, a ->
            val q = a.d("quantity", "qty"); val p = a.d("price", "avg_price")
            if (a.s("symbol").isBlank()) return@reg fail("Kis stock ki holding add karni hai?")
            if (q == null || p == null || q <= 0 || p <= 0) return@reg fail("Holding save nahi hui — quantity aur price check karo.")
            val s = FinanceEngine.resolve(ctx, a.s("symbol"))
            val h = FinanceStore.addHolding(ctx, s, q, p, a.s("note"))
            ok("$s: ab ${KpiStore.fmt(h.quantity)} @ avg ${FinanceEngine.fmt(h.avgPrice)} record hai.")
        }
        reg("remove_holding", "Remove a holding (or reduce it by quantity).", props("symbol" to str("Name or ticker"), "quantity" to num("Optional quantity sold")), listOf("symbol"), FIN) { ctx, a ->
            if (a.s("symbol").isBlank()) return@reg fail("Kis holding ko hatana hai?")
            val raw = a.s("symbol")
            val existing = FinanceStore.holdings(ctx).firstOrNull { it.symbol.equals(raw, true) || it.symbol.startsWith(raw.uppercase()) }?.symbol
                ?: FinanceEngine.resolve(ctx, raw)
            val h = FinanceStore.removeHolding(ctx, existing, a.d("quantity")) ?: return@reg fail("'$raw' ki koi holding record nahi thi.")
            ok("${h.symbol} ki holding ${if (a.d("quantity") != null) "kam kar di" else "hata di"}.")
        }
        reg("get_portfolio", "The user's recorded positions with live P&L (instant).", props(), emptyList(), FIN) { ctx, _ -> ok(FinanceEngine.portfolioText(ctx)) }
        reg("review_portfolio", "Review the recorded positions: overall P&L, who carries it, who drags, concentration risk. Background. Never suggests buying or selling.",
            props(), emptyList(), FIN) { ctx, _ ->
            val rec = FinanceEngine.startPortfolioReview(ctx) ?: return@reg fail("Ek market kaam pehle se chal raha hai.")
            ok("Portfolio review shuru — background me, ready hote hi bataungi.", JSONObject().put("id", rec.id))
        }
        reg("set_price_alert", "Tell the user when a stock/crypto crosses a price: direction above|below. Checked every ~15 minutes in the background; fires once with a notification.",
            props("symbol" to str("Name or ticker"), "direction" to str("above | below", listOf("above", "below")), "price" to num("Target price")),
            listOf("symbol", "price"), FIN) { ctx, a ->
            val price = a.d("price", "target") ?: return@reg fail("Kis price pe alert chahiye?")
            val s = FinanceEngine.resolve(ctx, a.s("symbol"))
            val live = FinanceEngine.client(ctx).getQuote(s)?.price
            val dir = if (a.s("direction").isNotBlank()) PriceAlertLogic.normalizeDirection(a.s("direction"))
            else if (live != null && live > price) "below" else "above"
            val alert = PriceAlert(symbol = s, direction = dir, price = price)
            FinanceStore.saveAlerts(ctx, FinanceStore.alerts(ctx) + alert)
            FinanceEngine.syncAlertWorker(ctx)
            ok("Theek hai - $s ${FinanceEngine.fmt(price)} ke ${if (dir == "above") "upar" else "neeche"} jaate hi bataungi (har ~15 min check)." +
                (live?.let { " Abhi ${FinanceEngine.fmt(it)} hai." } ?: ""), JSONObject().put("id", alert.id))
        }
        reg("cancel_price_alert", "Cancel the price alerts on a symbol (or one alert by id).", props("symbol" to str("Name or ticker"), "id" to str("Alert id")), emptyList(), FIN) { ctx, a ->
            val all = FinanceStore.alerts(ctx)
            val id = a.s("id")
            val raw = a.s("symbol")
            val s = if (raw.isNotBlank()) all.firstOrNull { it.symbol.equals(raw, true) || it.symbol.startsWith(raw.uppercase()) }?.symbol ?: FinanceEngine.resolve(ctx, raw) else ""
            val keep = all.filterNot { it.isArmed && ((id.isNotBlank() && it.id == id) || (s.isNotBlank() && it.symbol.equals(s, true))) }
            val n = all.size - keep.size
            FinanceStore.saveAlerts(ctx, keep); FinanceEngine.syncAlertWorker(ctx)
            if (n > 0) ok("$n alert cancel kar diye.") else fail("Aisa koi armed alert nahi mila.")
        }
        reg("list_price_alerts", "Every price alert that's armed, plus recent ones that already fired.", props(), emptyList(), FIN) { ctx, _ ->
            val all = FinanceStore.alerts(ctx)
            if (all.isEmpty()) return@reg ok("Koi price alert set nahi hai.")
            ok(all.sortedByDescending { it.createdAt }.take(20).joinToString("\n") {
                "- [${it.id}] ${it.symbol} ${it.direction} ${FinanceEngine.fmt(it.price)} - " + if (it.isArmed) "armed" else "fired ${DateTimeParse.formatDateTime(it.firedAt)} @ ${FinanceEngine.fmt(it.firedPrice)}"
            })
        }
    }
}
