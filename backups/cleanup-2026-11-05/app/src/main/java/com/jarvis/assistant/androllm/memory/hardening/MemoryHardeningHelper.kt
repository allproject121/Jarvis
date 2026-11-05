package com.jarvis.assistant.androllm.memory.hardening

import com.jarvis.assistant.androllm.memory.ExtractedMemory
import com.jarvis.assistant.androllm.memory.Memory
import com.jarvis.assistant.androllm.memory.MemoryCategory
import com.jarvis.assistant.androllm.memory.MemoryExchange

/**
 * Production hardening utilities: confidence scoring, temporary-context detection,
 * contradiction (correction) detection, deterministic dedup helpers.
 * All logic is deterministic and side-effect free.
 */
object MemoryHardeningHelper {

    // ── Temporary / one-off detection ────────────────────────────────────────
    private val temporaryPatterns = listOf(
        Regex("""\b(just for (now|this (time|session|chat)|today)|for now|temporary|temporarily|this (time|session) only|one[-\s]?off|just this once)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(remind me|explain|summarize|translate|debug|test|demo|example)\b.*\b(for now|quick|once)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(playing|listening|watching) .+ right now\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(current (task|request|question)|in this (chat|conversation) only)\b""", RegexOption.IGNORE_CASE)
    )

    private val shortLivedPreferencePatterns = listOf(
        Regex("""\b(use|prefer).{0,30}\b(for (now|this (project|task)|today)|just (today|now))\b""", RegexOption.IGNORE_CASE)
    )

    fun isTemporaryContext(content: String, exchange: MemoryExchange? = null): Boolean {
        val lower = content.lowercase()
        if (temporaryPatterns.any { it.containsMatchIn(lower) }) return true
        if (shortLivedPreferencePatterns.any { it.containsMatchIn(lower) }) return true
        if (exchange != null) {
            val exchangeText = (exchange.userMessage + " " + exchange.assistantResponse).lowercase()
            if (exchangeText.contains("just for this chat") || exchangeText.contains("just for now") ||
                exchangeText.contains("one-off") || exchangeText.contains("one off")
            ) {
                return true
            }
            // One-off request: user asked to "explain X" and the memory is that explanation.
            if (exchangeText.contains("explain") && content.length < 80 && !content.contains("prefer") && !content.contains("is")) {
                return true
            }
        }
        return false
    }

    // ── Confidence scoring ───────────────────────────────────────────────────
    /**
     * Confidence 0.0..1.0 that this extracted memory is worth persisting long-term.
     * <0.55 is dropped, 0.55..0.75 is low, >=0.75 is high.
     */
    fun confidenceScore(extracted: ExtractedMemory, exchange: MemoryExchange? = null): Double {
        var score = 0.5
        val content = extracted.content.trim()
        val lower = content.lowercase()
        val len = content.length

        when {
            len < 15 -> score -= 0.3
            len in 15..40 -> score += 0.1
            len in 41..140 -> score += 0.2
            len > 280 -> score -= 0.1
        }

        when (extracted.category) {
            MemoryCategory.PREFERENCES, MemoryCategory.IDENTITY, MemoryCategory.PINNED_FACTS -> score += 0.15
            MemoryCategory.CUSTOM -> score -= 0.05
            else -> score += 0.05
        }

        score += (extracted.importance.coerceIn(1, 5) - 3) * 0.07

        if (exchange != null) {
            val grounded = isGroundedForConfidence(content, exchange)
            if (!grounded) score -= 0.35 else score += 0.1
        }

        if (isTemporaryContext(content, exchange)) score -= 0.4
        if (!extracted.projectName.isNullOrBlank()) score += 0.05
        if (extracted.tags.isNotEmpty()) score += 0.03
        if (lower in setOf("hello", "thanks", "ok", "debug log")) score -= 0.5

        return score.coerceIn(0.0, 1.0)
    }

    private fun isGroundedForConfidence(content: String, exchange: MemoryExchange): Boolean {
        // Manual saves use an empty exchange — considered grounded.
        if (exchange.isManual) return true
        val words = content.lowercase().split(Regex("""\W+""")).filter { it.length > 3 }.toSet()
        if (words.size < 2) return true
        val exchangeText = (exchange.userMessage + " " + exchange.assistantResponse + " " +
            exchange.recentMessages.joinToString(" ") { it.second }).lowercase()
        val overlap = words.count { it in exchangeText }
        return overlap >= (words.size * 0.3).coerceAtLeast(1.0)
    }

    fun shouldCommit(extracted: ExtractedMemory, exchange: MemoryExchange?): Boolean {
        if (isTemporaryContext(extracted.content, exchange)) return false
        val conf = confidenceScore(extracted, exchange)
        val threshold = if (extracted.category == MemoryCategory.CUSTOM && extracted.importance <= 2) 0.62 else 0.55
        return conf >= threshold
    }

