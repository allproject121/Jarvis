package com.jarvis.assistant.androllm.memory

/**
 * Domain models for the AndroLLM memory pipeline running on top of Jarvis'
 * existing memory store (ported from io.androllm.core.memory.model).
 *
 * A memory's text lives in Jarvis' `memories` table ([com.jarvis.assistant.data.memory.MemoryEntity],
 * id = [id]); the lifecycle fields below come from the separate metadata database.
 */
data class Memory(
    /** Jarvis `memories.id`. */
    val id: Long,
    val category: MemoryCategory = MemoryCategory.CUSTOM,
    /** The Jarvis category string the row is stored under. */
    val jarvisCategory: String = category.jarvisCategory,
    val content: String,
    val importance: Int = 1,
    val tags: List<String> = emptyList(),
    val projectName: String? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val accessCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessedAt: Long? = null,
    val chatId: String? = null,
    val type: MemoryType = MemoryType.LONG_TERM,
    val priority: Int = 1,
    val expiryAt: Long? = null,
    /** Where the memory came from: "jarvis" (legacy/regex learner), "extracted" (LLM), "manual". */
    val source: String = SOURCE_JARVIS
) {
    /** Priority/importance are kept in sync — priority is canonical. */
    val effectivePriority: Int
        get() = priority.coerceIn(1, 5).let { if (it == 1 && importance != 1) importance.coerceIn(1, 5) else it }

    companion object {
        const val SOURCE_JARVIS = "jarvis"
        const val SOURCE_EXTRACTED = "extracted"
        const val SOURCE_MANUAL = "manual"
    }
}

/** A memory as extracted by the LLM (before persistence). */
data class ExtractedMemory(
    val content: String,
    val category: MemoryCategory = MemoryCategory.CUSTOM,
    val importance: Int = 1,
    val tags: List<String> = emptyList(),
    val projectName: String? = null
)

enum class MemoryWriteAction { INSERTED, UPDATED, SKIPPED }

data class MemoryWriteResult(
    val memoryId: Long,
    val action: MemoryWriteAction,
    val similarityScore: Float? = null,
    val reason: String? = null
)

/** Aggregated outcome of processing one conversation exchange. */
data class MemoryWriteSummary(
    val inserted: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val extracted: Int = 0,
    val summarized: Boolean = false
)

/** A rolling conversation summary, stored in the AndroLLM metadata database. */
data class MemorySummary(
    val conversationId: String,
    val summary: String,
    val messageCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

/** A retrieved memory together with its similarity score (0..1). */
data class MemorySearchResult(
    val memory: Memory,
    val score: Float,
    val matchedByKeyword: Boolean = false
)

/** Retrieved memories + summaries formatted for system prompt injection. */
data class MemoryContext(
    val memories: List<MemorySearchResult> = emptyList(),
    val summaries: List<MemorySummary> = emptyList(),
    val retrievalMs: Long = 0L,
    val systemText: String = ""
) {
    val isEmpty: Boolean get() = memories.isEmpty() && summaries.isEmpty()
}

/** A single exchange fed into the extraction pipeline. */
data class MemoryExchange(
    val conversationId: String,
    val userMessage: String,
    val assistantResponse: String,
    /** The most recent chat turns (role to content), oldest first. */
    val recentMessages: List<Pair<String, String>> = emptyList(),
    /** Total message count in the conversation (for summarization scheduling). */
    val messageCount: Int = 0
) {
    val isManual: Boolean
        get() = userMessage.isBlank() && assistantResponse.isBlank() && recentMessages.isEmpty()

    companion object {
        /** Exchange used for manual saves: skips grounding checks. */
        fun manual(): MemoryExchange = MemoryExchange("", "", "")
    }
}

/** Snapshot for the memory settings section. */
data class MemoryStats(
    val memoryCount: Int = 0,
    val indexedCount: Int = 0,
    val pinnedCount: Int = 0,
    val archivedCount: Int = 0,
    val extractedCount: Int = 0,
    val summaryCount: Int = 0,
    val lastIndexingAt: Long = 0L,
    val lastIndexingResult: String = "",
    val lastExtractionAt: Long = 0L,
    val lastExtractionResult: String = ""
)

/** Outcome of a memory import (ported from AndroLLM MemoryImportResult). */
data class MemoryImportResult(
    val inserted: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    /** Relationships restored from the export file. */
    val relationships: Int = 0
)

/** A named project that groups memories (e.g. "Jarvis", "Portfolio site"). */
data class Project(
    val id: String,
    val name: String,
    val description: String = "",
    val createdAt: Long,
    /** Number of memories assigned to the project (UI convenience). */
    val memoryCount: Int = 0
)

/** A normalized tag attached to memories. */
data class Tag(
    val id: String,
    val name: String,
    /** Number of memories carrying the tag (UI convenience). */
    val memoryCount: Int = 0
)

/** A typed link between two Jarvis memories. */
data class Relationship(
    val id: String,
    val fromMemoryId: Long,
    val toMemoryId: Long,
    val type: String,
    val createdAt: Long
)

/** Filtering options for memory retrieval / listing. */
data class MemorySearchFilters(
    val category: MemoryCategory? = null,
    /** Project id (see [Project.id]). */
    val projectId: String? = null,
    val chatId: String? = null,
    val type: MemoryType? = null,
    val pinnedOnly: Boolean = false,
    /** Match-any tag names. */
    val tags: Set<String> = emptySet(),
    val minImportance: Int = 0,
    val includeArchived: Boolean = false,
    val includeExpired: Boolean = false,
    val minPriority: Int = 0
) {
    val isEmpty: Boolean
        get() = category == null && projectId == null && chatId == null && type == null && !pinnedOnly &&
            tags.isEmpty() && minImportance <= 0 && minPriority <= 0 && !includeArchived && !includeExpired
}

/** Snapshot of everything the Memory Inspector renders. */
data class MemoryInspectorStats(
    val memoryCount: Int = 0,
    val embeddingCount: Int = 0,
    val vectorCount: Int = 0,
    val projectCount: Int = 0,
    val tagCount: Int = 0,
    val summaryCount: Int = 0,
    val relationshipCount: Int = 0,
    val avgRetrievalMs: Long = 0L,
    val lastRetrievalMs: Long = 0L,
    val lastEmbeddingMs: Long = 0L,
    val lastExtractionMs: Long = 0L,
    val totalExtractions: Long = 0L,
    val totalEmbeddings: Long = 0L,
    val totalInserted: Long = 0L,
    val totalUpdated: Long = 0L,
    val retrievalCount: Int = 5,
    val similarityThreshold: Float = MemorySettings.DEFAULT_THRESHOLD,
    val embeddingSource: String = "",
    val embedderSignature: String = "",
    val embeddingModelPath: String = "",
    /** Cloud embedding model id ("" = none). */
    val cloudEmbeddingModel: String = "",
    val embeddingDimension: Int = 0,
    val embeddingModelLoaded: Boolean = false,
    val enabled: Boolean = false,
    val logs: List<MemoryLogEntry> = emptyList()
)

/** A single memory pipeline log entry (extraction / embedding / retrieval). */
data class MemoryLogEntry(
    val timestamp: Long,
    val level: MemoryLogLevel,
    val message: String
)

enum class MemoryLogLevel { DEBUG, INFO, WARN, ERROR }
