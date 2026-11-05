package com.jarvis.assistant.maya.screen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.dispatcher.JarvisToolDispatcher
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry.ToolOutcome
import com.jarvis.assistant.automation.macro.Macro
import com.jarvis.assistant.automation.macro.MacroEngine
import com.jarvis.assistant.automation.macro.MacroExecutionResult
import com.jarvis.assistant.automation.macro.MacroRepository
import com.jarvis.assistant.util.ActivityLauncherHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * WP1 — Maya screen automation pack: verb loop (run_agent_task), #N element tools, watches,
 * phone-event announcements, screen awareness and macro training/playback.
 * Installed by MayaToolPacks via [install].
 */
object ScreenToolPack {

    private const val TAG = "ScreenToolPack"
    private const val FAMILY = "screen"
    private const val SYNC_BUDGET_MS = 180_000L

    @Volatile
    private var registered = false

    fun install(context: Context) {
        val app = context.applicationContext
        registerAll()
        ensureStarted(app)
    }

    /** Re-arms persisted watches / receivers and restores an in-progress training. */
    fun ensureStarted(context: Context) {
        try { WatcherEngine.start(context.applicationContext) } catch (e: Throwable) { Log.w(TAG, "watcher start failed: ${e.message}") }
        try { MacroRecorder.restore(context.applicationContext) } catch (e: Throwable) { Log.w(TAG, "recorder restore failed: ${e.message}") }
    }

    // ------------------------------------------------------------------ helpers

    private fun p(type: String, desc: String, enum: List<String>? = null) = MayaToolRegistry.prop(type, desc, enum)

    private fun str(a: JSONObject, vararg keys: String): String {
        for (k in keys) {
            if (a.has(k) && !a.isNull(k)) {
                val v = a.opt(k)
                val s = when (v) {
                    is JSONArray -> (0 until v.length()).joinToString("\n") { v.optString(it) }
                    else -> v.toString()
                }
                if (s.isNotBlank()) return s.trim()
            }
        }
        return ""
    }

    private fun int(a: JSONObject, key: String): Int? {
        if (!a.has(key) || a.isNull(key)) return null
        return when (val v = a.opt(key)) {
            is Number -> v.toInt()
            is String -> v.trim().removePrefix("#").toDoubleOrNull()?.toInt()
            else -> null
        }
    }

    private fun long(a: JSONObject, key: String): Long? {
        if (!a.has(key) || a.isNull(key)) return null
        return when (val v = a.opt(key)) {
            is Number -> v.toLong()
            is String -> v.trim().toDoubleOrNull()?.toLong()
            else -> null
        }
    }

    private fun bool(a: JSONObject, key: String, def: Boolean): Boolean {
        if (!a.has(key) || a.isNull(key)) return def
        return when (val v = a.opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.trim().lowercase() in setOf("true", "1", "yes", "on", "haan", "ha", "enable", "enabled", "chalu")
            else -> def
        }
    }

    private fun serviceOff(): ToolOutcome = ToolOutcome.fail(ScreenElementSerializer.SERVICE_OFF, JSONObject().put("accessibility", false))

    private fun repo(context: Context): MacroRepository =
        try { JarvisApp.instance.macroRepository } catch (_: Throwable) { MacroRepository(context.applicationContext) }

    private fun engine(context: Context): MacroEngine =
        try { JarvisApp.instance.macroEngine } catch (_: Throwable) { MacroEngine(context.applicationContext, repo(context)) }

    private fun elementsText(snap: ScreenSnapshot, settled: Boolean): String {
        val sb = StringBuilder()
        if (!settled) sb.append(ScreenExecutorPrompt.STALE_LIST_WARNING).append('\n')
        sb.append(ScreenExecutorPrompt.ELEMENTS_HEADER).append(" Tap any of these with tap_element(id=N) — that is exact. Actionable elements:\n")
        sb.append(snap.elementsBlock(ScreenElementSerializer.assistantName()))
        return sb.toString()
    }

    /** The label to store in a macro step for a tapped element (first part of a composite label). */
    private fun recordTap(el: SnapshotElement) {
        if (!MacroRecorder.isRecording()) return
        val label = el.line.label
        when {
            label != ScreenElementFormat.NO_LABEL -> MacroRecorder.recordTool("tap_text", mapOf("text" to label.substringBefore(" | ").removeSuffix("…").trim()))
            !el.resourceId.isNullOrBlank() -> MacroRecorder.recordTool("tap_resource_id", mapOf("id" to el.resourceId))
            else -> MacroRecorder.recordTool("tap_coords", mapOf("x" to el.line.x, "y" to el.line.y))
        }
    }

    private suspend fun afterTap(context: Context, before: ScreenSnapshot, res: ActionResult, what: String): ToolOutcome {
        if (!res.ok) return ToolOutcome.fail("$what — ${res.message}")
        delay(400)
        val (after, _) = ScreenActions.waitForSettle(1_600L)
        val changed = ScreenActions.changed(before.signature, after)
        val note = if (changed) " ${ScreenExecutorPrompt.SCREEN_CHANGED_REREAD}" else " ${ScreenExecutorPrompt.NO_VISIBLE_CHANGE}"
        return ToolOutcome.ok("$what.$note", JSONObject().put("screen_changed", changed))
    }

    // ------------------------------------------------------------------ registration

