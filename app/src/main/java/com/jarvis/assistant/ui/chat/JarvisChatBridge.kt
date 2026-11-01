package com.jarvis.assistant.ui.chat

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.androllm.chat.AndroLlmChatStore
import com.jarvis.assistant.androllm.chat.ChatPrefs
import com.jarvis.assistant.androllm.chat.model.Conversation
import com.jarvis.assistant.androllm.chat.model.Message
import com.jarvis.assistant.androllm.chat.model.MessageOrigin
import com.jarvis.assistant.androllm.chat.model.MessageRole
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ChatTurn
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Connects Jarvis' Chat tab to the multi-conversation store (androllm_chat.db)
 * WITHOUT changing how Jarvis talks to Gemini:
 *
 *  - Sending: [recordAndSend] / [resend] deliver text exclusively through
 *    [JarvisConversationService.sendTextMessage] — the exact call ChatFragment
 *    always used (Gemini Live client_content). If the service is not running it
 *    is started with MainActivity.handleMicOrOrbClick(), as before.
 *  - Receiving: Jarvis keeps writing every finished turn to its own
 *    ChatRepository (service finalizeTurn, offline engine, memory skills, …).
 *    This bridge observes ChatRepository.turns and mirrors new turns into the
 *    active conversation, so ChatRepository stays the source of truth for the
 *    existing pipeline / memory and is never modified here.
 */
object JarvisChatBridge {

    private const val TAG = "JarvisChatBridge"

    /** ChatRepository.addTurn() stores this when the reply half is empty (text sends). */
    const val PLACEHOLDER_REPLY = "(Audio response)"

    /** JarvisConversationService.finalizeTurn() stores this when there was no input transcription. */
    const val PLACEHOLDER_QUERY = "Spoken user query"

    const val DEFAULT_TITLE = "New Chat"
    const val LIVE_MODEL_LABEL = "Jarvis • Gemini Live"

    private const val KEY_WATERMARK = "jarvis_chat_tab_mirror_watermark"
    private const val PENDING_TTL_MS = 3 * 60_000L
    private const val SESSION_WAIT_MS = 15_000L
    private const val SETUP_GRACE_MS = 1_500L
    private const val DUPLICATE_WINDOW_MS = 2 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mirrorMutex = Mutex()

    @Volatile
    private var started = false

    private data class PendingSend(val conversationId: String, val sentText: String, val sentAt: Long)

    /** Text sends waiting for Jarvis' reply, oldest first. Guarded by itself. */
    private val pending = ArrayDeque<PendingSend>()

    private val _activeConversationId = MutableStateFlow("")

    /** Conversation shown in the Chat tab ("" = new, unsaved chat). */
    val activeConversationId: StateFlow<String> = _activeConversationId.asStateFlow()

    private val _awaitingConversationId = MutableStateFlow<String?>(null)

    /** Conversation whose message is waiting for a Jarvis reply (null = none). */
    val awaitingConversationId: StateFlow<String?> = _awaitingConversationId.asStateFlow()

    private val _lastReplyLatencyMs = MutableStateFlow<Long?>(null)

    /** Real, measured time between the last chat-tab send and Jarvis' reply. */
    val lastReplyLatencyMs: StateFlow<Long?> = _lastReplyLatencyMs.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One-off user notices (shown as toasts by ChatFragment). */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val _ready = MutableStateFlow(false)

    /** True once the Jarvis history import + initial conversation selection finished. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Last conversation whose text was delivered in this process (used to re-send context after a switch). */
    @Volatile
    var lastSentConversationId: String? = null
        private set

    /** Last non-blank live transcript (keeps the reply's line breaks, which finalizeTurn collapses). */
    @Volatile
    private var lastLiveReply: String = ""

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        _activeConversationId.value = ChatPrefs.lastConversationId(app)
        scope.launch {
            try {
                initialize(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Chat bridge init failed: ${e.message}", e)
            } finally {
                _ready.value = true
            }
            launch {
                JarvisConversationService.liveAssistantResponse.collect { live ->
                    if (live.isNotBlank()) lastLiveReply = live
                }
            }
            JarvisApp.instance.chatRepository.turns.collect { turns ->
                try {
                    mirror(app, turns)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Mirroring Jarvis turns failed: ${e.message}", e)
                }
            }
        }
    }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Chat action failed: ${e.message}", e)
            notice("Chat action failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun notice(text: String) {
        _notices.tryEmit(text)
    }

