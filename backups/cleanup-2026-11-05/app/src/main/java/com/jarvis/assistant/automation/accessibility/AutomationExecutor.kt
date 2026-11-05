package com.jarvis.assistant.automation.accessibility

import android.content.Context
import android.util.Log
import com.jarvis.assistant.androllm.accessibility.debug.AccessibilityDebugStore
import com.jarvis.assistant.androllm.accessibility.planner.ActionPlanner
import com.jarvis.assistant.androllm.accessibility.planner.HeuristicActionPlanner
import com.jarvis.assistant.androllm.accessibility.planner.LlmActionPlanner
import com.jarvis.assistant.androllm.accessibility.planner.SafetyClassifier
import com.jarvis.assistant.androllm.accessibility.settings.AccessibilitySettingsStore
import com.jarvis.assistant.androllm.accessibility.state.WindowMonitor
import com.jarvis.assistant.automation.agent.PendingAction
import com.jarvis.assistant.automation.agent.ToolConfirmationManager
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/** One executed plan step, recorded for the developer log. */
data class ExecutedStep(val action: PlannedAction, val result: UiActionResult)

/**
 * Executes multi-step UI tasks inside any app using AccessibilityController.
 *
 * AndroLLM upgrade (core/accessibility/executor/AutomationExecutor) — the
 * plan-act-verify loop behind `ui_run`:
 *
 *   read screen → planner picks ONE action → execute → settle (retry clicks once)
 *
 * - Planner: [HeuristicActionPlanner] (LaunchApp → Wait → act), or
 *   [LlmActionPlanner] when the `llmPlanning` setting AND the
 *   AndroLlmFlags.LLM_UI_PLANNER flag are both on.
 * - Step budget from settings guards against runaway loops; a repeated
 *   identical action means the screen is stuck and the run stops.
 * - High-risk taps ([SafetyClassifier]) route through Jarvis'
 *   [ToolConfirmationManager] when `confirmHighRisk` is on.
 */
