package com.jarvis.assistant.ui.compose.feature.chat

import com.jarvis.assistant.androllm.chat.model.Message
import com.jarvis.assistant.androllm.chat.model.MessageRole
import kotlin.math.ceil
import kotlin.math.max

/**
 * Sliding-window history trimmer that keeps a chat prompt inside the model's
 * context window (ported from AndroLLM feature/chat ChatHistoryTrimmer).
 *
 * Enforces a token budget BEFORE the history is handed to the router by
 * dropping the OLDEST messages first, while always preserving the current
 * user prompt so the assistant never answers a different question.
 *
 * Pure and side-effect free (see ChatHistoryTrimmerTest).
 */
object ChatHistoryTrimmer {

    private const val CHARS_PER_TOKEN = 4.0
    private const val ROLE_OVERHEAD_TOKENS = 4

    /**
     * Approximate tokens in [text] plus a fixed per-message overhead for role
     * markers. ASCII ~4 chars/token; non-ASCII (CJK, emoji, accents) 1 token per
     * character — deliberately conservative (never under-estimates).
     */
    fun estimateTokens(text: String): Int {
        var asciiChars = 0
        var wideChars = 0
        for (c in text) {
            if (c.code < 0x80) asciiChars++ else wideChars++
        }
        return max(1, ceil(asciiChars / CHARS_PER_TOKEN).toInt() + wideChars) + ROLE_OVERHEAD_TOKENS
    }

    /**
     * Generic sliding-window trim. Returns the chronological sub-list of
     * [history] that fits in [contextLength] - [reservedOutputTokens] -
     * [systemTokenOverhead].
     *
     * Invariants:
     * - The last user message is ALWAYS kept, even when it alone exceeds the budget.
     * - Oldest messages are dropped first.
     * - The result starts with a user message (an orphaned leading assistant reply is dropped).
     */
    fun <T> trim(
        history: List<T>,
        contextLength: Int,
        reservedOutputTokens: Int,
        systemTokenOverhead: Int = 0,
        isUser: (T) -> Boolean,
        content: (T) -> String
    ): List<T> {
        if (history.isEmpty()) return emptyList()

        val lastUserIdx = history.indexOfLast(isUser)
        if (lastUserIdx < 0) return emptyList()

        val budget = (contextLength - reservedOutputTokens - systemTokenOverhead).coerceAtLeast(0)
        if (budget <= 0) return listOf(history[lastUserIdx])

        val kept = ArrayDeque<T>()
        var used = 0
        for (i in history.indices.reversed()) {
            val message = history[i]
            val cost = estimateTokens(content(message))
            if (i != lastUserIdx && used + cost > budget) break
            kept.addFirst(message)
            used += cost
        }

        while (kept.isNotEmpty() && !isUser(kept.first())) {
            kept.removeFirst()
        }
        return kept.toList()
    }

    /** [trim] over AndroLLM-chat [Message] rows (SYSTEM rows never count as user turns). */
    fun trim(
        history: List<Message>,
        contextLength: Int,
        reservedOutputTokens: Int,
        systemTokenOverhead: Int = 0
    ): List<Message> = trim(
        history = history,
        contextLength = contextLength,
        reservedOutputTokens = reservedOutputTokens,
        systemTokenOverhead = systemTokenOverhead,
        isUser = { it.role == MessageRole.USER },
        content = { it.content }
    )
}
