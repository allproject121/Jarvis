package com.jarvis.assistant.androllm.accessibility.state

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

/**
 * Ported from AndroLLM (core/accessibility/controller/WindowMonitor).
 *
 * Watches the accessibility event stream for changes that invalidate UI
 * state (window switches, content changes, scrolls, focus). [generation] is a
 * cheap freshness key: the executor waits for it to move after an action
 * instead of acting on stale data.
 */
object WindowMonitor {

    private val _generation = MutableStateFlow(0L)
    val generation: StateFlow<Long> = _generation.asStateFlow()

    private val _lastEventType = MutableStateFlow("")
    val lastEventType: StateFlow<String> = _lastEventType.asStateFlow()

    fun onEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> _generation.value++
            else -> Unit
        }
        _lastEventType.value = AccessibilityEvent.eventTypeToString(event.eventType)
    }

    /**
     * Suspends until the UI generation moves past [since] or [timeoutMs]
     * elapses. Returns true when a change was observed.
     */
    suspend fun awaitChange(since: Long, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { generation.first { it > since } } != null
}
