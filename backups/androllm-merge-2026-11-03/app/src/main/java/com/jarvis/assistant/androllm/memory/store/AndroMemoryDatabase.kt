package com.jarvis.assistant.androllm.memory.store

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * AndroLLM lifecycle metadata for a Jarvis memory row.
 *
 * Lives in its OWN database file ([AndroMemoryDatabase.DATABASE_NAME]) keyed by
 * Jarvis `memories.id`, so Jarvis' existing `jarvis_memory.db` (which still uses
 * a destructive-fallback builder) never needs a schema change or migration.
 * A memory without a meta row simply behaves as a plain Jarvis memory.
 */
@Entity(tableName = "memory_meta")
data class MemoryMetaEntity(
    @PrimaryKey val memoryId: Long,
    val androCategory: String = "CUSTOM",
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val accessCount: Int = 0,
    val lastAccessedAt: Long? = null,
    val priority: Int = 1,
    val importance: Int = 1,
    val type: String = "LONG_TERM",
    val expiryAt: Long? = null,
    val chatId: String? = null,
    /** Comma separated, lower-cased tags. */
    val tags: String = "",
    val projectName: String? = null,
    val source: String = "jarvis",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val tagList: List<String>
        get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Rolling conversation summary (AndroLLM SummaryEntity). */
@Entity(tableName = "memory_summaries")
data class MemorySummaryEntity(
    @PrimaryKey val conversationId: String,
    val summary: String,
    val messageCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

@Dao
interface MemoryMetaDao {
    @Query("SELECT * FROM memory_meta")
    suspend fun getAll(): List<MemoryMetaEntity>

    @Query("SELECT * FROM memory_meta WHERE memoryId = :id")
    suspend fun getById(id: Long): MemoryMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: MemoryMetaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(meta: List<MemoryMetaEntity>)

    @Query("DELETE FROM memory_meta WHERE memoryId = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memory_meta WHERE memoryId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM memory_meta")
    suspend fun deleteAll()

    @Query("UPDATE memory_meta SET isPinned = :pinned, updatedAt = :now WHERE memoryId = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, now: Long)

    @Query("UPDATE memory_meta SET isArchived = :archived, updatedAt = :now WHERE memoryId = :id")
    suspend fun setArchived(id: Long, archived: Boolean, now: Long)

    @Query("UPDATE memory_meta SET accessCount = accessCount + 1, lastAccessedAt = :now WHERE memoryId IN (:ids)")
    suspend fun recordAccess(ids: List<Long>, now: Long)

    @Query("SELECT * FROM memory_meta WHERE isArchived = 0 AND expiryAt IS NOT NULL AND expiryAt <= :now AND isPinned = 0")
    suspend fun getExpired(now: Long): List<MemoryMetaEntity>
}

@Dao
interface MemorySummaryDao {
    @Query("SELECT * FROM memory_summaries WHERE conversationId = :conversationId")
    suspend fun getForConversation(conversationId: String): MemorySummaryEntity?

    @Query("SELECT * FROM memory_summaries ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MemorySummaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: MemorySummaryEntity)

    @Query("SELECT COUNT(*) FROM memory_summaries")
    suspend fun count(): Int

    @Query("DELETE FROM memory_summaries WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)

    @Query("DELETE FROM memory_summaries")
    suspend fun deleteAll()
}

/**
 * Separate Room database for AndroLLM memory metadata, summaries and the
 * memory graph (embeddings, projects, tags, relationships).
 *
 * Version history (never destructive — no fallbackToDestructiveMigration):
 *  - v1: memory_meta + memory_summaries.
 *  - v2: + memory_embeddings, memory_projects, memory_tags,
 *        memory_tag_cross_ref, memory_relationships ([MIGRATION_1_2]; only
 *        CREATE statements, existing rows untouched).
 * Future schema changes MUST add another real [Migration].
 */
@Database(
    entities = [
        MemoryMetaEntity::class,
        MemorySummaryEntity::class,
        EmbeddingEntity::class,
        ProjectEntity::class,
        TagEntity::class,
        MemoryTagCrossRef::class,
        RelationshipEntity::class
    ],
    version = AndroMemoryDatabase.VERSION,
    exportSchema = false
)
abstract class AndroMemoryDatabase : RoomDatabase() {
    abstract fun metaDao(): MemoryMetaDao
    abstract fun summaryDao(): MemorySummaryDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun projectDao(): ProjectDao
    abstract fun tagDao(): TagDao
    abstract fun relationshipDao(): RelationshipDao

    companion object {
        const val DATABASE_NAME = "androllm_memory_meta.db"
        const val VERSION = 2

        /** v1 -> v2 (see [AndroMemoryMigrations.MIGRATION_1_2]). */
        val MIGRATION_1_2: Migration get() = AndroMemoryMigrations.MIGRATION_1_2

        @Volatile
        private var instance: AndroMemoryDatabase? = null

        fun getInstance(context: Context): AndroMemoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AndroMemoryDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(*AndroMemoryMigrations.ALL)
                    .build()
                    .also { instance = it }
            }
    }
}

/**
 * Explicit, non-destructive migrations for [AndroMemoryDatabase]. Kept in a
 * plain object (no RoomDatabase subclass) so they are unit-testable on the JVM.
 */
object AndroMemoryMigrations {
    /**
     * SQL executed by [MIGRATION_1_2]. Mirrors exactly what Room generates for
     * the v2 entities (column affinities, NOT NULL, primary keys, foreign key
     * and index names) so Room's post-migration schema validation passes.
     */
    val MIGRATION_1_2_SQL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `$EMBEDDING_TABLE` (`memory_id` INTEGER NOT NULL, `vector` BLOB NOT NULL, " +
            "`dimension` INTEGER NOT NULL, `model_path` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
            "PRIMARY KEY(`memory_id`))",
        "CREATE INDEX IF NOT EXISTS `index_${EMBEDDING_TABLE}_dimension` ON `$EMBEDDING_TABLE` (`dimension`)",
        "CREATE INDEX IF NOT EXISTS `index_${EMBEDDING_TABLE}_model_path` ON `$EMBEDDING_TABLE` (`model_path`)",
        "CREATE TABLE IF NOT EXISTS `$PROJECT_TABLE` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`description` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_${PROJECT_TABLE}_name` ON `$PROJECT_TABLE` (`name`)",
        "CREATE TABLE IF NOT EXISTS `$TAG_TABLE` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_${TAG_TABLE}_name` ON `$TAG_TABLE` (`name`)",
        "CREATE TABLE IF NOT EXISTS `$MEMORY_TAG_CROSS_REF_TABLE` (`memory_id` INTEGER NOT NULL, " +
            "`tag_id` TEXT NOT NULL, PRIMARY KEY(`memory_id`, `tag_id`), " +
            "FOREIGN KEY(`tag_id`) REFERENCES `$TAG_TABLE`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_${MEMORY_TAG_CROSS_REF_TABLE}_tag_id` ON `$MEMORY_TAG_CROSS_REF_TABLE` (`tag_id`)",
        "CREATE TABLE IF NOT EXISTS `$RELATIONSHIP_TABLE` (`id` TEXT NOT NULL, `from_memory_id` INTEGER NOT NULL, " +
            "`to_memory_id` INTEGER NOT NULL, `type` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_${RELATIONSHIP_TABLE}_from_memory_id` ON `$RELATIONSHIP_TABLE` (`from_memory_id`)",
        "CREATE INDEX IF NOT EXISTS `index_${RELATIONSHIP_TABLE}_to_memory_id` ON `$RELATIONSHIP_TABLE` (`to_memory_id`)"
    )

    /** v1 -> v2: additive — creates the memory graph tables, keeps every v1 row. */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_1_2_SQL.forEach { db.execSQL(it) }
        }
    }

    val ALL: Array<Migration> get() = arrayOf(MIGRATION_1_2)
}
