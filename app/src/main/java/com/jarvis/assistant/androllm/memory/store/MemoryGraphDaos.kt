package com.jarvis.assistant.androllm.memory.store

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** AndroLLM name for the summary DAO (already in schema v1). */
typealias SummaryDao = MemorySummaryDao

@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(embedding: EmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(embeddings: List<EmbeddingEntity>)

    @Query("SELECT * FROM $EMBEDDING_TABLE WHERE memory_id = :memoryId")
    suspend fun getByMemoryId(memoryId: Long): EmbeddingEntity?

    @Query("SELECT * FROM $EMBEDDING_TABLE")
    suspend fun getAll(): List<EmbeddingEntity>

    @Query("SELECT * FROM $EMBEDDING_TABLE WHERE model_path = :modelPath")
    suspend fun getAllForModel(modelPath: String): List<EmbeddingEntity>

    @Query("SELECT * FROM $EMBEDDING_TABLE WHERE memory_id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<EmbeddingEntity>

    @Query("SELECT memory_id FROM $EMBEDDING_TABLE WHERE model_path = :modelPath")
    suspend fun getEmbeddedIds(modelPath: String): List<Long>

    @Query("SELECT COUNT(*) FROM $EMBEDDING_TABLE")
    suspend fun count(): Int

    @Query("DELETE FROM $EMBEDDING_TABLE WHERE memory_id = :memoryId")
    suspend fun deleteByMemoryId(memoryId: Long): Int

    @Query("DELETE FROM $EMBEDDING_TABLE WHERE memory_id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("DELETE FROM $EMBEDDING_TABLE WHERE model_path != :modelPath")
    suspend fun deleteByModelPathNot(modelPath: String): Int

    @Query("DELETE FROM $EMBEDDING_TABLE")
    suspend fun deleteAll(): Int
}

@Dao
interface ProjectDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("SELECT * FROM $PROJECT_TABLE WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): ProjectEntity?

    @Query("SELECT * FROM $PROJECT_TABLE WHERE id = :id")
    suspend fun getById(id: String): ProjectEntity?

    @Query("SELECT * FROM $PROJECT_TABLE ORDER BY name ASC")
    suspend fun getAll(): List<ProjectEntity>

    @Query("SELECT COUNT(*) FROM $PROJECT_TABLE")
    suspend fun count(): Int

    @Query("DELETE FROM $PROJECT_TABLE WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM $PROJECT_TABLE")
    suspend fun deleteAll(): Int
}

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: TagEntity)

    @Query("SELECT * FROM $TAG_TABLE WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    @Query("SELECT * FROM $TAG_TABLE WHERE id = :id")
    suspend fun getById(id: String): TagEntity?

    @Query("SELECT * FROM $TAG_TABLE ORDER BY name ASC")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT COUNT(*) FROM $TAG_TABLE")
    suspend fun count(): Int

    @Query("DELETE FROM $TAG_TABLE WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM $TAG_TABLE")
    suspend fun deleteAll(): Int

    /** Tags no memory references any more. */
    @Query("DELETE FROM $TAG_TABLE WHERE id NOT IN (SELECT DISTINCT tag_id FROM $MEMORY_TAG_CROSS_REF_TABLE)")
    suspend fun deleteUnused(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRef(crossRef: MemoryTagCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRefs(crossRefs: List<MemoryTagCrossRef>)

    @Query("DELETE FROM $MEMORY_TAG_CROSS_REF_TABLE WHERE memory_id = :memoryId")
    suspend fun deleteCrossRefsForMemory(memoryId: Long): Int

    @Query("DELETE FROM $MEMORY_TAG_CROSS_REF_TABLE WHERE memory_id IN (:memoryIds)")
    suspend fun deleteCrossRefsForMemories(memoryIds: List<Long>): Int

    @Query("DELETE FROM $MEMORY_TAG_CROSS_REF_TABLE WHERE tag_id = :tagId")
    suspend fun deleteCrossRefsForTag(tagId: String): Int

    @Query(
        "SELECT t.name FROM $MEMORY_TAG_CROSS_REF_TABLE c JOIN $TAG_TABLE t ON t.id = c.tag_id " +
            "WHERE c.memory_id = :memoryId ORDER BY t.name"
    )
    suspend fun getTagNamesForMemory(memoryId: Long): List<String>

    @Query("SELECT memory_id FROM $MEMORY_TAG_CROSS_REF_TABLE WHERE tag_id = :tagId")
    suspend fun getMemoryIdsForTag(tagId: String): List<Long>

    @Query(
        "SELECT DISTINCT c.memory_id FROM $MEMORY_TAG_CROSS_REF_TABLE c JOIN $TAG_TABLE t ON t.id = c.tag_id " +
            "WHERE t.name LIKE '%' || :query || '%'"
    )
    suspend fun searchMemoryIdsByTag(query: String): List<Long>

    /** (tag name, memory count) pairs for the settings tag list. */
    @Query(
        "SELECT t.id AS id, t.name AS name, COUNT(c.memory_id) AS memoryCount FROM $TAG_TABLE t " +
            "LEFT JOIN $MEMORY_TAG_CROSS_REF_TABLE c ON c.tag_id = t.id GROUP BY t.id ORDER BY t.name ASC"
    )
    suspend fun getTagUsage(): List<TagUsageRow>

    @Delete
    suspend fun deleteCrossRef(crossRef: MemoryTagCrossRef)

    data class TagUsageRow(
        val id: String,
        val name: String,
        val memoryCount: Int
    )
}

@Dao
interface RelationshipDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(relationship: RelationshipEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(relationships: List<RelationshipEntity>)

    @Query("SELECT * FROM $RELATIONSHIP_TABLE WHERE from_memory_id = :memoryId OR to_memory_id = :memoryId")
    suspend fun getForMemory(memoryId: Long): List<RelationshipEntity>

    @Query("SELECT * FROM $RELATIONSHIP_TABLE ORDER BY created_at DESC")
    suspend fun getAll(): List<RelationshipEntity>

    @Query("SELECT COUNT(*) FROM $RELATIONSHIP_TABLE")
    suspend fun count(): Int

    @Query("DELETE FROM $RELATIONSHIP_TABLE WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM $RELATIONSHIP_TABLE WHERE from_memory_id IN (:memoryIds) OR to_memory_id IN (:memoryIds)")
    suspend fun deleteForMemories(memoryIds: List<Long>): Int

    @Query("DELETE FROM $RELATIONSHIP_TABLE")
    suspend fun deleteAll(): Int
}
