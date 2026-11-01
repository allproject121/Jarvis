package com.jarvis.assistant.ui.compose.feature.chat

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.local.LocalEngineState
import com.jarvis.assistant.ai.local.ToolCallParser
import com.jarvis.assistant.ai.router.AiModelMode
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.attachments.AttachmentCache
import com.jarvis.assistant.androllm.attachments.AttachmentProcessor
import com.jarvis.assistant.androllm.attachments.AttachmentPromptBuilder
import com.jarvis.assistant.androllm.attachments.AttachmentSettingsStore
import com.jarvis.assistant.androllm.attachments.ChatAttachmentJson
import com.jarvis.assistant.androllm.attachments.model.ChatAttachment
import com.jarvis.assistant.androllm.chat.AndroLlmChatStore
import com.jarvis.assistant.androllm.chat.ChatPrefs
import com.jarvis.assistant.androllm.chat.export.ConversationExporter
import com.jarvis.assistant.androllm.chat.export.ExportFormat
import com.jarvis.assistant.androllm.chat.model.Conversation
import com.jarvis.assistant.androllm.chat.model.Message
import com.jarvis.assistant.androllm.chat.model.MessageOrigin
import com.jarvis.assistant.androllm.chat.model.MessageRole
import com.jarvis.assistant.data.model.ChatTurn
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Who wrote a message in the Compose chat. */
internal enum class ChatRole { USER, ASSISTANT }

/** Display model for one chat message (backed by an AndroLLM-chat [Message] row). */
internal data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val timestamp: Long,
    val conversationId: String = "",
    val isBookmarked: Boolean = false,
    val attachments: List<ChatAttachment> = emptyList(),
    /** Engine / provider that produced an assistant reply ("Jarvis" for imported history). */
    val modelId: String? = null,
    val origin: MessageOrigin = MessageOrigin.TYPED
)

/** Real, measured telemetry of the last (or current) generation. */
internal data class ChatGenerationStats(
    val providerName: String = "",
    val firstTokenMs: Long = 0L,
    val totalMs: Long = 0L,
    val chunkCount: Int = 0,
    val charCount: Int = 0,
    val stopReason: String = ""
) {
    val charsPerSecond: Float
        get() = if (totalMs > 0) charCount * 1000f / totalMs else 0f
}

/** One cross-conversation search hit. */
internal data class ChatSearchHit(
    val message: ChatMessage,
    val conversationTitle: String
)

/** Internal, mutable part of the screen state. */
private data class LocalChatState(
    val initialized: Boolean = false,
    val streamingText: String = "",
    val generatingConversationId: String? = null,
    /** True from the send click until the turn job finishes (covers the pre-stream DB writes). */
    val busy: Boolean = false,
    val stats: ChatGenerationStats? = null,
    val errorMessage: String? = null,
    val isSearchOpen: Boolean = false,
    val searchQuery: String = "",
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    val attachmentsProcessing: Boolean = false,
    val attachmentProgress: String = "",
    val scrollToMessageId: String? = null,
    /** Tool calls the provider reported during the current / last turn. */
    val toolEvents: List<ToolInvocationUi> = emptyList(),
    val toolEventsConversationId: String? = null
)

internal data class ChatUiState(
    val conversationId: String = "",
    val conversation: Conversation? = null,
    val pinnedConversations: List<Conversation> = emptyList(),
    val recentConversations: List<Conversation> = emptyList(),
    val archivedConversations: List<Conversation> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val rawMessages: List<Message> = emptyList(),
    val streamingText: String = "",
    val isGenerating: Boolean = false,
    val stats: ChatGenerationStats? = null,
    val errorMessage: String? = null,
    val isSearchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchConversations: List<Conversation> = emptyList(),
    val searchHits: List<ChatSearchHit> = emptyList(),
    val mode: AiModelMode = AiModelMode.AUTO_HYBRID,
    val providerName: String = "Auto",
    val offlineFallbackActive: Boolean = false,
    val attachmentsEnabled: Boolean = true,
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    val attachmentsProcessing: Boolean = false,
    val attachmentProgress: String = "",
    val scrollToMessageId: String? = null,
    /** False until history import + first emission (shows the skeleton). */
    val isLoaded: Boolean = false,
    /** Tool calls reported by the provider during the current / last turn of this conversation. */
    val toolEvents: List<ToolInvocationUi> = emptyList()
) {
    val conversationTitle: String
        get() = conversation?.title ?: if (messages.isEmpty()) "New Chat" else "Conversation"
}

