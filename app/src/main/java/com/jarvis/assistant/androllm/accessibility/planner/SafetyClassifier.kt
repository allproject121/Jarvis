package com.jarvis.assistant.androllm.accessibility.planner

import com.jarvis.assistant.automation.accessibility.PlannedAction

/**
 * Ported from AndroLLM (core/accessibility/planner/SafetyClassifier) and
 * merged with Jarvis' former HIGH_RISK regex (uninstall / post / share / call).
 *
 * Pure keyword classifier deciding whether an automation step is high-risk
 * and must be confirmed first: sending, paying, booking, deleting,
 * installing, posting, calling, account or system changes always require
 * explicit approval — everything else executes immediately.
 */
object SafetyClassifier {

    private val RISKY_GOAL_WORDS = listOf(
        "send", "pay", "payment", "buy", "purchase", "book", "booking", "order",
        "place order", "confirm", "delete", "remove", "install", "uninstall",
        "transfer", "subscribe", "subscription", "charge", "checkout",
        "withdraw", "change password", "reset", "cancel subscription", "sign up",
        // Jarvis HIGH_RISK additions
        "post", "share", "call", "publish", "upload", "factory"
    )

    private val RISKY_BUTTON_WORDS = listOf(
        "send", "pay", "buy", "purchase", "book", "order", "place order",
        "confirm", "checkout", "delete", "remove", "install", "transfer",
        "subscribe", "charge", "withdraw", "submit", "request", "reserve",
        "place",
        // Jarvis HIGH_RISK additions
        "uninstall", "post", "publish", "share", "call", "erase", "factory reset"
    )

    fun isRiskyGoal(goal: String): Boolean {
        val g = goal.lowercase()
        return RISKY_GOAL_WORDS.any { g.contains(it) }
    }

    /** True when the tapped element's label suggests a risky action. */
    fun isRiskyTarget(target: String): Boolean {
        val t = target.lowercase().trim()
        if (t.isEmpty() || t.length > 48) return false
        return RISKY_BUTTON_WORDS.any { t == it || t.contains(it) }
    }

    /**
     * Confirmation decision for an emitted [action]: risky buttons always
     * confirm; a risky goal + any risky-flow tap confirms too.
     */
    fun requiresConfirmation(goal: String, action: PlannedAction): Boolean {
        if (action !is PlannedAction.Click) return false
        if (isRiskyTarget(action.target)) return true
        val t = action.target.lowercase()
        return isRiskyGoal(goal) && RISKY_BUTTON_WORDS.any { t.contains(it) }
    }
}
