package com.jarvis.assistant.data.repository

import android.content.Context
import android.util.Log
import com.jarvis.assistant.androllm.chat.AndroLlmChatStore
import com.jarvis.assistant.androllm.chat.ChatPrefs
import com.jarvis.assistant.androllm.chat.db.AndroLlmChatDatabase
import com.jarvis.assistant.androllm.chat.db.MessageEntity
import com.jarvis.assistant.androllm.chat.model.Conversation
import com.jarvis.assistant.androllm.chat.model.Message
import com.jarvis.assistant.androllm.chat.model.MessageOrigin
import com.jarvis.assistant.androllm.chat.model.MessageRole
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID

/**
 * Jarvis' chat history — a façade over the AndroLLM multi-conversation chat
 * store (`androllm_chat.db`), which is now the single canonical place where
 * every exchange (voice, Chat tab, Compose chat, offline engine, skills) lives.
 *
 * Public surface unchanged for the callers (JarvisConversationService,
 * GeminiLiveWebSocket, OfflineVoiceConversationEngine, MemoryManager, ...):
 *  - [turns]        in-memory window of the last [MAX_TURNS] turns, built from
 *                   the chat DB over all conversations (USER → next ASSISTANT
 *                   paired by timestamp; lone halves get the audio placeholders),
 *                   loaded at startup on IO and kept live as the DB changes;
 *  - [addTurn]      optimistic in-memory merge (immediate `.value` consistency)
 *                   + AppPreferences JSON dual-write (rollback safety) + a
 *                   single-consumer IO queue that persists the turn into the
 *                   active Chat-tab conversation (or Jarvis' own `jarvis_main`);
 *  - [clearHistory] clears the JSON, deletes the Jarvis-owned conversations
 *                   and resets the window.
 *
 * The persisting logic (pending-send matching, dedupe, auto-title, VOICE/TYPED
 * origin, reply latency) moved here from `JarvisChatBridge`, which is now a thin
 * send-only wrapper.
 */
class ChatRepository(private val preferences: AppPreferences, context: Context) {

    private val appContext: Context = context.applicationContext

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loaded = CompletableDeferred<Unit>()

    /** Turns waiting to be written to the chat DB, in call order (single consumer). */
    private val mirrorQueue = Channel<ChatTurn>(Channel.UNLIMITED)

    /** Ids of queued-but-not-yet-persisted turns; kept in the window when it is rebuilt from the DB. */
    private val pendingMirrorIds: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Queued turns older than this are dropped (set by [clearHistory]). */
    @Volatile
    private var discardMirrorsBefore = 0L

    // ── Chat-tab state (moved from JarvisChatBridge) ────────────────────────

    /** A text send waiting for Jarvis' reply. */
    data class PendingSend(val conversationId: String, val sentText: String, val sentAt: Long)

    /** Text sends waiting for Jarvis' reply, oldest first. Guarded by itself. */
    private val pending = ArrayDeque<PendingSend>()

    private val _activeConversationId = MutableStateFlow(ChatPrefs.lastConversationId(appContext))

    /** Conversation shown in the Chat tab ("" = new, unsaved chat). */
    val activeConversationId: StateFlow<String> = _activeConversationId.asStateFlow()

    private val _awaitingConversationId = MutableStateFlow<String?>(null)

    /** Conversation whose message is waiting for a Jarvis reply (null = none). */
    val awaitingConversationId: StateFlow<String?> = _awaitingConversationId.asStateFlow()

    private val _lastReplyLatencyMs = MutableStateFlow<Long?>(null)

    /** Real, measured time between the last chat-tab send and Jarvis' reply. */
    val lastReplyLatencyMs: StateFlow<Long?> = _lastReplyLatencyMs.asStateFlow()

    /** Last conversation whose text was delivered in this process (used to re-send context after a switch). */
    @Volatile
    var lastSentConversationId: String? = null

    /** Last non-blank live transcript (keeps the reply's line breaks, which finalizeTurn collapses). */
    @Volatile
    private var lastLiveReply: String = ""

    private val conversations get() = AndroLlmChatStore.conversationRepository(appContext)
    private val messages get() = AndroLlmChatStore.messageRepository(appContext)
    private val messageDao get() = AndroLlmChatDatabase.getInstance(appContext).messageDao()

