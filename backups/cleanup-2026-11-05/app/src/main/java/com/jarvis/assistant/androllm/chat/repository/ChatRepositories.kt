package com.jarvis.assistant.androllm.chat.repository

import com.jarvis.assistant.androllm.chat.db.ConversationDao
import com.jarvis.assistant.androllm.chat.db.MessageDao
import com.jarvis.assistant.androllm.chat.db.toDomain
import com.jarvis.assistant.androllm.chat.db.toEntity
import com.jarvis.assistant.androllm.chat.model.Conversation
import com.jarvis.assistant.androllm.chat.model.Message
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Like [runCatching] but never swallows coroutine cancellation (AndroLLM's
 * core/common runCatching contract).
 */
internal inline fun <T> chatResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** Repository for conversations backed by Room (ported from AndroLLM; Hilt removed). */
class ConversationRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {

    fun getById(id: String): Flow<Conversation?> =
        conversationDao.observeByIdWithStats(id).map { it?.toDomain() }

    fun getAll(): Flow<List<Conversation>> =
        conversationDao.observeAllWithStats().map { list -> list.map { it.toDomain() } }

    /** Active (unarchived) conversations with live counts/previews, pinned first. */
    fun observeActive(): Flow<List<Conversation>> =
        conversationDao.observeActiveWithStats().map { list -> list.map { it.toDomain() } }

    fun observePinned(): Flow<List<Conversation>> =
        conversationDao.observePinnedWithStats().map { list -> list.map { it.toDomain() } }

    fun observeArchived(): Flow<List<Conversation>> =
        conversationDao.observeArchivedWithStats().map { list -> list.map { it.toDomain() } }

    fun observeRecent(): Flow<List<Conversation>> = observeActive()

    suspend fun findById(id: String): Conversation? = conversationDao.getById(id)?.toDomain()

    suspend fun setPinned(id: String, isPinned: Boolean): Result<Unit> = chatResult {
        conversationDao.updatePinned(id, isPinned)
        Unit
    }

    suspend fun setArchived(id: String, isArchived: Boolean): Result<Unit> = chatResult {
        conversationDao.updateArchived(id, isArchived)
        Unit
    }

    suspend fun updateTitle(id: String, title: String): Result<Unit> = chatResult {
        conversationDao.updateTitle(id, title, System.currentTimeMillis())
        Unit
    }

    suspend fun touch(id: String): Result<Unit> = chatResult {
        conversationDao.touch(id, System.currentTimeMillis())
        Unit
    }

    /** Copies a conversation and all of its messages under fresh ids. */
    suspend fun duplicateConversation(id: String): Result<Conversation> = chatResult {
        val original = conversationDao.getById(id) ?: throw IllegalArgumentException("Conversation $id not found")
        val messages = messageDao.getByConversationId(id)
        val now = System.currentTimeMillis()
        val newId = UUID.randomUUID().toString()

        val copyConv = original.copy(
            id = newId,
            title = "${original.title} (Copy)",
            createdAt = now,
            updatedAt = now,
            isPinned = false,
            isArchived = false
        )
        conversationDao.upsert(copyConv)
        messageDao.upsertAll(
            messages.map { msg -> msg.copy(id = UUID.randomUUID().toString(), conversationId = newId) }
        )
        copyConv.toDomain()
    }

    fun searchByTitle(query: String): Flow<List<Conversation>> =
        conversationDao.searchByTitleWithStats(query).map { list -> list.map { it.toDomain() } }

    suspend fun upsert(entity: Conversation): Result<String> = chatResult {
        conversationDao.upsert(entity.toEntity())
        entity.id
    }

    suspend fun deleteById(id: String): Result<Unit> = chatResult {
        conversationDao.deleteById(id)
        messageDao.deleteByConversationId(id)
        Unit
    }

    suspend fun deleteAll(): Result<Unit> = chatResult {
        conversationDao.deleteAll()
        messageDao.deleteAll()
        Unit
    }

    suspend fun existsById(id: String): Result<Boolean> = chatResult { conversationDao.existsById(id) }

    suspend fun count(): Result<Int> = chatResult { conversationDao.count() }
}

/** Repository for messages backed by Room (ported from AndroLLM; Hilt removed). */
class MessageRepository(
    private val messageDao: MessageDao
) {

    fun observeByConversationId(conversationId: String): Flow<List<Message>> =
        messageDao.observeByConversationId(conversationId).map { list -> list.map { it.toDomain() } }

    suspend fun getByConversationId(conversationId: String): List<Message> =
        messageDao.getByConversationId(conversationId).map { it.toDomain() }

    suspend fun getById(id: String): Message? = messageDao.getById(id)?.toDomain()

    suspend fun upsert(message: Message): Result<Unit> = chatResult {
        messageDao.upsert(message.toEntity())
    }

    suspend fun upsertAll(messages: List<Message>): Result<Unit> = chatResult {
        messageDao.upsertAll(messages.map { it.toEntity() })
    }

    suspend fun deleteById(id: String): Result<Unit> = chatResult {
        messageDao.deleteById(id)
        Unit
    }

    suspend fun deleteByIds(ids: List<String>): Result<Unit> = chatResult {
        if (ids.isNotEmpty()) messageDao.deleteByIds(ids)
        Unit
    }

    suspend fun deleteByConversationId(conversationId: String): Result<Unit> = chatResult {
        messageDao.deleteByConversationId(conversationId)
        Unit
    }

    suspend fun getLastMessageContent(conversationId: String): Result<String?> = chatResult {
        messageDao.getLastMessageContent(conversationId)
    }

    suspend fun countByConversationId(conversationId: String): Result<Int> = chatResult {
        messageDao.countByConversationId(conversationId)
    }

    suspend fun setBookmarked(id: String, isBookmarked: Boolean): Result<Unit> = chatResult {
        messageDao.updateBookmarked(id, isBookmarked)
        Unit
    }

    fun searchContent(query: String): Flow<List<Message>> =
        messageDao.searchContent(query).map { list -> list.map { it.toDomain() } }

    fun observeBookmarked(): Flow<List<Message>> =
        messageDao.observeBookmarked().map { list -> list.map { it.toDomain() } }

    /** Deletes messages after [timestamp] in a conversation (prompt editing / branching). */
    suspend fun truncateAfterTimestamp(conversationId: String, timestamp: Long): Result<Unit> = chatResult {
        messageDao.deleteMessagesAfterTimestamp(conversationId, timestamp)
        Unit
    }
}
