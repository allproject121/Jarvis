package com.jarvis.assistant.androllm.memory

import androidx.sqlite.db.SupportSQLiteDatabase
import com.jarvis.assistant.androllm.memory.store.AndroMemoryMigrations
import com.jarvis.assistant.androllm.memory.store.EMBEDDING_TABLE
import com.jarvis.assistant.androllm.memory.store.MEMORY_TAG_CROSS_REF_TABLE
import com.jarvis.assistant.androllm.memory.store.PROJECT_TABLE
import com.jarvis.assistant.androllm.memory.store.RELATIONSHIP_TABLE
import com.jarvis.assistant.androllm.memory.store.TAG_TABLE
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * androllm_memory_meta.db v1 -> v2 must be purely additive: it may only CREATE
 * the new graph tables/indices and must never drop, delete, rename or alter the
 * v1 tables (memory_meta, memory_summaries).
 */
class AndroMemoryMigrationTest {

    private fun recordMigrationSql(): List<String> {
        val executed = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    executed += args!![0] as String
                    null
                }
                "toString" -> "RecordingDb"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException("Migration must only use execSQL, called ${method.name}")
            }
        } as SupportSQLiteDatabase
        AndroMemoryMigrations.MIGRATION_1_2.migrate(db)
        return executed
    }

    @Test
    fun migration_versions_are_1_to_2() {
        assertEquals(1, AndroMemoryMigrations.MIGRATION_1_2.startVersion)
        assertEquals(2, AndroMemoryMigrations.MIGRATION_1_2.endVersion)
    }

    @Test
    fun migration_only_creates_tables_and_indices() {
        val sql = recordMigrationSql()
        assertTrue(sql.isNotEmpty())
        for (statement in sql) {
            val upper = statement.trim().uppercase()
            assertTrue("Non-additive statement: $statement", upper.startsWith("CREATE "))
            assertTrue("Statement must be idempotent: $statement", upper.contains("IF NOT EXISTS"))
            assertFalse(upper.contains("DROP "))
            // "ON DELETE CASCADE" in a foreign key is fine; deleting rows is not.
            assertFalse(upper.contains("DELETE FROM"))
            assertFalse(upper.contains("ALTER "))
        }
    }

    @Test
    fun migration_never_touches_v1_tables() {
        for (statement in recordMigrationSql()) {
            assertFalse(statement.contains("`memory_meta`"))
            assertFalse(statement.contains("`memory_summaries`"))
        }
    }

    @Test
    fun migration_creates_every_v2_table() {
        val sql = recordMigrationSql()
        for (table in listOf(EMBEDDING_TABLE, PROJECT_TABLE, TAG_TABLE, MEMORY_TAG_CROSS_REF_TABLE, RELATIONSHIP_TABLE)) {
            assertTrue("Missing CREATE TABLE for $table", sql.any { it.startsWith("CREATE TABLE IF NOT EXISTS `$table`") })
        }
        assertTrue(sql.any { it.contains("UNIQUE INDEX") && it.contains("`index_${TAG_TABLE}_name`") })
        assertTrue(sql.any { it.contains("UNIQUE INDEX") && it.contains("`index_${PROJECT_TABLE}_name`") })
        assertTrue(sql.any { it.contains("REFERENCES `$TAG_TABLE`(`id`)") && it.contains("ON DELETE CASCADE") })
    }
}
