package com.jarvis.assistant.androllm.accessibility.state

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ported from AndroLLM (core/accessibility/controller/UIStateTracker).
 *
 * Tracks the ongoing UI context: current package / window, focused and
 * selected text, loading state and a small navigation stack (package
 * history). Driven by accessibility events forwarded from
 * JarvisAccessibilityService — no polling.
 */
object UIStateTracker {

    private const val MAX_STACK = 8

    private val _currentPackage = MutableStateFlow("")
    val currentPackage: StateFlow<String> = _currentPackage.asStateFlow()

    private val _windowTitle = MutableStateFlow("")
    val windowTitle: StateFlow<String> = _windowTitle.asStateFlow()

    private val _focusedText = MutableStateFlow("")
    val focusedText: StateFlow<String> = _focusedText.asStateFlow()

    private val _selectedText = MutableStateFlow("")
    val selectedText: StateFlow<String> = _selectedText.asStateFlow()

    /** True while a progress indicator is visible (screen is busy). */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Most recent packages, newest first. */
    private val _navStack = MutableStateFlow<List<String>>(emptyList())
    val navStack: StateFlow<List<String>> = _navStack.asStateFlow()

    fun onEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isNotBlank() && pkg != _currentPackage.value &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            _currentPackage.value = pkg
            _navStack.value = (listOf(pkg) + _navStack.value.filter { it != pkg }).take(MAX_STACK)
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                _windowTitle.value = event.className?.toString()?.substringAfterLast('.') ?: ""
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                event.text.firstOrNull()?.let { _focusedText.value = it.toString().take(80) }
            }
            else -> Unit
        }
    }

    /** Called by the screen analyzer after a tree read. */
    fun setLoading(loading: Boolean) {
        _loading.value = loading
    }

    fun setFocusedText(text: String) {
        _focusedText.value = text.take(80)
    }

    fun setSelectedText(text: String) {
        _selectedText.value = text.take(80)
    }

    fun snapshot(): UiContextSnapshot = UiContextSnapshot(
        currentPackage = _currentPackage.value,
        windowTitle = _windowTitle.value,
        focusedText = _focusedText.value,
        selectedText = _selectedText.value,
        loading = _loading.value,
        navStack = _navStack.value
    )
}

/** Immutable context snapshot fed to the screen analyzer. */
data class UiContextSnapshot(
    val currentPackage: String = "",
    val windowTitle: String = "",
    val focusedText: String = "",
    val selectedText: String = "",
    val loading: Boolean = false,
    val navStack: List<String> = emptyList()
)