    init {
        // Immediate value for synchronous readers (exactly as before); replaced
        // by the DB-backed window as soon as it is loaded.
        _turns.value = preferences.loadChatHistory()

        scope.launch {
            try {
                migrateLegacyPrefsOnce()
                applyDbWindow(messageDao.observeRecent(WINDOW_MESSAGE_LIMIT).first())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Chat history load failed: ${e.message}", e)
            } finally {
                loaded.complete(Unit)
            }
            // Keep the window live: exchanges written straight into the store
            // (Compose chat, voice ChatManager) show up without an addTurn.
            try {
                messageDao.observeRecent(WINDOW_MESSAGE_LIMIT).conflate().collect { applyDbWindow(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Chat history observer stopped: ${e.message}", e)
            }
        }

        scope.launch {
            for (turn in mirrorQueue) {
                try {
                    loaded.await()
                    if (turn.timestamp > discardMirrorsBefore) mirrorTurn(turn)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not persist turn: ${e.message}", e)
                } finally {
                    pendingMirrorIds.remove(turn.id)
                    runCatching { applyDbWindow(messageDao.observeRecent(WINDOW_MESSAGE_LIMIT).first()) }
                }
            }
        }

        scope.launch {
            runCatching {
                JarvisConversationService.liveAssistantResponse.collect { live ->
                    if (live.isNotBlank()) lastLiveReply = live
                }
            }
        }
    }

    /** Suspends until the DB-backed window (and the one-time legacy import) is ready. */
    suspend fun awaitLoaded() {
        loaded.await()
    }

    // ── Jarvis' unchanged API ───────────────────────────────────────────────

    @Synchronized
    fun addTurn(userText: String, jarvisText: String) {
        val cleanUser = userText.trim()
        val cleanJarvis = jarvisText.trim()

        if (cleanUser.isEmpty() && cleanJarvis.isEmpty()) return

        val currentList = _turns.value.toMutableList()

        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.userTranscript == cleanUser && last.jarvisResponse == cleanJarvis) {
                return
            }
            // In-place update: if the last turn was a pending text send awaiting reply,
            // update its placeholder response with the actual assistant reply instead of creating a duplicate turn.
            val isLastPlaceholder = last.jarvisResponse == PLACEHOLDER_REPLY || last.jarvisResponse.isBlank()
            if (cleanJarvis.isNotBlank() && cleanJarvis != PLACEHOLDER_REPLY && isLastPlaceholder) {
                if (last.userTranscript == cleanUser || cleanUser.isBlank() || cleanUser == PLACEHOLDER_QUERY) {
                    val updated = last.copy(
                        jarvisResponse = cleanJarvis,
                        timestamp = System.currentTimeMillis()
                    )
                    currentList[currentList.size - 1] = updated
                    _turns.value = currentList
                    preferences.saveChatHistory(currentList)
                    enqueueMirror(updated)
                    logMayaEpisode(updated.userTranscript, updated.jarvisResponse)
                    return
                }
            }
        }

        // The turn id (a UUID generated here) doubles as the stored message id.
        val newTurn = ChatTurn(
            userTranscript = cleanUser.ifEmpty { AUDIO_QUERY_PLACEHOLDER },
            jarvisResponse = cleanJarvis.ifEmpty { PLACEHOLDER_REPLY }
        )

        currentList.add(newTurn)
        if (currentList.size > MAX_TURNS) {
            currentList.removeAt(0)
        }

        _turns.value = currentList
        preferences.saveChatHistory(currentList)
        enqueueMirror(newTurn)
        logMayaEpisode(newTurn.userTranscript, newTurn.jarvisResponse)
    }

    /** Maya parity (WP6): append the exchange to the episodic log (skips placeholders, respects incognito). */
    private fun logMayaEpisode(userText: String, jarvisText: String) {
        try {
            com.jarvis.assistant.maya.memory.MayaEpisodeHook.onTurn(appContext, userText, jarvisText)
        } catch (e: Throwable) {
            Log.w(TAG, "Episode log failed: ${e.message}")
        }
    }

    @Synchronized
    fun clearHistory() {
        _turns.value = emptyList()
        preferences.clearChatHistory()
        discardMirrorsBefore = System.currentTimeMillis()
        pendingMirrorIds.clear()
        synchronized(pending) { pending.clear() }
        refreshAwaiting()
        scope.launch {
            runCatching {
                conversations.deleteById(AndroLlmChatStore.IMPORTED_CONVERSATION_ID)
                conversations.deleteById(JARVIS_CONVERSATION_ID)
            }.onFailure { Log.w(TAG, "Deleting Jarvis conversations failed: ${it.message}") }
            val active = _activeConversationId.value
            if (active == JARVIS_CONVERSATION_ID || active == AndroLlmChatStore.IMPORTED_CONVERSATION_ID) {
                setActiveConversation("")
            }
            runCatching { applyDbWindow(messageDao.observeRecent(WINDOW_MESSAGE_LIMIT).first()) }
        }
    }

