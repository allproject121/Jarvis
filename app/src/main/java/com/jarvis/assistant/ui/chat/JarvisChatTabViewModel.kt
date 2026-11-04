package com.jarvis.assistant.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.attachments.AttachmentCache
import com.jarvis.assistant.androllm.attachments.AttachmentProcessor
import com.jarvis.assistant.androllm.attachments.AttachmentPromptBuilder
import com.jarvis.assistant.androllm.attachments.AttachmentSettingsStore
import com.jarvis.assistant.androllm.attachments.ChatAttachmentJson
import com.jarvis.assistant.androllm.attachments.model.ChatAttachment
import com.jarvis.assistant.androllm.chat.AndroLlmChatStore
import com.jarvis.assistant.androllm.chat.export.ConversationExporter
import com.jarvis.assistant.androllm.chat.export.ExportFormat
import com.jarvis.assistant.androllm.chat.model.Conversation
import com.jarvis.assistant.androllm.chat.model.Message
import com.jarvis.assistant.androllm.chat.model.MessageRole
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cross-conversation search result for the Chat tab drawer. */
internal data class ChatTabSearchResults(
    val query: String = "",
    val conversations: List<Conversation> = emptyList(),
    val hits: List<Pair<Message, String>> = emptyList()
)

/**
 * State holder for Jarvis' Chat tab. Conversations / messages come from the
 * AndroLLM chat store; every send goes through [JarvisChatBridge] →
 * JarvisConversationService.sendTextMessage (Jarvis' Gemini Live path).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal class JarvisChatTabViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application
    private val conversationRepo = AndroLlmChatStore.conversationRepository(application)
    private val messageRepo = AndroLlmChatStore.messageRepository(application)

    init {
        JarvisChatBridge.start(application)
    }

    val conversationId: StateFlow<String> = JarvisChatBridge.activeConversationId

    val conversation: StateFlow<Conversation?> = conversationId
        .flatMapLatest { id -> if (id.isBlank()) flowOf(null) else conversationRepo.getById(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val messages: StateFlow<List<Message>> = conversationId
        .flatMapLatest { id -> if (id.isBlank()) flowOf(emptyList()) else messageRepo.observeByConversationId(id) }
        .map { list -> list.filter { it.role != MessageRole.SYSTEM } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeConversations: StateFlow<List<Conversation>> = conversationRepo.observeActive()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val archivedConversations: StateFlow<List<Conversation>> = conversationRepo.observeArchived()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val bookmarkedMessages: StateFlow<List<Message>> = messageRepo.observeBookmarked()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val allConversations = conversationRepo.getAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val searchQuery = MutableStateFlow("")

    val searchResults: StateFlow<ChatTabSearchResults> = searchQuery
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce(150)
        .flatMapLatest { query ->
            if (query.isBlank()) {
                flowOf(ChatTabSearchResults())
            } else {
                combine(
                    conversationRepo.searchByTitle(query),
                    messageRepo.searchContent(query),
                    allConversations
                ) { convs, msgs, all ->
                    val titles = all.associate { it.id to it.title }
                    ChatTabSearchResults(
                        query = query,
                        conversations = convs,
                        hits = msgs.take(MAX_SEARCH_HITS).map { it to (titles[it.conversationId] ?: "Conversation") }
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatTabSearchResults())

    fun titleOf(conversationId: String): String =
        allConversations.value.firstOrNull { it.id == conversationId }?.title ?: "Conversation"

    // ── Attachments ─────────────────────────────────────────────────────────

    val pendingAttachments = MutableStateFlow<List<ChatAttachment>>(emptyList())

    /** Non-null while attachments are being parsed ("2/3 · report.pdf"). */
    val attachmentProgress = MutableStateFlow<String?>(null)

    fun attachmentsEnabled(): Boolean =
        AndroLlmFlags.isEnabled(app, AndroLlmFlags.CHAT_ATTACHMENTS, default = true)

    /** Copies + parses picked files (PDF, images via OCR, Office, text) into pending attachments. */
    fun attachFiles(uris: List<Uri>) {
        if (uris.isEmpty() || attachmentProgress.value != null) return
        if (!attachmentsEnabled()) {
            JarvisChatBridge.notice("Chat attachments are turned off in Settings.")
            return
        }
        viewModelScope.launch {
            val settings = AttachmentSettingsStore.get(app).current()
            val room = (settings.maxAttachmentsPerMessage - pendingAttachments.value.size).coerceAtLeast(0)
            val toProcess = uris.take(room)
            if (toProcess.isEmpty()) {
                JarvisChatBridge.notice("Up to ${settings.maxAttachmentsPerMessage} attachments per message.")
                return@launch
            }
            attachmentProgress.value = "Reading files…"
            try {
                val convId = JarvisChatBridge.ensureConversation(app, JarvisChatBridge.DEFAULT_TITLE)
                val results = AttachmentProcessor.get(app).processBatch(convId, toProcess) { current, total, name ->
                    attachmentProgress.value = "Reading $current/$total · $name"
                }
                pendingAttachments.update { (it + results.map { r -> r.attachment }).takeLast(settings.maxAttachmentsPerMessage) }
                results.filter { it.attachment.isFailed }.forEach {
                    JarvisChatBridge.notice("${it.attachment.name}: ${it.error ?: it.attachment.error ?: "could not be read"}")
                }
                if (uris.size > toProcess.size) {
                    JarvisChatBridge.notice("Only ${toProcess.size} of ${uris.size} files attached (limit ${settings.maxAttachmentsPerMessage}).")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                JarvisChatBridge.notice("Attachment failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                attachmentProgress.value = null
            }
        }
    }

    fun removeAttachment(attachmentId: String) {
        val removed = pendingAttachments.value.find { it.id == attachmentId }
        pendingAttachments.update { list -> list.filterNot { it.id == attachmentId } }
        removed?.let { att -> viewModelScope.launch(Dispatchers.IO) { runCatching { File(att.filePath).delete() } } }
    }

    private fun clearPendingAttachments(deleteFiles: Boolean) {
        val pending = pendingAttachments.value
        pendingAttachments.value = emptyList()
        if (deleteFiles && pending.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) { pending.forEach { runCatching { File(it.filePath).delete() } } }
        }
    }

    // ── Sending (through Jarvis' Gemini Live path) ──────────────────────────

    /**
     * Sends the composer text (+ pending attachments) to Jarvis. Returns false when
     * there is nothing to send. [startSession] = MainActivity.handleMicOrOrbClick().
     */
    fun send(rawText: String, startSession: () -> Unit): Boolean {
        val text = rawText.trim()
        val attachments = pendingAttachments.value
        if (attachmentProgress.value != null) {
            JarvisChatBridge.notice("Still reading attachments…")
            return false
        }
        if (text.isEmpty() && attachments.none { it.isReady }) return false
        clearPendingAttachments(deleteFiles = false)
        JarvisChatBridge.launch {
            val convId = JarvisChatBridge.ensureConversation(app, text.ifEmpty { attachments.first().name })
            val prior = messageRepo.getByConversationId(convId)
            val outgoing = buildOutgoing(convId, text, attachments, prior)
            JarvisChatBridge.recordAndSend(
                context = app,
                conversationId = convId,
                userContent = text,
                attachmentsJson = ChatAttachmentJson.encodeToString(attachments),
                outgoing = outgoing,
                startSession = startSession
            )
        }
        return true
    }

    /** Re-asks Jarvis the last prompt of the current conversation, replacing the reply(ies) after it. */
    fun regenerateLast(startSession: () -> Unit) {
        val convId = conversationId.value
        if (convId.isBlank()) return
        JarvisChatBridge.launch {
            val msgs = messageRepo.getByConversationId(convId)
            val lastUser = msgs.indexOfLast { it.role == MessageRole.USER }
            if (lastUser < 0) {
                JarvisChatBridge.notice("Nothing to regenerate")
                return@launch
            }
            messageRepo.deleteByIds(msgs.subList(lastUser + 1, msgs.size).map { it.id })
            val user = msgs[lastUser]
            val outgoing = buildOutgoing(
                convId,
                user.content,
                ChatAttachmentJson.decodeFromString(user.attachmentsJson),
                msgs.subList(0, lastUser)
            )
            JarvisChatBridge.resend(convId, outgoing, startSession)
        }
    }

    /** AndroLLM-style edit: replaces a user prompt, drops everything after it and asks Jarvis again. */
    fun editAndResend(messageId: String, newText: String, startSession: () -> Unit) {
        val clean = newText.trim()
        val convId = conversationId.value
        if (clean.isEmpty() || convId.isBlank()) return
        JarvisChatBridge.launch {
            val msgs = messageRepo.getByConversationId(convId)
            val index = msgs.indexOfFirst { it.id == messageId && it.role == MessageRole.USER }
            if (index < 0) {
                JarvisChatBridge.notice("That message is no longer in this chat")
                return@launch
            }
            val target = msgs[index]
            messageRepo.deleteByIds(msgs.drop(index + 1).map { it.id })
            val updated = target.copy(content = clean, timestamp = System.currentTimeMillis())
            messageRepo.upsert(updated).getOrThrow()
            val outgoing = buildOutgoing(
                convId,
                clean,
                ChatAttachmentJson.decodeFromString(target.attachmentsJson),
                msgs.subList(0, index)
            )
            JarvisChatBridge.resend(convId, outgoing, startSession)
        }
    }

    /**
     * Text actually delivered to Gemini Live (it only accepts text over this path):
     *  - a short recap of this conversation when Jarvis' live session last talked
     *    about a different conversation (so switching chats keeps per-chat context),
     *  - extracted attachment text (AndroLLM AttachmentPromptBuilder block),
     *  - the user's message.
     */
    private fun buildOutgoing(
        convId: String,
        text: String,
        attachments: List<ChatAttachment>,
        prior: List<Message>
    ): String {
        val prompt = text.ifBlank { if (attachments.isNotEmpty()) "Please review the attached file(s)." else "" }
        val attachmentBlock = if (attachmentsEnabled()) {
            AttachmentPromptBuilder.build(attachments, MAX_ATTACHMENT_CHARS_PER_FILE)
        } else {
            ""
        }
        val recap = if (JarvisChatBridge.lastSentConversationId != convId) recapOf(prior) else ""
        return buildString {
            if (recap.isNotBlank()) append(recap).append("\n\n")
            if (attachmentBlock.isNotBlank()) append(attachmentBlock).append("\n\n")
            append(prompt)
        }.trim()
    }

    private fun recapOf(prior: List<Message>): String {
        val recent = prior.filter { it.role != MessageRole.SYSTEM && it.content.isNotBlank() }.takeLast(RECAP_MESSAGES)
        if (recent.isEmpty()) return ""
        val lines = recent.joinToString("\n") { m ->
            val who = if (m.role == MessageRole.USER) "User" else "Jarvis"
            "$who: ${m.content.replace(Regex("\\s+"), " ").take(RECAP_CHARS_PER_MESSAGE)}"
        }
        return "[Earlier in this chat — context only, do not repeat it]\n$lines"
    }

    // ── Conversations ───────────────────────────────────────────────────────

    fun newConversation() {
        clearPendingAttachments(deleteFiles = true)
        JarvisChatBridge.setActiveConversation(app, "")
    }

    fun selectConversation(id: String) {
        if (id == conversationId.value) return
        clearPendingAttachments(deleteFiles = true)
        JarvisChatBridge.setActiveConversation(app, id)
    }

    fun togglePin(conversation: Conversation) {
        viewModelScope.launch { conversationRepo.setPinned(conversation.id, !conversation.isPinned) }
    }

    fun toggleArchive(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.setArchived(conversation.id, !conversation.isArchived)
            if (!conversation.isArchived && conversation.id == conversationId.value) newConversation()
        }
    }

    fun rename(conversationId: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch { conversationRepo.updateTitle(conversationId, clean) }
    }

    fun duplicate(conversationId: String) {
        viewModelScope.launch {
            conversationRepo.duplicateConversation(conversationId)
                .onSuccess { selectConversation(it.id) }
                .onFailure { JarvisChatBridge.notice("Duplicate failed: ${it.message}") }
        }
    }

    fun delete(conversationId: String) {
        if (conversationId == this.conversationId.value) newConversation()
        JarvisChatBridge.cancelAwaiting(conversationId)
        viewModelScope.launch {
            conversationRepo.deleteById(conversationId)
            withContext(Dispatchers.IO) { runCatching { AttachmentCache.clearConversation(app, conversationId) } }
        }
    }

    // ── Messages ────────────────────────────────────────────────────────────

    fun toggleBookmark(message: Message) {
        viewModelScope.launch { messageRepo.setBookmarked(message.id, !message.isBookmarked) }
    }

    fun bookmarkAll(ids: Collection<String>) {
        viewModelScope.launch { ids.forEach { messageRepo.setBookmarked(it, true) } }
    }

    fun deleteMessages(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch { messageRepo.deleteByIds(ids.toList()) }
    }

    // ── Export ──────────────────────────────────────────────────────────────

    fun currentTitle(): String = conversation.value?.title ?: JarvisChatBridge.DEFAULT_TITLE

    /** Messages of any conversation (export / share / stats from the drawer). */
    suspend fun messagesOf(conversationId: String): List<Message> =
        if (conversationId == this.conversationId.value) messages.value
        else messageRepo.getByConversationId(conversationId).filter { it.role != MessageRole.SYSTEM }

    suspend fun exportText(conversationId: String, title: String, format: ExportFormat): String =
        ConversationExporter.export(title, messagesOf(conversationId), format)

    suspend fun exportToUri(uri: Uri, conversationId: String, title: String, format: ExportFormat): Result<Unit> {
        val msgs = messagesOf(conversationId)
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ConversationExporter.exportBytes(title, msgs, format)
                app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Could not open the destination file")
                Unit
            }
        }
    }

    companion object {
        private const val MAX_SEARCH_HITS = 50
        private const val RECAP_MESSAGES = 6
        private const val RECAP_CHARS_PER_MESSAGE = 220

        /** Gemini Live receives the extracted text inline; keep each file within a sane size. */
        private const val MAX_ATTACHMENT_CHARS_PER_FILE = 8_000
    }
}