    private suspend fun initialize(app: Context) {
        val turns = JarvisApp.instance.chatRepository.turns.value
        val imported = try {
            AndroLlmChatStore.importJarvisHistoryOnce(app, turns)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Jarvis history import failed: ${e.message}", e)
            null
        }
        val prefs = ChatPrefs.prefs(app)
        if (!prefs.contains(KEY_WATERMARK)) {
            // Everything up to now is covered by the one-time history import.
            val newest = turns.maxOfOrNull { it.timestamp } ?: 0L
            prefs.edit().putLong(KEY_WATERMARK, newest).apply()
        }
        val repo = AndroLlmChatStore.conversationRepository(app)
        val current = _activeConversationId.value
        val target = when {
            current.isNotBlank() && repo.findById(current) != null -> current
            imported != null -> imported
            else -> repo.observeActive().first().firstOrNull()?.id.orEmpty()
        }
        setActiveConversation(app, target)
    }

    // ── Conversations ───────────────────────────────────────────────────────

    fun setActiveConversation(context: Context, id: String) {
        _activeConversationId.value = id
        ChatPrefs.setLastConversationId(context.applicationContext, id)
    }

    /** Returns the active conversation id, creating the conversation when it is new / was deleted. */
    suspend fun ensureConversation(context: Context, titleHint: String): String {
        val app = context.applicationContext
        val repo = AndroLlmChatStore.conversationRepository(app)
        val current = _activeConversationId.value
        if (current.isNotBlank() && repo.findById(current) != null) return current
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        repo.upsert(Conversation(id = id, title = generateTitle(titleHint), createdAt = now, updatedAt = now)).getOrThrow()
        setActiveConversation(app, id)
        return id
    }

    private suspend fun maybeAutoTitle(context: Context, conversationId: String, hint: String) {
        val repo = AndroLlmChatStore.conversationRepository(context)
        val conversation = repo.findById(conversationId) ?: return
        if (conversation.title == DEFAULT_TITLE && hint.isNotBlank()) {
            repo.updateTitle(conversationId, generateTitle(hint))
        } else {
            repo.touch(conversationId)
        }
    }

    fun generateTitle(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return DEFAULT_TITLE
        return if (clean.length > 30) clean.take(30).trimEnd() + "…" else clean
    }

    // ── Sending (Jarvis' unchanged Gemini Live path) ────────────────────────

