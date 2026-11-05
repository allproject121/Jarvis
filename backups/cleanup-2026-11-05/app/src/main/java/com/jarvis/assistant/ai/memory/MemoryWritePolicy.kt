package com.jarvis.assistant.ai.memory

import com.jarvis.assistant.androllm.memory.ExtractedMemory
import com.jarvis.assistant.androllm.memory.MemoryCategory
import com.jarvis.assistant.androllm.memory.MemoryExchange

/**
 * Deterministic write policy deciding what facts to persist, update, or ignore.
 * Differentiates between stable personal facts, preferences, user corrections, and ephemeral noise.
 *
 * Upgraded with the AndroLLM policy: prompt-injection and temporary-scope
 * checks ("just for now", "for this chat only"), exchange-aware correction
 * detection, and [decideExtracted] for LLM-extracted memories.
 *
 * Correction targeting (bug fix): previously [Decision.Update.targetKeyword]
 * was the text AFTER the correction signal — for "actually my phone is X" /
 * "change it to X" that is the NEW value, so searching the store for it never
 * matched the OLD memory. Now [Decision.Update] distinguishes:
 *  - [Decision.Update.oldValue]: set only for signals followed by the OLD value
 *    ("instead of X", "rather than X", "not X");
 *  - [Decision.Update.subjectHint]: the fact slot being corrected ("name",
 *    "phone", ...), or the text before the signal ("my email should be X").
 * [com.jarvis.assistant.androllm.memory.correction.MemoryCorrectionResolver]
 * uses these plus contradiction detection to find the memory to update.
 */
object MemoryWritePolicy {

    sealed interface Decision {
        data object Persist : Decision

        /**
         * @param targetKeyword best single search hint for the memory being corrected
         *        (old value when known, otherwise the subject hint).
         * @param oldValue the superseded value when the phrasing names it.
         * @param subjectHint the corrected fact slot / subject text, if found.
         * @param newStatement the user's corrected statement.
         */
        data class Update(
            val targetKeyword: String,
            val oldValue: String? = null,
            val subjectHint: String? = null,
            val newStatement: String = ""
        ) : Decision

        data class Ignore(val reason: String) : Decision
    }

    private val greetingOnly = Regex(
        """^(hi|hello|hey|thanks|thank you|ok|okay|yes|no|sure|bye|k|shukriya|theek hai)\W*$""",
        RegexOption.IGNORE_CASE
    )

    /** Signals followed by the OLD (superseded) value: "tea instead of coffee". */
    private val oldValueSignals = listOf("instead of", "rather than", "not ")

    /** Signals followed by the NEW value / corrected statement. */
    private val newValueSignals = listOf(
        "actually", "i meant", "correction", "my mistake", "change to",
        "update to", "should be", "is now", "no,", "no -",
        "forget the", "galti se", "ab se mera"
    )

    private val correctionSignals = newValueSignals + oldValueSignals

    /** Fact slots used as subject hints for the conflict resolver. */
    private val subjectKeywords = listOf(
        "name", "birthday", "location", "address", "live", "city", "phone", "number",
        "email", "job", "profession", "work", "project", "favorite", "favourite",
        "prefer", "theme", "mode", "language", "device", "stack", "routine", "naam"
    )

    private val usefulSignals = listOf(
        "prefer", "like", "dislike", "love", "hate", "always", "never",
        "my name is", "i am", "i'm", "i work", "project", "remember this",
        "remember that", "don't forget", "my goal", "working on",
        "decided", "decision", "task", "todo", "deadline", "my device",
        "i use", "i own", "my stack", "speaks", "lives in", "works at",
        "mera naam", "meri pasand", "pasand hai", "mera birthday", "yaad rakhna"
    )

    private val noiseSignals = listOf(
        "explain ", "summarize this", "translate this", "what is",
        "what's", "how do i", "debug", "stacktrace", "console.log",
        "lorem ipsum", "test message", "batao", "kaise kare"
    )

    private val temporaryMarkers = listOf(
        "just for now", "just for this chat", "just for this session",
        "for now", "temporary", "temporarily", "one-off", "one off",
        "just this once", "in this session only", "in this chat only",
        "for this project only", "just today", "right now"
    )