    private fun registerAll() {
        if (registered) return
        registered = true

        // ---------------- run_agent_task
        MayaToolRegistry.register(
            name = "run_agent_task",
            description = "Do a task ON THE PHONE'S SCREEN yourself — any setting/switch in any app, tapping buttons, typing into apps, multi-step app flows ('WiFi band karo', 'Instagram pe X ki profile kholo', 'ChatGPT me ye poochho'). " +
                "Runs the screen-automation verb loop: reads the accessibility tree, taps by #id, types, scrolls, opens apps, waits for results and looks at the screen when labels are missing. " +
                "Pass the user's goal in plain words; optionally 'steps' (one per line) and 'app' to open first. Anything done on the phone's screen you can ALWAYS do with this when no other tool fits. " +
                "Do NOT use it to record a macro (training) — do those steps with the real tools one by one.",
            properties = MayaToolRegistry.props(
                "goal" to p("string", "What must be achieved on screen, in plain words (include exact text to type, contact names, etc.)"),
                "steps" to p("string", "Optional plan, one step per line (or separated by ';')"),
                "app" to p("string", "Optional app to open first, e.g. 'Instagram', 'Settings'")
            ),
            required = listOf("goal"),
            family = FAMILY
        ) { ctx, args -> runAgentTask(ctx, args) }

        // ---------------- read_screen_elements
        MayaToolRegistry.register(
            name = "read_screen_elements",
            description = "List the on-screen elements you can act on — buttons, list items, icons and input fields — each with a #id, its label, kind and tap point. Tap any of them with tap_element(id=N): that is exact and cannot land off-target. Call this whenever you're unsure what's on screen, and again after the screen changes.",
            family = FAMILY
        ) { ctx, _ ->
            if (!ScreenElementSerializer.isServiceOn()) return@register serviceOff()
            val (_, settled) = ScreenActions.waitForSettle(900L)
            val snap = ScreenElementSerializer.capture(ctx) ?: return@register ToolOutcome.fail("Screen padh nahi paaya — koi active window nahi mili.")
            ToolOutcome.ok(elementsText(snap, settled), JSONObject().put("package", snap.packageName).put("app", snap.appLabel).put("count", snap.elements.size))
        }

        // ---------------- read_screen_text
        MayaToolRegistry.register(
            name = "read_screen_text",
            description = "Read all visible text on the user's current screen (fast, offline). Use when the user asks what's written on screen, to read a message, an article, an error, etc.",
            family = FAMILY
        ) { ctx, _ ->
            if (!ScreenElementSerializer.isServiceOn()) return@register serviceOff()
            val texts = ScreenElementSerializer.allVisibleText()
            val pkg = ScreenElementSerializer.foregroundPackage()
            val joined = texts.joinToString("\n")
            val max = 3500
            val body = if (joined.length > max) joined.substring(0, max) + "\n…" else joined
            val more = joined.length > max || texts.size >= 40
            val tail = if (more) "\n(Aur bhi ho sakta hai — scroll_screen('down') karke phir read_screen_text se aage padho.)" else ""
            if (texts.isEmpty()) ToolOutcome.ok("Screen pe koi padhne layak text nahi mila (${ScreenElementSerializer.appLabel(ctx, pkg)}). Image/canvas ho to look_at_screen use karo.")
            else ToolOutcome.ok("Screen text (${ScreenElementSerializer.appLabel(ctx, pkg)}):\n$body$tail", JSONObject().put("truncated", joined.length > max))
        }

        // ---------------- tap_element
        MayaToolRegistry.register(
            name = "tap_element",
            description = "Tap an element by the #id number that read_screen_elements printed next to it. THIS IS THE MOST ACCURATE WAY TO TAP — no label guessing, no coordinates to copy. Use it for anything in that list, especially unlabeled icons (send/next/post arrows) and rows whose label repeats. Read the list first; if the screen has changed since, this refuses instead of tapping the wrong thing, so just read it again.",
            properties = MayaToolRegistry.props("id" to p("integer", "The #id number from read_screen_elements")),
            required = listOf("id"),
            family = FAMILY
        ) { ctx, args -> tapElementTool(ctx, args) }

        // ---------------- tap_text
        MayaToolRegistry.register(
            name = "tap_text",
            description = "Tap the on-screen element whose label matches this text (exact label first, then a close match). If two controls share the label it refuses and lists their #ids — then use tap_element(id=N).",
            properties = MayaToolRegistry.props("text" to p("string", "Exact visible label of the button/row to tap")),
            required = listOf("text"),
            family = FAMILY
        ) { ctx, args ->
            if (!ScreenElementSerializer.isServiceOn()) return@register serviceOff()
            val text = str(args, "text", "label")
            if (text.isEmpty()) return@register ToolOutcome.fail("tap_text ko 'text' chahiye.")
            val snap = ScreenElementSerializer.capture(ctx) ?: return@register ToolOutcome.fail("Screen padh nahi paaya.")
            val matches = snap.find(text)
            if (matches.isEmpty()) return@register ToolOutcome.fail("'$text' naam ka kuch nahi mila. read_screen_elements call karke dekho asli labels kya hain, phir tap_element(id=N) use karo.")
            val exact = matches.filter { it.line.label.equals(text, ignoreCase = true) }
            if (exact.size > 1 && exact.map { "${it.line.x / 40},${it.line.y / 40}" }.distinct().size > 1) {
                return@register ToolOutcome.fail("'$text' matches ${exact.size} different things (${exact.take(6).joinToString { "#${it.line.id} @ (${it.line.x},${it.line.y})" }}) — I won't guess which. Call read_screen_elements and tap the right one with tap_element(id=N).")
            }
            val el = matches.first()
            val res = ScreenActions.tapElement(el)
            if (res.ok) recordTap(el)
            afterTap(ctx, snap, res, "Tapped '${el.line.label}' (#${el.line.id})")
        }

        // ---------------- tap_coordinates
        MayaToolRegistry.register(
            name = "tap_coordinates",
            description = "Tap a point by pixel coordinates. If x,y came from look_at_screen or the screen share, pass space='image' or the tap lands far off-target. LAST RESORT — prefer tap_element (by #id) or tap_text, which can't be mis-typed. Use only for something that has no #id at all, e.g. a spot on a canvas or an image.",
            properties = MayaToolRegistry.props(
                "x" to p("integer", "X coordinate"),
                "y" to p("integer", "Y coordinate"),
                "space" to p("string", "'device' (default, real pixels from read_screen_elements) or 'image' (coords read off the shared screenshot you see).", listOf("device", "image"))
            ),
            required = listOf("x", "y"),
            family = FAMILY
        ) { ctx, args ->
            if (!ScreenElementSerializer.isServiceOn()) return@register serviceOff()
            val x = int(args, "x") ?: return@register ToolOutcome.fail("x chahiye")
            val y = int(args, "y") ?: return@register ToolOutcome.fail("y chahiye")
            val space = str(args, "space").lowercase().ifEmpty { "device" }
            val (dx, dy) = if (space.startsWith("image") || space == "img") VisionLookup.imageToDevice(ctx, x, y) else x to y
            val before = ScreenElementSerializer.capture(ctx) ?: return@register ToolOutcome.fail("Screen padh nahi paaya.")
            val res = ScreenActions.tapCoords(dx, dy)
            if (res.ok && MacroRecorder.isRecording()) MacroRecorder.recordTool("tap_coords", mapOf("x" to dx, "y" to dy))
            afterTap(ctx, before, res, "Tapped ($dx,$dy)" + if (dx != x || dy != y) " [mapped from image ($x,$y)]" else "")
        }

        // ---------------- wait_for_screen_text
        MayaToolRegistry.register(
            name = "wait_for_screen_text",
            description = "WAIT until some text APPEARS on (or DISAPPEARS from) the screen, then return. Use this instead of a fixed wait whenever you're waiting for something whose duration you can't predict — an AI image or video finishing, an upload, a page loading. It returns the moment the condition is met, so it never waits longer than needed and never gives up too early. Example: after asking Gemini for a video, wait_for_screen_text(text='Your video is ready', timeout_ms=600000).",
            properties = MayaToolRegistry.props(
                "text" to p("string", "Text to wait for (case-insensitive, partial match)"),
                "state" to p("string", "'appear' (default) or 'disappear'", listOf("appear", "disappear")),
                "timeout_ms" to p("integer", "Max wait in milliseconds (default 60000, max 600000)")
            ),
            required = listOf("text"),
            family = FAMILY
        ) { _, args ->
            if (!ScreenElementSerializer.isServiceOn()) return@register serviceOff()
            val text = str(args, "text")
            if (text.isEmpty()) return@register ToolOutcome.fail("text chahiye")
            val appear = str(args, "state", "mode").lowercase().let { !(it.startsWith("dis") || it == "gone" || it == "hide") }
            val timeout = (long(args, "timeout_ms") ?: long(args, "timeout_seconds")?.times(1000) ?: 60_000L).coerceIn(1_000L, 600_000L)
            if (MacroRecorder.isRecording()) MacroRecorder.recordTool("wait_for_screen_text", mapOf("text" to text, "mode" to if (appear) "appear" else "disappear", "timeout_seconds" to (timeout / 1000)))
            val elapsed = ScreenActions.waitForText(text, appear, timeout)
            if (elapsed >= 0) ToolOutcome.ok("'$text' ${if (appear) "screen pe aa gaya" else "screen se hat gaya"} (${elapsed / 1000}s).", JSONObject().put("met", true).put("elapsed_ms", elapsed))
            else if (appear) ToolOutcome.fail("'$text' ${timeout / 1000}s me screen pe aaya hi nahi. read_screen_elements se dekho ab kya dikh raha hai.", JSONObject().put("met", false))
            else ToolOutcome.fail("'$text' ${timeout / 1000}s me bhi screen se nahi hata. Pakka karna ho to read_screen_elements se dekho.", JSONObject().put("met", false))
        }

        // ---------------- go_home / go_back
        MayaToolRegistry.register(
            name = "go_home",
            description = "Go to the phone's home screen.",
            family = FAMILY
        ) { _, _ ->
            val r = ScreenActions.home()
            if (r.ok && MacroRecorder.isRecording()) MacroRecorder.recordTool("go_home", emptyMap())
            if (r.ok) ToolOutcome.ok("Home screen pe aa gaye.") else if (!ScreenElementSerializer.isServiceOn()) serviceOff() else ToolOutcome.fail(r.message)
        }
        MayaToolRegistry.register(
            name = "go_back",
            description = "Press the Android BACK button once (close a dialog/keyboard, go to the previous screen).",
            family = FAMILY
        ) { ctx, _ ->
            val before = ScreenElementSerializer.capture(ctx)
            val r = ScreenActions.back()
            if (r.ok && MacroRecorder.isRecording()) MacroRecorder.recordTool("go_back", emptyMap())
            if (!r.ok) return@register if (!ScreenElementSerializer.isServiceOn()) serviceOff() else ToolOutcome.fail(r.message)
            if (before == null) ToolOutcome.ok("Back dabaya.") else afterTap(ctx, before, r, "Pressed back")
        }

        // ---------------- open_app_by_name
        MayaToolRegistry.register(
            name = "open_app_by_name",
            description = "Open any installed app by its display name, e.g. 'Instagram', 'Settings', 'Spotify'. This only LAUNCHES an app — it does not show YOU anything; to see what's on screen call read_screen_elements afterwards.",
            properties = MayaToolRegistry.props("name" to p("string", "App display name")),
            required = listOf("name"),
            family = FAMILY
        ) { ctx, args ->
            val name = str(args, "name", "app", "app_name")
            if (name.isEmpty()) return@register ToolOutcome.fail("App ka naam chahiye.")
            val (res, opened) = ScreenActions.openApp(ctx, name)
            if (!res.ok || opened == null) return@register ToolOutcome.fail(res.message)
            if (MacroRecorder.isRecording()) MacroRecorder.recordTool("open_app_by_name", mapOf("name" to opened.label, "_pkg" to opened.packageName))
            ToolOutcome.ok(res.message, JSONObject().put("package", opened.packageName).put("app", opened.label))
        }

        // ---------------- open_settings
        MayaToolRegistry.register(
            name = "open_settings",
            description = "Open an Android Settings screen: wifi, bluetooth, display, sound, battery, apps, accessibility, notifications, location, date, developer, hotspot, nfc, airplane, storage, security, language, data, vpn, cast, keyboard, dnd, main. Opening the panel is only HALF the job — then read_screen_elements, check the switch's [currently ON/OFF] and tap_element it yourself; tell the user only the final result.",
            properties = MayaToolRegistry.props("panel" to p("string", "Which settings screen", SETTINGS_PANELS)),
            required = listOf("panel"),
            family = FAMILY
        ) { ctx, args -> openSettings(ctx, str(args, "panel", "name", "screen")) }

        // ---------------- look_at_screen
        MayaToolRegistry.register(
            name = "look_at_screen",
            description = "Take a screenshot of the phone screen and get your question about it answered (e.g. 'Is the generated image fully visible?', 'where is the send arrow?'). Use to find buttons or CONFIRM visual state when the element list has no labels. Coordinates in the answer are IMAGE px — tap them with tap_coordinates(space='image').",
            properties = MayaToolRegistry.props("question" to p("string", "What you want to know about the screen")),
            family = FAMILY
        ) { ctx, args ->
            if (!ScreenElementSerializer.isServiceOn()) return@register serviceOff()
            val q = str(args, "question", "query").ifEmpty { "What is on the screen right now?" }
            val a = VisionLookup.look(ctx, q)
            if (a.ok) ToolOutcome.ok(a.text + "\n[Coordinates above are IMAGE px of a ${a.imageWidth}x${a.imageHeight} screenshot. To tap one you MUST call tap_coordinates with space='image' — passing it raw taps the wrong place.]",
                JSONObject().put("image_width", a.imageWidth).put("image_height", a.imageHeight))
            else {
                val snap = ScreenElementSerializer.capture(ctx)
                val fallback = if (snap != null) "\nElement list instead:\n" + elementsText(snap, true) else ""
                ToolOutcome.fail(a.text + fallback)
            }
        }

        // ---------------- find_tool
        MayaToolRegistry.register(
            name = "find_tool",
            description = "Search the assistant's FULL tool catalog for a capability that is not in the tool list you can already see — alarms, torch, brightness, volume, clipboard, notes, calendar, smart home, macros, watches, contacts and more. ALWAYS call this before telling the user you cannot do something. Query in ENGLISH, describing the capability, e.g. 'turn on flashlight', 'read clipboard', 'add calendar event'. If nothing matches you get the tool families back — that means your words missed, NOT that the capability is absent: search once more with different words. Anything done on the phone's screen you can ALWAYS do yourself with run_agent_task. Never say the word 'tool' to the user.",
            properties = MayaToolRegistry.props("query" to p("string", "The capability you need, in English")),
            required = listOf("query"),
            family = FAMILY
        ) { ctx, args -> findTool(ctx, str(args, "query", "q", "text")) }

        // ---------------- watches
        MayaToolRegistry.register(
            name = "watch_for",
            description = "When the user says 'X ho to batana / bolna / bata dena', don't just say okay — arm a standing watch: kind screen_text (words appear on screen), app_opens (an app is opened), notification (a notification arrives, optionally containing value), screen_change (screen changes), battery (value like 'below 20' / 'full'), charger / bluetooth / wifi / headset (value 'in' or 'out', empty = either). When it happens you get '[SYSTEM EVENT] Jo baat Boss ne batane ko kaha tha wo ho gayi' — say it right away. Confirm in one line, then continue the normal conversation.",
            properties = MayaToolRegistry.props(
                "kind" to p("string", "What to watch", WatchCodec.KINDS),
                "value" to p("string", "Text / app name / condition to match (see kind)"),
                "reason" to p("string", "What to tell the user when it happens, in their words")
            ),
            required = listOf("kind"),
            family = FAMILY
        ) { ctx, args ->
            val (w, msg) = WatcherEngine.add(ctx, str(args, "kind", "type"), str(args, "value", "text", "app"), str(args, "reason", "message", "note"))
            if (w == null) ToolOutcome.fail(msg) else ToolOutcome.ok(msg, JSONObject().put("watch_id", w.id))
        }
        MayaToolRegistry.register(
            name = "cancel_watch",
            description = "Stop a watch — by its #id, a keyword from it, or 'all' to clear every watch.",
            properties = MayaToolRegistry.props("id" to p("string", "Watch #id, a keyword, or 'all'")),
            required = listOf("id"),
            family = FAMILY
        ) { ctx, args ->
            WatcherEngine.start(ctx)
            val sel = str(args, "id", "keyword", "query")
            val n = WatcherEngine.cancel(sel)
            if (n > 0) ToolOutcome.ok("$n watch hata diya. Bache: ${WatcherEngine.list().size}.") else ToolOutcome.fail("'$sel' se koi watch match nahi hua. list_watches se dekho.")
        }
        MayaToolRegistry.register(
            name = "list_watches",
            description = "List what the user has asked to be told about (the standing watches).",
            family = FAMILY
        ) { ctx, _ ->
            WatcherEngine.start(ctx)
            val list = WatcherEngine.list()
            if (list.isEmpty()) ToolOutcome.ok("Abhi koi watch nahi laga hai.")
            else ToolOutcome.ok("Active watches:\n" + list.joinToString("\n") { it.describe() }, JSONObject().put("count", list.size))
        }

        // ---------------- event triggers
        MayaToolRegistry.register(
            name = "set_event_trigger",
            description = "Turn an automatic phone-event announcement on or off. Use for 'charging wali baat mat bataya karo', 'battery low pe bata dena', 'headphone wala band kar do', 'sab event bata diya karo'. Put the user's own words in 'event' — 'charging', 'battery', 'headphone', 'bluetooth', 'wifi', 'airplane', 'silent', or 'sab' for all of them (exact ids also work: battery_low, battery_critical, battery_full, charger_in, charger_out, bluetooth_in, bluetooth_out, wifi_in, wifi_out, headset_in, headset_out, silent_on, silent_off, airplane_on, airplane_off). This only changes announcements; it never changes the phone setting itself. For a battery level ('battery 50% pe bata dena') pass event='battery low' and percent=50 — it is for the battery going DOWN only.",
            properties = MayaToolRegistry.props(
                "event" to p("string", "Event words or id, or 'sab'"),
                "enabled" to p("boolean", "true = announce it, false = stop announcing"),
                "percent" to p("integer", "Battery-low threshold percent (1-99), only with battery low")
            ),
            required = listOf("event", "enabled"),
            family = FAMILY
        ) { ctx, args -> setEventTrigger(ctx, args) }
        MayaToolRegistry.register(
            name = "list_event_triggers",
            description = "Read back which phone events you announce on your own — charger plugged in or out, battery full/low, headphones, Bluetooth, Wi-Fi, airplane mode, silent mode. Use when the user asks what you tell them about, or before changing one you're unsure of.",
            family = FAMILY
        ) { ctx, _ -> ToolOutcome.ok(eventTriggerSummary(ctx)) }

        // ---------------- screen awareness
        MayaToolRegistry.register(
            name = "screen_awareness",
            description = "Turn screen awareness on/off. When ON (and the live conversation is running) you get one quiet line '[SCREEN — Boss ne nahi bheja] Ab saamne: <app>' when the user switches apps (max once per app change, at most every 20 s) — use it to understand 'ye/isko/is app me'; do NOT comment on every app switch.",
            properties = MayaToolRegistry.props("enabled" to p("boolean", "true = on, false = off")),
            required = listOf("enabled"),
            family = FAMILY
        ) { ctx, args ->
            val on = bool(args, "enabled", true)
            ScreenPrefs.setScreenAwareness(ctx, on)
            WatcherEngine.start(ctx)
            ToolOutcome.ok(if (on) "Screen awareness ON — app badalne pe mujhe chupchaap pata chalega." else "Screen awareness OFF.")
        }

        // ---------------- macros
        MayaToolRegistry.register(
            name = "record_macro",
            description = "Start TRAINING a new task macro: from now on every action is recorded as a reusable routine. Use when the user says 'ye task seekho' / 'sikhao' / 'ye yaad karlo jab main bolu...'. TRAINING RULES: (1) do the steps the user asks by calling the REAL tools (open_app_by_name, tap_element, tap_text, type_text, wait_for_screen_text, go_back...), one at a time — the user may also demonstrate on the phone themselves; (2) if an instruction is unclear, ASK first — a wrong guess gets recorded as a wrong step; (3) after each action say ONE short line confirming what you did; (4) if the user corrects you ('galat', 'wo nahi'), call undo_last_step; (5) when they say they're done ('seekh liya'/'bas itna'), call save_macro; (6) call THIS tool exactly ONCE per training — if the result says a recording is already in progress, continue, never restart; (7) do NOT hand training steps to run_agent_task.",
            properties = MayaToolRegistry.props(
                "name" to p("string", "Name of the routine, e.g. 'good morning'"),
                "params" to p("string", "Optional fill-in slots, comma separated (e.g. 'message, contact')")
            ),
            required = listOf("name"),
            family = FAMILY
        ) { ctx, args ->
            val params = str(args, "params", "param_name", "param_names").split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            val (ok, msg) = MacroRecorder.start(ctx, str(args, "name", "macro"), params)
            if (ok) ToolOutcome.ok(msg) else ToolOutcome.fail(msg, JSONObject().put("recording", MacroRecorder.isRecording()))
        }
        MayaToolRegistry.register(
            name = "save_macro",
            description = "Finish TRAINING and save the recorded routine. Call when the user says 'seekh liya / bas / save kardo'.",
            properties = MayaToolRegistry.props(
                "param_values" to p("string", "For EVERY slot named in record_macro's params, the actual value you just used while training, as 'slot=value' pairs separated by ';' — e.g. \"message=good morning; contact=Amit\". These exact values get replaced by fill-in slots, so the macro asks for them next time. Empty only if the macro has no slots."),
                "intent" to p("string", "Optional trigger phrase the user will say, e.g. 'good morning wala kaam'")
            ),
            family = FAMILY
        ) { ctx, args ->
            val (ok, msg) = MacroRecorder.save(ctx, str(args, "param_values", "params"), str(args, "intent"))
            if (ok) ToolOutcome.ok(msg) else ToolOutcome.fail(msg)
        }
        MayaToolRegistry.register(
            name = "cancel_recording",
            description = "Throw away the macro currently being trained WITHOUT saving. Use ONLY if the user clearly means the WHOLE recording ('rehne do' / 'cancel kardo' / 'galti ho gayi'). For a single wrong step use undo_last_step instead. If steps are already recorded the tool first asks for confirmation — ask the user, and call it again with confirm=true only if they clearly repeat 'haan cancel karo'.",
            properties = MayaToolRegistry.props("confirm" to p("boolean", "true only after the user clearly confirmed")),
            family = FAMILY
        ) { _, args ->
            val (ok, msg) = MacroRecorder.cancel(bool(args, "confirm", false))
            if (ok) ToolOutcome.ok(msg) else ToolOutcome.fail(msg)
        }
        MayaToolRegistry.register(
            name = "undo_last_step",
            description = "Remove the LAST recorded step of the macro being trained (the user said 'galat' / 'wo nahi'). Then redo that step correctly.",
            family = FAMILY
        ) { _, _ -> ToolOutcome.ok(MacroRecorder.undoLast()) }
        MayaToolRegistry.register(
            name = "show_macro",
            description = "Show the step-by-step contents of ONE saved macro. Use when the user asks 'us task me kya kya karti ho?' / 'X wale task ke steps batao' — or to verify a macro after training.",
            properties = MayaToolRegistry.props("name" to p("string", "Macro name")),
            required = listOf("name"),
            family = FAMILY
        ) { ctx, args ->
            val name = str(args, "name", "macro")
            val m = repo(ctx).findMacroByName(name) ?: return@register ToolOutcome.fail("'$name' naam ka task mila nahi — list_macros se dekho kya kya sikhaya hai.")
            val kind = if (repo(ctx).isBuiltinMacro(m.name)) "built-in" else "learned"
            ToolOutcome.ok("'${m.name}' ($kind) — ${m.intent}\nParams: ${m.params.ifEmpty { listOf("none") }.joinToString()}\n" + MacroRecorder.format(m.steps))
        }
        MayaToolRegistry.register(
            name = "delete_macro",
            description = "Delete a saved task macro by name. Use when the user says 'wo task hata do' / 'bhool jao ye kaam'. Built-in routines cannot be deleted.",
            properties = MayaToolRegistry.props("name" to p("string", "Macro name")),
            required = listOf("name"),
            family = FAMILY
        ) { ctx, args ->
            val name = str(args, "name", "macro")
            val r = repo(ctx)
            val m = r.findMacroByName(name) ?: return@register ToolOutcome.fail("'$name' naam ka task mila nahi — list_macros se dekho.")
            if (r.isBuiltinMacro(m.name)) return@register ToolOutcome.fail("'${m.name}' built-in routine hai — delete nahi ho sakta.")
            if (r.deleteCustomMacro(m.name)) ToolOutcome.ok("'${m.name}' bhool gaya — delete ho gaya.") else ToolOutcome.fail("'${m.name}' delete nahi ho paaya.")
        }
        MayaToolRegistry.register(
            name = "list_macros",
            description = "List the trained task macros (learned) and the built-in routines with their fill-in params.",
            family = FAMILY
        ) { ctx, _ ->
            val r = repo(ctx)
            val all = r.getAllMacros()
            if (all.isEmpty()) return@register ToolOutcome.ok("Abhi koi routine nahi hai. Sikhane ke liye record_macro.")
            val (builtin, learned) = all.partition { r.isBuiltinMacro(it.name) }
            fun line(m: Macro) = "- ${m.name}" + (if (m.params.isNotEmpty()) " (params: ${m.params.joinToString()})" else "") + " — ${m.intent.take(80)}"
            val sb = StringBuilder()
            if (learned.isNotEmpty()) sb.append("Seekhe hue:\n").append(learned.joinToString("\n") { line(it) }).append('\n')
            if (builtin.isNotEmpty()) sb.append("Built-in:\n").append(builtin.joinToString("\n") { line(it) })
            if (MacroRecorder.isRecording()) sb.append("\n(Training chal rahi hai: '${MacroRecorder.recordingName()}', ${MacroRecorder.steps().size} steps)")
            ToolOutcome.ok(sb.toString().trim(), JSONObject().put("count", all.size))
        }
        MayaToolRegistry.register(
            name = "play_macro",
            description = "Replay a saved task macro by name. Use when the user triggers a trained routine, e.g. 'good morning wala kaam karo' / 'office nikalna chala'. If the macro has fill-in params, pass them as 'slot=value; slot2=value' (ask the user if you don't know a value).",
            properties = MayaToolRegistry.props(
                "name" to p("string", "Macro name"),
                "params" to p("string", "Fill-in values as 'slot=value; slot2=value' (a bare value is fine when the macro has one slot)")
            ),
            required = listOf("name"),
            family = FAMILY
        ) { ctx, args -> playMacro(ctx, args) }
    }

    // ------------------------------------------------------------------ run_agent_task

    private suspend fun runAgentTask(ctx: Context, args: JSONObject): ToolOutcome {
        if (!ScreenElementSerializer.isServiceOn()) return serviceOff()
        val goal = str(args, "goal", "task", "instruction", "text")
        if (goal.isEmpty()) return ToolOutcome.fail("Goal chahiye — screen pe kya karna hai?")
        if (MacroRecorder.isRecording()) {
            return ToolOutcome.fail("Training chal rahi hai ('${MacroRecorder.recordingName()}') — training ke steps run_agent_task se mat karwao; har step apne asli tool se (open_app_by_name / tap_element / type_text / wait_for_screen_text) ek-ek karke karo, warna replay pe kaam nahi karega.")
        }
        if (VerbLoopExecutor.running) return ToolOutcome.fail("Ek screen task pehle se chal raha hai (${VerbLoopExecutor.currentGoal.value ?: ""}). Wo khatam hone do ya cancel_active_task se roko.")
        val steps = VerbLoopExecutor.splitSteps(str(args, "steps", "plan"))
        val app = str(args, "app", "app_name").ifEmpty { null }
        if (ScreenRuntime.isLive()) {
            ScreenRuntime.scope.launch {
                val out = VerbLoopExecutor.run(ctx, goal, steps, app)
                val msg = when (out.status) {
                    VerbLoopExecutor.Status.ASK_USER ->
                        "[SYSTEM EVENT] Screen task ko Boss se ek jawab chahiye: ${out.question} — Boss se poochho; jawab mile to run_agent_task dobara chalao aur goal me wo detail daal do."
                    VerbLoopExecutor.Status.DONE ->
                        "[SYSTEM EVENT] Screen task result: ${out.toToolText()} — Boss ko ek line me bata do ki ho gaya."
                    else ->
                        "[SYSTEM EVENT] Screen task result (${out.status}): ${out.toToolText()} — Boss ko seedha batao kya hua aur kahan atka."
                }
                if (!ScreenRuntime.inject(msg)) ScreenRuntime.announceLocally(ctx, "Screen task", out.summary, speak = false)
            }
            return ToolOutcome.ok(
                "Screen task shuru kar diya: '$goal'. Main khud screen pe kar raha hoon — result [SYSTEM EVENT] me aayega. Tab tak Boss ko bas ek chhoti line bolo ki kaam chal raha hai; beech me koi aur screen tool mat chalao.",
                JSONObject().put("status", "started").put("background", true)
            )
        }
        val out = VerbLoopExecutor.run(ctx, goal, steps, app, timeBudgetMs = SYNC_BUDGET_MS)
        val extra = JSONObject().put("status", out.status.name.lowercase()).put("iterations", out.iterations)
        out.question?.let { extra.put("question", it) }
        return when (out.status) {
            VerbLoopExecutor.Status.DONE -> ToolOutcome.ok(out.toToolText(), extra)
            VerbLoopExecutor.Status.ASK_USER -> ToolOutcome.ok(out.toToolText() + "\nBoss se poochho, phir run_agent_task dobara chalao.", extra)
            else -> ToolOutcome.fail(out.toToolText(), extra)
        }
    }

    // ------------------------------------------------------------------ tap_element

    private suspend fun tapElementTool(ctx: Context, args: JSONObject): ToolOutcome {
        if (!ScreenElementSerializer.isServiceOn()) return serviceOff()
        val id = int(args, "id") ?: int(args, "element_id") ?: return ToolOutcome.fail("tap_element ko ek #id number chahiye — read_screen_elements se lo.")
        val last = ScreenElementSerializer.last ?: return ToolOutcome.fail("No element list has been read yet — call read_screen_elements first.")
        val fg = ScreenElementSerializer.foregroundPackage()
        if (fg.isNotEmpty() && fg != last.packageName) {
            val fresh = ScreenElementSerializer.capture(ctx)
            return ToolOutcome.fail("Didn't tap: the element list was read in ${last.appLabel} but the screen is now ${ScreenElementSerializer.appLabel(ctx, fg)}. Galat jagah tap karne se accha hai ki dobara dekh loon — fresh list below, use the NEW #id.\n" +
                (fresh?.let { elementsText(it, true) } ?: ""))
        }
        val el = last.byId[id] ?: return ToolOutcome.fail("#$id is not in the last element list (1..${last.elements.size}). read_screen_elements phir se call karo aur naya #id use karo.")
        // freshness: the node must still be there, at (about) the same place
        val node = el.node
        var target: SnapshotElement? = el
        val alive = try { node != null && node.refresh() } catch (_: Exception) { false }
        if (alive && node != null) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            if (abs(r.centerX() - el.line.x) > 60 || abs(r.centerY() - el.line.y) > 60) target = null
        } else {
            val fresh = ScreenElementSerializer.capture(ctx)
            target = fresh?.elements?.firstOrNull {
                it.line.label == el.line.label && it.line.role == el.line.role && abs(it.line.x - el.line.x) < 40 && abs(it.line.y - el.line.y) < 40
            }
        }
        if (target == null) {
            val fresh = ScreenElementSerializer.capture(ctx)
            return ToolOutcome.fail("Didn't tap: #$id ('${el.line.label}') now reads something else / has moved — the screen changed. Fresh list below, tap the NEW #id.\n" +
                (fresh?.let { elementsText(it, true) } ?: ""))
        }
        val before = ScreenElementSerializer.last ?: last
        val res = ScreenActions.tapElement(target)
        if (res.ok) recordTap(target)
        return afterTap(ctx, before, res, "Tapped #$id '${target.line.label}'")
    }

