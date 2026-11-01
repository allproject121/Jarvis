package com.jarvis.assistant.androllm.chat.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Data access object for conversations (ported from AndroLLM ConversationDao). */
@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(conversations: List<ConversationEntity>)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM conversations")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    suspend fun getAll(): List<ConversationEntity>

    @Query("SELECT COUNT(*) FROM conversations")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM conversations WHERE id = :id")
    suspend fun existsById(id: String): Boolean

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int

    @Query("UPDATE conversations SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long): Int

    @Query("UPDATE conversations SET updated_at = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long): Int

    @Query("UPDATE conversations SET is_pinned = :isPinned WHERE id = :id")
    suspend fun updatePinned(id: String, isPinned: Boolean): Int

    @Query("UPDATE conversations SET is_archived = :isArchived WHERE id = :id")
    suspend fun updateArchived(id: String, isArchived: Boolean): Int

    @Query("SELECT * FROM conversations WHERE is_pinned = 1 AND is_archived = 0 ORDER BY updated_at DESC")
    fun observePinned(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE is_archived = 0 ORDER BY is_pinned DESC, updated_at DESC")
    fun observeActive(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE is_archived = 1 ORDER BY updated_at DESC")
    fun observeArchived(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE title LIKE '%' || :query || '%' ORDER BY updated_at DESC")
    fun searchByTitle(query: String): Flow<List<ConversationEntity>>

    // ── Live-computed statistics queries ────────────────────────────────────

    @Query(
        """
        SELECT
            c.id,
            c.title,
            c.created_at AS createdAt,
            c.is_pinned AS isPinned,
            c.is_archived AS isArchived,
            COUNT(m.id) AS messageCount,
            (SELECT m2.content FROM messages m2
             WHERE m2.conversation_id = c.id
             ORDER BY m2.timestamp DESC LIMIT 1) AS lastMessagePreview,
            MAX(m.timestamp) AS lastMessageTimestamp
        FROM conversations c
        LEFT JOIN messages m ON c.id = m.conversation_id
        GROUP BY c.id
        ORDER BY COALESCE(MAX(m.timestamp), c.updated_at) DESC
        """
    )
    fun observeAllWithStats(): Flow<List<ConversationWithStats>>

    @Query(
        """
        SELECT
            c.id,
            c.title,
            c.created_at AS createdAt,
            c.is_pinned AS isPinned,
            c.is_archived AS isArchived,
            COUNT(m.id) AS messageCount,
            (SELECT m2.content FROM messages m2
             WHERE m2.conversation_id = c.id
             ORDER BY m2.timestamp DESC LIMIT 1) AS lastMessagePreview,
            MAX(m.timestamp) AS lastMessageTimestamp
        FROM conversations c
        LEFT JOIN messages m ON c.id = m.conversation_id
        WHERE c.is_archived = 0
        GROUP BY c.id
        ORDER BY c.is_pinned DESC, COALESCE(MAX(m.timestamp), c.updated_at) DESC
        """
    )
    fun observeActiveWithStats(): Flow<List<ConversationWithStats>>

    @Query(
        """
        SELECT
            c.id,
            c.title,
            c.created_at AS createdAt,
            c.is_pinned AS isPinned,
            c.is_archived AS isArchived,
            COUNT(m.id) AS messageCount,
            (SELECT m2.content FROM messages m2
             WHERE m2.conversation_id = c.id
             ORDER BY m2.timestamp DESC LIMIT 1) AS lastMessagePreview,
            MAX(m.timestamp) AS lastMessageTimestamp
        FROM conversations c
        LEFT JOIN messages m ON c.id = m.conversation_id
        WHERE c.is_pinned = 1 AND c.is_archived = 0
        GROUP BY c.id
        ORDER BY COALESCE(MAX(m.timestamp), c.updated_at) DESC
        """
    )
    fun observePinnedWithStats(): Flow<List<ConversationWithStats>>

    @Query(
        """
        SELECT
            c.id,
            c.title,
            c.created_at AS createdAt,
            c.is_pinned AS isPinned,
            c.is_archived AS isArchived,
            COUNT(m.id) AS messageCount,
            (SELECT m2.content FROM messages m2
             WHERE m2.conversation_id = c.id
             ORDER BY m2.timestamp DESC LIMIT 1) AS lastMessagePreview,
            MAX(m.timestamp) AS lastMessageTimestamp
        FROM conversations c
        LEFT JOIN messages m ON c.id = m.conversation_id
        WHERE c.is_archived = 1
        GROUP BY c.id
        ORDER BY COALESCE(MAX(m.timestamp), c.updated_at) DESC
        """
    )
    fun observeArchivedWithStats(): Flow<List<ConversationWithStats>>

    @Query(
        """
        SELECT
            c.id,
            c.title,
            c.created_at AS createdAt,
            c.is_pinned AS isPinned,
            c.is_archived AS isArchived,
            COUNT(m.id) AS messageCount,
            (SELECT m2.content FROM messages m2
             WHERE m2.conversation_id = c.id
             ORDER BY m2.timestamp DESC LIMIT 1) AS lastMessagePreview,
            MAX(m.timestamp) AS lastMessageTimestamp
        FROM conversations c
        LEFT JOIN messages m ON c.id = m.conversation_id
        WHERE c.title LIKE '%' || :query || '%'
        GROUP BY c.id
        ORDER BY COALESCE(MAX(m.timestamp), c.updated_at) DESC
        """
    )
    fun searchByTitleWithStats(query: String): Flow<List<ConversationWithStats>>

    @Query(
        """
        SELECT
            c.id,
            c.title,
            c.created_at AS createdAt,
            c.is_pinned AS isPinned,
            c.is_archived AS isArchived,
            COUNT(m.id) AS messageCount,
            (SELECT m2.content FROM messages m2
             WHERE m2.conversation_id = c.id
             ORDER BY m2.timestamp DESC LIMIT 1) AS lastMessagePreview,
            MAX(m.timestamp) AS lastMessageTimestamp
        FROM conversations c
        LEFT JOIN messages m ON c.id = m.conversation_id
        WHERE c.id = :id
        GROUP BY c.id
        """
    )
    fun observeByIdWithStats(id: String): Flow<ConversationWithStats?>
}

/** Data access object for messages (ported from AndroLLM MessageDao). */
@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteByConversationId(conversationId: String): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM messages WHERE id = :id")
    fun observeById(id: String): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    fun observeByConversationId(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    suspend fun getByConversationId(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun countByConversationId(conversationId: String): Int

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("SELECT content FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageContent(conversationId: String): String?

    @Query("UPDATE messages SET is_bookmarked = :isBookmarked WHERE id = :id")
    suspend fun updateBookmarked(id: String, isBookmarked: Boolean): Int

    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchContent(query: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE is_bookmarked = 1 ORDER BY timestamp DESC")
    fun observeBookmarked(): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId AND timestamp > :timestamp")
    suspend fun deleteMessagesAfterTimestamp(conversationId: String, timestamp: Long): Int
}