class AutomationExecutor(
    private val context: Context,
    private val controller: AccessibilityController
) {
    companion object {
        private const val TAG = "JarvisUiAutomation"
    }

    private val cancelled = AtomicBoolean(false)
    private val settingsStore = AccessibilitySettingsStore.getInstance(context)

    fun cancel() {
        cancelled.set(true)
    }

    /**
     * @param maxSteps optional override; null uses the persisted setting.
     * @param preApproved true when the user already explicitly confirmed this
     *   goal in conversation (ui_run `confirmed=true`) — high-risk steps then
     *   proceed without a second prompt.
     */
    suspend fun run(
        goal: String,
        maxSteps: Int? = null,
        onStatus: suspend (String) -> Unit = {},
        preApproved: Boolean = false
    ): AutomationResult {
        cancelled.set(false)
        if (!controller.isConnected) {
            return AutomationResult(
                success = false,
                summary = "Jarvis Accessibility Service is not enabled. Please enable it in Settings > Accessibility.",
                steps = 0
            )
        }
        val settings = settingsStore.current()
        val budget = (maxSteps ?: settings.maxSteps).coerceIn(1, 40)
        val useLlm = settings.llmPlanning && LlmActionPlanner.isEnabled(context)
        val planner: ActionPlanner = if (useLlm) LlmActionPlanner(context) else HeuristicActionPlanner
        AccessibilityDebugStore.record("executor", "run '$goal' (planner=${if (useLlm) "llm" else "heuristic"}, budget=$budget)")

        val history = mutableListOf<PlannedAction>()
        val steps = mutableListOf<ExecutedStep>()
        var lastSignature: String? = null
        var loadingWaits = 0

        for (step in 0 until budget) {
            if (cancelled.get()) {
                return AutomationResult(false, "Task cancelled by user.", steps.size)
            }
            onStatus("Step ${step + 1}/$budget: inspecting screen for '$goal'...")

            val screen = controller.readScreen()
            // App transitions / loading spinners: wait, don't act on stale UI.
            // Capped so a permanent progress indicator can't burn the whole budget.
            if ((screen.loading || (screen.isEmpty && step > 0)) && loadingWaits < 4) {
                loadingWaits++
                delay(600)
                continue
            }
            loadingWaits = 0

            val action = runCatching { planner.nextAction(goal, screen, history) }.getOrNull()
                ?: runCatching { HeuristicActionPlanner.nextAction(goal, screen, history) }.getOrNull()
                ?: return AutomationResult(false, "Could not plan the next step for this screen.", steps.size)

            // Loop guard: the same action twice in a row means the screen did
            // not change. Waits are exempt (slow apps legitimately need several).
            val signature = signatureOf(action)
            if (action !is PlannedAction.Wait && signature == lastSignature) {
                return AutomationResult(false, "Stuck on '${action.description}' - the screen did not change.", steps.size)
            }
            lastSignature = signature

            // Never drive Jarvis' own UI (e.g. typing the goal into Jarvis' chat box).
            val ownApp = screen.packageName == context.packageName
            if (ownApp && (action is PlannedAction.Click || action is PlannedAction.Type ||
                    action is PlannedAction.Scroll || action is PlannedAction.Swipe)
            ) {
                return AutomationResult(
                    false,
                    "Jarvis itself is in the foreground - say which app to use (e.g. 'open YouTube and ...').",
                    steps.size
                )
            }
            history += action

            val generation = WindowMonitor.generation.value
            when (action) {
                is PlannedAction.Done -> {
                    val failed = steps.count { !it.result.success }
                    val summary = action.summary.ifBlank {
                        if (steps.isEmpty()) "No UI actions were needed for this goal." else "Task complete."
                    }
                    AccessibilityDebugStore.record("executor", "done in ${steps.size} step(s): $summary")
                    return if (failed == 0) {
                        AutomationResult(true, summary + describeSteps(steps), steps.size)
                    } else {
                        AutomationResult(false, "$failed of ${steps.size} steps failed." + describeSteps(steps), steps.size)
                    }
                }

                is PlannedAction.Wait -> {
                    delay(action.millis.coerceIn(0, 10_000))
                    steps += ExecutedStep(action, UiActionResult.ok("waited ${action.millis}ms"))
                }

                is PlannedAction.RequestConfirmation -> {
                    if (!preApproved && !confirm(action.summary)) {
                        return AutomationResult(false, notConfirmed(action.summary), steps.size)
                    }
                    steps += ExecutedStep(action, UiActionResult.ok("user approved: ${action.summary}"))
                }

                is PlannedAction.LaunchApp -> {
                    val result = controller.launchApp(action.label)
                    steps += ExecutedStep(action, result)
                    if (!result.success) return AutomationResult(false, result.message, steps.size)
                }

                is PlannedAction.Click -> {
                    if (settings.confirmHighRisk && !preApproved && SafetyClassifier.requiresConfirmation(goal, action)) {
                        val what = "tap \"${action.target}\""
                        if (!confirm(what)) return AutomationResult(false, notConfirmed(what), steps.size)
                    }
                    val selector = UiSelector(textContains = action.target, index = action.index)
                    var result = controller.click(selector)
                    if (!result.success) {
                        delay(500) // let the UI settle, then one retry
                        result = controller.click(selector)
                    }
                    steps += ExecutedStep(action, result)
                    if (!result.success) return AutomationResult(false, result.message + describeSteps(steps), steps.size)
                }

                is PlannedAction.Type -> {
                    val result = controller.type(action.text, action.into)
                    steps += ExecutedStep(action, result)
                    if (!result.success) return AutomationResult(false, result.message + describeSteps(steps), steps.size)
                }

                is PlannedAction.Scroll -> steps += ExecutedStep(action, controller.scroll(action.direction))
                is PlannedAction.Swipe -> steps += ExecutedStep(action, controller.swipe(action.direction))
                is PlannedAction.Back -> steps += ExecutedStep(action, controller.navigate("back"))
                is PlannedAction.Home -> steps += ExecutedStep(action, controller.navigate("home"))
                is PlannedAction.Recents -> steps += ExecutedStep(action, controller.navigate("recents"))
                is PlannedAction.Notifications -> steps += ExecutedStep(action, controller.navigate("notifications"))
                is PlannedAction.QuickSettings -> steps += ExecutedStep(action, controller.navigate("quick_settings"))
            }

            val last = steps.lastOrNull()
            if (last != null && last.action === action) {
                if (!last.result.success) Log.w(TAG, "Step failed: ${last.result.message}")
                onStatus("Step ${step + 1}: ${action.description} -> ${last.result.message}")
                AccessibilityDebugStore.record("executor", "step ${steps.size}: ${action.description} -> ${last.result.message}")
            }
            // Settle: wait for the UI to react (event-driven), then a short beat.
            if (action !is PlannedAction.Wait && action !is PlannedAction.RequestConfirmation) {
                WindowMonitor.awaitChange(generation, 1_500L)
                delay(300)
            }
        }

        return AutomationResult(false, "Reached the step limit ($budget) before finishing the task." + describeSteps(steps), steps.size)
    }

    private fun signatureOf(action: PlannedAction): String = when (action) {
        is PlannedAction.Click -> "click:${action.target.lowercase()}#${action.index}"
        is PlannedAction.Type -> "type:${action.text}@${action.into}"
        is PlannedAction.Scroll -> "scroll:${action.direction}"
        is PlannedAction.Swipe -> "swipe:${action.direction}"
        is PlannedAction.LaunchApp -> "launch:${action.label.lowercase()}"
        else -> action.description
    }

    private fun describeSteps(steps: List<ExecutedStep>): String =
        if (steps.isEmpty()) "" else " Steps: " + steps.joinToString("; ") { "${it.action.description} -> ${it.result.message}" }

    private fun notConfirmed(summary: String): String =
        "Stopped before a sensitive action that was not confirmed: $summary. " +
            "Ask the user to confirm, then retry ui_run with confirmed=true (or tap it with ui_click)."

    /** Asks the user through Jarvis' shared confirmation manager. */
    private suspend fun confirm(summary: String): Boolean {
        val action = PendingAction(
            id = "ui_${System.currentTimeMillis()}_${summary.hashCode().toUInt().toString(16)}",
            toolName = "ui_run",
            description = "UI automation wants to $summary",
            speakableQuestion = "Do you want me to $summary? Say yes to confirm, or no to cancel."
        )
        return runCatching { ToolConfirmationManager.awaitApproval(action) }.getOrDefault(false)
    }
}