    // ── Contradiction detection ──────────────────────────────────────────────
    // Same subject + different value means UPDATE, not duplicate.
    private val contradictionPairs = listOf(
        Pair("prefers dark mode", "prefers light mode"),
        Pair("prefers light mode", "prefers dark mode"),
        Pair("likes", "dislikes"),
        Pair("enables", "disables"),
        Pair("short", "long"),
        Pair("dark", "light"),
        Pair("english", "japanese"),
        Pair("true", "false")
    )

    /** Subjects whose value change always means "update the old fact". */
    private val correctionSubjects = listOf(
        "name is", "named", "lives in", "located in", "works at",
        "works for", "prefers", "likes", "dislikes", "uses",
        "project", "goal is", "deadline", "device", "phone",
        "email", "language", "theme", "mode",
        // Jarvis phrasing ("User's birthday is ...", "User's favorite song is ...")
        "birthday is", "favorite", "favourite", "profession", "job is"
    )

    /**
     * Single-valued slots: a user has one name / home / employer / birthday, so
     * sharing only the anchor is enough to call it a correction.
     */
    private val singleValuedAnchors = setOf(
        "name is", "named", "lives in", "located in", "works at", "works for",
        "birthday is", "job is", "profession", "goal is", "email"
    )

    /**
     * Words that every extracted memory shares ("User prefers ...") and that
     * therefore carry no subject information. Excluding them prevents
     * "User prefers Kotlin" from being treated as a correction of
     * "User prefers dark mode" (a false positive in the original port that
     * would overwrite unrelated memories).
     */
    private val genericWords = setOf("user", "users", "their")

    private fun significantWords(text: String): Set<String> =
        text.split(Regex("""\W+""")).filter { it.length > 3 }.toSet()

    fun isContradictory(a: String, b: String): Boolean {
        val al = a.lowercase()
        val bl = b.lowercase()
        if (al.trim() == bl.trim()) return false
        val aWords = significantWords(al)
        val bWords = significantWords(bl)

        for ((p1, p2) in contradictionPairs) {
            if ((p1 in al && p2 in bl) || (p2 in al && p1 in bl)) {
                if (aWords.intersect(bWords).isNotEmpty()) return true
            }
        }
        val sharedSubjectWords = (aWords - genericWords).intersect(bWords - genericWords)
        for (subject in correctionSubjects) {
            if (subject in al && subject in bl) {
                if (subject in singleValuedAnchors && sharedSubjectWords.isNotEmpty()) return true
                if (sharedSubjectWords.size >= 2) return true
            }
        }
        // Generic: same 4-word prefix but different trailing value.
        if (al.length > 20 && bl.length > 20) {
            val aPrefix = al.split(" ").take(4).joinToString(" ")
            val bPrefix = bl.split(" ").take(4).joinToString(" ")
            if (aPrefix == bPrefix) {
                val aSuffix = al.substringAfter(aPrefix).trim()
                val bSuffix = bl.substringAfter(bPrefix).trim()
                if (aSuffix.isNotEmpty() && bSuffix.isNotEmpty() && aSuffix != bSuffix) {
                    if ((aSuffix.contains("short") && bSuffix.contains("long")) ||
                        (aSuffix.contains("long") && bSuffix.contains("short")) ||
                        (aSuffix.contains("dark") && bSuffix.contains("light")) ||
                        (aSuffix.contains("light") && bSuffix.contains("dark"))
                    ) {
                        return true
                    }
                    if (aPrefix.contains("user") &&
                        (aPrefix.contains("name") || aPrefix.contains("prefer") ||
                            aPrefix.contains("likes") || aPrefix.contains("uses"))
                    ) return true
                }
            }
        }
        return false
    }

    fun findContradictions(newContent: String, existing: List<Memory>): List<Memory> =
        existing.filter { isContradictory(newContent, it.content) }

    // ── Deterministic dedup helper ───────────────────────────────────────────
    fun normalizeForDedupe(content: String): String =
        content.lowercase()
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""[^\p{L}\p{N}\s]"""), "")
            .trim()

    // ── Evidence / recency resolver ──────────────────────────────────────────
    /**
     * Picks the winner between conflicting memories. Returns [existing] when it
     * should be kept, or null when the new content should replace it.
     */
    fun resolveConflict(newContent: String, newImportance: Int, newTimestamp: Long, existing: Memory): Memory? {
        if (existing.updatedAt > newTimestamp && existing.effectivePriority >= newImportance) {
            return existing
        }
        if (newTimestamp >= existing.updatedAt && newImportance >= existing.effectivePriority) {
            return null
        }
        val newScore = newImportance * 0.6 + (if (newContent.length > existing.content.length) 0.2 else 0.0)
        val existingScore = existing.effectivePriority * 0.6 + 0.1
        return if (newScore > existingScore) null else existing
    }
}
