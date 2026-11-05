package com.jarvis.assistant.androllm.accessibility.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One entry in the automation developer log. */
data class DebugEntry(
    val category: String,
    val message: String,
    val at: Long = System.currentTimeMillis()
) {
    val timestamp: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(at))

    override fun toString(): String = "[$timestamp] $category: $message"
}

/**
 * Ported from AndroLLM (core/accessibility/debug/AccessibilityDebugStore).
 *
 * Bounded, in-memory log for the automation developer mode: execution steps,
 * gesture log, node dumps and failures. Capped so a long automation session
 * can never grow memory unboundedly.
 */
object AccessibilityDebugStore {

    private const val MAX_ENTRIES = 500

    private val _entries = MutableStateFlow<List<DebugEntry>>(emptyList())
    val entries: StateFlow<List<DebugEntry>> = _entries.asStateFlow()

    /** Latest screen dump (single slot, replaced on every read in developer mode). */
    private val _lastScreenDump = MutableStateFlow<String?>(null)
    val lastScreenDump: StateFlow<String?> = _lastScreenDump.asStateFlow()

    private val history = ConcurrentLinkedDeque<DebugEntry>()

    fun record(category: String, message: String) {
        history.addLast(DebugEntry(category, message))
        while (history.size > MAX_ENTRIES) history.pollFirst()
        _entries.value = history.toList()
    }

    fun recordScreenDump(dump: String) {
        _lastScreenDump.value = dump.take(6_000)
    }

    fun clear() {
        history.clear()
        _entries.value = emptyList()
        _lastScreenDump.value = null
    }
}
