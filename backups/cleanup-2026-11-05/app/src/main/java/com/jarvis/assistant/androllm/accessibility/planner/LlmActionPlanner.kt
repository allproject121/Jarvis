package com.jarvis.assistant.androllm.accessibility.planner

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ai.router.ModelRouter
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.automation.accessibility.PlannedAction
import com.jarvis.assistant.automation.accessibility.UiScreenSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Ported from AndroLLM (core/accessibility/planner/LlmActionPlanner).
 *
 * LLM-driven next-action planner. AndroLLM fed a local GGUF engine with a
 * JSON-schema grammar; Jarvis instead asks [ModelRouter] (text routing —
 * whichever text model the user configured: ChatGPT, Gemini REST, local host
 * or the offline engine) for a single JSON action. Gemini Live is never
 * touched. Falls back to [HeuristicActionPlanner] whenever the flag is off,
 * the router is slow, or the output cannot be parsed — planning can never
 * brick a task.
 *
 * Gated by AndroLlmFlags.LLM_UI_PLANNER (default OFF).
 */
class LlmActionPlanner(private val context: Context) : ActionPlanner {

    companion object {
        private const val TAG = "JarvisLlmUiPlanner"
        private const val TIMEOUT_MS = 20_000L

        private val routerScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

        /** True when the user opted into LLM UI planning (feature flag). */
        fun isEnabled(context: Context): Boolean =
            AndroLlmFlags.isEnabled(context, AndroLlmFlags.LLM_UI_PLANNER, false)

        private val SYSTEM_PROMPT = """
            You are the UI automation planner of an on-device AI assistant.
            You receive a user goal, the current screen (App, focused element,
            semantic element list) and the steps already done. Choose the ONE
            next action that moves the goal forward and output ONLY a JSON
            object: {"action": "...", "target": "...", "text": "...", ...}
            Actions: click (target=on-screen label), type (text, optional
            field=hint), scroll/swipe (direction=up|down|left|right),
            back, home, recents, notifications, quick_settings, launch
            (target=app name), wait (millis), ask (summary) when the goal
            needs user input, done (summary) when the goal is satisfied.
            Rules:
            - Click only elements listed on screen; use their exact visible text.
            - After typing a search query, click the search/go button.
            - Before tapping anything that sends, pays, books, deletes or
              installs, emit ask instead — never perform it silently.
            - If the screen shows a permission/update dialog, click allow/ok.
            - Never repeat an action that is already in STEPS DONE.
            - Respond with the JSON object only — no prose, no markdown.
        """.trimIndent()
    }

    private val heuristic: ActionPlanner = HeuristicActionPlanner

    override suspend fun nextAction(
        goal: String,
        screen: UiScreenSnapshot,
        history: List<PlannedAction>
    ): PlannedAction? {
        if (!isEnabled(context)) return heuristic.nextAction(goal, screen, history)

        val output = runCatching {
            val router = ModelRouter.getInstance(context, routerScope)
            withTimeoutOrNull(TIMEOUT_MS) {
                val sb = StringBuilder()
                router.routeConversationalQuery(
                    userPrompt = buildUserContent(goal, screen, history),
                    systemPrompt = SYSTEM_PROMPT
                ).collect { chunk -> sb.append(chunk) }
                sb.toString()
            }
        }.onFailure { Log.w(TAG, "router planning failed: ${it.message}") }.getOrNull()

        if (output.isNullOrBlank()) return heuristic.nextAction(goal, screen, history)
        return parse(output) ?: heuristic.nextAction(goal, screen, history)
    }

    /** Extracts the first JSON object (models often wrap it in prose/markdown). */
    internal fun parse(raw: String): PlannedAction? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return null
        val action = json.optString("action").trim().lowercase().ifBlank { return null }
        val target = json.optString("target").trim().ifBlank { null }
        val text = json.optString("text").ifBlank { null }
        val field = json.optString("field").trim().ifBlank { null }
        val direction = json.optString("direction").trim().lowercase().ifBlank { null }
        val index = json.optInt("index", 0).coerceAtLeast(0)
        return when (action) {
            "click" -> target?.let { PlannedAction.Click(it, index) }
            "type" -> text?.let { PlannedAction.Type(it, field) }
            "scroll" -> PlannedAction.Scroll(direction ?: "down")
            "swipe" -> PlannedAction.Swipe(direction ?: "down")
            "back" -> PlannedAction.Back
            "home" -> PlannedAction.Home
            "recents" -> PlannedAction.Recents
            "notifications" -> PlannedAction.Notifications
            "quick_settings" -> PlannedAction.QuickSettings
            "launch" -> target?.let { PlannedAction.LaunchApp(it) }
            "wait" -> PlannedAction.Wait(json.optLong("millis", 1_000L))
            "ask" -> PlannedAction.RequestConfirmation(target ?: text ?: json.optString("summary").ifBlank { "proceed" })
            "done" -> PlannedAction.Done(json.optString("summary", ""))
            else -> null
        }
    }

    private fun buildUserContent(goal: String, screen: UiScreenSnapshot, history: List<PlannedAction>): String = buildString {
        append("GOAL: ").append(goal).append('\n')
        append("CURRENT SCREEN:\n").append(screen.describe(70)).append('\n')
        append("STEPS DONE:\n")
        if (history.isEmpty()) append("  (none)\n")
        else history.forEach { append("  - ").append(it.description).append('\n') }
        append("Output the single next action as JSON.")
    }
}
