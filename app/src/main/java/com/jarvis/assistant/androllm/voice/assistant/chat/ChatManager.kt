package com.jarvis.assistant.androllm.voice.assistant.chat

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.local.ToolCallParser
import com.jarvis.assistant.ai.router.ModelRouter
import com.jarvis.assistant.androllm.chat.AndroLlmChatStore
import com.jarvis.assistant.androllm.chat.model.Conversation
import com.jarvis.assistant.androllm.chat.model.Message
import com.jarvis.assistant.androllm.chat.model.MessageOrigin
import com.jarvis.assistant.androllm.chat.model.MessageRole
import com.jarvis.assistant.data.model.ChatTurn
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Voice ↔ LLM turn handling for the optional AndroLLM voice assistant (ported from
 * AndroLLM feature/voice `ChatManager`, adapted to Jarvis).
 *
 * Jarvis-first routing: every turn goes through Jarvis's public
 * [ModelRouter.routeConversationalQuery] (the same entry point the Compose chat uses),
 * so the user's selected Jarvis brain (ChatGPT / Gemini REST / local / LiteRT / cloud
 * gateway / Hermes) answers. Gemini Live is never used here - ModelRouter's text path
 * calls Gemini REST, not the Live WebSocket.
 *
 * Shared state, like upstream:
 *  - conversation persistence in the AndroLLM chat database ([AndroLlmChatStore]),
 *    messages tagged [MessageOrigin.VOICE] so the Compose chat shows them;
 *  - Jarvis's ChatRepository reads its history window from this same store, so each
 *    exchange feeds Jarvis's own memory pipeline without a separate mirror;
 *  - ModelRouter injects Jarvis's vector memory (RAG) into every request.
 *
 * AndroLLM's in-ChatManager tool loop (ToolRunCoordinator / cloud tool_calls) is not
 * duplicated: tool execution in Jarvis happens inside its own brains/agents, and their
 * high-risk confirmations are asked aloud by the voice service through Jarvis's
 * [com.jarvis.assistant.automation.agent.ToolConfirmationManager].
 */
class ChatManager private constructor(private val context: Context) {

    private val app: JarvisApp get() = (context as? JarvisApp) ?: JarvisApp.instance
    private val router: ModelRouter get() = app.modelRouter
    private val conversations = AndroLlmChatStore.conversationRepository(context)
    private val messages = AndroLlmChatStore.messageRepository(context)

    /** Conversation that holds the most recent voice turn (overlay "Open conversation"). */
    @Volatile var lastConversationId: String? = null
        private set

    /**
     * `(providerLabel, modelName)` of what will answer, for the overlay model chip.
     * Never hard-codes a provider: it reflects ModelRouter's live state.
     */
    fun activeModelLabel(): Pair<String, String> = runCatching {
        val mode = router.currentMode.value
        val provider = router.lastActiveProviderName.value
        mode.displayName to provider.ifBlank { mode.displayName }
    }.getOrDefault("" to "")

    /**
     * Sends [content] into the current (or a new) voice conversation and returns a
     * [Flow] of cleaned response text deltas. Persists the user + assistant messages.
     */
    fun sendMessageStream(
        content: String,
        origin: MessageOrigin = MessageOrigin.VOICE,
        lowLatencyMode: Boolean = false,
        onToolStatus: suspend (String) -> Unit = {}
    ): Flow<String> = channelFlow {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return@channelFlow
        if (origin == MessageOrigin.VOICE) {
            Log.i(TAG, "ChatManager received EXACT transcript: '$trimmed' (${trimmed.length} chars)")
        }

        val now = System.currentTimeMillis()
        val convId = resolveConversation(trimmed, now)
        lastConversationId = convId

        // History BEFORE this prompt (ModelRouter's ChatTurn format). Low-latency mode
        // skips history for the fastest first token (documented upstream trade-off).
        val history = if (lowLatencyMode) emptyList() else
            toChatTurns(runCatching { messages.getByConversationId(convId) }.getOrDefault(emptyList()))
                .takeLast(MAX_HISTORY_TURNS)

        messages.upsert(
            Message(
                id = UUID.randomUUID().toString(),
                conversationId = convId,
                role = MessageRole.USER,
                content = trimmed,
                timestamp = now,
                origin = origin
            )
        )

        val raw = StringBuilder()
        var lastVisible = ""
        var emitted = 0
        var failure: Throwable? = null
        try {
            router.routeConversationalQuery(
                userPrompt = trimmed,
                systemPrompt = VOICE_SYSTEM_PROMPT,
                openAiApiKey = runCatching { app.preferences.openAiApiKey }.getOrDefault(""),
                chatHistory = history
            ).collect { token ->
                if (token.isEmpty()) return@collect
                raw.append(token)
                // Tool-call XML/JSON can span tokens: clean the whole buffer and only
                // forward the newly visible suffix.
                val visible = ToolCallParser.cleanConversationalText(raw.toString())
                if (visible.length > lastVisible.length && visible.startsWith(lastVisible)) {
                    send(visible.substring(lastVisible.length))
                    emitted += visible.length - lastVisible.length
                }
                // When cleaning rewrote already-forwarded text (rare) nothing is re-sent.
                lastVisible = visible
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "generation failed", e)
            failure = e
        }

        var finalAnswer = ToolCallParser.cleanConversationalText(raw.toString()).trim()
        if (finalAnswer.isBlank()) {
            val fallback = if (failure != null) "Error: ${failure.message ?: "generation failed"}"
            else "I couldn't generate a response. Check the AI model selected in Jarvis settings."
            if (emitted == 0) send(fallback)
            finalAnswer = ""
        }
        withContext(NonCancellable) {
            if (finalAnswer.isNotBlank()) {
                messages.upsert(
                    Message(
                        id = UUID.randomUUID().toString(),
                        conversationId = convId,
                        role = MessageRole.ASSISTANT,
                        content = finalAnswer,
                        timestamp = maxOf(System.currentTimeMillis(), now + 1),
                        modelId = runCatching { router.lastActiveProviderName.value }.getOrNull(),
                        origin = origin
                    )
                )
                conversations.touch(convId)
            }
        }
    }