/** Read-only snapshot of the Jarvis routing backend for the debug dialog. */
internal data class ChatDebugInfo(
    val mode: String,
    val provider: String,
    val offlineFallback: Boolean,
    val networkAvailable: Boolean,
    val localEngineState: String,
    val localEndpoint: String,
    val localHostModel: String,
    val openAiKeyConfigured: Boolean,
    val geminiKeyConfigured: Boolean,
    val storedTurns: Int,
    val conversationId: String,
    val conversationMessages: Int
)

private data class ConversationBits(
    val conversationId: String,
    val conversation: Conversation?,
    val active: List<Conversation>,
    val archived: List<Conversation>,
    val messages: List<Message>
)

private data class RouterBits(
    val mode: AiModelMode,
    val provider: String,
    val fallback: Boolean
)

private data class SearchBits(
    val conversations: List<Conversation> = emptyList(),
    val hits: List<ChatSearchHit> = emptyList()
)

/**
 * Multi-conversation chat ViewModel (AndroLLM feature/chat, adapted to Jarvis):
 *  - persistence: the separate `androllm_chat.db` via [AndroLlmChatStore]
 *    (Jarvis' ChatRepository history is imported once, read-only, and each
 *    new exchange is optionally mirrored back with ChatRepository.addTurn),
 *  - generation:  [com.jarvis.assistant.ai.router.ModelRouter.routeConversationalQuery], streamed,
 *  - attachments: [AttachmentProcessor] → extracted text injected into the system prompt.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app: JarvisApp = (application as? JarvisApp) ?: JarvisApp.instance
    private val jarvisHistory = app.chatRepository
    private val router = app.modelRouter
    private val conversationRepository = AndroLlmChatStore.conversationRepository(app)
    private val messageRepository = AndroLlmChatStore.messageRepository(app)
    private val attachmentProcessor = AttachmentProcessor.get(app)
    private val attachmentSettings = AttachmentSettingsStore.get(app)

    private val conversationId = MutableStateFlow("")
    private val local = MutableStateFlow(LocalChatState())
    private val attachmentsFlag = MutableStateFlow(attachmentsEnabledNow())
    private var generationJob: Job? = null
    private var attachJob: Job? = null

    private val messagesFlow = conversationId.flatMapLatest { id ->
        if (id.isBlank()) flowOf(emptyList()) else messageRepository.observeByConversationId(id)
    }

    private val conversationFlow = conversationId.flatMapLatest { id ->
        if (id.isBlank()) flowOf(null) else conversationRepository.getById(id)
    }

    private val conversationBits = combine(
        conversationId,
        conversationFlow,
        conversationRepository.observeActive(),
        conversationRepository.observeArchived(),
        messagesFlow
    ) { id, conv, active, archived, msgs ->
        ConversationBits(id, conv, active, archived, msgs)
    }

    private val routerBits = combine(
        router.currentMode,
        router.lastActiveProviderName,
        router.isOfflineFallbackActive
    ) { mode, provider, fallback -> RouterBits(mode, provider, fallback) }

    private val searchBits = local
        .map { it.searchQuery.trim() to it.isSearchOpen }
        .distinctUntilChanged()
        .debounce(150)
        .flatMapLatest { (query, open) ->
            if (!open || query.isBlank()) {
                flowOf(SearchBits())
            } else {
                combine(
                    conversationRepository.searchByTitle(query),
                    messageRepository.searchContent(query),
                    conversationRepository.getAll()
                ) { convs, msgs, all ->
                    val titles = all.associate { it.id to it.title }
                    SearchBits(
                        conversations = convs,
                        hits = msgs.take(MAX_SEARCH_HITS).map {
                            ChatSearchHit(it.toChatMessage(), titles[it.conversationId] ?: "Conversation")
                        }
                    )
                }
            }
        }

    val uiState: StateFlow<ChatUiState> = combine(
        conversationBits,
        routerBits,
        local,
        searchBits,
        attachmentsFlag
    ) { conv, routing, state, search, attachmentsOn ->
        val generatingHere = state.generatingConversationId != null &&
            state.generatingConversationId == conv.conversationId
        ChatUiState(
            conversationId = conv.conversationId,
            conversation = conv.conversation,
            pinnedConversations = conv.active.filter { it.isPinned },
            recentConversations = conv.active.filterNot { it.isPinned },
            archivedConversations = conv.archived,
            messages = conv.messages.map { it.toChatMessage() },
            rawMessages = conv.messages,
            streamingText = if (generatingHere) state.streamingText else "",
            isGenerating = state.generatingConversationId != null || state.busy,
            stats = state.stats,
            errorMessage = state.errorMessage,
            isSearchOpen = state.isSearchOpen,
            searchQuery = state.searchQuery,
            searchConversations = search.conversations,
            searchHits = search.hits,
            mode = routing.mode,
            providerName = routing.provider,
            offlineFallbackActive = routing.fallback,
            attachmentsEnabled = attachmentsOn,
            pendingAttachments = state.pendingAttachments,
            attachmentsProcessing = state.attachmentsProcessing,
            attachmentProgress = state.attachmentProgress,
            scrollToMessageId = state.scrollToMessageId,
            isLoaded = state.initialized,
            toolEvents = if (state.toolEventsConversationId == conv.conversationId) state.toolEvents else emptyList()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch {
            val imported = try {
                AndroLlmChatStore.importJarvisHistoryOnce(app, jarvisHistory.turns.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Jarvis history import failed: ${e.message}", e)
                null
            }
            val last = ChatPrefs.lastConversationId(app)
            val target = when {
                last.isNotBlank() && conversationRepository.findById(last) != null -> last
                imported != null -> imported
                else -> conversationRepository.observeActive().first().firstOrNull()?.id.orEmpty()
            }
            conversationId.value = target
            local.update { it.copy(initialized = true) }
            conversationId.collect { ChatPrefs.setLastConversationId(app, it) }
        }
    }

    // ── Sending & generation ────────────────────────────────────────────────

    /** Sends [text] (plus any pending attachments) and streams the reply. */
    fun sendMessage(text: String) {
        val clean = text.trim()
        val state = local.value
        val attachments = state.pendingAttachments
        // An attachment-only message ("ask about these files") is allowed, but only with a usable file.
        if (clean.isEmpty() && attachments.none { it.isReady }) return
        if (state.generatingConversationId != null || state.busy || state.attachmentsProcessing) return
        if (attachments.isNotEmpty() && !attachmentsFlag.value) {
            local.update { it.copy(errorMessage = "Chat attachments are turned off in Settings.") }
            return
        }
        local.update { it.copy(pendingAttachments = emptyList(), errorMessage = null) }

        generationJob = launchTurn {
            try {
                val id = ensureConversation(titleHint = clean.ifEmpty { attachments.first().name })
                val now = System.currentTimeMillis()
                val userMessage = Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = id,
                    role = MessageRole.USER,
                    content = clean,
                    timestamp = now,
                    attachmentsJson = ChatAttachmentJson.encodeToString(attachments)
                )
                messageRepository.upsert(userMessage).getOrThrow()
                maybeAutoTitle(id, clean.ifEmpty { attachments.first().name })
                val history = messageRepository.getByConversationId(id)
                generate(id, history)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Send failed: ${e.message}", e)
                local.update { it.copy(errorMessage = "Could not send: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** Launches one chat turn, keeping [LocalChatState.busy] set for its whole lifetime. */
    private fun launchTurn(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job {
        local.update { it.copy(busy = true) }
        val job = viewModelScope.launch(block = block)
        job.invokeOnCompletion {
            // A newer turn (started after a Stop) keeps the flag.
            val current = generationJob
            if (current == null || current === job || current.isCompleted) local.update { it.copy(busy = false) }
        }
        return job
    }

    /** Creates the conversation lazily (blank id = unsaved "New Chat"). */
    private suspend fun ensureConversation(titleHint: String): String {
        val current = conversationId.value
        if (current.isNotBlank() && conversationRepository.findById(current) != null) return current
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        conversationRepository.upsert(
            Conversation(id = id, title = generateTitle(titleHint), createdAt = now, updatedAt = now)
        ).getOrThrow()
        conversationId.value = id
        return id
    }

    /**
     * AndroLLM retitled the conversation on EVERY send, clobbering user
     * renames. Here only a still-default title is replaced.
     */
    private suspend fun maybeAutoTitle(id: String, hint: String) {
        val conv = conversationRepository.findById(id) ?: return
        if (conv.title == DEFAULT_TITLE && hint.isNotBlank()) {
            conversationRepository.updateTitle(id, generateTitle(hint))
        } else {
            conversationRepository.touch(id)
        }
    }

    /**
     * Streams a reply for [history] (which must end with the user prompt) via
     * ModelRouter and persists it to [convId].
     */
    private suspend fun generate(convId: String, history: List<Message>) {
        val userIndex = history.indexOfLast { it.role == MessageRole.USER }
        if (userIndex < 0) return
        val userMessage = history[userIndex]
        val attachments = ChatAttachmentJson.decodeFromString(userMessage.attachmentsJson)
        val prompt = userMessage.content.ifBlank {
            if (attachments.isNotEmpty()) "Please review the attached file(s)." else ""
        }
        if (prompt.isBlank()) return

        val mode = router.currentMode.value
        // Token-budget sliding window (AndroLLM ChatHistoryTrimmer) before the
        // MAX_HISTORY_TURNS cap, so long histories never overflow small on-device contexts.
        val trimmed = ChatHistoryTrimmer.trim(
            history = history.subList(0, userIndex + 1).filter { it.role != MessageRole.SYSTEM },
            contextLength = contextBudgetFor(mode),
            reservedOutputTokens = outputReserveFor(mode),
            systemTokenOverhead = ChatHistoryTrimmer.estimateTokens(SYSTEM_PROMPT) + MEMORY_CONTEXT_RESERVE_TOKENS
        )
        val priorTurns = toChatTurns(trimmed.dropLast(1)).takeLast(MAX_HISTORY_TURNS)
        val attachmentBlock = if (attachmentsFlag.value) {
            AttachmentPromptBuilder.build(
                attachments,
                if (mode == AiModelMode.LOCAL_OFFLINE) AttachmentPromptBuilder.MAX_CHARS_PER_FILE_ON_DEVICE
                else AttachmentPromptBuilder.MAX_CHARS_PER_FILE
            )
        } else ""
        val systemPrompt = if (attachmentBlock.isBlank()) SYSTEM_PROMPT else "$SYSTEM_PROMPT\n\n$attachmentBlock"

        val startedAt = System.currentTimeMillis()
        local.update {
            it.copy(
                generatingConversationId = convId,
                streamingText = "",
                errorMessage = null,
                stats = ChatGenerationStats(providerName = router.lastActiveProviderName.value)
            )
        }

        val accumulator = StringBuilder()
        var firstTokenAt = 0L
        var chunks = 0
        var stopReason = "completed"
        var failure: Throwable? = null
        local.update { it.copy(toolEvents = emptyList(), toolEventsConversationId = convId) }
        val toolWatcher = watchToolTraces(convId, startedAt)
        try {
            router.routeConversationalQuery(
                userPrompt = prompt,
                systemPrompt = systemPrompt,
                openAiApiKey = app.preferences.openAiApiKey,
                chatHistory = priorTurns
            ).collect { token ->
                if (token.isEmpty()) return@collect
                if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                chunks++
                accumulator.append(token)
                val visible = ToolCallParser.cleanConversationalText(accumulator.toString())
                val now = System.currentTimeMillis()
                local.update {
                    it.copy(
                        streamingText = visible,
                        stats = ChatGenerationStats(
                            providerName = router.lastActiveProviderName.value,
                            firstTokenMs = firstTokenAt - startedAt,
                            totalMs = now - startedAt,
                            chunkCount = chunks,
                            charCount = visible.length,
                            stopReason = "streaming"
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            stopReason = "cancelled"
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Generation failed: ${e.message}", e)
            stopReason = "error"
            failure = e
        } finally {
            toolWatcher.cancel()
            val reply = ToolCallParser.cleanConversationalText(accumulator.toString()).trim()
            val provider = router.lastActiveProviderName.value
            // Persist even when cancelled (Stop / conversation switch keeps the partial answer).
            withContext(NonCancellable) {
                if (reply.isNotEmpty()) {
                    val assistant = Message(
                        id = UUID.randomUUID().toString(),
                        conversationId = convId,
                        role = MessageRole.ASSISTANT,
                        content = reply,
                        timestamp = maxOf(System.currentTimeMillis(), userMessage.timestamp + 1),
                        modelId = provider
                    )
                    messageRepository.upsert(assistant)
                    conversationRepository.touch(convId)
                    if (ChatPrefs.mirrorToJarvis(app)) {
                        // Keeps Jarvis' classic chat + memory pipeline aware of this exchange.
                        runCatching { jarvisHistory.addTurn(userText = prompt, jarvisText = reply) }
                    }
                }
            }
            val end = System.currentTimeMillis()
            local.update {
                it.copy(
                    generatingConversationId = null,
                    streamingText = "",
                    errorMessage = when {
                        failure != null -> "Generation failed: ${failure?.message ?: failure?.javaClass?.simpleName}"
                        reply.isEmpty() && stopReason == "completed" -> "The active AI engine returned an empty response."
                        else -> it.errorMessage
                    },
                    stats = ChatGenerationStats(
                        providerName = provider,
                        firstTokenMs = if (firstTokenAt > 0) firstTokenAt - startedAt else 0L,
                        totalMs = end - startedAt,
                        chunkCount = chunks,
                        charCount = reply.length,
                        stopReason = stopReason
                    )
                )
            }
        }
    }

    /**
     * Mirrors the tool calls the provider reported during this turn into chat
     * cards. Jarvis' non-Gemini providers execute tools through the AndroLLM
     * agent pipeline, whose executor records every call in the
     * [com.jarvis.assistant.androllm.agent.trace.ToolExecutionTraceStore]; nothing
     * is shown when no provider reports tool calls.
     */
    private fun watchToolTraces(convId: String, since: Long): Job = viewModelScope.launch {
        var graph = com.jarvis.assistant.androllm.agent.AgentToolGraph.peek()
        while (graph == null) {
            kotlinx.coroutines.delay(TOOL_TRACE_POLL_MS)
            graph = com.jarvis.assistant.androllm.agent.AgentToolGraph.peek()
        }
        graph.traceStore.traces.collect { traces ->
            val turn = traces
                .filter { it.at >= since }
                .sortedBy { it.at }
                .map { ToolInvocationUi.fromTrace(it) }
                .takeLast(MAX_TOOL_CARDS)
            local.update {
                if (it.toolEventsConversationId != convId || it.toolEvents == turn) it
                else it.copy(toolEvents = turn)
            }
        }
    }

    /** Approximate context window of the active route (tokens). */
    private fun contextBudgetFor(mode: AiModelMode): Int = when (mode) {
        AiModelMode.LOCAL_OFFLINE ->
            runCatching { com.jarvis.assistant.ai.local.LocalAiConfig().contextWindowLimit }.getOrDefault(LOCAL_CONTEXT_FALLBACK)
        AiModelMode.LOCAL_LITERT -> LITERT_CONTEXT_BUDGET
        AiModelMode.LOCAL_HOST, AiModelMode.HERMES_AGENT -> LAN_CONTEXT_BUDGET
        else -> if (router.isOfflineFallbackActive.value) {
            runCatching { com.jarvis.assistant.ai.local.LocalAiConfig().contextWindowLimit }.getOrDefault(LOCAL_CONTEXT_FALLBACK)
        } else {
            CLOUD_CONTEXT_BUDGET
        }
    }

    private fun outputReserveFor(mode: AiModelMode): Int =
        (contextBudgetFor(mode) / 4).coerceIn(MIN_OUTPUT_RESERVE_TOKENS, MAX_OUTPUT_RESERVE_TOKENS)

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
    }

    /** Deletes the reply(ies) after the last user prompt and generates a fresh one. */
    fun regenerateLastResponse() {
        if (local.value.generatingConversationId != null || local.value.busy) return
        val convId = conversationId.value
        if (convId.isBlank()) return
        generationJob = launchTurn {
            try {
                val msgs = messageRepository.getByConversationId(convId)
                val lastUser = msgs.indexOfLast { it.role == MessageRole.USER }
                if (lastUser < 0) return@launchTurn
                val toDelete = msgs.subList(lastUser + 1, msgs.size).map { it.id }
                messageRepository.deleteByIds(toDelete)
                generate(convId, msgs.subList(0, lastUser + 1))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Regenerate failed: ${e.message}", e)
                local.update { it.copy(errorMessage = "Regenerate failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** Edits a user prompt: drops everything after it, updates it and regenerates (AndroLLM branching). */
    fun editUserPrompt(messageId: String, newContent: String) {
        val clean = newContent.trim()
        if (clean.isEmpty() || local.value.generatingConversationId != null || local.value.busy) return
        val convId = conversationId.value
        generationJob = launchTurn {
            try {
                val msgs = messageRepository.getByConversationId(convId)
                val target = msgs.find { it.id == messageId && it.role == MessageRole.USER } ?: return@launchTurn
                messageRepository.truncateAfterTimestamp(convId, target.timestamp).getOrThrow()
                // Rows sharing the target's timestamp but placed after it are dropped too.
                val index = msgs.indexOf(target)
                val sameTsAfter = msgs.drop(index + 1).filter { it.timestamp == target.timestamp }.map { it.id }
                messageRepository.deleteByIds(sameTsAfter)
                val updated = target.copy(content = clean, timestamp = System.currentTimeMillis())
                messageRepository.upsert(updated).getOrThrow()
                generate(convId, msgs.subList(0, index) + updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Edit prompt failed: ${e.message}", e)
                local.update { it.copy(errorMessage = "Edit failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    // ── Conversations ───────────────────────────────────────────────────────

    /** Starts a fresh, unsaved conversation (created on first send / attach). */
    fun newConversation() {
        cancelGeneration()
        clearPendingAttachments()
        conversationId.value = ""
        local.update { it.copy(stats = null, errorMessage = null) }
    }

    fun selectConversation(id: String) {
        if (id == conversationId.value) return
        cancelGeneration()
        clearPendingAttachments()
        conversationId.value = id
        local.update { it.copy(stats = null, errorMessage = null) }
    }

    fun togglePinConversation(conversation: Conversation) {
        viewModelScope.launch { conversationRepository.setPinned(conversation.id, !conversation.isPinned) }
    }

    fun toggleArchiveConversation(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepository.setArchived(conversation.id, !conversation.isArchived)
            if (!conversation.isArchived && conversation.id == conversationId.value) newConversation()
        }
    }

    fun renameConversation(conversationId: String, newTitle: String) {
        val title = newTitle.trim()
        if (title.isEmpty()) return
        viewModelScope.launch { conversationRepository.updateTitle(conversationId, title) }
    }

    fun duplicateConversation(id: String) {
        viewModelScope.launch {
            conversationRepository.duplicateConversation(id)
                .onSuccess { selectConversation(it.id) }
                .onFailure { e -> local.update { it.copy(errorMessage = "Duplicate failed: ${e.message}") } }
        }
    }

    fun deleteConversation(id: String) {
        if (id == conversationId.value) newConversation()
        viewModelScope.launch {
            conversationRepository.deleteById(id)
            // Conversation-scoped attachment cache goes with the conversation.
            withContext(Dispatchers.IO) { runCatching { AttachmentCache.clearConversation(app, id) } }
        }
    }

    // ── Messages ────────────────────────────────────────────────────────────

    fun toggleBookmarkMessage(messageId: String, currentBookmarked: Boolean) {
        viewModelScope.launch { messageRepository.setBookmarked(messageId, !currentBookmarked) }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch { messageRepository.deleteById(messageId) }
    }

    fun deleteMessages(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch { messageRepository.deleteByIds(ids.toList()) }
    }

    // ── Attachments ─────────────────────────────────────────────────────────

    /** Re-reads the attachment feature flag (the user may have toggled it in Settings). */
    fun refreshFlags() {
        attachmentsFlag.value = attachmentsEnabledNow()
    }

    private fun attachmentsEnabledNow(): Boolean =
        AndroLlmFlags.isEnabled(app, AndroLlmFlags.CHAT_ATTACHMENTS, default = true)

    /** Copies + parses picked files into pending attachments for the next message. */
    fun attachFiles(uris: List<Uri>) {
        if (uris.isEmpty() || local.value.attachmentsProcessing) return
        if (!attachmentsFlag.value) {
            local.update { it.copy(errorMessage = "Chat attachments are turned off in Settings.") }
            return
        }
        attachJob = viewModelScope.launch {
            val settings = attachmentSettings.current()
            val room = (settings.maxAttachmentsPerMessage - local.value.pendingAttachments.size).coerceAtLeast(0)
            val toProcess = uris.take(room)
            if (toProcess.isEmpty()) {
                local.update {
                    it.copy(errorMessage = "Up to ${settings.maxAttachmentsPerMessage} attachments per message.")
                }
                return@launch
            }
            local.update { it.copy(attachmentsProcessing = true, attachmentProgress = "") }
            try {
                val convId = ensureConversation(titleHint = DEFAULT_TITLE)
                val results = attachmentProcessor.processBatch(convId, toProcess) { current, total, name ->
                    local.update { it.copy(attachmentProgress = "$current/$total · $name") }
                }
                val processed = results.map { it.attachment }
                local.update {
                    it.copy(
                        pendingAttachments = (it.pendingAttachments + processed).takeLast(settings.maxAttachmentsPerMessage)
                    )
                }
                if (uris.size > toProcess.size) {
                    local.update {
                        it.copy(errorMessage = "Only ${toProcess.size} of ${uris.size} files attached (limit ${settings.maxAttachmentsPerMessage}).")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(errorMessage = "Attachment failed: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                local.update { it.copy(attachmentsProcessing = false, attachmentProgress = "") }
            }
        }
    }

    /** Removes one pending attachment before sending (and its private copy). */
    fun removeAttachment(attachmentId: String) {
        val removed = local.value.pendingAttachments.find { it.id == attachmentId }
        local.update { s -> s.copy(pendingAttachments = s.pendingAttachments.filterNot { it.id == attachmentId }) }
        removed?.let { att ->
            viewModelScope.launch(Dispatchers.IO) { runCatching { File(att.filePath).delete() } }
        }
    }

    fun clearPendingAttachments() {
        attachJob?.cancel()
        val pending = local.value.pendingAttachments
        local.update { it.copy(pendingAttachments = emptyList(), attachmentsProcessing = false) }
        if (pending.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                pending.forEach { att -> runCatching { File(att.filePath).delete() } }
            }
        }
    }

    // ── Search / misc UI ────────────────────────────────────────────────────

    fun dismissError() = local.update { it.copy(errorMessage = null) }

    fun toggleSearch(open: Boolean) = local.update {
        it.copy(isSearchOpen = open, searchQuery = if (open) it.searchQuery else "")
    }

    fun updateSearchQuery(query: String) = local.update { it.copy(searchQuery = query) }

    /** Opens the conversation of a search hit and asks the screen to scroll to it. */
    fun openSearchHit(hit: ChatSearchHit) {
        selectConversation(hit.message.conversationId)
        local.update { it.copy(isSearchOpen = false, searchQuery = "", scrollToMessageId = hit.message.id) }
    }

    fun consumeScrollTarget() = local.update { it.copy(scrollToMessageId = null) }

    /** Switches the router mode exactly as Jarvis' Settings screen does. */
    fun setMode(mode: AiModelMode) {
        router.setMode(mode)
        app.preferences.aiModelMode = mode.name
    }

    /** Writes the current conversation (or [subset]) to [uri] in [format]. */
    suspend fun exportToUri(uri: Uri, format: ExportFormat, subset: List<Message>? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val state = uiState.value
                val messages = subset ?: state.rawMessages
                val bytes = ConversationExporter.exportBytes(state.conversationTitle, messages, format)
                app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Could not open the destination file")
                Unit
            }
        }

    fun exportText(format: ExportFormat): String {
        val state = uiState.value
        return ConversationExporter.export(state.conversationTitle, state.rawMessages, format)
    }

    fun debugInfo(): ChatDebugInfo {
        val prefs = app.preferences
        val engineState = when (val s = app.localAiEngine.engineState.value) {
            is LocalEngineState.Error -> "Error: ${s.message}"
            LocalEngineState.Uninitialized -> "Uninitialized"
            LocalEngineState.Loading -> "Loading"
            LocalEngineState.Ready -> "Ready"
            LocalEngineState.Generating -> "Generating"
        }
        return ChatDebugInfo(
            mode = router.currentMode.value.displayName,
            provider = router.lastActiveProviderName.value,
            offlineFallback = router.isOfflineFallbackActive.value,
            networkAvailable = router.isNetworkAvailable(),
            localEngineState = engineState,
            localEndpoint = prefs.customEndpoint,
            localHostModel = prefs.localHostModel,
            openAiKeyConfigured = prefs.openAiApiKey.isNotBlank(),
            geminiKeyConfigured = prefs.apiKey.isNotBlank(),
            storedTurns = jarvisHistory.turns.value.size,
            conversationId = conversationId.value.ifBlank { "(new, unsaved)" },
            conversationMessages = uiState.value.messages.size
        )
    }

    override fun onCleared() {
        // The partial reply is persisted in generate()'s NonCancellable finally block.
        generationJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "ComposeChatViewModel"
        /** Prior turns sent to ModelRouter as context. */
        const val MAX_HISTORY_TURNS = 10
        private const val MAX_TOOL_CARDS = 12
        private const val TOOL_TRACE_POLL_MS = 400L
        // Context budgets (tokens) used by ChatHistoryTrimmer per route.
        private const val LOCAL_CONTEXT_FALLBACK = 2048
        private const val LITERT_CONTEXT_BUDGET = 4096
        private const val LAN_CONTEXT_BUDGET = 8192
        private const val CLOUD_CONTEXT_BUDGET = 32768
        private const val MIN_OUTPUT_RESERVE_TOKENS = 256
        private const val MAX_OUTPUT_RESERVE_TOKENS = 4096
        /** Room for the memory RAG block ModelRouter injects. */
        private const val MEMORY_CONTEXT_RESERVE_TOKENS = 256
        private const val MAX_SEARCH_HITS = 50
        const val DEFAULT_TITLE = "New Chat"

        private const val SYSTEM_PROMPT =
            "You are JARVIS, an ultra-capable AI assistant. " +
                "Answer clearly and helpfully. Use Markdown (headings, lists, tables, fenced code blocks) when it improves readability."

        private fun generateTitle(text: String): String {
            val clean = text.replace(Regex("\\s+"), " ").trim()
            if (clean.isEmpty()) return DEFAULT_TITLE
            return if (clean.length > 30) clean.take(30).trimEnd() + "…" else clean
        }

        /** Pairs user prompts with the assistant reply that follows them (ModelRouter history format). */
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
            return turns.filter { it.userTranscript.isNotBlank() || it.jarvisResponse.isNotBlank() }
        }
    }
}

internal fun Message.toChatMessage(): ChatMessage = ChatMessage(
    id = id,
    role = if (role == MessageRole.USER) ChatRole.USER else ChatRole.ASSISTANT,
    content = content,
    timestamp = timestamp,
    conversationId = conversationId,
    isBookmarked = isBookmarked,
    attachments = ChatAttachmentJson.decodeFromString(attachmentsJson),
    modelId = modelId,
    origin = origin
)
