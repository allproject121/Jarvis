package com.jarvis.assistant.automation.accessibility

/**
 * One planned action in a multi-step Android UI automation task.
 */
sealed class PlannedAction(val description: String) {
    class Click(val target: String, val index: Int = 0) : PlannedAction("tap \"$target\"")
    class Type(val text: String, val into: String? = null) : PlannedAction("type text")
    class Scroll(val direction: String) : PlannedAction("scroll $direction")
    class Swipe(val direction: String) : PlannedAction("swipe $direction")
    data object Back : PlannedAction("go back")
    data object Home : PlannedAction("go home")
    data object Recents : PlannedAction("open recents")
    data object Notifications : PlannedAction("open notifications")
    data object QuickSettings : PlannedAction("open quick settings")
    class LaunchApp(val label: String) : PlannedAction("open $label")
    class Wait(val millis: Long) : PlannedAction("wait")
    class RequestConfirmation(val summary: String) : PlannedAction("ask: $summary")
    class Done(val summary: String = "") : PlannedAction("done")
}

data class AutomationResult(val success: Boolean, val summary: String, val steps: Int)