    /**
     * Stores the user's message in [conversationId] and delivers [outgoing]
     * (the message, plus any attachment / context text) to Jarvis.
     */
    suspend fun recordAndSend(
        context: Context,
        conversationId: String,
        userContent: String,
        attachmentsJson: String,
        outgoing: String,
        startSession: () -> Unit
    ): Boolean {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        AndroLlmChatStore.messageRepository(app).upsert(
            Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = MessageRole.USER,
                content = userContent,
                timestamp = now,
                origin = MessageOrigin.TYPED,
                attachmentsJson = attachmentsJson
            )
        ).getOrThrow()
        maybeAutoTitle(app, conversationId, userContent)
        return resend(conversationId, outgoing, startSession)
    }

    /** Delivers [outgoing] for an already-stored user message (regenerate / edit). */
    suspend fun resend(conversationId: String, outgoing: String, startSession: () -> Unit): Boolean {
        val text = outgoing.trim()
        if (text.isEmpty()) return false
        val entry = PendingSend(conversationId, text, System.currentTimeMillis())
        synchronized(pending) { pending.addLast(entry) }
        _awaitingConversationId.value = conversationId
        val delivered = deliver(text, startSession)
        if (delivered) {
            lastSentConversationId = conversationId
        } else {
            synchronized(pending) { pending.remove(entry) }
            refreshAwaiting()
            notice("Jarvis session is not running. Tap the mic / orb to start Jarvis, then send again.")
        }
        return delivered
    }

    /**
     * Same behaviour as the original ChatFragment.sendMessage(): start the
     * session via MainActivity.handleMicOrOrbClick() when the service is not
     * running, then JarvisConversationService.sendTextMessage(text). The only
     * addition: when the session had to be started, wait for it to come up
     * instead of sending into a not-yet-running service (where it was dropped).
     */
    private suspend fun deliver(text: String, startSession: () -> Unit): Boolean {
        if (JarvisConversationService.isServiceRunning()) {
            JarvisConversationService.sendTextMessage(text)
            return true
        }
        startSession()
        val up = withTimeoutOrNull(SESSION_WAIT_MS) {
            while (!(JarvisConversationService.isServiceRunning() && JarvisConversationService.isSessionOn.value)) {
                delay(250)
            }
            true
        } ?: false
        if (!up && !JarvisConversationService.isServiceRunning()) return false
        delay(SETUP_GRACE_MS)
        JarvisConversationService.sendTextMessage(text)
        return true
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

    // ── Receiving: mirror ChatRepository turns ──────────────────────────────

    private suspend fun mirror(app: Context, turns: List<ChatTurn>) = mirrorMutex.withLock {
        if (!_ready.value) return@withLock
        val prefs = ChatPrefs.prefs(app)
        var watermark = prefs.getLong(KEY_WATERMARK, Long.MAX_VALUE)
        val fresh = turns.filter { it.timestamp > watermark }.sortedBy { it.timestamp }
        if (fresh.isEmpty()) return@withLock
        for (turn in fresh) {
            try {
                mirrorTurn(app, turn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not mirror turn: ${e.message}", e)
            }
            watermark = maxOf(watermark, turn.timestamp)
        }
        prefs.edit().putLong(KEY_WATERMARK, watermark).apply()
    }

    private suspend fun mirrorTurn(app: Context, turn: ChatTurn) {
        // Gson can leave these null in old history despite the Kotlin types.
        val rawUser: String? = turn.userTranscript
        val rawReply: String? = turn.jarvisResponse
        val user = rawUser.orEmpty().trim()
        val reply = rawReply.orEmpty().trim()
        val messages = AndroLlmChatStore.messageRepository(app)
        expirePending()

        if (reply == PLACEHOLDER_REPLY) {
            // The user half of a text send (JarvisConversationService.sendTextMessage).
            val ours = synchronized(pending) { pending.any { it.sentText == user } }
            if (ours || user.isEmpty()) return
            // Sent from another Jarvis surface: show it here and wait for its reply.
            val convId = ensureConversation(app, user)
            if (!recentlyStored(app, MessageRole.USER, user)) {
                messages.upsert(
                    Message(
                        id = UUID.randomUUID().toString(),
                        conversationId = convId,
                        role = MessageRole.USER,
                        content = user,
                        timestamp = System.currentTimeMillis(),
                        origin = MessageOrigin.TYPED
                    )
                )
                maybeAutoTitle(app, convId, user)
            }
            synchronized(pending) { pending.addLast(PendingSend(convId, user, System.currentTimeMillis())) }
            refreshAwaiting()
            return
        }
        if (reply.isEmpty()) return

        val match = synchronized(pending) {
            val first = pending.firstOrNull()
            if (first != null && (user.isEmpty() || user == PLACEHOLDER_QUERY || user == first.sentText)) {
                pending.removeFirst()
                first
            } else {
                null
            }
        }
        refreshAwaiting()

        // Exchanges another chat surface already stored (e.g. mirrored AndroLLM chat) are not duplicated.
        if (recentlyStored(app, MessageRole.ASSISTANT, reply)) return

        val content = lastLiveReply.trim().takeIf { collapse(it) == collapse(reply) } ?: reply
        val now = System.currentTimeMillis()
        val convId = match?.conversationId?.takeIf {
            AndroLlmChatStore.conversationRepository(app).findById(it) != null
        } ?: ensureConversation(app, if (user.isNotEmpty() && user != PLACEHOLDER_QUERY) user else reply)

        if (match == null && user.isNotEmpty() && user != PLACEHOLDER_QUERY) {
            // A voice (or other non-chat-tab) exchange: keep it in the active conversation.
            messages.upsert(
                Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = MessageRole.USER,
                    content = user,
                    timestamp = now,
                    origin = MessageOrigin.VOICE
                )
            )
            maybeAutoTitle(app, convId, user)
        }
        messages.upsert(
            Message(
                id = UUID.randomUUID().toString(),
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = content,
                timestamp = now + 1,
                modelId = if (match != null) LIVE_MODEL_LABEL else "Jarvis",
                origin = if (match != null) MessageOrigin.TYPED else MessageOrigin.VOICE
            )
        )
        AndroLlmChatStore.conversationRepository(app).touch(convId)
        if (match != null) _lastReplyLatencyMs.value = now - match.sentAt
    }

    /** True when the same text was stored by any chat surface in the last couple of minutes. */
    private suspend fun recentlyStored(app: Context, role: MessageRole, text: String): Boolean {
        val probe = text.take(48)
        if (probe.isBlank()) return false
        val target = collapse(text)
        val now = System.currentTimeMillis()
        return AndroLlmChatStore.messageRepository(app).searchContent(probe).first().any {
            it.role == role &&
                // Replies this bridge stored itself never count (Jarvis may legitimately repeat "Done.").
                (role != MessageRole.ASSISTANT || (it.modelId != LIVE_MODEL_LABEL && it.modelId != "Jarvis")) &&
                collapse(it.content) == target &&
                now - it.timestamp < DUPLICATE_WINDOW_MS
        }
    }

    private fun collapse(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
