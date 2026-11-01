package com.jarvis.assistant.androllm.memory

import com.jarvis.assistant.data.memory.MemoryEntity

/**
 * AndroLLM long-term memory taxonomy (ported from io.androllm.core.memory.MemoryCategory).
 *
 * Jarvis stores memories in its own `memories` table with five coarse categories
 * ([MemoryEntity.ALL_CATEGORIES]). The richer AndroLLM category is kept in the
 * separate metadata database ([com.jarvis.assistant.androllm.memory.store.MemoryMetaEntity])
 * and mapped onto a Jarvis category for the existing table, so Jarvis UI/filters
 * keep working unchanged.
 */
enum class MemoryCategory(val label: String) {
    IDENTITY("Identity"),
    PREFERENCES("Preferences"),
    PROJECTS("Projects"),
    GOALS("Goals"),
    SKILLS("Skills"),
    PROGRAMMING_LANGUAGES("Programming Languages"),
    FRAMEWORKS("Frameworks"),
    DEVICES("Devices"),
    PINNED_FACTS("Pinned Facts"),
    DEVELOPER_NOTES("Developer Notes"),
    CUSTOM("Custom");

    /** The Jarvis `memories.category` value this AndroLLM category is stored under. */
    val jarvisCategory: String
        get() = when (this) {
            IDENTITY, SKILLS, DEVICES -> MemoryEntity.CATEGORY_PERSONAL
            PREFERENCES -> MemoryEntity.CATEGORY_PREFERENCES
            PROJECTS, GOALS, PROGRAMMING_LANGUAGES, FRAMEWORKS,
            PINNED_FACTS, DEVELOPER_NOTES, CUSTOM -> MemoryEntity.CATEGORY_NOTES
        }

    companion object {
        /**
         * Parses any casing/format of a category name coming from LLM JSON
         * and falls back to [CUSTOM] for unknown values.
         */
        fun fromName(raw: String?): MemoryCategory {
            if (raw.isNullOrBlank()) return CUSTOM
            val normalized = raw.trim().uppercase().replace(' ', '_').replace('-', '_')
            return entries.firstOrNull { it.name == normalized } ?: CUSTOM
        }

        /** Best-effort reverse mapping for legacy Jarvis rows without metadata. */
        fun fromJarvisCategory(jarvisCategory: String?): MemoryCategory = when {
            jarvisCategory.equals(MemoryEntity.CATEGORY_PREFERENCES, ignoreCase = true) -> PREFERENCES
            jarvisCategory.equals(MemoryEntity.CATEGORY_PERSONAL, ignoreCase = true) -> IDENTITY
            else -> CUSTOM
        }
    }
}

/**
 * Lifecycle type for memories (ported from io.androllm.core.memory.MemoryType).
 */
enum class MemoryType(val label: String, val defaultTtlMs: Long?) {
    /** Current conversation context — short lived. */
    SHORT_TERM("Short-term", null),

    /** Stable user preferences and important facts — persisted indefinitely. */
    LONG_TERM("Long-term", null),

    /** Recent chats and recent prompts — 7-day sliding window. */
    SESSION("Session", 7 * 24 * 60 * 60 * 1000L),

    /** Context tied to a specific project or chat thread. */
    PROJECT("Project", 30 * 24 * 60 * 60 * 1000L);

    companion object {
        fun fromName(raw: String?): MemoryType {
            if (raw.isNullOrBlank()) return LONG_TERM
            val normalized = raw.trim().uppercase().replace(' ', '_').replace('-', '_')
            return entries.firstOrNull { it.name == normalized } ?: LONG_TERM
        }
    }
}
