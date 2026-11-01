package com.jarvis.assistant.androllm.accessibility.planner

import com.jarvis.assistant.automation.accessibility.PlannedAction
import com.jarvis.assistant.automation.accessibility.UiScreenSnapshot

/**
 * Ported from AndroLLM (core/accessibility/planner/ActionPlanner).
 *
 * Decides the next [PlannedAction] for a goal given the current
 * [UiScreenSnapshot] and the plan steps already executed. Called once per
 * loop iteration by the AutomationExecutor; null means "cannot plan further".
 *
 * Implementations: [HeuristicActionPlanner] (deterministic rules, always
 * available) and [LlmActionPlanner] (ModelRouter text routing, gated by
 * AndroLlmFlags.LLM_UI_PLANNER, falling back to the rules).
 */
interface ActionPlanner {
    suspend fun nextAction(
        goal: String,
        screen: UiScreenSnapshot,
        history: List<PlannedAction>
    ): PlannedAction?
}
