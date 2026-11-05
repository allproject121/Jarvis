package com.jarvis.assistant.androllm.memory.correction

import com.jarvis.assistant.ai.memory.MemoryWritePolicy
import com.jarvis.assistant.androllm.memory.hardening.MemoryHardeningHelper

/**
 * Finds the EXISTING memory a user correction supersedes.
 *
 * Fixes the Jarvis bug where `MemoryManager.rememberFact` searched the store
 * for the text after the correction signal — i.e. the NEW value — which can
 * never match the OLD memory. Resolution order (first hit wins):
 *  1. contradiction detection ([MemoryHardeningHelper.isContradictory]) between
 *     the corrected statement and each candidate (same fact slot, different value);
 *  2. the explicitly named old value ("tea instead of coffee" -> "coffee");
 *  3. the subject hint ("name", "phone", "my email") with the strongest word overlap.
 * Ties are broken by word overlap, then recency. Pure / deterministic.
 */
object MemoryCorrectionResolver {

    data class Candidate(val id: Long, val text: String, val timestamp: Long)

    private val stopWords = setOf(
        "user", "users", "actually", "meant", "correction", "mistake", "change",
        "update", "should", "instead", "rather", "that", "this", "with", "from",
        "have", "mera", "meri", "hai", "the", "and", "for", "you", "are", "was",
        "not", "now", "but", "has", "had", "can", "his", "her", "its", "our", "is"
    )

    fun findTarget(
        newStatement: String,
        decision: MemoryWritePolicy.Decision.Update,
        candidates: List<Candidate>
    ): Candidate? {
        val newNorm = MemoryHardeningHelper.normalizeForDedupe(newStatement)
        val pool = candidates.filter {
            it.text.isNotBlank() && MemoryHardeningHelper.normalizeForDedupe(it.text) != newNorm
        }
        if (pool.isEmpty()) return null
        val newWords = words(newStatement)

        // 1) Same fact slot, different value.
        pool.filter { MemoryHardeningHelper.isContradictory(newStatement, it.text) }
            .best(newWords)?.let { return it }

        // 2) The superseded value is named explicitly.
        decision.oldValue?.trim()?.takeIf { it.length >= 2 }?.let { old ->
            pool.filter { it.text.contains(old, ignoreCase = true) }.best(newWords)?.let { return it }
            val oldWords = words(old)
            if (oldWords.isNotEmpty()) {
                pool.filter { c -> words(c.text).containsAll(oldWords) }.best(newWords)?.let { return it }
            }
        }

        // 3) Subject hint ("name", "phone", "my email").
        decision.subjectHint?.trim()?.takeIf { it.length >= 3 }?.let { hint ->
            val hintWords = words(hint)
            val matches = pool.filter { c ->
                c.text.contains(hint, ignoreCase = true) ||
                    (hintWords.isNotEmpty() && words(c.text).intersect(hintWords).isNotEmpty())
            }
            // Require some topical overlap beyond the hint itself when several match.
            if (matches.size == 1) return matches.first()
            matches.filter { c -> (words(c.text) - hintWords).intersect(newWords - hintWords).isNotEmpty() }
                .best(newWords)?.let { return it }
        }
        return null
    }

    private fun List<Candidate>.best(newWords: Set<String>): Candidate? =
        maxWithOrNull(
            compareBy<Candidate> { words(it.text).intersect(newWords).size }
                .thenBy { it.timestamp }
        )

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("""\W+"""))
            .filter { it.length > 2 && it !in stopWords }
            // Light stemming so "lives"/"live", "prefers"/"prefer" match.
            .map { if (it.length > 4 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }
            .toSet()
}
