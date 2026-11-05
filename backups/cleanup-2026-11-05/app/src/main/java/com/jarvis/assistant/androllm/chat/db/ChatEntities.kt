package com.jarvis.assistant.androllm.chat.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jarvis.assistant.androllm.chat.model.Conversation
import com.jarvis.assistant.androllm.chat.model.Message
import com.jarvis.assistant.androllm.chat.model.MessageOrigin
import com.jarvis.assistant.androllm.chat.model.MessageRole

/** Table names of the AndroLLM chat database (ported from AppConstants.Database). */
object ChatTables {
    const val CONVERSATIONS = "conversations"
    const val MESSAGES = "messages"
}

/** Room entity representing a chat conversation (ported from AndroLLM). */
@Entity(
    tableName = ChatTables.CONVERSATIONS,
    indices = [Index(value = ["updated_at"])]
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "last_message_preview")
    val lastMessagePreview: String? = null,
    @ColumnInfo(name = "message_count")
    val messageCount: Int = 0,
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false,
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false
)

fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastMessagePreview = lastMessagePreview,
    messageCount = messageCount,
    isPinned = isPinned,
    isArchived = isArchived
)

fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastMessagePreview = lastMessagePreview,
    messageCount = messageCount,
    isPinned = isPinned,
    isArchived = isArchived
)

/** Room entity representing a single chat message (ported from AndroLLM). */
@Entity(
    tableName = ChatTables.MESSAGES,
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversation_id"])]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    @ColumnInfo(name = "is_pending")
    val isPending: Boolean = false,
    @ColumnInfo(name = "model_id")
    val modelId: String? = null,
    @ColumnInfo(name = "is_bookmarked")
    val isBookmarked: Boolean = false,
    @ColumnInfo(name = "origin")
    val origin: String = MessageOrigin.TYPED.name,
    @ColumnInfo(name = "attachments_json")
    val attachmentsJson: String = ""
)

fun MessageEntity.toDomain(): Message = Message(
    id = id,
    conversationId = conversationId,
    role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.USER),
    content = content,
    timestamp = timestamp,
    isPending = isPending,
    modelId = modelId,
    isBookmarked = isBookmarked,
    origin = runCatching { MessageOrigin.valueOf(origin) }.getOrDefault(MessageOrigin.TYPED),
    attachmentsJson = attachmentsJson
)

fun Message.toEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role.name,
    content = content,
    timestamp = timestamp,
    isPending = isPending,
    modelId = modelId,
    isBookmarked = isBookmarked,
    origin = origin.name,
    attachmentsJson = attachmentsJson
)

/**
 * Room POJO populated by a JOIN query that computes message statistics live
 * from the messages table (ported from AndroLLM ConversationWithStats).
 */
data class ConversationWithStats(
    val id: String,
    val title: String,
    val createdAt: Long,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val messageCount: Int,
    val lastMessagePreview: String?,
    val lastMessageTimestamp: Long?
) {
    fun toDomain(): Conversation = Conversation(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = lastMessageTimestamp ?: createdAt,
        lastMessagePreview = lastMessagePreview,
        messageCount = messageCount,
        isPinned = isPinned,
        isArchived = isArchived
    )
}