    // ── Window ──────────────────────────────────────────────────────────────

    private fun enqueueMirror(turn: ChatTurn) {
        pendingMirrorIds += turn.id
        mirrorQueue.trySend(turn)
    }

    /**
     * Rebuilds [turns] from the newest DB messages, keeping optimistic turns
     * that are still queued (and not yet visible in the DB) at their place.
     */
    @Synchronized
    private fun applyDbWindow(recentDesc: List<MessageEntity>) {
        val dbTurns = pairIntoTurns(recentDesc)
        val optimistic = _turns.value.filter { t ->
            t.id in pendingMirrorIds && dbTurns.none { d -> d.id == t.id || sameExchange(d, t) }
        }
        _turns.value = (dbTurns + optimistic).sortedBy { it.timestamp }.takeLast(MAX_TURNS)
    }

    /** USER → next ASSISTANT within each conversation; lone halves get the audio placeholders. */
    private fun pairIntoTurns(recentDesc: List<MessageEntity>): List<ChatTurn> {
        val result = ArrayList<ChatTurn>(recentDesc.size / 2 + 1)
        recentDesc.asReversed().groupBy { it.conversationId }.values.forEach { list ->
            var open: MessageEntity? = null
            for (m in list) {
                when (m.role) {
                    MessageRole.USER.name -> {
                        open?.let { result += loneUser(it) }
                        open = m
                    }
                    MessageRole.ASSISTANT.name -> {
                        val u = open
                        if (u != null) {
                            result += ChatTurn(
                                id = u.id,
                                userTranscript = u.content.trim().ifEmpty { AUDIO_QUERY_PLACEHOLDER },
                                jarvisResponse = m.content.trim().ifEmpty { PLACEHOLDER_REPLY },
                                timestamp = u.timestamp
                            )
                            open = null
                        } else {
                            result += ChatTurn(
                                id = m.id,
                                userTranscript = AUDIO_QUERY_PLACEHOLDER,
                                jarvisResponse = m.content.trim().ifEmpty { PLACEHOLDER_REPLY },
                                timestamp = m.timestamp
                            )
                        }
                    }
                    else -> Unit // SYSTEM rows are not dialogue turns
                }
            }
            open?.let { result += loneUser(it) }
        }
        result.sortBy { it.timestamp }
        return result.takeLast(MAX_TURNS)
    }

    private fun loneUser(m: MessageEntity) = ChatTurn(
        id = m.id,
        userTranscript = m.content.trim().ifEmpty { AUDIO_QUERY_PLACEHOLDER },
        jarvisResponse = PLACEHOLDER_REPLY,
        timestamp = m.timestamp
    )

    /** Same exchange text (an optimistic placeholder reply matches the stored turn's real reply). */
    private fun sameExchange(db: ChatTurn, optimistic: ChatTurn): Boolean {
        if (collapse(db.userTranscript) != collapse(optimistic.userTranscript)) return false
        return optimistic.jarvisResponse == PLACEHOLDER_REPLY || collapse(db.jarvisResponse) == collapse(optimistic.jarvisResponse)
    }

    // ── One-time legacy import ──────────────────────────────────────────────

    /**
     * Imports the turns Jarvis used to keep in AppPreferences JSON into the
     * conversation `jarvis_history_import` (idempotent ids) once; the flag
     * [ChatPrefs.KEY_HISTORY_IMPORTED] prevents a second run. The JSON itself
     * is left in place (dual-write keeps it current) for rollback safety.
     */
    private suspend fun migrateLegacyPrefsOnce() {
        if (ChatPrefs.prefs(appContext).getBoolean(ChatPrefs.KEY_HISTORY_IMPORTED, false)) return
        val legacy = preferences.loadChatHistory()
        val id = AndroLlmChatStore.importJarvisHistoryOnce(appContext, legacy)
        Log.i(TAG, if (id != null) "Migrated ${legacy.size} legacy turn(s) into '$id'" else "No legacy chat history to migrate")
    }

    // ── Conversations (moved from JarvisChatBridge) ─────────────────────────

    fun setActiveConversation(id: String) {
        _activeConversationId.value = id
        ChatPrefs.setLastConversationId(appContext, id)
    }