    fun decide(content: String, exchange: MemoryExchange? = null): Decision {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return Decision.Ignore("empty")
        if (trimmed.length < 8) return Decision.Ignore("low-value: too short")
        if (trimmed.length > 800) return Decision.Ignore("too long")
        val lower = trimmed.lowercase()

        if (greetingOnly.matches(trimmed)) return Decision.Ignore("greeting/filler")

        // Secrets and injection are never stored, even if otherwise useful.
        if (MemorySecurityFilter.containsSecret(trimmed) || looksLikeSecret(trimmed)) {
            return Decision.Ignore("security: contains secret")
        }
        if (looksLikeInjection(trimmed)) return Decision.Ignore("injection")

        // Temporary scope beats usefulness — "just for now" is never long-term.
        if (isTemporaryScope(lower, exchange)) return Decision.Ignore("temporary scope")

        // Corrections update rather than duplicate.
        correctionUpdate(trimmed, lower, exchange)?.let { return it }

        // Explicit remember commands always persist
        if (lower.contains("remember") || lower.contains("yaad rakhna") || lower.contains("don't forget")) {
            return Decision.Persist
        }

        if (usefulSignals.any { lower.contains(it) }) return Decision.Persist

        if (noiseSignals.any { lower.contains(it) } && !hasDurableAnchor(lower)) {
            return Decision.Ignore("ephemeral request")
        }

        return Decision.Persist
    }

    fun shouldPersist(content: String, exchange: MemoryExchange? = null): Boolean =
        decide(content, exchange) !is Decision.Ignore

    fun isCorrection(content: String, exchange: MemoryExchange? = null): Boolean {
        val lower = content.lowercase()
        if (correctionSignals.any { it in lower }) return true
        if (exchange == null) return false
        val userLower = exchange.userMessage.lowercase()
        return correctionSignals.any { it in userLower }
    }

    /** Convenience for LLM-extracted memories: pinned facts and project context always persist. */
    fun decideExtracted(item: ExtractedMemory, exchange: MemoryExchange?): Decision {
        if (item.category == MemoryCategory.PINNED_FACTS) return Decision.Persist
        if (item.category == MemoryCategory.PROJECTS) return Decision.Persist
        if (!item.projectName.isNullOrBlank()) return Decision.Persist
        return decide(item.content, exchange)
    }

    private fun correctionUpdate(original: String, lower: String, exchange: MemoryExchange?): Decision.Update? {
        if (!isCorrection(lower, exchange)) return null

        // Correction phrased in the statement itself, or (for extracted
        // memories) in the user's message of the exchange.
        val source = if (correctionSignals.any { it in lower }) lower else exchange?.userMessage?.lowercase().orEmpty()

        var oldValue: String? = null
        for (signal in oldValueSignals) {
            val idx = source.indexOf(signal)
            if (idx >= 0) {
                val after = source.substring(idx + signal.length).trim()
                    .split(Regex("""[.,;!?]""")).first().trim().take(40)
                if (after.isNotEmpty()) {
                    oldValue = after
                    break
                }
            }
        }

        val subjectKeyword = subjectKeywords.firstOrNull { it in source }
        var subjectHint: String? = subjectKeyword
        if (subjectHint == null) {
            // "my email should be X" -> subject "my email"
            for (signal in newValueSignals) {
                val idx = source.indexOf(signal)
                if (idx > 0) {
                    val before = source.substring(0, idx).trim().takeLast(40).trim()
                    if (before.length >= 3) {
                        subjectHint = before
                        break
                    }
                }
            }
        }

        val target = oldValue ?: subjectHint ?: exchange?.userMessage?.take(60)?.trim()?.ifBlank { null }
            ?: return null
        return Decision.Update(
            targetKeyword = target,
            oldValue = oldValue,
            subjectHint = subjectHint,
            newStatement = original
        )
    }

    private fun isTemporaryScope(lower: String, exchange: MemoryExchange?): Boolean {
        if (temporaryMarkers.any { it in lower }) return true
        if (exchange == null) return false
        val exchangeText = (exchange.userMessage + " " + exchange.assistantResponse).lowercase()
        return temporaryMarkers.any { it in exchangeText }
    }

    private fun looksLikeSecret(content: String): Boolean {
        val lower = content.lowercase()
        return lower.contains("sk-") || lower.contains("ghp_") ||
            lower.contains("gsk_") || lower.contains("xoxb-") ||
            Regex("""(?i)(password|passwd|pwd|secret|api[_-]?key|token)\s*[:=]""").containsMatchIn(content) ||
            Regex("""\b\d{4}[\s-]?\d{4}[\s-]?\d{4}[\s-]?\d{4}\b""").containsMatchIn(content)
    }

    private fun looksLikeInjection(content: String): Boolean {
        val lower = content.lowercase()
        return lower.contains("ignore previous instructions") ||
            lower.contains("pretend you are") || lower.contains("<tool_call>") ||
            lower.startsWith("system:") || lower.startsWith("developer:")
    }

    private fun hasDurableAnchor(lower: String): Boolean =
        usefulSignals.any { it in lower } ||
            lower.contains("prefer") || lower.contains("is ") ||
            lower.contains("project")
}