    /** Most recent active conversation, or a new one titled from the prompt. */
    private suspend fun resolveConversation(prompt: String, now: Long): String {
        val active = runCatching { conversations.observeActive().first().firstOrNull() }.getOrNull()
        if (active != null) return active.id
        val id = UUID.randomUUID().toString()
        val title = if (prompt.length > 30) prompt.take(30) + "..." else prompt
        conversations.upsert(Conversation(id = id, title = title, createdAt = now, updatedAt = now))
        return id
    }

    /** Active conversation (for local commands). */
    suspend fun activeConversation(): Conversation? =
        runCatching { conversations.observeActive().first().firstOrNull() }.getOrNull()

    suspend fun deleteConversation(id: String): Boolean = conversations.deleteById(id).isSuccess

    /**
     * Summarizes the active conversation through ModelRouter; falls back to
     * AndroLLM's plain recent-messages digest when the model returns nothing.
     */
    suspend fun summarizeActiveConversation(): String {
        val active = activeConversation() ?: return "There is no conversation to summarize."
        val msgs = runCatching { messages.getByConversationId(active.id) }.getOrDefault(emptyList())
        if (msgs.isEmpty()) return "This conversation has no messages yet."
        val recent = msgs.takeLast(10).joinToString("\n") { "${it.role.name.lowercase()}: ${it.content.take(400)}" }
        val summary = StringBuilder()
        runCatching {
            router.routeConversationalQuery(
                userPrompt = "Summarize this conversation in two or three short spoken sentences:\n$recent",
                systemPrompt = VOICE_SYSTEM_PROMPT,
                openAiApiKey = runCatching { app.preferences.openAiApiKey }.getOrDefault("")
            ).collect { summary.append(it) }
        }.onFailure { if (it is CancellationException) throw it }
        val text = ToolCallParser.cleanConversationalText(summary.toString()).trim()
        return text.ifBlank { "Conversation summary:\n${recent.take(200)}..." }
    }

    companion object {
        private const val TAG = "VoiceChatManager"
        private const val MAX_HISTORY_TURNS = 10

        private const val VOICE_SYSTEM_PROMPT =
            "You are JARVIS, a helpful voice assistant. Your reply will be spoken aloud: " +
                "answer in short, natural sentences without Markdown, tables, code blocks or emoji."

        @Volatile private var instance: ChatManager? = null

        fun get(context: Context): ChatManager =
            instance ?: synchronized(this) {
                instance ?: ChatManager(context.applicationContext).also { instance = it }
            }

        /** Pairs user prompts with the assistant reply that follows them (ModelRouter history). */
        private fun toChatTurns(messages: List<Message>): List<ChatTurn> {
            val turns = ArrayList<ChatTurn>()
            var pendingUser: Message? = null
            for (m in messages) {
                when (m.role) {
                    MessageRole.USER -> {
                        pendingUser?.let { u ->
                            turns += ChatTurn(id = u.id, userTranscript = u.content, jarvisResponse = "", timestamp = u.timestamp)
                        }
                        pendingUser = m
                    }
                    MessageRole.ASSISTANT -> {
                        val u = pendingUser
                        turns += ChatTurn(
                            id = u?.id ?: m.id,
                            userTranscript = u?.content.orEmpty(),
                            jarvisResponse = m.content,
                            timestamp = u?.timestamp ?: m.timestamp
                        )
                        pendingUser = null
                    }
                    MessageRole.SYSTEM -> Unit
                }
            }
            pendingUser?.let { u ->
                turns += ChatTurn(id = u.id, userTranscript = u.content, jarvisResponse = "", timestamp = u.timestamp)
            }
            return turns
        }
    }
}
