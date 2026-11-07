package com.jarvis.assistant.ui.chat

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.androllm.chat.AndroLlmChatStore
import com.jarvis.assistant.androllm.chat.model.Message
import com.jarvis.assistant.androllm.chat.model.MessageOrigin
import com.jarvis.assistant.androllm.chat.model.MessageRole
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.repository.ChatRepository
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Thin send-only wrapper for Jarvis' Chat tab.
 *
 *  - Sending: [recordAndSend] / [resend] deliver text exclusively through
 *    [JarvisConversationService.sendTextMessage] — the exact call ChatFragment
 *    always used (Gemini Live client_content). If the service is not running it
 *    is started with MainActivity.handleMicOrOrbClick(), as before.
 *  - State (active / awaiting conversation, reply latency, pending sends) and
 *    the persisting of Jarvis' replies now live in [ChatRepository], which is
 *    the façade over the chat store; this object only delegates to it.
 */
object JarvisChatBridge {

    private const val TAG = "JarvisChatBridge"

    /** ChatRepository.addTurn() stores this when the reply half is empty (text sends). */
    const val PLACEHOLDER_REPLY = ChatRepository.PLACEHOLDER_REPLY

    /** JarvisConversationService.finalizeTurn() stores this when there was no input transcription. */
    const val PLACEHOLDER_QUERY = ChatRepository.PLACEHOLDER_QUERY

    const val DEFAULT_TITLE = ChatRepository.DEFAULT_TITLE
    const val LIVE_MODEL_LABEL = ChatRepository.LIVE_MODEL_LABEL

    private const val SESSION_WAIT_MS = 15_000L
    private const val SETUP_GRACE_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var started = false

    private val repository: ChatRepository
        get() = JarvisApp.instance.chatRepository

    /** Conversation shown in the Chat tab ("" = new, unsaved chat). */
    val activeConversationId: StateFlow<String>
        get() = repository.activeConversationId

    /** Conversation whose message is waiting for a Jarvis reply (null = none). */
    val awaitingConversationId: StateFlow<String?>
        get() = repository.awaitingConversationId

    /** Real, measured time between the last chat-tab send and Jarvis' reply. */
    val lastReplyLatencyMs: StateFlow<Long?>
        get() = repository.lastReplyLatencyMs

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One-off user notices (shown as toasts by ChatFragment). */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val _ready = MutableStateFlow(false)

    /** True once the initial conversation selection finished. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Last conversation whose text was delivered in this process (used to re-send context after a switch). */
    val lastSentConversationId: String?
        get() = repository.lastSentConversationId

    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            try {
                repository.selectInitialConversation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Chat bridge init failed: ${e.message}", e)
            } finally {
                _ready.value = true
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

    // ── Conversations (delegated) ───────────────────────────────────────────

    @Suppress("UNUSED_PARAMETER")
    fun setActiveConversation(context: Context, id: String) = repository.setActiveConversation(id)

    /** Returns the active conversation id, creating the conversation when it is new / was deleted. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun ensureConversation(context: Context, titleHint: String): String =
        repository.ensureConversation(titleHint)

    fun generateTitle(text: String): String = ChatRepository.generateTitle(text)

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
        repository.maybeAutoTitle(conversationId, userContent)
        return resend(conversationId, outgoing, startSession)
    }

    /** Delivers [outgoing] for an already-stored user message (regenerate / edit). */
    suspend fun resend(conversationId: String, outgoing: String, startSession: () -> Unit): Boolean {
        val text = outgoing.trim()
        if (text.isEmpty()) return false
        val entry = repository.beginPendingSend(conversationId, text)
        val prefs = JarvisApp.instance.preferences
        // When Gemini is disabled by the user or On-Device Local Brain is selected, or if Gemini Live is not running,
        // answer directly through the text brain (On-Device Local Brain / ModelRouter).
        val bypassLiveSession = !prefs.isGeminiEnabled || prefs.selectedAiProvider == com.jarvis.assistant.ai.MultiModelAiRouter.PROVIDER_ONDEVICE_LOCAL
        if (bypassLiveSession || !(JarvisConversationService.isServiceRunning() && JarvisConversationService.isSessionOn.value)) {
            repository.lastSentConversationId = conversationId
            scope.launch(Dispatchers.IO) { answerWithTextBrain(entry, text) }
            return true
        }
        val delivered = deliver(text, startSession)
        if (delivered) {
            repository.lastSentConversationId = conversationId
        } else {
            repository.abandonPendingSend(entry)
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

    /**
     * Answers a chat-tab message without a Gemini Live session: the same router the offline voice
     * engine uses (phone commands first, then the AI model mode — Auto Hybrid = Gemini text with
     * offline-brain failover). The reply is stored like a Live reply.
     */
    private suspend fun answerWithTextBrain(entry: ChatRepository.PendingSend, text: String) {
        val app = JarvisApp.instance
        val prefs = app.preferences
        val router = com.jarvis.assistant.ai.router.ModelRouter.getInstance(app, scope)
        val raw = StringBuilder()
        val reply = try {
            val isLocalBrainActive = !prefs.isGeminiEnabled || 
                prefs.selectedAiProvider == com.jarvis.assistant.ai.MultiModelAiRouter.PROVIDER_ONDEVICE_LOCAL
            val system = if (isLocalBrainActive) {
                "You are JARVIS, an autonomous on-device AI assistant created by Roshan Sir. Answer concisely and assist the user."
            } else {
                runCatching {
                    com.jarvis.assistant.util.PromptGenerator.generateSystemPrompt(
                        personality = prefs.personality,
                        userName = prefs.userName.ifBlank { "Roshan" },
                        language = prefs.language
                    )
                }.getOrDefault("")
            }
            router.routeConversationalQuery(
                userPrompt = text,
                systemPrompt = system,
                openAiApiKey = runCatching { prefs.openAiApiKey }.getOrDefault("")
            ).collect { raw.append(it) }
            com.jarvis.assistant.ai.local.ToolCallParser.cleanConversationalText(raw.toString()).trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Text brain failed: ${e.message}", e)
            ""
        }
        val answer = reply.ifBlank { "Abhi jawab nahi mil paya. Internet ya API key check karke dobara bhejo." }
        Log.i(TAG, "Text brain replied via ${router.lastActiveProviderName.value} (${answer.length} chars)")
        repository.completeTextReply(entry, answer, router.lastActiveProviderName.value)
    }

    /** Forgets a send that will never be answered here (e.g. its conversation was deleted). */
    fun cancelAwaiting(conversationId: String) = repository.cancelAwaiting(conversationId)
}
