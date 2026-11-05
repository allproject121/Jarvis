package com.jarvis.assistant.maya.memory

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry.ToolOutcome
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.data.memory.MemoryManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Maya memory parity tools (family "memory"): facts, episodic recall, standing rules, user
 * name, app aliases, backup, incognito/auto-save, wiki, notices, daily quote.
 *
 * Does NOT re-register the dispatcher's built-in `add_memory` / `delete_memory` / `search_memories`.
 */
object MemoryToolPack {

    private const val TAG = "MemoryToolPack"
    const val FAMILY = "memory"

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private fun prop(type: String, desc: String, enum: List<String>? = null) = MayaToolRegistry.prop(type, desc, enum)

    private fun memoryManager(context: Context): MemoryManager = MemoryManager.getInstance(context)

    private fun note(context: Context, tool: String) {
        runCatching { EpisodeLog.get(context).noteToolUse(tool) }
    }

    private fun systemEvent(text: String) {
        runCatching {
            if (JarvisConversationService.isSessionOn.value) JarvisConversationService.sendTextMessage("[SYSTEM EVENT] $text")
        }
    }

    private fun jarvisCategory(raw: String?): String? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        null, "" -> null
        "personal", "identity", "about", "faith", "health", "self" -> MemoryEntity.CATEGORY_PERSONAL
        "preference", "preferences", "likes", "pasand" -> MemoryEntity.CATEGORY_PREFERENCES
        "people", "contact", "contacts", "family", "friend", "relationship" -> MemoryEntity.CATEGORY_CONTACTS
        "routine", "routines", "schedule", "habit" -> MemoryEntity.CATEGORY_ROUTINES
        else -> MemoryEntity.CATEGORY_NOTES
    }

    fun install(context: Context) {
        val app = context.applicationContext
        runCatching { NoticesStore.refresh(app) }
        runCatching { AppAliasStore.all(app) }
        runCatching { WikiScheduler.schedule(app) }

        // ── Facts ────────────────────────────────────────────────────────────
        MayaToolRegistry.register(
            name = "remember_fact",
            description = "Save ONE durable fact about the user the FIRST time they say it (name, family/friends, relationships, work/study, place, likes/dislikes, health, plans/dates, promises, anything they say 'yaad rakhna'). One short self-contained sentence ('Unka bhai Rohit Pune me rehta hai'). Do it silently; do not announce unless asked. Not for passing moods/weather. Corrections ('ab main Delhi me hoon') are saved the same way and the old fact is superseded.",
            properties = MayaToolRegistry.props(
                "fact" to prop("string", "The fact, one clear sentence in the user's own words."),
                "category" to prop("string", "Optional: personal | preferences | people | routine | notes | faith | health")
            ),
            required = listOf("fact"),
            family = FAMILY
        ) { ctx, args ->
            val fact = args.optString("fact").trim()
            if (fact.isEmpty()) return@register ToolOutcome.fail("fact is empty")
            if (!MemoryPrefs.canStore(ctx)) return@register ToolOutcome.ok("Incognito is on, so nothing was stored.", JSONObject().put("stored", false).put("incognito", true))
            note(ctx, "remember_fact")
            val cat = jarvisCategory(args.optString("category"))
            val response = memoryManager(ctx).rememberFact(fact)
            if (cat != null && cat != MemoryEntity.CATEGORY_NOTES) {
                runCatching {
                    val repo = JarvisApp.instance.memoryRepository
                    repo.getAllMemories().firstOrNull { it.rawText.equals(fact, true) && it.category != cat }?.let { repo.updateMemory(it.copy(category = cat)) }
                }
            }
            ToolOutcome.ok(response, JSONObject().put("stored", true).put("category", cat ?: "auto"))
        }

        MayaToolRegistry.register(
            name = "recall_memory",
            description = "Semantic search over the facts the user told you (names, people, preferences, places, dates). Use before answering anything about the user's life. For what was DONE on a day use recall_episodes; for compiled understanding use read_wiki_page.",
            properties = MayaToolRegistry.props(
                "query" to prop("string", "What to look for, in natural language."),
                "limit" to prop("integer", "Max results (default 6).")
            ),
            required = listOf("query"),
            family = FAMILY
        ) { ctx, args ->
            val query = args.optString("query").trim()
            if (query.isEmpty()) return@register ToolOutcome.fail("query is empty")
            note(ctx, "recall_memory")
            val limit = args.optInt("limit", 6).coerceIn(1, 20)
            val hits = runCatching { memoryManager(ctx).retrieve(query, limit) }.getOrDefault(emptyList())
            val arr = JSONArray()
            hits.forEach { h ->
                arr.put(JSONObject().put("id", h.memory.id).put("content", h.memory.content).put("category", h.memory.jarvisCategory)
                    .put("score", (h.score * 100).toInt() / 100.0).put("updatedAt", dateFmt.format(Date(h.memory.updatedAt))))
            }
            if (arr.length() == 0) {
                val fallback = runCatching { JarvisApp.instance.memoryRepository.searchMemories(query) }.getOrDefault(emptyList()).take(limit)
                fallback.forEach { e -> arr.put(JSONObject().put("id", e.id).put("content", e.rawText).put("category", e.category).put("score", 0.5)) }
            }
            val msg = if (arr.length() == 0) "No saved fact matches '$query'." else "Found ${arr.length()} memory(ies) for '$query'."
            ToolOutcome.ok(msg, JSONObject().put("memories", arr))
        }

        MayaToolRegistry.register(
            name = "forget_memory",
            description = "Delete saved facts matching a few words ('mera purana number bhool jao', 'Rohit wali baat delete karo'). Only when the user asks to forget.",
            properties = MayaToolRegistry.props("query" to prop("string", "Words that identify the fact(s) to delete.")),
            required = listOf("query"),
            family = FAMILY,
            risky = true
        ) { ctx, args ->
            val query = args.optString("query").trim()
            if (query.isEmpty()) return@register ToolOutcome.fail("query is empty")
            note(ctx, "forget_memory")
            ToolOutcome.ok(memoryManager(ctx).forgetFact(query))
        }

        // ── Episodes ─────────────────────────────────────────────────────────
        MayaToolRegistry.register(
            name = "recall_episodes",
            description = "Look up what was actually DONE in the past: your activity log of conversations (who said what, when, which tools ran, which app was open), not saved facts. Use for 'us din kya kiya tha', 'pichhle hafte wo kaam hua tha kya', 'kitni baar aisa hua'. For facts the user told you use recall_memory.",
            properties = MayaToolRegistry.props(
                "query" to prop("string", "Optional keywords."),
                "date" to prop("string", "Optional: yyyy-MM-dd, dd/MM/yyyy, yyyy-MM, today|aaj, yesterday|kal, this week, last week."),
                "limit" to prop("integer", "Max results (default 8).")
            ),
            family = FAMILY
        ) { ctx, args ->
            note(ctx, "recall_episodes")
            val query = args.optString("query").trim().ifEmpty { null }
            val date = args.optString("date").trim().ifEmpty { null }
            val limit = args.optInt("limit", 8).coerceIn(1, 30)
            if (date != null && EpisodeScoring.dateRange(date) == null) return@register ToolOutcome.fail("Could not understand date '$date'. Use yyyy-MM-dd, today, yesterday, this week or last week.")
            val hits = EpisodeLog.get(ctx).recall(query, date, limit)
            val arr = JSONArray()
            hits.forEach { e ->
                arr.put(JSONObject().put("when", dateFmt.format(Date(e.ts))).put("user", e.user.take(300)).put("jarvis", e.assistant.take(300))
                    .put("tools", JSONArray(e.tools)).put("app", e.app ?: JSONObject.NULL))
            }
            val msg = if (hits.isEmpty()) "No episodes found" + (date?.let { " for $it" } ?: "") + (query?.let { " about '$it'" } ?: "") + "." else "${hits.size} episode(s), newest first."
            ToolOutcome.ok(msg, JSONObject().put("episodes", arr).put("total_logged", EpisodeLog.get(ctx).count()))
        }

        // ── Rules ────────────────────────────────────────────────────────────
        MayaToolRegistry.register(
            name = "add_rule",
            description = "Save a NEW permanent rule about how you should behave, talk or work. Call whenever the user lays down a standing instruction: 'ab se hamesha...', 'kabhi mat...', 'mujhe ... bulaya karo', 'jawab chhote rakha karo'. One short clear sentence in the user's words. NOT for one-off requests and NOT for facts about the user (remember_fact).",
            properties = MayaToolRegistry.props("text" to prop("string", "The rule, one sentence.")),
            required = listOf("text"),
            family = FAMILY
        ) { ctx, args ->
            val text = args.optString("text").trim()
            if (text.isEmpty()) return@register ToolOutcome.fail("text is empty")
            note(ctx, "add_rule")
            val rule = RulesStore.get(ctx).add(text)
            systemEvent("New standing rule saved: \"${rule.text}\". Follow it from now on.")
            ToolOutcome.ok("Rule saved (${rule.id}): ${rule.text}", JSONObject().put("id", rule.id).put("version", RulesStore.get(ctx).version()))
        }

        MayaToolRegistry.register(
            name = "update_rule",
            description = "Change an existing rule. Pass its id (from list_rules) or a few words of the OLD rule in 'find', and the full replacement in 'text'. If nothing matches the tool says so: ask which rule or call list_rules.",
            properties = MayaToolRegistry.props(
                "id" to prop("string", "Rule id from list_rules (optional if 'find' is given)."),
                "find" to prop("string", "A few words of the old rule (optional if 'id' is given)."),
                "text" to prop("string", "The full replacement rule.")
            ),
            required = listOf("text"),
            family = FAMILY
        ) { ctx, args ->
            val text = args.optString("text").trim()
            if (text.isEmpty()) return@register ToolOutcome.fail("text is empty")
            val store = RulesStore.get(ctx)
            val target = args.optString("id").trim().takeIf { it.isNotEmpty() }?.let { store.get(it) }
                ?: args.optString("find").trim().takeIf { it.isNotEmpty() }?.let { store.findByWords(it) }
                ?: return@register ToolOutcome.fail("No rule matches. Call list_rules and pass the id.")
            note(ctx, "update_rule")
            val updated = store.update(target.id, text) ?: return@register ToolOutcome.fail("Could not update rule ${target.id}")
            systemEvent("Rule updated: \"${updated.text}\" (replaces \"${target.text}\").")
            ToolOutcome.ok("Rule ${updated.id} updated to: ${updated.text}", JSONObject().put("id", updated.id).put("version", updated.version))
        }

        MayaToolRegistry.register(
            name = "delete_rule",
            description = "Delete a standing rule by id (from list_rules) or by a few words of it. Only when the user asks ('wo rule hata do').",
            properties = MayaToolRegistry.props(
                "id" to prop("string", "Rule id (optional if 'find' is given)."),
                "find" to prop("string", "A few words of the rule (optional if 'id' is given).")
            ),
            family = FAMILY,
            risky = true
        ) { ctx, args ->
            val store = RulesStore.get(ctx)
            val target = args.optString("id").trim().takeIf { it.isNotEmpty() }?.let { store.get(it) }
                ?: args.optString("find").trim().takeIf { it.isNotEmpty() }?.let { store.findByWords(it) }
                ?: return@register ToolOutcome.fail("No rule matches. Call list_rules first.")
            note(ctx, "delete_rule")
            store.delete(target.id)
            systemEvent("Rule removed: \"${target.text}\". It no longer applies.")
            ToolOutcome.ok("Deleted rule: ${target.text}", JSONObject().put("id", target.id))
        }

        MayaToolRegistry.register(
            name = "list_rules",
            description = "List every standing rule the user gave, with ids (for update_rule / delete_rule).",
            family = FAMILY
        ) { ctx, _ ->
            val rules = RulesStore.get(ctx).list()
            val arr = JSONArray()
            rules.forEach { r -> arr.put(JSONObject().put("id", r.id).put("text", r.text).put("version", r.version).put("createdAt", dateFmt.format(Date(r.createdAt)))) }
            ToolOutcome.ok(if (rules.isEmpty()) "No standing rules yet." else "${rules.size} rule(s).", JSONObject().put("rules", arr).put("version", RulesStore.get(ctx).version()))
        }

        // ── Name & app aliases ───────────────────────────────────────────────
        MayaToolRegistry.register(
            name = "set_user_name",
            description = "Save the user's name so you address them by it from now on (and in every later session). Call when the user tells you their name ('mera naam Rahul hai', 'mujhe Priya bulao') or answers your question about it. Not for a nickname for YOU. If the tool says the name is reserved, do not store a variant; ask for another name or a nickname.",
            properties = MayaToolRegistry.props("name" to prop("string", "The name exactly as the user said it.")),
            required = listOf("name"),
            family = FAMILY
        ) { ctx, args ->
            val raw = args.optString("name")
            val name = UserNames.normalize(raw)
            if (name.isEmpty()) return@register ToolOutcome.fail("name is empty")
            if (UserNames.isReserved(name)) {
                return@register ToolOutcome.fail("'$name' is reserved (it is the assistant's/creator's name). Kindly say this name cannot be used and ask for another name or nickname. Do not store it.", JSONObject().put("reserved", true))
            }
            if (!UserNames.isValid(name)) return@register ToolOutcome.fail("'$name' does not look like a name (max 40 letters, no digits).")
            note(ctx, "set_user_name")
            runCatching { JarvisApp.instance.preferences.userName = name }
                .onFailure { return@register ToolOutcome.fail("Could not save name: ${it.message}") }
            systemEvent("User's name is now '$name'. Address them by it.")
            ToolOutcome.ok("Saved. The user's name is $name.", JSONObject().put("name", name))
        }

        MayaToolRegistry.register(
            name = "remember_app_name",
            description = "Teach a spoken name for an installed app ('Insta' = Instagram, 'bank wala' = com.sbi.yono) so open_app works next time. Pass the package if you know it (from list_installed_apps); otherwise the tool looks the app up by label. Calling again overwrites.",
            properties = MayaToolRegistry.props(
                "spoken_name" to prop("string", "What the user calls the app."),
                "package" to prop("string", "Package name (optional; label lookup otherwise)."),
                "app_label" to prop("string", "Optional: the real app name to look up when package is unknown.")
            ),
            required = listOf("spoken_name"),
            family = FAMILY
        ) { ctx, args ->
            val spoken = args.optString("spoken_name").trim()
            if (spoken.isEmpty()) return@register ToolOutcome.fail("spoken_name is empty")
            var pkg = args.optString("package").trim()
            if (pkg.isEmpty() || !AppAliasStore.isInstalled(ctx, pkg)) {
                val label = args.optString("app_label").trim().ifEmpty { if (pkg.isNotEmpty()) pkg else spoken }
                pkg = AppAliasStore.findPackageByLabel(ctx, label).orEmpty()
            }
            if (pkg.isEmpty()) {
                return@register ToolOutcome.fail("No installed app named '$spoken' found, so nothing was remembered. Use list_installed_apps to find the real name, or ask the user to open the app and call remember_app_name with its package.")
            }
            note(ctx, "remember_app_name")
            AppAliasStore.remember(ctx, spoken, pkg)
            val label = AppAliasStore.labelOf(ctx, pkg)
            ToolOutcome.ok("Remembered: '$spoken' means $label ($pkg).", JSONObject().put("spoken_name", spoken).put("package", pkg).put("label", label))
        }

        MayaToolRegistry.register(
            name = "forget_app_name",
            description = "Undo a name you were taught for an app, when it pointed at the wrong one. To point it at a different app instead, just call remember_app_name again.",
            properties = MayaToolRegistry.props("spoken_name" to prop("string", "The taught spoken name.")),
            required = listOf("spoken_name"),
            family = FAMILY
        ) { ctx, args ->
            val spoken = args.optString("spoken_name").trim()
            if (spoken.isEmpty()) return@register ToolOutcome.fail("spoken_name is empty")
            note(ctx, "forget_app_name")
            val removed = AppAliasStore.forget(ctx, spoken) ?: return@register ToolOutcome.fail("No alias '$spoken' was taught.")
            ToolOutcome.ok("Forgot '$spoken' (was $removed).", JSONObject().put("package", removed))
        }

        // ── Backup & privacy ─────────────────────────────────────────────────
        MayaToolRegistry.register(
            name = "export_backup",
            description = "Back up everything the user has shared (app settings, remembered facts, chat transcripts, rules, episodes, wiki, notices, custom macros) into one JSON file and open the share sheet so they can send it to Drive/WhatsApp/another phone. 'backup le lo', 'meri yaadein save kar do', 'naye phone me le jaana hai'. API keys and the voice print are never included.",
            family = FAMILY
        ) { ctx, _ ->
            note(ctx, "export_backup")
            // One backup format app-wide: same file as Settings -> Backup & restore (no API keys).
            val result = com.jarvis.assistant.data.backup.BackupManager.exportAndShare(ctx)
            result.fold(
                onSuccess = { f -> ToolOutcome.ok("Backup ready (${f.length() / 1024} KB); share sheet opened.", JSONObject().put("file", f.name).put("bytes", f.length())) },
                onFailure = { ToolOutcome.fail("Backup failed: ${it.message}") }
            )
        }

        MayaToolRegistry.register(
            name = "import_chatgpt_data",
            description = "Learn the user's habits, preferences and standing rules from THEIR OWN ChatGPT data export, and remember them. The user exports from ChatGPT (Settings - Data controls - Export data), unzips it, and gives the path to conversations.json. 'ChatGPT se meri aadatein seekh lo', 'mera purana data import karo', 'ChatGPT wala data Jarvis me daal do'. Only reads the given file; never contacts OpenAI.",
            properties = MayaToolRegistry.props(
                "path" to prop("string", "Absolute path (or content:// uri) to the exported conversations.json, or the folder that holds it.")
            ),
            required = listOf("path"),
            family = FAMILY
        ) { ctx, args ->
            note(ctx, "import_chatgpt_data")
            val path = args.optString("path").ifBlank { args.optString("file") }.ifBlank { args.optString("uri") }.trim()
            if (path.isEmpty()) return@register ToolOutcome.fail("Give me the path to your ChatGPT conversations.json.")
            val r = ChatGptImporter.import(ctx, path)
            if (r.ok) ToolOutcome.ok(
                r.message,
                JSONObject().put("facts", r.facts).put("rules", r.rules).put("habits", r.habits)
                    .put("conversations", r.conversations).put("user_turns", r.userTurns)
            ) else ToolOutcome.fail(r.message)
        }

        MayaToolRegistry.register(
            name = "set_memory_incognito",
            description = "Turn incognito on/off: while on, nothing from this session is stored (no facts, no episodes, no wiki). 'ye baat yaad mat rakhna', 'incognito on karo'.",
            properties = MayaToolRegistry.props("enabled" to prop("boolean", "true = do not store anything.")),
            required = listOf("enabled"),
            family = FAMILY
        ) { ctx, args ->
            val enabled = args.optBoolean("enabled", true)
            MemoryPrefs.setIncognito(ctx, enabled)
            systemEvent(if (enabled) "Memory incognito ON: nothing from this session is stored." else "Memory incognito OFF: storing resumed.")
            ToolOutcome.ok(if (enabled) "Incognito on; this session will not be stored." else "Incognito off; memory storage resumed.", JSONObject().put("incognito", enabled))
        }

        MayaToolRegistry.register(
            name = "set_memory_auto_save",
            description = "Turn automatic memory extraction from conversations on/off. When off, only explicit remember_fact calls are stored.",
            properties = MayaToolRegistry.props("enabled" to prop("boolean", "true = auto-save facts from chat.")),
            required = listOf("enabled"),
            family = FAMILY
        ) { ctx, args ->
            val enabled = args.optBoolean("enabled", true)
            MemoryPrefs.setAutoSave(ctx, enabled)
            ToolOutcome.ok(if (enabled) "Auto-save on." else "Auto-save off; only explicit facts are stored.", JSONObject().put("auto_save", enabled))
        }

        // ── Wiki ─────────────────────────────────────────────────────────────
        MayaToolRegistry.register(
            name = "compile_wiki",
            description = "Rebuild the personal wiki pages (About you, People, Preferences, Projects, Routine, Places) from memories and episodes now, in the background. Needs a configured AI model.",
            family = FAMILY
        ) { ctx, _ ->
            note(ctx, "compile_wiki")
            if (!WikiCompiler.hasLlm(ctx)) return@register ToolOutcome.fail("No AI model/key configured, so the wiki cannot be compiled.")
            if (MemoryPrefs.isIncognito(ctx)) return@register ToolOutcome.fail("Incognito is on; wiki compile skipped.")
            WikiScheduler.runNow(ctx, force = true)
            ToolOutcome.ok("Wiki compile started in the background; pages refresh within a minute.", JSONObject().put("started", true))
        }

        MayaToolRegistry.register(
            name = "read_wiki_page",
            description = "Read one page of YOUR OWN notebook about the user, compiled from what actually happens. Call when a topic needs real depth. Pages: about-you, people, preferences, projects, routine, places (see list_wiki_pages).",
            properties = MayaToolRegistry.props("slug" to prop("string", "Page slug, e.g. routine.")),
            required = listOf("slug"),
            family = FAMILY
        ) { ctx, args ->
            val slug = WikiIndex.slugify(args.optString("slug"))
            note(ctx, "read_wiki_page")
            val md = WikiCompiler.readPage(ctx, slug)
                ?: return@register ToolOutcome.fail("No page '$slug' yet. Pages: ${WikiCompiler.readIndex(ctx).joinToString { it.slug }.ifBlank { "(none compiled yet; call compile_wiki)" }}")
            val meta = WikiCompiler.readIndex(ctx).firstOrNull { it.slug == slug }
            ToolOutcome.ok(md, JSONObject().put("slug", slug).put("title", meta?.title ?: slug).put("updatedAt", meta?.updatedAt ?: 0L).put("sources", meta?.sources ?: 0))
        }

        MayaToolRegistry.register(
            name = "list_wiki_pages",
            description = "List the personal wiki pages with their one-line summaries and last update.",
            family = FAMILY
        ) { ctx, _ ->
            val pages = WikiCompiler.readIndex(ctx)
            val arr = JSONArray()
            pages.forEach { p -> arr.put(JSONObject().put("slug", p.slug).put("title", p.title).put("summary", p.summary).put("updatedAt", if (p.updatedAt > 0) dateFmt.format(Date(p.updatedAt)) else "").put("sources", p.sources)) }
            ToolOutcome.ok(if (pages.isEmpty()) "No wiki pages compiled yet (call compile_wiki)." else "${pages.size} page(s).", JSONObject().put("pages", arr))
        }

        // ── Notices & quote ──────────────────────────────────────────────────
        MayaToolRegistry.register(
            name = "post_notice",
            description = "Post a local notice to the user's Home notices list (e.g. 'Backup ready', 'Price alert hit'). kind: info | alert.",
            properties = MayaToolRegistry.props(
                "title" to prop("string", "Short title."),
                "body" to prop("string", "One or two lines."),
                "kind" to prop("string", "info | alert (default info)", listOf("info", "alert"))
            ),
            required = listOf("title", "body"),
            family = FAMILY
        ) { ctx, args ->
            val title = args.optString("title").trim()
            val body = args.optString("body").trim()
            if (title.isEmpty() && body.isEmpty()) return@register ToolOutcome.fail("title/body empty")
            val n = NoticesStore.post(ctx, title, body, args.optString("kind", NoticesStore.KIND_INFO))
            ToolOutcome.ok("Notice posted: ${n.title}", JSONObject().put("id", n.id).put("unread", NoticesStore.unreadCount(ctx)))
        }

        MayaToolRegistry.register(
            name = "list_notices",
            description = "List the user's notices (newest first) with read state; optionally only unread.",
            properties = MayaToolRegistry.props(
                "unread_only" to prop("boolean", "Only unread (default false)."),
                "limit" to prop("integer", "Max (default 10).")
            ),
            family = FAMILY
        ) { ctx, args ->
            val unreadOnly = args.optBoolean("unread_only", false)
            val limit = args.optInt("limit", 10).coerceIn(1, 50)
            val list = NoticesStore.list(ctx).filter { !unreadOnly || !it.read }.take(limit)
            val arr = JSONArray()
            list.forEach { n -> arr.put(JSONObject().put("id", n.id).put("title", n.title).put("body", n.body).put("kind", n.kind).put("read", n.read).put("when", dateFmt.format(Date(n.ts)))) }
            ToolOutcome.ok(if (list.isEmpty()) "No notices." else "${list.size} notice(s).", JSONObject().put("notices", arr).put("unread", NoticesStore.unreadCount(ctx)))
        }

        MayaToolRegistry.register(
            name = "daily_quote",
            description = "Today's quote of the day (generated once per day and cached). Use for 'aaj ka quote', 'motivate me'.",
            family = FAMILY
        ) { ctx, _ ->
            val q = DailyQuote.today(ctx)
            ToolOutcome.ok(q, JSONObject().put("quote", q).put("date", DailyQuote.dateKey()))
        }

        Log.i(TAG, "Memory tool pack installed")
    }
}
