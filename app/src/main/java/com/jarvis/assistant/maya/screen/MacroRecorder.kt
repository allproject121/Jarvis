package com.jarvis.assistant.maya.screen

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.Gson
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.macro.Macro
import com.jarvis.assistant.automation.macro.MacroRepository
import com.jarvis.assistant.automation.macro.MacroStep
import java.io.File

/**
 * Maya `record_macro` training: while recording, the user's (or the assistant's own) actions are
 * turned into [MacroStep]s that [com.jarvis.assistant.automation.macro.MacroEngine] can replay:
 * open_app_by_name / tap_text / tap_resource_id / tap_coords / type_text / wait_for_screen_text /
 * go_back / go_home / scroll. Accessibility click + text events arrive through [ScreenEventBus];
 * the screen tools record their own steps explicitly. The in-progress recording is persisted so
 * a session restart continues the same training.
 */
object MacroRecorder {

    private const val TAG = "MayaMacroRecorder"
    private const val STATE_FILE = "maya_recording.json"
    private const val SUPPRESS_MS = 1_300L

    private data class State(
        val name: String,
        val params: List<String>,
        val steps: List<MacroStep>,
        val startedAt: Long,
        val lastOpenedPkg: String
    )

    private val gson = Gson()
    private val lock = Any()
    private var state: State? = null
    @Volatile private var suppressUntil = 0L
    @Volatile private var restored = false
    @Volatile private var addedEventBits = 0
    /** field key of the last type_text step (for coalescing keystrokes into one step). */
    private var lastTypeFieldKey: String? = null

    fun isRecording(): Boolean = synchronized(lock) { state != null }

    fun recordingName(): String? = synchronized(lock) { state?.name }

    fun steps(): List<MacroStep> = synchronized(lock) { state?.steps ?: emptyList() }

    // ------------------------------------------------------------------ persistence

    private fun stateFile(ctx: Context) = File(ctx.filesDir, STATE_FILE)

    fun restore(context: Context) {
        if (restored) return
        restored = true
        try {
            val f = stateFile(context.applicationContext)
            if (!f.exists()) return
            val s = gson.fromJson(f.readText(), State::class.java) ?: return
            if (s.name.isBlank()) return
            synchronized(lock) { state = s.copy(steps = s.steps ?: emptyList(), params = s.params ?: emptyList(), lastOpenedPkg = s.lastOpenedPkg ?: "") }
            enableEventTypes(true)
            Log.i(TAG, "Restored in-progress recording '${s.name}' with ${s.steps?.size ?: 0} steps")
        } catch (e: Exception) {
            Log.w(TAG, "restore failed: ${e.message}")
        }
    }

