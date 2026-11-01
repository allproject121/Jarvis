package com.jarvis.assistant.androllm.memory.classify

import com.jarvis.assistant.androllm.memory.ExtractedMemory
import com.jarvis.assistant.androllm.memory.MemoryCategory
import com.jarvis.assistant.androllm.memory.MemoryType

/**
 * Classifies extracted memories into lifecycle types and handles prompt-memory intents.
 * Determines type, priority, and expiry based on content stability and scope.
 * Pure and deterministic.
 */
object MemoryClassifier {

    /** Classifies the lifecycle type for an extracted memory. */
    fun classifyType(extracted: ExtractedMemory, @Suppress("UNUSED_PARAMETER") chatId: String?): MemoryType {
        val contentLower = extracted.content.lowercase()
        val hasProject = !extracted.projectName.isNullOrBlank() || extracted.category == MemoryCategory.PROJECTS

        if (hasProject || contentLower.contains("project context") || contentLower.contains("remember this project")) {
            return MemoryType.PROJECT
        }
        if (contentLower.contains("recent") || contentLower.contains("last chat") ||
            contentLower.contains("previous prompt") || contentLower.contains("frequent") ||
            contentLower.contains("recurring task") || contentLower.contains("recent prompt")
        ) {
            return MemoryType.SESSION
        }
        if (contentLower.contains("current conversation") || contentLower.contains("this chat") ||
            contentLower.contains("right now") || contentLower.contains("in this session") ||
            (extracted.category == MemoryCategory.CUSTOM && contentLower.contains("context"))
        ) {
            return MemoryType.SHORT_TERM
        }
        return MemoryType.LONG_TERM
    }

    /** Computes priority (1..5) from category, content signals and explicit importance. */
    fun computePriority(extracted: ExtractedMemory, type: MemoryType): Int {
        var priority = extracted.importance.coerceIn(1, 5)
        val promptSignals = listOf("prefer", "喜欢", "format", "code block", "short prompt", "tone", "template", "instruction")
        if (promptSignals.any { it in extracted.content.lowercase() }) {
            priority = maxOf(priority, 4)
        }
        when (type) {
            MemoryType.LONG_TERM -> priority = maxOf(priority, 3)
            MemoryType.PROJECT -> priority = maxOf(priority, 3)
            MemoryType.SESSION -> priority = minOf(priority, 3)
            MemoryType.SHORT_TERM -> priority = minOf(priority, 2)
        }
        if (extracted.category == MemoryCategory.PINNED_FACTS) priority = 5
        return priority.coerceIn(1, 5)
    }

    /** Expiry timestamp from the type's default TTL. Null = never expires. */
    fun computeExpiry(type: MemoryType, now: Long = System.currentTimeMillis()): Long? {
        val ttl = type.defaultTtlMs ?: return null
        return now + ttl
    }

    /** Detects prompt-memory intent: how the user likes prompts formatted, tone, templates. */
    fun isPromptMemory(content: String): Boolean {
        val lower = content.lowercase()
        return listOf(
            "prompt", "format", "template", "tone", "style", "code block", "copyable",
            "instruction", "prefer short", "prefer long", "writing style", "use this formatting"
        ).any { it in lower }
    }
}
