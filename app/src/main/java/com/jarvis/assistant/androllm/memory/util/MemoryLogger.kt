package com.jarvis.assistant.androllm.memory.util

import android.util.Log
import com.jarvis.assistant.androllm.memory.MemoryLogEntry
import com.jarvis.assistant.androllm.memory.MemoryLogLevel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

/**
 * Bounded in-memory log of memory pipeline activity (ported from AndroLLM
 * `core/memory/util/MemoryLogger`), surfaced by the Memory Inspector in
 * Settings → Memory. Never persisted, never leaves the device; messages are
 * sanitized (secrets masked, long content truncated) before they are kept.
 *
 * Process-wide singleton (replaces the upstream Hilt @Singleton). Entries below
 * [minLevel] are dropped (the inspector "log level" setting); every accepted
 * entry is mirrored to logcat under [TAG].
 */
object MemoryLogger {

    private const val TAG = "AndroMemory"

    private val entries = CopyOnWriteArrayList<MemoryLogEntry>()

    @Volatile
    private var maxEntries: Int = 200

    /** Entries below this level are ignored. INFO by default (DEBUG is chatty). */
    @Volatile
    var minLevel: MemoryLogLevel = MemoryLogLevel.INFO

    fun setMaxEntries(max: Int) {
        maxEntries = max.coerceAtLeast(10)
        trim()
    }

    fun log(level: MemoryLogLevel, message: String) {
        if (level.ordinal < minLevel.ordinal) return
        val sanitized = sanitizeForLog(message)
        entries.add(MemoryLogEntry(System.currentTimeMillis(), level, sanitized))
        trim()
        when (level) {
            MemoryLogLevel.DEBUG -> Log.d(TAG, sanitized)
            MemoryLogLevel.INFO -> Log.i(TAG, sanitized)
            MemoryLogLevel.WARN -> Log.w(TAG, sanitized)
            MemoryLogLevel.ERROR -> Log.e(TAG, sanitized)
        }
    }

    private fun trim() {
        while (entries.size > maxEntries) entries.removeAt(0)
    }

    /** Masks potential secrets/tokens and truncates long content. Visible for tests. */
    fun sanitizeForLog(msg: String): String {
        var s = msg
        s = s.replace(Regex("""\b(sk-[A-Za-z0-9]{4})[A-Za-z0-9_\-]*""")) { it.groupValues[1] + "***" }
        s = s.replace(Regex("""\b(ghp_[A-Za-z0-9]{4})[A-Za-z0-9]*""")) { it.groupValues[1] + "***" }
        s = s.replace(Regex("""\b(AIza[A-Za-z0-9_\-]{4})[A-Za-z0-9_\-]*""")) { it.groupValues[1] + "***" }
        s = s.replace(Regex("""(?i)(password|passwd|pwd|secret|token|api[_ ]?key)\s*[:=]\s*\S+""")) { "${it.groupValues[1]}=***" }
        if (s.length > 400) s = s.take(400) + "…"
        return s
    }

    fun info(message: String) = log(MemoryLogLevel.INFO, message)

    fun warn(message: String) = log(MemoryLogLevel.WARN, message)

    fun error(message: String) = log(MemoryLogLevel.ERROR, message)

    fun debug(message: String) = log(MemoryLogLevel.DEBUG, message)

    /** All entries, newest first. */
    fun snapshot(): List<MemoryLogEntry> = entries.toList().asReversed()

    /** Entries at or above [level], newest first. */
    fun snapshot(level: MemoryLogLevel): List<MemoryLogEntry> =
        snapshot().filter { it.level.ordinal >= level.ordinal }

    fun clear() = entries.clear()

    /** Up to [limit] (max 100) entries, newest first. */
    fun recent(limit: Int): List<MemoryLogEntry> =
        snapshot().take(maxOf(1, min(limit, 100)))
}