    private fun persist() {
        val ctx = ScreenRuntime.appContext() ?: return
        try {
            val s = synchronized(lock) { state }
            val f = stateFile(ctx)
            if (s == null) f.delete() else f.writeText(gson.toJson(s))
        } catch (e: Exception) {
            Log.w(TAG, "persist failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts training. Returns (started, message). Exactly once per training (Maya rule 6). */
    fun start(context: Context, name: String, params: List<String>): Pair<Boolean, String> {
        restore(context)
        val clean = name.trim()
        synchronized(lock) {
            val cur = state
            if (cur != null) {
                return false to "Training pehle se chal rahi hai: '${cur.name}' (${cur.steps.size} step ho chuke hain). Naya record_macro mat call karo — jahan chhoda tha wahin se continue karo. Jab Boss 'seekh liya / bas / save kardo' bole tab save_macro karna."
            }
            if (clean.isEmpty()) return false to "Macro ka naam chahiye (jaise 'good morning')."
            state = State(clean, params.map { it.trim() }.filter { it.isNotEmpty() }.distinct(), emptyList(), System.currentTimeMillis(), "")
            lastTypeFieldKey = null
        }
        persist()
        enableEventTypes(true)
        val serviceNote = if (!ScreenElementSerializer.isServiceOn()) "\n(Warning: ${ScreenElementSerializer.SERVICE_OFF} — tab tak sirf tools wale steps record honge.)" else ""
        return true to "Training shuru: '$clean'. Ab har step ek-ek karke karo (asli tools se, ya Boss khud phone pe karke dikhayein) — main record kar raha hoon. Galat step ho to undo_last_step, ho jaye to save_macro.$serviceNote"
    }

    fun undoLast(): String {
        var removed: MacroStep? = null
        synchronized(lock) {
            val cur = state ?: return "Abhi koi training chal hi nahi rahi thi — kuch seekhna ho to pehle record_macro se training shuru karo."
            if (cur.steps.isEmpty()) return "Abhi tak koi step record nahi hua."
            val list = cur.steps.toMutableList()
            val dropped = list.removeAt(list.size - 1)
            removed = dropped
            // also drop the auto-inserted optional wait that guarded a removed tap
            if (dropped.tool == "tap_text" && list.isNotEmpty() && list.last().tool == "wait_for_screen_text" && list.last().isOptional) {
                list.removeAt(list.size - 1)
            }
            state = cur.copy(steps = list)
            lastTypeFieldKey = null
        }
        persist()
        return "Last step hata diya: ${describe(removed!!)}. Ab ${steps().size} step bache."
    }

    fun cancel(confirm: Boolean): Pair<Boolean, String> {
        val n = steps().size
        if (!isRecording()) return false to "Abhi koi training chal hi nahi rahi thi."
        if (n > 0 && !confirm) {
            return false to "$n step record ho chuke hain, cancel karne pe sab chale jayenge. User se PAKKA poochho; wo dobara saaf-saaf 'haan cancel karo' bole tabhi cancel_recording(confirm=true) call karna. Save karna ho to save_macro."
        }
        synchronized(lock) { state = null; lastTypeFieldKey = null }
        persist()
        enableEventTypes(false)
        return true to "Training cancel kar di — kuch save nahi hua."
    }

    /**
     * Saves the macro. [paramValues] = "slot=value; slot2=value" — those exact values become
     * {slot} placeholders so play_macro asks for them next time.
     */
    fun save(context: Context, paramValues: String?, intent: String?): Pair<Boolean, String> {
        val cur = synchronized(lock) { state } ?: return false to "Abhi koi training chal hi nahi rahi thi — kuch seekhna ho to pehle `record_macro` se training shuru karo, phir steps karke dikhane ke baad save karna."
        if (cur.steps.isEmpty()) return false to "Koi step record nahi hua — pehle kaam karke dikhao, phir save_macro."
        val values = parseParamValues(paramValues, cur.params)
        val params = (cur.params + values.keys).distinct()
        val steps = cur.steps.map { step ->
            if (step.tool != "type_text" || values.isEmpty()) step else {
                var text = step.args["text"]?.toString() ?: ""
                for ((slot, value) in values) if (value.isNotBlank()) text = text.replace(value, "{$slot}")
                MacroStep(step.tool, step.args + ("text" to text), step.why)
            }
        }
        val macro = Macro(name = cur.name, intent = intent?.takeIf { it.isNotBlank() } ?: cur.name, params = params, steps = steps)
        val ok = try {
            val repo: MacroRepository = try { JarvisApp.instance.macroRepository } catch (_: Throwable) { MacroRepository(context.applicationContext) }
            repo.saveCustomMacro(macro)
            true
        } catch (e: Exception) {
            Log.e(TAG, "save failed: ${e.message}", e)
            false
        }
        if (!ok) return false to "Macro save nahi ho paaya."
        synchronized(lock) { state = null; lastTypeFieldKey = null }
        persist()
        enableEventTypes(false)
        val p = if (params.isEmpty()) "" else " Params: ${params.joinToString()} (play_macro me in ki value deni hogi)."
        return true to "Seekh liya! '${macro.name}' save ho gaya — ${steps.size} steps.$p Chalane ke liye: play_macro(name='${macro.name}').\n" + format(steps)
    }

    fun parseParamValues(raw: String?, declared: List<String>): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val pairs = raw.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        for (p in pairs) {
            val i = p.indexOf('=')
            if (i > 0) out[p.substring(0, i).trim().replace(' ', '_')] = p.substring(i + 1).trim()
            else if (declared.size == 1 && pairs.size == 1) out[declared[0]] = p
        }
        return out
    }

    // ------------------------------------------------------------------ explicit (tool) steps

    /** Called by the screen tools after a successful action; suppresses the duplicate a11y event. */
    fun recordTool(tool: String, args: Map<String, Any?>, why: String? = null) {
        if (!isRecording()) return
        suppressUntil = System.currentTimeMillis() + SUPPRESS_MS
        synchronized(lock) {
            val cur = state ?: return
            val list = cur.steps.toMutableList()
            var opened = cur.lastOpenedPkg
            if (tool == "tap_text") {
                val t = args["text"]?.toString().orEmpty()
                if (t.isNotBlank()) list.add(MacroStep("wait_for_screen_text", mapOf("text" to t, "mode" to "appear", "timeout_seconds" to 15, "_optional" to true), "wait until it shows"))
            }
            if (tool == "open_app_by_name") opened = args["_pkg"]?.toString().orEmpty()
            list.add(MacroStep(tool, args.filterKeys { !it.startsWith("_pkg") }, why))
            state = cur.copy(steps = list, lastOpenedPkg = opened)
            lastTypeFieldKey = null
        }
        persist()
    }

    // ------------------------------------------------------------------ accessibility events

    /** Hook target (via [ScreenEventBus]); runs on the accessibility thread, must be cheap. */
    fun onEvent(event: AccessibilityEvent) {
        if (!isRecording()) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED && type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (System.currentTimeMillis() < suppressUntil) return
        if (VerbLoopExecutor.running) return
        val ctx = ScreenRuntime.appContext() ?: return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == ctx.packageName || pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        val src: AccessibilityNodeInfo? = try { event.source } catch (_: Exception) { null }
        val className = event.className?.toString().orEmpty()
        try {
            when (type) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    if (className.contains("EditText") || src?.isEditable == true) return // typing follows
                    val label = labelFor(event, src)
                    val resId = src?.viewIdResourceName
                    val rect = Rect()
                    try { src?.getBoundsInScreen(rect) } catch (_: Exception) {}
                    addUserStep(ctx, pkg) { list ->
                        when {
                            label.isNotBlank() -> {
                                list.add(MacroStep("wait_for_screen_text", mapOf("text" to label, "mode" to "appear", "timeout_seconds" to 15, "_optional" to true), "wait until it shows"))
                                list.add(MacroStep("tap_text", mapOf("text" to label), "user tapped"))
                            }
                            !resId.isNullOrBlank() -> list.add(MacroStep("tap_resource_id", mapOf("id" to resId), "user tapped an unlabeled control"))
                            rect.width() > 0 -> list.add(MacroStep("tap_coords", mapOf("x" to rect.centerX(), "y" to rect.centerY()), "user tapped an unlabeled spot"))
                        }
                    }
                    lastTypeFieldKey = null
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val text = event.text?.joinToString("")?.toString().orEmpty()
                    val fieldKey = pkg + "|" + (src?.viewIdResourceName ?: className)
                    val value = if (event.isPassword) "{password}" else text
                    synchronized(lock) {
                        val cur = state ?: return
                        val list = cur.steps.toMutableList()
                        var opened = cur.lastOpenedPkg
                        var params = cur.params
                        if (lastTypeFieldKey == fieldKey && list.isNotEmpty() && list.last().tool == "type_text") {
                            list[list.size - 1] = MacroStep("type_text", mapOf("text" to value), "user typed")
                        } else {
                            val newlyOpened = ensureOpenStep(ctx, pkg, list, cur)
                            if (newlyOpened != null) opened = newlyOpened
                            list.add(MacroStep("type_text", mapOf("text" to value), "user typed"))
                        }
                        if (event.isPassword && "password" !in params) params = params + "password"
                        state = cur.copy(steps = list, lastOpenedPkg = opened, params = params)
                        lastTypeFieldKey = fieldKey
                    }
                    persist()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "onEvent: ${e.message}")
        }
    }

    private fun addUserStep(ctx: Context, pkg: String, add: (MutableList<MacroStep>) -> Unit) {
        synchronized(lock) {
            val cur = state ?: return
            val list = cur.steps.toMutableList()
            val opened = ensureOpenStep(ctx, pkg, list, cur)
            val before = list.size
            add(list)
            if (list.size == before && opened == null) return
            state = cur.copy(steps = list, lastOpenedPkg = opened ?: cur.lastOpenedPkg)
        }
        persist()
    }

    /** Inserts open_app_by_name when the user acts inside an app we haven't opened yet. */
    private fun ensureOpenStep(ctx: Context, pkg: String, list: MutableList<MacroStep>, cur: State): String? {
        if (pkg == cur.lastOpenedPkg || ScreenActions.isLauncher(pkg)) return null
        val launchable = try { ctx.packageManager.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false }
        if (!launchable) return null
        val label = ScreenElementSerializer.appLabel(ctx, pkg)
        list.add(MacroStep("open_app_by_name", mapOf("name" to label), "open the app"))
        list.add(MacroStep("wait", mapOf("seconds" to 2), "let it load"))
        return pkg
    }

    private fun labelFor(event: AccessibilityEvent, src: AccessibilityNodeInfo?): String {
        val t = event.text?.joinToString(" ")?.trim().orEmpty()
        if (t.isNotEmpty()) return t.take(60)
        val d = event.contentDescription?.toString()?.trim().orEmpty()
        if (d.isNotEmpty()) return d.take(60)
        if (src != null) {
            val st = src.text?.toString()?.trim().orEmpty().ifEmpty { src.contentDescription?.toString()?.trim().orEmpty() }
            if (st.isNotEmpty()) return st.take(60)
            try {
                for (i in 0 until src.childCount) {
                    val c = src.getChild(i) ?: continue
                    val ct = c.text?.toString()?.trim().orEmpty().ifEmpty { c.contentDescription?.toString()?.trim().orEmpty() }
                    if (ct.isNotEmpty()) return ct.take(60)
                }
            } catch (_: Exception) {}
        }
        return ""
    }

    @Volatile private var lastEnsureAt = 0L

    /**
     * Re-applies the recording event bits if the accessibility service (re)connected after the
     * training started. Throttled; called from [ScreenEventBus] on window changes.
     */
    fun ensureEventTypes() {
        if (!isRecording()) return
        val now = System.currentTimeMillis()
        if (now - lastEnsureAt < 10_000L) return
        lastEnsureAt = now
        enableEventTypes(true)
    }

    /** Adds TYPE_VIEW_CLICKED | TYPE_VIEW_TEXT_CHANGED to the service while recording. */
    fun enableEventTypes(on: Boolean) {
        val svc = JarvisAccessibilityService.instance ?: return
        try {
            val info: AccessibilityServiceInfo = svc.serviceInfo ?: return
            val bits = AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            if (on) {
                val missing = bits and info.eventTypes.inv()
                if (missing != 0) {
                    info.eventTypes = info.eventTypes or missing
                    svc.serviceInfo = info
                    addedEventBits = addedEventBits or missing
                }
            } else if (addedEventBits != 0) {
                info.eventTypes = info.eventTypes and addedEventBits.inv()
                svc.serviceInfo = info
                addedEventBits = 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "enableEventTypes failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ formatting

    fun describe(step: MacroStep): String {
        val a = step.args
        val opt = if (step.isOptional) " (optional)" else ""
        return when (step.tool) {
            "open_app_by_name" -> "open '${a["name"]}'"
            "tap_text" -> "tap '${a["text"]}'"
            "tap_resource_id" -> "tap [id:${a["id"]?.toString()?.substringAfterLast('/')}]"
            "tap_coords" -> "tap at (${num(a["x"])},${num(a["y"])})"
            "type_text" -> "type '${a["text"]}'"
            "wait" -> "wait ${num(a["seconds"])}s"
            "wait_for_screen_text" -> "wait for '${a["text"]}' to ${a["mode"] ?: "appear"}$opt"
            "go_back" -> "back"
            "go_home" -> "home"
            "scroll" -> "scroll ${a["direction"] ?: "down"}"
            "read_screen_text" -> "read the screen"
            else -> step.tool + " " + a.filterKeys { !it.startsWith("_") }
        }
    }

    private fun num(v: Any?): String = when (v) {
        is Number -> if (v.toDouble() == Math.floor(v.toDouble())) v.toLong().toString() else v.toString()
        null -> "?"
        else -> v.toString()
    }

    /** Numbered list, hiding the auto-inserted optional waits. */
    fun format(steps: List<MacroStep>): String {
        val visible = steps.filterNot { it.tool == "wait_for_screen_text" && it.isOptional }
        return visible.mapIndexed { i, s -> "${i + 1}. ${describe(s)}" }.joinToString("\n")
    }
}
