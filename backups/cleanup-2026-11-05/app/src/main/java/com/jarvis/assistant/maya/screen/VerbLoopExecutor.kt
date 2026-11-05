package com.jarvis.assistant.maya.screen

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ai.AiTextSynthesizer
import com.jarvis.assistant.automation.agent.AgentTaskEngine
import com.jarvis.assistant.automation.agent.ScreenSignature
import com.jarvis.assistant.automation.agent.SerializedActionExecutor
import com.jarvis.assistant.automation.agent.TaskGoal
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.ui.agent.TaskProgressOverlay
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.coroutineContext

/**
 * Maya's LLM-driven screen verb loop: read the accessibility tree → ask the text LLM for ONE
 * JSON verb → execute it → verify the screen changed → repeat, until task_done / ask_user /
 * a guard trips. Progress is published to [AgentTaskEngine] so Jarvis' TaskProgressOverlay
 * shows the steps, and a live step log is exposed for the Macros & Watches "Automation lab".
 */
object VerbLoopExecutor {

    private const val TAG = "MayaVerbLoop"
    private const val RECENT_ACTIONS = 8
    private const val MAX_PARSE_FAILURES = 3
    private const val NO_CHANGE_VISION = 5
    private const val NO_CHANGE_FAIL = 12

    enum class Status { DONE, ASK_USER, FAILED, CANCELLED, LIMIT, BUSY }

    data class Outcome(
        val status: Status,
        val summary: String,
        val question: String? = null,
        val iterations: Int = 0,
        val says: List<String> = emptyList(),
        val actions: List<String> = emptyList()
    ) {
        fun toToolText(): String {
            val sb = StringBuilder(summary)
            if (says.isNotEmpty()) sb.append("\nNotes: ").append(says.takeLast(4).joinToString(" | "))
            if (actions.isNotEmpty() && status != Status.DONE) sb.append("\nLast actions:\n").append(actions.takeLast(5).joinToString("\n"))
            return sb.toString()
        }
    }

    private val mutex = Mutex()

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    private var cancelRequested = false

    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** Live step log (Automation lab). */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _currentGoal = MutableStateFlow<String?>(null)
    val currentGoal: StateFlow<String?> = _currentGoal.asStateFlow()

    fun cancel() {
        cancelRequested = true
        if (running) {
            try { AgentTaskEngine.requestCancellation() } catch (_: Throwable) {}
        }
    }

    private fun logLine(line: String) {
        Log.i(TAG, line)
        val next = (_log.value + line)
        _log.value = if (next.size > 200) next.takeLast(200) else next
    }

    /** Splits a free-form plan ("1. open X\n2. tap Y" or "a; b; c") into steps. */
    fun splitSteps(raw: String?): List<String> = VerbParser.splitSteps(raw)

