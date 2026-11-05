package com.jarvis.assistant.androllm.memory.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * AndroLLM memory "graph" tables (ported from io.androllm.core.memory.db.entity):
 * embeddings, projects, tags (+ memory/tag join) and typed relationships.
 *
 * They live in androllm_memory_meta.db (schema v2, added by
 * [AndroMemoryDatabase.MIGRATION_1_2]) and are keyed by Jarvis `memories.id`
 * (Long). Jarvis' memory rows live in a DIFFERENT database file, so there is no
 * SQL foreign key to them; [com.jarvis.assistant.data.memory.MemoryRepository.deleteMeta] removes dependent rows.
 */

const val EMBEDDING_TABLE = "memory_embeddings"
const val PROJECT_TABLE = "memory_projects"
const val TAG_TABLE = "memory_tags"
const val MEMORY_TAG_CROSS_REF_TABLE = "memory_tag_cross_ref"
const val RELATIONSHIP_TABLE = "memory_relationships"

/** AndroLLM name for the rolling conversation summary row (already in schema v1). */
typealias SummaryEntity = MemorySummaryEntity

/**
 * The persisted neural embedding of a memory. Stored as a raw float32 BLOB
 * (little endian); [dimension] and [modelPath] (embedding source label) let rows
 * from a different embedding model be detected and re-embedded safely.
 */
@Entity(
    tableName = EMBEDDING_TABLE,
    indices = [Index(value = ["dimension"]), Index(value = ["model_path"])]
)
data class EmbeddingEntity(
    @PrimaryKey @ColumnInfo(name = "memory_id") val memoryId: Long,
    val vector: ByteArray,
    val dimension: Int,
    @ColumnInfo(name = "model_path") val modelPath: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingEntity) return false
        return memoryId == other.memoryId && dimension == other.dimension &&
            modelPath == other.modelPath && createdAt == other.createdAt &&
            vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int = memoryId.hashCode()
}

/** A project grouping memories by name (unique). */
@Entity(
    tableName = PROJECT_TABLE,
    indices = [Index(value = ["name"], unique = true)]
)
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/** A normalized (lower-cased) tag, unique by name. */
@Entity(
    tableName = TAG_TABLE,
    indices = [Index(value = ["name"], unique = true)]
)
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String
)

/** Many-to-many join between Jarvis memories and tags. */
@Entity(
    tableName = MEMORY_TAG_CROSS_REF_TABLE,
    primaryKeys = ["memory_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["tag_id"])]
)
data class MemoryTagCrossRef(
    @ColumnInfo(name = "memory_id") val memoryId: Long,
    @ColumnInfo(name = "tag_id") val tagId: String
)

/** A typed link between two Jarvis memories (e.g. "related_to"). */
@Entity(
    tableName = RELATIONSHIP_TABLE,
    indices = [Index(value = ["from_memory_id"]), Index(value = ["to_memory_id"])]
)
data class RelationshipEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "from_memory_id") val fromMemoryId: Long,
    @ColumnInfo(name = "to_memory_id") val toMemoryId: Long,
    val type: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