    /** Validates the remembered Chat-tab conversation (or picks the most recent one) once the store is ready. */
    suspend fun selectInitialConversation() {
        loaded.await()
        val current = _activeConversationId.value
        val target = when {
            current.isNotBlank() && conversations.findById(current) != null -> current
            else -> conversations.observeActive().first().firstOrNull()?.id.orEmpty()
        }
        setActiveConversation(target)
    }

    /** Returns the active conversation id, creating a new titled conversation when it is new / was deleted. */
    suspend fun ensureConversation(titleHint: String): String {
        val current = _activeConversationId.value
        if (current.isNotBlank() && conversations.findById(current) != null) return current
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        conversations.upsert(Conversation(id = id, title = generateTitle(titleHint), createdAt = now, updatedAt = now)).getOrThrow()
        setActiveConversation(id)
        return id
    }

    /** Active Chat-tab conversation, or Jarvis' own [JARVIS_CONVERSATION_ID] (created on first use). */
    private suspend fun resolveMirrorConversation(): String {
        val current = _activeConversationId.value
        if (current.isNotBlank() && conversations.findById(current) != null) return current
        if (conversations.findById(JARVIS_CONVERSATION_ID) == null) {
            val now = System.currentTimeMillis()
            conversations.upsert(
                Conversation(id = JARVIS_CONVERSATION_ID, title = JARVIS_CONVERSATION_TITLE, createdAt = now, updatedAt = now)
            ).getOrThrow()
        }
        setActiveConversation(JARVIS_CONVERSATION_ID)
        return JARVIS_CONVERSATION_ID
    }

    suspend fun maybeAutoTitle(conversationId: String, hint: String) {
        val conversation = conversations.findById(conversationId) ?: return
        if (conversation.title == DEFAULT_TITLE && hint.isNotBlank()) {
            conversations.updateTitle(conversationId, generateTitle(hint))
        } else {
            conversations.touch(conversationId)
        }
    }

    // ── Pending text sends (bridge registers, persister matches) ────────────

    /** Registers a chat-tab send that is waiting for Jarvis' reply. */
    fun beginPendingSend(conversationId: String, text: String): PendingSend {
        val entry = PendingSend(conversationId, text, System.currentTimeMillis())
        synchronized(pending) { pending.addLast(entry) }
        _awaitingConversationId.value = conversationId
        return entry
    }

    /** Forgets a send that could not be delivered. */
    fun abandonPendingSend(entry: PendingSend) {
        synchronized(pending) { pending.remove(entry) }
        refreshAwaiting()
    }

    /**
     * Stores the reply to a chat-tab send that was answered by the text brain
     * (Gemini text / offline model) instead of a Gemini Live session.
     */
    suspend fun completeTextReply(entry: PendingSend, reply: String, modelLabel: String) {
        synchronized(pending) { pending.remove(entry) }
        refreshAwaiting()
        val text = reply.trim()
        if (text.isEmpty()) return
        val convId = entry.conversationId.takeIf { conversations.findById(it) != null } ?: resolveMirrorConversation()
        val now = System.currentTimeMillis()
        messages.upsert(
            Message(
                id = UUID.randomUUID().toString(),
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = text,
                timestamp = now,
                modelId = modelLabel.ifBlank { "Jarvis" },
                origin = MessageOrigin.TYPED
            )
        )
        conversations.touch(convId)
        _lastReplyLatencyMs.value = now - entry.sentAt
    }

    /** Forgets a send that will never be answered here (e.g. its conversation was deleted). */
    fun cancelAwaiting(conversationId: String) {
        synchronized(pending) { pending.removeAll { it.conversationId == conversationId } }
        refreshAwaiting()
    }

    private fun refreshAwaiting() {
        _awaitingConversationId.value = synchronized(pending) { pending.firstOrNull()?.conversationId }
    }

    private fun expirePending() {
        val cutoff = System.currentTimeMillis() - PENDING_TTL_MS
        synchronized(pending) { pending.removeAll { it.sentAt < cutoff } }
        refreshAwaiting()
    }

    // ── Persisting a turn (the former JarvisChatBridge.mirrorTurn) ──────────