    suspend fun run(
        context: Context,
        goal: String,
        steps: List<String> = emptyList(),
        app: String? = null,
        maxIterations: Int? = null,
        timeBudgetMs: Long = 10 * 60_000L
    ): Outcome {
        val ctx = context.applicationContext
        if (!ScreenElementSerializer.isServiceOn()) return Outcome(Status.FAILED, ScreenElementSerializer.SERVICE_OFF)
        if (goal.isBlank()) return Outcome(Status.FAILED, "Goal khaali hai — kya karna hai wo batao.")
        if (!mutex.tryLock()) return Outcome(Status.BUSY, "Ek screen task pehle se chal raha hai (${_currentGoal.value ?: "?"}) — usko khatam hone do ya cancel karo.")
        running = true
        cancelRequested = false
        _log.value = emptyList()
        _currentGoal.value = goal
        var overlayJob: Job? = null
        try {
            val assistant = ScreenElementSerializer.assistantName()
            val plan = if (steps.isNotEmpty()) steps else listOf(goal.trim())
            val bulk = try { TaskGoal.fromUserUtterance(goal).isBulkAction } catch (_: Throwable) { false }
            val maxTurns = (maxIterations ?: if (bulk) 400 else 40).coerceIn(1, 400)
            val startedAt = System.currentTimeMillis()

            // ---- publish to AgentTaskEngine (overlay)
            val task = AgentTaskEngine.begin(goal, "maya_screen")
            val myTaskId = task.taskId
            val generation = task.actionGeneration
            val titles = plan.mapIndexed { i, s -> "${i + 1}. ${s.take(70)}" }
            try { AgentTaskEngine.replacePlan(titles) } catch (_: Throwable) {}
            if (!JarvisConversationService.isServiceRunning()) {
                overlayJob = ScreenRuntime.scope.launch {
                    try {
                        AgentTaskEngine.taskState.collect { snap ->
                            try { TaskProgressOverlay.getInstance(ctx).update(snap) } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {}
                }
            }
            logLine("▶ Goal: $goal" + if (plan.size > 1) " (${plan.size} steps)" else "")

            val recent = ArrayList<String>()
            val says = ArrayList<String>()
            var stepIndex = 0
            var parseFailures = 0
            var noChangeStreak = 0
            var lastKey: String? = null
            var lastNoChange = false
            var repeatBlocks = 0
            var scrollNoChange = 0
            var visionNote: String? = null
            var visionFirstAttempts = 0
            var lastVisionSignature: ScreenSignature? = null
            val pendingNotes = ArrayList<String>()
            var actionNo = 0

            fun cancelled(): Boolean = cancelRequested ||
                AgentTaskEngine.isCancelled() ||
                AgentTaskEngine.current()?.taskId != myTaskId

            suspend fun finishWith(outcome: Outcome): Outcome {
                try {
                    when (outcome.status) {
                        Status.DONE -> {
                            for (i in stepIndex until titles.size) {
                                try { AgentTaskEngine.completeStepByTitle(titles[i], "done", true) } catch (_: Throwable) {}
                            }
                            AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.VERIFIED_GOAL, outcome.summary.take(200))
                            AgentTaskEngine.finish(AgentTaskEngine.State.COMPLETED, null)
                        }
                        Status.CANCELLED -> AgentTaskEngine.finish(AgentTaskEngine.State.CANCELLED, "Cancelled")
                        Status.ASK_USER -> AgentTaskEngine.finish(AgentTaskEngine.State.BLOCKED, "Needs user input: ${outcome.question ?: ""}")
                        Status.LIMIT -> {
                            AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.SAFETY_LIMIT, outcome.summary.take(200))
                            AgentTaskEngine.finish(AgentTaskEngine.State.BLOCKED, outcome.summary.take(200))
                        }
                        else -> AgentTaskEngine.finish(AgentTaskEngine.State.FAILED, outcome.summary.take(200))
                    }
                } catch (_: Throwable) {}
                logLine("■ ${outcome.status}: ${outcome.summary}")
                return outcome.copy(says = says.toList(), actions = recent.toList())
            }

            // ---- optional pre-step: open the target app
            if (!app.isNullOrBlank()) {
                val res = SerializedActionExecutor.execute("maya_open_app", 12_000L, generation) {
                    ScreenActions.openApp(ctx, app)
                }
                actionNo++
                val line = "$actionNo. open_app '$app' → ${res?.first?.message ?: "cancelled"}"
                recent.add(line); logLine(line)
                if (res == null) return finishWith(Outcome(Status.CANCELLED, "Task cancel ho gaya.", iterations = 0))
                if (!res.first.ok) pendingNotes.add("open_app '$app' failed: ${res.first.message}. If it isn't installed, use ask_user.")
            }

            var turn = 0
            while (turn < maxTurns) {
                if (cancelled() || !coroutineContext.isActive) return finishWith(Outcome(Status.CANCELLED, "Task cancel ho gaya.", iterations = turn))
                if (!AgentTaskEngine.awaitIfRunnable()) return finishWith(Outcome(Status.CANCELLED, "Task cancel ho gaya.", iterations = turn))
                if (System.currentTimeMillis() - startedAt > timeBudgetMs) {
                    return finishWith(Outcome(Status.LIMIT, "Time budget (${timeBudgetMs / 1000}s) khatam — task poora nahi hua; step ${stepIndex + 1}/${plan.size} pe ruka.", iterations = turn))
                }
                if (!ScreenElementSerializer.isServiceOn()) return finishWith(Outcome(Status.FAILED, ScreenElementSerializer.SERVICE_OFF, iterations = turn))
                turn++

                // ---- observe
                val (_, settled) = ScreenActions.waitForSettle(1_500L)
                var snap = ScreenElementSerializer.capture(ctx)
                    ?: return finishWith(Outcome(Status.FAILED, "Screen padh nahi paaya (koi active window nahi).", iterations = turn))
                val dismissed = ScreenActions.dismissSystemDialogs(snap)
                if (dismissed != null) {
                    logLine("⚠ $dismissed")
                    pendingNotes.add(dismissed)
                    delay(600)
                    snap = ScreenElementSerializer.capture(ctx) ?: snap
                }
                try { AgentTaskEngine.observe(snap.signature, snap.packageName, "${snap.appLabel}: ${snap.elements.size} elements") } catch (_: Throwable) {}

                val notes = ArrayList<String>(pendingNotes)
                pendingNotes.clear()
                if (!settled) notes.add(ScreenExecutorPrompt.OBSERVE_SETTLING + ". " + ScreenExecutorPrompt.STALE_LIST_WARNING)

                // ---- VISION-FIRST on mostly unlabeled screens
                if (visionNote == null && snap.elements.size >= 3 && snap.unlabeledRatio() >= 0.6f &&
                    visionFirstAttempts < 2 && (lastVisionSignature == null || !snap.signature.isEquivalentTo(lastVisionSignature)) &&
                    VisionLookup.hasKey()
                ) {
                    visionFirstAttempts++
                    lastVisionSignature = snap.signature
                    val a = VisionLookup.look(ctx, "What is needed for this step: ${plan.getOrNull(stepIndex) ?: goal}? Where are the relevant buttons?")
                    if (a.ok) {
                        visionNote = a.text
                        notes.add(ScreenExecutorPrompt.visionFirst(visionFirstAttempts))
                        logLine("👁 vision-first look")
                    }
                }

                val prompt = ScreenExecutorPrompt.turn(
                    goal = goal,
                    steps = plan,
                    currentStepIndex = stepIndex,
                    foregroundPackage = snap.packageName,
                    foregroundLabel = snap.appLabel,
                    recentActions = recent.takeLast(RECENT_ACTIONS),
                    notes = notes,
                    visionNote = visionNote,
                    elementsBlock = ScreenExecutorPrompt.ELEMENTS_HEADER + "\n" + snap.elementsBlock(assistant),
                    turn = turn,
                    maxTurns = maxTurns
                )
                visionNote = null
                try { AgentTaskEngine.updateActivity("Turn $turn: thinking on ${snap.appLabel}", AgentTaskEngine.State.RESOLVING_TARGET) } catch (_: Throwable) {}

                val reply = try {
                    AiTextSynthesizer.synthesizeText(ctx, ScreenExecutorPrompt.system(assistant), prompt, 0.2, 400) { "" }
                } catch (e: Throwable) {
                    Log.w(TAG, "LLM call failed: ${e.message}")
                    ""
                }
                if (cancelled()) return finishWith(Outcome(Status.CANCELLED, "Task cancel ho gaya.", iterations = turn))
                val verb = VerbParser.parse(reply)
                if (verb == null) {
                    parseFailures++
                    logLine("? unparsable reply (${parseFailures}/$MAX_PARSE_FAILURES): ${reply.take(120)}")
                    if (reply.isBlank() && parseFailures >= 2) {
                        return finishWith(Outcome(Status.FAILED, "AI model se jawab nahi aa raha (API key / internet check karo).", iterations = turn))
                    }
                    if (parseFailures >= MAX_PARSE_FAILURES) {
                        return finishWith(Outcome(Status.FAILED, "Model ne sahi JSON verb nahi diya — task ruk gaya.", iterations = turn))
                    }
                    pendingNotes.add("Your last reply was not ONE valid JSON object with a known verb. Reply with the JSON object ONLY.")
                    continue
                }
                parseFailures = 0
                verb.say?.takeIf { it.isNotBlank() }?.let { if (says.lastOrNull() != it) says.add(it) }

                // ---- terminal verbs
                when (verb.verb) {
                    "task_done" -> return finishWith(Outcome(Status.DONE, "Ho gaya: $goal" + (verb.text?.let { " — $it" } ?: ""), iterations = turn))
                    "ask_user" -> {
                        val q = verb.text?.takeIf { it.isNotBlank() } ?: verb.say ?: "Aage kaise badhun?"
                        return finishWith(Outcome(Status.ASK_USER, "Boss se poochna hai: $q", question = q, iterations = turn))
                    }
                    "step_done" -> {
                        actionNo++
                        val line = "$actionNo. step_done (step ${stepIndex + 1}: ${plan.getOrNull(stepIndex)?.take(60) ?: ""})"
                        recent.add(line); logLine("✓ $line")
                        try { AgentTaskEngine.completeStepByTitle(titles[stepIndex.coerceAtMost(titles.size - 1)], verb.say ?: "done", true) } catch (_: Throwable) {}
                        stepIndex++
                        lastKey = null; lastNoChange = false; repeatBlocks = 0
                        if (stepIndex >= plan.size) {
                            stepIndex = plan.size - 1
                            return finishWith(Outcome(Status.DONE, "Ho gaya: $goal", iterations = turn))
                        }
                        continue
                    }
                }

                // ---- repeat guard
                val key = verb.actionKey()
                val repeatable = verb.verb in setOf("scroll", "swipe", "wait_for", "need_vision")
                if (!repeatable && key == lastKey && lastNoChange) {
                    repeatBlocks++
                    val msg = "BLOCKED: you already did ${verb.describe()} and NOTHING changed on screen. Do NOT repeat it — pick a DIFFERENT element/approach (another #N, scroll, go_back to clear a dialog) or need_vision."
                    logLine("⟲ repeat blocked: ${verb.describe()}")
                    pendingNotes.add(msg)
                    if (repeatBlocks >= 2 && VisionLookup.hasKey()) {
                        val a = VisionLookup.look(ctx, "The assistant keeps trying '${verb.describe()}' with no effect for step: ${plan.getOrNull(stepIndex) ?: goal}. What is actually on screen and what should be tapped instead?")
                        if (a.ok) visionNote = a.text
                        repeatBlocks = 0
                    }
                    continue
                }

                // ---- execute
                val before = snap.signature
                try { AgentTaskEngine.updateActivity("Turn $turn: ${verb.describe()}", AgentTaskEngine.State.ACTING) } catch (_: Throwable) {}
                val result: ActionResult = execute(ctx, verb, snap, generation) { cancelled() }
                if (cancelled()) return finishWith(Outcome(Status.CANCELLED, "Task cancel ho gaya.", iterations = turn))

                if (verb.verb == "need_vision") {
                    visionNote = result.message
                    lastVisionSignature = before
                }

                // ---- verify change
                var changedNote = ""
                var changed = false
                if (verb.verb !in setOf("need_vision", "wait_for")) {
                    delay(if (verb.verb == "open_app") 300L else 450L)
                    val (after, _) = ScreenActions.waitForSettle(1_800L)
                    changed = ScreenActions.changed(before, after)
                    changedNote = when {
                        !result.ok -> ""
                        changed -> " " + ScreenExecutorPrompt.SCREEN_CHANGED_REREAD
                        else -> " " + ScreenExecutorPrompt.NO_VISIBLE_CHANGE
                    }
                } else {
                    changed = result.changed == true
                }
                actionNo++
                val line = "$actionNo. ${verb.describe()} → ${if (result.ok) "" else "FAILED: "}${result.message}$changedNote"
                recent.add(line)
                while (recent.size > 40) recent.removeAt(0)
                logLine(line)
                try { AgentTaskEngine.recordAction(verb.describe(), result.message, result.ok && (changed || verb.verb == "type")) } catch (_: Throwable) {}

                // ---- progress guards
                if (verb.verb in setOf("scroll", "swipe")) {
                    scrollNoChange = if (changed) 0 else scrollNoChange + 1
                    if (scrollNoChange >= 2) pendingNotes.add("Scrolling/swiping no longer changes the screen — you have reached the END (or something blocks it). Don't scroll that way again.")
                }
                val progressed = changed || (verb.verb == "type" && result.ok) || (verb.verb == "wait_for" && result.ok) || verb.verb == "need_vision"
                noChangeStreak = if (progressed) 0 else noChangeStreak + 1
                if (noChangeStreak == NO_CHANGE_VISION && VisionLookup.hasKey()) {
                    val a = VisionLookup.look(ctx, "Nothing changed after several actions for step: ${plan.getOrNull(stepIndex) ?: goal}. Is a dialog/keyboard/popup blocking? What should be done next?")
                    if (a.ok) { visionNote = a.text; logLine("👁 stuck → looked at screen") }
                }
                if (noChangeStreak >= NO_CHANGE_FAIL) {
                    return finishWith(Outcome(Status.FAILED, "$NO_CHANGE_FAIL actions ke baad bhi screen nahi badli — main atak gaya. Step ${stepIndex + 1}: ${plan.getOrNull(stepIndex) ?: goal}", iterations = turn))
                }
                lastKey = key
                lastNoChange = !changed && verb.verb !in setOf("type", "wait_for")
                if (!lastNoChange) repeatBlocks = 0
            }
            return finishWith(Outcome(Status.LIMIT, "$maxTurns turns ki limit poori — task adhoora reh gaya (step ${stepIndex + 1}/${plan.size}).", iterations = turn))
        } catch (e: kotlinx.coroutines.CancellationException) {
            try { AgentTaskEngine.finish(AgentTaskEngine.State.CANCELLED, "Cancelled") } catch (_: Throwable) {}
            logLine("■ CANCELLED")
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "verb loop crashed: ${e.message}", e)
            try { AgentTaskEngine.finish(AgentTaskEngine.State.FAILED, e.message ?: "error") } catch (_: Throwable) {}
            logLine("■ ERROR: ${e.message}")
            return Outcome(Status.FAILED, "Screen task me error: ${e.message}")
        } finally {
            overlayJob?.cancel()
            running = false
            _currentGoal.value = null
            mutex.unlock()
        }
    }

    /** Executes one non-terminal verb against the snapshot it was decided on. */
    private suspend fun execute(
        ctx: Context,
        verb: ScreenVerb,
        snap: ScreenSnapshot,
        generation: Long,
        isCancelled: () -> Boolean
    ): ActionResult {
        suspend fun mutate(name: String, timeoutMs: Long = 15_000L, block: suspend () -> ActionResult): ActionResult =
            SerializedActionExecutor.execute("maya_$name", timeoutMs, generation) { block() }
                ?: ActionResult(false, "cancelled / timed out")

        return when (verb.verb) {
            "tap_id" -> {
                val id = verb.id ?: return ActionResult(false, "tap_id needs \"id\" (the #N number)")
                val el = snap.byId[id] ?: return ActionResult(false, "#$id is not in the current SCREEN ELEMENTS list — use a #N from the list")
                mutate("tap_id") { ScreenActions.tapElement(el) }.let { it.copy(message = "${it.message} #$id '${el.line.label}'") }
            }
            "tap_text" -> {
                val text = verb.text?.trim().orEmpty()
                if (text.isEmpty()) return ActionResult(false, "tap_text needs \"text\"")
                val matches = snap.find(text)
                when {
                    matches.isEmpty() -> ActionResult(false, "'$text' naam ka kuch nahi mila — use a #N from the list (tap_id)")
                    matches.size > 1 && matches.count { it.line.label.equals(text, ignoreCase = true) } > 1 &&
                        matches.map { "${it.line.x / 40},${it.line.y / 40}" }.distinct().size > 1 ->
                        ActionResult(false, "'$text' matches ${matches.size} different things (${matches.take(5).joinToString { "#" + it.line.id }}) — pick the right one with tap_id")
                    else -> {
                        val el = matches.first()
                        mutate("tap_text") { ScreenActions.tapElement(el) }.let { it.copy(message = "${it.message} '${el.line.label}' (#${el.line.id})") }
                    }
                }
            }
            "tap_coords" -> {
                val x = verb.x ?: return ActionResult(false, "tap_coords needs x and y")
                val y = verb.y ?: return ActionResult(false, "tap_coords needs x and y")
                val (dx, dy) = if (verb.space == "image") VisionLookup.imageToDevice(ctx, x, y) else x to y
                mutate("tap_coords") { ScreenActions.tapCoords(dx, dy) }
            }
            "type" -> {
                val text = verb.text ?: return ActionResult(false, "type needs \"text\"")
                val focusedInput = snap.elements.firstOrNull { it.line.role == "input" && it.line.state == "focused" }
                mutate("type", 90_000L) { ScreenActions.type(text, verb.realistic, focusedInput) }
            }
            "scroll" -> mutate("scroll") { ScreenActions.scroll(verb.direction ?: "down") }
            "swipe" -> mutate("swipe") { ScreenActions.swipe(ctx, verb.direction ?: "up") }
            "open_app" -> {
                val name = verb.text?.trim().orEmpty()
                if (name.isEmpty()) return ActionResult(false, "open_app needs the app name in \"text\"")
                mutate("open_app", 15_000L) { ScreenActions.openApp(ctx, name).first }
            }
            "go_back" -> mutate("go_back") { ScreenActions.back() }
            "go_home" -> mutate("go_home") { ScreenActions.home() }
            "wait_for" -> ScreenActions.waitForCondition(ctx, verb.condition ?: verb.text ?: "", verb.timeoutMs, isCancelled)
            "need_vision" -> {
                val q = verb.text?.takeIf { it.isNotBlank() } ?: "Describe what's on screen and where the thing needed for the current step is."
                val a = VisionLookup.look(ctx, q)
                if (a.ok) ActionResult(true, a.text, null) else ActionResult(false, a.text)
            }
            else -> ActionResult(false, "unknown verb '${verb.verb}'")
        }
    }
}
