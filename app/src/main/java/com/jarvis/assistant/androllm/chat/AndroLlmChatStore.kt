package com.jarvis.assistant.androllm.chat

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jarvis.assistant.androllm.chat.db.AndroLlmChatDatabase
import com.jarvis.assistant.androllm.chat.model.Conversation
import com.jarvis.assistant.androllm.chat.model.Message
import com.jarvis.assistant.androllm.chat.model.MessageOrigin
import com.jarvis.assistant.androllm.chat.model.MessageRole
import com.jarvis.assistant.androllm.chat.repository.ConversationRepository
import com.jarvis.assistant.androllm.chat.repository.MessageRepository
import com.jarvis.assistant.data.model.ChatTurn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide entry point for the multi-conversation chat store (replaces
 * AndroLLM's Hilt DatabaseModule). Everything lives in `androllm_chat.db`.
 */
object AndroLlmChatStore {

    private const val TAG = "AndroLlmChatStore"

    @Volatile
    private var conversations: ConversationRepository? = null

    @Volatile
    private var messages: MessageRepository? = null

    private fun ensure(context: Context) {
        if (conversations != null && messages != null) return
        synchronized(this) {
            if (conversations == null || messages == null) {
                val db = AndroLlmChatDatabase.getInstance(context)
                conversations = ConversationRepository(db.conversationDao(), db.messageDao())
                messages = MessageRepository(db.messageDao())
            }
        }
    }

    fun conversationRepository(context: Context): ConversationRepository {
        ensure(context)
        return conversations!!
    }

    fun messageRepository(context: Context): MessageRepository {
        ensure(context)
        return messages!!
    }

    // ── Jarvis history import ───────────────────────────────────────────────

    private val importMutex = Mutex()

    /**
     * One-time import of Jarvis' legacy AppPreferences chat history into a single
     * conversation (run by ChatRepository at startup). Read-only: the original
     * turns stay untouched in Jarvis' preferences. Idempotent: message ids are
     * derived from the turn ids, and a completion flag prevents a second import.
     *
     * @return the id of the imported conversation, or null when nothing was imported.
     */
    suspend fun importJarvisHistoryOnce(context: Context, turns: List<ChatTurn>): String? = importMutex.withLock {
        val prefs = ChatPrefs.prefs(context)
        if (prefs.getBoolean(ChatPrefs.KEY_HISTORY_IMPORTED, false)) return@withLock null
        val id = if (turns.isEmpty()) null else writeHistory(context, turns, IMPORTED_CONVERSATION_ID, "Jarvis history")
        prefs.edit().putBoolean(ChatPrefs.KEY_HISTORY_IMPORTED, true).apply()
        id
    }

    /**
     * User-triggered snapshot: copies the CURRENT Jarvis history into a new
     * conversation (e.g. to pick up turns from voice / classic chat made after
     * the first import). Never deletes or edits the originals.
     */
    suspend fun importJarvisHistorySnapshot(context: Context, turns: List<ChatTurn>): String? = importMutex.withLock {
        if (turns.isEmpty()) return@withLock null
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        writeHistory(context, turns, UUID.randomUUID().toString(), "Jarvis history ($stamp)")
    }

    private suspend fun writeHistory(
        context: Context,
        turns: List<ChatTurn>,
        conversationId: String,
        title: String
    ): String? {
        val convRepo = conversationRepository(context)
        val msgRepo = messageRepository(context)
        val sorted = turns.sortedBy { it.timestamp }
        val first = sorted.first().timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
        val last = sorted.last().timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
        val conversation = Conversation(
            id = conversationId,
            title = title,
            createdAt = first,
            updatedAt = last
        )
        val rows = ArrayList<Message>(sorted.size * 2)
        sorted.forEachIndexed { index, turn ->
            // Gson may leave non-null Kotlin fields null in old history.
            val rawId: String? = turn.id
            val rawUser: String? = turn.userTranscript
            val rawReply: String? = turn.jarvisResponse
            val turnId: String = rawId ?: "turn$index"
            val user: String = rawUser.orEmpty()
            val reply: String = rawReply.orEmpty()
            val ts = turn.timestamp.takeIf { it > 0 } ?: (first + index)
            if (user.isNotBlank()) {
                rows += Message(
                    id = "${conversationId}_${turnId}_u",
                    conversationId = conversationId,
                    role = MessageRole.USER,
                    content = user,
                    timestamp = ts,
                    origin = MessageOrigin.AUTOMATION
                )
            }
            if (reply.isNotBlank()) {
                rows += Message(
                    id = "${conversationId}_${turnId}_a",
                    conversationId = conversationId,
                    role = MessageRole.ASSISTANT,
                    content = reply,
                    // +1 ms keeps the reply ordered after its prompt.
                    timestamp = ts + 1,
                    modelId = "Jarvis",
                    origin = MessageOrigin.AUTOMATION
                )
            }
        }
        if (rows.isEmpty()) return null
        val convResult = convRepo.upsert(conversation)
        if (convResult.isFailure) {
            Log.w(TAG, "History import failed", convResult.exceptionOrNull())
            throw convResult.exceptionOrNull() ?: IllegalStateException("History import failed")
        }
        val msgResult = msgRepo.upsertAll(rows)
        if (msgResult.isFailure) {
            Log.w(TAG, "History import failed", msgResult.exceptionOrNull())
            throw msgResult.exceptionOrNull() ?: IllegalStateException("History import failed")
        }
        Log.i(TAG, "Imported ${rows.size} Jarvis history messages into '$title'")
        return conversationId
    }

    /** Conversation holding the legacy (AppPreferences) Jarvis history. */
    const val IMPORTED_CONVERSATION_ID = "jarvis_history_import"
}

/** Chat-feature preferences (own SharedPreferences file; never Jarvis' prefs). */
object ChatPrefs {
    private const val PREFS = "androllm_chat_prefs"
    const val KEY_HISTORY_IMPORTED = "jarvis_history_imported"
    const val KEY_LAST_CONVERSATION = "last_conversation_id"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastConversationId(context: Context): String =
        prefs(context).getString(KEY_LAST_CONVERSATION, "").orEmpty()

    fun setLastConversationId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_LAST_CONVERSATION, id).apply()
    }
}