    private suspend fun mirrorTurn(turn: ChatTurn) {
        // Gson can leave these null in old history despite the Kotlin types.
        val rawUser: String? = turn.userTranscript
        val rawReply: String? = turn.jarvisResponse
        val user = rawUser.orEmpty().trim()
        val reply = rawReply.orEmpty().trim()
        val hasUser = user.isNotEmpty() && user != PLACEHOLDER_QUERY && user != AUDIO_QUERY_PLACEHOLDER
        expirePending()

        if (reply == PLACEHOLDER_REPLY) {
            // The user half of a text send (JarvisConversationService.sendTextMessage).
            val ours = synchronized(pending) { pending.any { it.sentText == user } }
            if (ours || !hasUser) return
            // Sent from another Jarvis surface: show it here and wait for its reply.
            val convId = resolveMirrorConversation()
            if (!recentlyStored(MessageRole.USER, user)) {
                messages.upsert(
                    Message(
                        id = turn.id,
                        conversationId = convId,
                        role = MessageRole.USER,
                        content = user,
                        timestamp = System.currentTimeMillis(),
                        origin = MessageOrigin.TYPED
                    )
                )
                maybeAutoTitle(convId, user)
            }
            synchronized(pending) { pending.addLast(PendingSend(convId, user, System.currentTimeMillis())) }
            refreshAwaiting()
            return
        }
        if (reply.isEmpty()) return

        val match = synchronized(pending) {
            val first = pending.firstOrNull()
            if (first != null && (!hasUser || user == first.sentText)) {
                pending.removeFirst()
                first
            } else {
                null
            }
        }
        refreshAwaiting()

        // Exchanges another chat surface already stored are not duplicated.
        if (recentlyStored(MessageRole.ASSISTANT, reply)) return

        val content = lastLiveReply.trim().takeIf { collapse(it) == collapse(reply) } ?: reply
        val now = System.currentTimeMillis()
        val convId = match?.conversationId?.takeIf { conversations.findById(it) != null }
            ?: resolveMirrorConversation()

        if (match == null && hasUser) {
            // A voice (or other non-chat-tab) exchange: keep it in the active conversation.
            messages.upsert(
                Message(
                    id = turn.id,
                    conversationId = convId,
                    role = MessageRole.USER,
                    content = user,
                    timestamp = now,
                    origin = MessageOrigin.VOICE
                )
            )
            maybeAutoTitle(convId, user)
        }
        messages.upsert(
            Message(
                id = "${turn.id}_a",
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = content,
                timestamp = now + 1,
                modelId = if (match != null) LIVE_MODEL_LABEL else "Jarvis",
                origin = if (match != null) MessageOrigin.TYPED else MessageOrigin.VOICE
            )
        )
        conversations.touch(convId)
        if (match != null) _lastReplyLatencyMs.value = now - match.sentAt
    }

    /** True when the same text was stored by any chat surface in the last couple of minutes. */
    private suspend fun recentlyStored(role: MessageRole, text: String): Boolean {
        val probe = text.take(48)
        if (probe.isBlank()) return false
        val target = collapse(text)
        val now = System.currentTimeMillis()
        return messages.searchContent(probe).first().any {
            it.role == role &&
                // Replies this repository stored itself never count (Jarvis may legitimately repeat "Done.").
                (role != MessageRole.ASSISTANT || (it.modelId != LIVE_MODEL_LABEL && it.modelId != "Jarvis")) &&
                collapse(it.content) == target &&
                now - it.timestamp < DUPLICATE_WINDOW_MS
        }
    }

    private fun collapse(text: String): String = text.replace(WHITESPACE, " ").trim()

    companion object {
        private const val TAG = "ChatRepository"

        /** Size of the in-memory history window (unchanged). */
        const val MAX_TURNS = 100
        private const val WINDOW_MESSAGE_LIMIT = 2 * MAX_TURNS + 50

        /** Stored when the reply half is empty (text sends). */
        const val PLACEHOLDER_REPLY = "(Audio response)"

        /** Stored when the user half is empty. */
        const val AUDIO_QUERY_PLACEHOLDER = "(Audio query)"

        /** JarvisConversationService.finalizeTurn() stores this when there was no input transcription. */
        const val PLACEHOLDER_QUERY = "Spoken user query"

        const val DEFAULT_TITLE = "New Chat"
        const val LIVE_MODEL_LABEL = "Jarvis • Gemini Live"

        /** Jarvis' own conversation: voice turns land here when no Chat-tab conversation is open. */
        const val JARVIS_CONVERSATION_ID = "jarvis_main"
        const val JARVIS_CONVERSATION_TITLE = "Jarvis"

        private const val PENDING_TTL_MS = 3 * 60_000L
        private const val DUPLICATE_WINDOW_MS = 2 * 60_000L
        private val WHITESPACE = Regex("\\s+")

        fun generateTitle(text: String): String {
            val clean = text.replace(WHITESPACE, " ").trim()
            if (clean.isEmpty()) return DEFAULT_TITLE
            return if (clean.length > 30) clean.take(30).trimEnd() + "…" else clean
        }
    }
}