    // ------------------------------------------------------------------ open_settings

    private val SETTINGS_PANELS = listOf(
        "wifi", "bluetooth", "display", "sound", "battery", "apps", "accessibility", "notifications", "location", "date",
        "developer", "hotspot", "nfc", "airplane", "storage", "security", "language", "data", "vpn", "cast", "keyboard", "dnd", "main"
    )

    private fun settingsIntents(panel: String): List<Intent> {
        val p = panel.trim().lowercase().replace('-', ' ').replace('_', ' ')
        fun a(action: String) = Intent(action)
        return when {
            p.contains("wifi") || p.contains("wi fi") || p == "internet" -> listOf(a(Settings.ACTION_WIFI_SETTINGS), a(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            p.contains("bluetooth") || p == "bt" -> listOf(a(Settings.ACTION_BLUETOOTH_SETTINGS))
            p.contains("display") || p.contains("bright") || p.contains("screen") -> listOf(a(Settings.ACTION_DISPLAY_SETTINGS))
            p.contains("sound") || p.contains("volume") || p.contains("ring") -> listOf(a(Settings.ACTION_SOUND_SETTINGS), a(Settings.Panel.ACTION_VOLUME))
            p.contains("battery") || p.contains("power") -> listOf(a(Intent.ACTION_POWER_USAGE_SUMMARY), a(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            p.contains("notif") -> listOf(a("android.settings.NOTIFICATION_SETTINGS"), a(Settings.ACTION_APPLICATION_SETTINGS))
            p.contains("access") -> listOf(a(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            p.contains("app") -> listOf(a(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS), a(Settings.ACTION_APPLICATION_SETTINGS))
            p.contains("location") || p.contains("gps") -> listOf(a(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            p.contains("date") || p.contains("time") -> listOf(a(Settings.ACTION_DATE_SETTINGS))
            p.contains("develop") -> listOf(a(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            p.contains("hotspot") || p.contains("tether") -> listOf(
                Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
                a("android.settings.TETHER_SETTINGS"),
                a(Settings.ACTION_WIRELESS_SETTINGS)
            )
            p.contains("nfc") -> listOf(a(Settings.ACTION_NFC_SETTINGS), a(Settings.Panel.ACTION_NFC))
            p.contains("airplane") || p.contains("flight") -> listOf(a(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            p.contains("storage") -> listOf(a(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            p.contains("secur") || p.contains("lock") -> listOf(a(Settings.ACTION_SECURITY_SETTINGS))
            p.contains("lang") || p.contains("locale") -> listOf(a(Settings.ACTION_LOCALE_SETTINGS))
            p.contains("data") || p.contains("mobile") || p.contains("sim") -> listOf(a(Settings.ACTION_DATA_ROAMING_SETTINGS), a(Settings.ACTION_WIRELESS_SETTINGS))
            p.contains("vpn") -> listOf(a(Settings.ACTION_VPN_SETTINGS))
            p.contains("cast") -> listOf(a(Settings.ACTION_CAST_SETTINGS))
            p.contains("keyboard") || p.contains("input") -> listOf(a(Settings.ACTION_INPUT_METHOD_SETTINGS))
            p.contains("dnd") || p.contains("disturb") || p.contains("zen") -> listOf(a("android.settings.ZEN_MODE_SETTINGS"), a(Settings.ACTION_SOUND_SETTINGS))
            else -> listOf(a(Settings.ACTION_SETTINGS))
        }
    }

    private suspend fun openSettings(ctx: Context, panel: String): ToolOutcome {
        val name = panel.ifBlank { "main" }
        var launched = false
        for (intent in settingsIntents(name)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolvable = try { intent.resolveActivity(ctx.packageManager) != null } catch (_: Exception) { false }
            if (!resolvable) continue
            if (try { ActivityLauncherHelper.startActivitySafely(ctx, intent) } catch (_: Exception) { false }) {
                launched = true
                break
            }
        }
        if (!launched) return ToolOutcome.fail("'$name' settings nahi khul paayi. open_app_by_name('Settings') try karo, phir run_agent_task se raasta dhoondo.")
        if (MacroRecorder.isRecording()) MacroRecorder.recordTool("open_app_by_name", mapOf("name" to "Settings", "_pkg" to "com.android.settings"))
        if (!ScreenElementSerializer.isServiceOn()) return ToolOutcome.ok("'$name' settings khol di. (${ScreenElementSerializer.SERVICE_OFF} — switch user ko khud dabana hoga.)")
        val deadline = System.currentTimeMillis() + 3_000L
        var fg = ""
        while (System.currentTimeMillis() < deadline) {
            fg = ScreenElementSerializer.foregroundPackage()
            if (fg.contains("settings", ignoreCase = true)) break
            delay(250)
        }
        if (!fg.contains("settings", ignoreCase = true)) {
            return ToolOutcome.ok("'$name' settings launch kar di par wo abhi screen pe nahi dikhi. [NOTE — ye user ko mat bolo] Kuch tap mat karo, pehle read_screen_elements se confirm karo.")
        }
        ScreenActions.waitForSettle(1_200L)
        val snap = ScreenElementSerializer.capture(ctx)
        val list = if (snap != null) "\n" + elementsText(snap, true) else ""
        return ToolOutcome.ok("'$name' settings screen pe aa gayi. [NOTE — ye user ko mat bolo] Ab switch khud dabao: read_screen_elements → toggle ka [currently ON/OFF] dekho → zarurat ho to tap_element(id=N). Panel khol dena kaam poora nahi hai; user ko sirf final natija batao.$list")
    }

    // ------------------------------------------------------------------ find_tool

    private fun paramsOf(properties: JSONObject?, required: List<String>): List<String> {
        if (properties == null) return emptyList()
        val out = ArrayList<String>()
        val keys = properties.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val type = properties.optJSONObject(k)?.optString("type")?.ifBlank { "any" } ?: "any"
            out.add(if (k in required) "$k:$type" else "$k?:$type")
        }
        return out
    }

    private fun requiredOf(arr: JSONArray?): List<String> = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }

    fun catalog(ctx: Context): List<ToolCatalogEntry> {
        val entries = LinkedHashMap<String, ToolCatalogEntry>()
        for (t in MayaToolRegistry.all()) {
            entries[t.name] = ToolCatalogEntry(t.name, t.description, t.family, paramsOf(t.properties, t.required))
        }
        try {
            for (s in JarvisToolDispatcher.getInstance(ctx).getAvailableToolSchemas(true)) {
                val f = s.optJSONObject("function") ?: continue
                val n = f.optString("name")
                if (n.isBlank() || entries.containsKey(n)) continue
                val params = f.optJSONObject("parameters")
                entries[n] = ToolCatalogEntry(n, f.optString("description"), "builtin", paramsOf(params?.optJSONObject("properties"), requiredOf(params?.optJSONArray("required"))))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "builtin catalog unavailable: ${e.message}")
        }
        return entries.values.toList()
    }

    private fun findTool(ctx: Context, query: String): ToolOutcome {
        if (query.isBlank()) return ToolOutcome.fail("query chahiye — capability English me likho, jaise 'turn on flashlight'.")
        val cat = catalog(ctx).filter { it.name != "find_tool" }
        // ToolFinder (synonyms + name tokens) merged with the registry's own keyword search
        val byName = cat.associateBy { it.name }
        val merged = LinkedHashMap<String, Int>()
        ToolFinder.search(query, cat, 12).forEach { merged[it.entry.name] = it.score }
        try {
            val builtinPairs = cat.filter { it.family == "builtin" }.map { it.name to it.description }
            for ((n, _, sc) in MayaToolRegistry.search(query, builtinPairs, 12)) {
                if (byName.containsKey(n)) merged[n] = (merged[n] ?: 0) + sc
            }
        } catch (_: Throwable) {}
        val hits = merged.entries.sortedByDescending { it.value }.take(6)
            .mapNotNull { e -> byName[e.key]?.let { ToolMatch(it, e.value) } }
        if (hits.isEmpty()) {
            return ToolOutcome.ok(
                "Is query se kuch match nahi hua — iska matlab tumhare shabd chook gaye, capability gayab nahi. Ab isi order me koshish karo: (1) neeche wali kisi family ke shabd le kar find_tool ek baar aur chalao; (2) kaam phone pe hota hai (koi setting on/off, kisi app me kuch dabana/likhna) to run_agent_task se KHUD screen pe kar do — zaroorat ho to pehle open_settings ya open_app_by_name se sahi jagah kholo; (3) jaankari chahiye to search_web. User ko 'tool' shabd kabhi mat bolo.\nFamilies:\n" + ToolFinder.familiesSummary(cat),
                JSONObject().put("matches", JSONArray())
            )
        }
        val arr = JSONArray()
        hits.forEach { arr.put(JSONObject().put("name", it.entry.name).put("score", it.score)) }
        val top = hits.first()
        val header = if (top.score >= 15) "Ye mile (seedha naam se call karo):" else
            "Exact match nahi mila, par ye MILTE-JULTE hain. Jo kaam ka lage use chalao. Koi fit na ho aur kaam phone ki screen pe hota hai to run_agent_task se KHUD kar do; info chahiye to search_web. User ko 'tool' shabd kabhi mat bolo:"
        return ToolOutcome.ok(header + "\n" + hits.joinToString("\n") { "- " + ToolFinder.usageLine(it.entry) }, JSONObject().put("matches", arr))
    }

    // ------------------------------------------------------------------ event triggers

    private fun setEventTrigger(ctx: Context, args: JSONObject): ToolOutcome {
        val words = str(args, "event", "events", "name")
        val enabled = bool(args, "enabled", true)
        val ids = EventTriggers.resolve(words)
        if (ids.isEmpty()) {
            return ToolOutcome.fail("'$words' koi phone event nahi hai jo main announce kar sakun. Ye ho sakte hain: ${EventTriggers.ALL.joinToString { it.id }}. Is ke alawa kuch announce karne ka vaada mat karo.")
        }
        val all = ids.size == EventTriggers.ALL.size
        ids.forEach { ScreenPrefs.setEventEnabled(ctx, it, enabled) }
        if (enabled) ScreenPrefs.setEventsMaster(ctx, true)
        else if (all) ScreenPrefs.setEventsMaster(ctx, false)
        val pct = int(args, "percent") ?: int(args, "level")
        if (pct != null && ids.contains("battery_low")) ScreenPrefs.setBatteryLowPercent(ctx, pct)
        WatcherEngine.start(ctx)
        val names = ids.mapNotNull { EventTriggers.byId(it)?.label }.joinToString()
        val pctNote = if (pct != null && ids.contains("battery_low")) " Battery ${ScreenPrefs.batteryLowPercent(ctx)}% se neeche jaane pe bataunga." else ""
        return ToolOutcome.ok((if (enabled) "Theek hai, ab ye bataunga: " else "Theek hai, ye ab nahi bataunga: ") + names + "." + pctNote,
            JSONObject().put("events", JSONArray(ids)).put("enabled", enabled))
    }

    fun eventTriggerSummary(ctx: Context): String {
        if (!ScreenPrefs.eventsMaster(ctx)) return "Event announcements abhi poori tarah band hain. User bole to set_event_trigger se 'sab' on kar do."
        val on = EventTriggers.ALL.filter { ScreenPrefs.isEventEnabled(ctx, it.id) }
        val off = EventTriggers.ALL.filterNot { ScreenPrefs.isEventEnabled(ctx, it.id) }
        return "Announce karta hoon: " + (if (on.isEmpty()) "kuch nahi" else on.joinToString { it.label }) +
            "\nBand: " + (if (off.isEmpty()) "kuch nahi" else off.joinToString { it.label }) +
            "\nBattery-low threshold: ${ScreenPrefs.batteryLowPercent(ctx)}% (abhi ${WatcherEngine.batterySummary()})"
    }

    // ------------------------------------------------------------------ play_macro

    fun parsePlayParams(raw: Any?, macro: Macro): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        when (raw) {
            is JSONObject -> raw.keys().forEach { k -> out[k] = raw.optString(k) }
            is String -> {
                val s = raw.trim()
                if (s.startsWith("{")) {
                    try { val o = JSONObject(s); o.keys().forEach { k -> out[k] = o.optString(k) } } catch (_: Exception) {}
                } else if (s.isNotEmpty()) {
                    out.putAll(MacroRecorder.parseParamValues(s, macro.params))
                }
            }
        }
        // tolerate slot spelled with underscores / different case
        val fixed = LinkedHashMap<String, String>()
        for ((k, v) in out) {
            val match = macro.params.firstOrNull { it.equals(k, true) || it.replace(' ', '_').equals(k.replace(' ', '_'), true) } ?: k
            fixed[match] = v
        }
        return fixed
    }

    private suspend fun playMacro(ctx: Context, args: JSONObject): ToolOutcome {
        val name = str(args, "name", "macro")
        val macro = repo(ctx).findMacroByName(name) ?: return ToolOutcome.fail("'$name' naam ka task mila nahi — list_macros se dekho kya kya sikhaya hai.")
        val params = parsePlayParams(if (args.has("params")) args.opt("params") else null, macro)
        val missing = macro.params.filter { p -> params[p].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            return ToolOutcome.fail("'${macro.name}' ko ye value chahiye: ${missing.joinToString()}. Boss se poochho aur `play_macro` dobara call karo (param me '${missing.first()}=...').")
        }
        if (macro.steps.any { it.tool != "wait" } && !ScreenElementSerializer.isServiceOn()) return serviceOff()
        if (ScreenRuntime.isLive()) {
            ScreenRuntime.scope.launch {
                val res = runMacro(ctx, macro, params)
                if (!ScreenRuntime.inject("[SYSTEM EVENT] Routine '${macro.name}' result: $res — Boss ko ek line me batao.")) {
                    ScreenRuntime.announceLocally(ctx, "Routine ${macro.name}", res, speak = false)
                }
            }
            return ToolOutcome.ok("'${macro.name}' chala diya (${macro.steps.size} steps) — khatam hote hi result [SYSTEM EVENT] me aayega.", JSONObject().put("background", true))
        }
        val res = runMacro(ctx, macro, params)
        return if (res.startsWith("OK")) ToolOutcome.ok(res) else ToolOutcome.fail(res)
    }

    private suspend fun runMacro(ctx: Context, macro: Macro, params: Map<String, String>): String {
        ScreenRuntime.prepareUiActions()
        return try {
            when (val r = engine(ctx).executeMacro(macro, params)) {
                is MacroExecutionResult.Success -> {
                    val readsScreen = macro.steps.lastOrNull { it.tool != "wait" }?.tool == "read_screen_text"
                    val text = if (readsScreen) ScreenElementSerializer.allVisibleText().joinToString("\n").take(2500) else ""
                    "OK — '${macro.name}' ho gaya." + if (text.isNotBlank()) "\nScreen pe ab:\n$text" else ""
                }
                is MacroExecutionResult.Failure -> "'${macro.name}' beech me atak gaya: ${r.error}. Screen dekh ke (read_screen_elements) baaki kaam run_agent_task se poora kar sakte ho."
            }
        } catch (e: Exception) {
            "'${macro.name}' chalane me error: ${e.message}"
        }
    }
}
