package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.router.AiModelMode
import com.jarvis.assistant.androllm.attachments.model.ChatAttachment
import com.jarvis.assistant.androllm.chat.export.ConversationExporter
import com.jarvis.assistant.androllm.chat.export.ExportFormat
import com.jarvis.assistant.androllm.chat.model.Conversation
import com.jarvis.assistant.androllm.chat.model.Message
import com.jarvis.assistant.androllm.chat.model.MessageOrigin
import com.jarvis.assistant.androllm.chat.model.MessageRole
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.data.model.MariaPersona
import com.jarvis.assistant.databinding.FragmentChatBinding
import com.jarvis.assistant.ui.compose.feature.chat.ConversationSharer
import com.jarvis.assistant.ui.compose.feature.prompts.PendingPrompt
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.home.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Jarvis' Chat tab.
 *
 * The send/receive path is Jarvis' own and unchanged: text goes to
 * [JarvisConversationService.sendTextMessage] (Gemini Live), starting the
 * session with MainActivity.handleMicOrOrbClick() when needed, and replies come
 * back through Jarvis' ChatRepository (see [JarvisChatBridge]).
 *
 * AndroLLM chat features merged in, in Jarvis' look: multi-conversation drawer
 * (hamburger), new chat (+), pin / rename / archive / duplicate / delete,
 * search across chats and messages, bookmarks, regenerate, edit & resend,
 * delete, multi-select, share, follow-up chips, Markdown replies (code blocks
 * with copy, tables, lists, safe links), export (Markdown / JSON / HTML / PDF /
 * text), real stats, attachments (PDF / image OCR / Office / text) on the +
 * button, and the prompt-library hand-off (PendingPrompt).
 */
class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    @Suppress("unused")
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel

    private lateinit var chatVm: JarvisChatTabViewModel

    /** Message-row callbacks (kept as an object: the actions interface is module-internal). */
    private val messageActions = object : ChatTabMessageActions {
        override fun onCopy(message: Message) = this@ChatFragment.onCopy(message)
        override fun onEdit(message: Message) = this@ChatFragment.onEdit(message)
        override fun onPlay(message: Message) = this@ChatFragment.onPlay(message)
        override fun onRegenerate(message: Message) = this@ChatFragment.onRegenerate(message)
        override fun onToggleBookmark(message: Message) = this@ChatFragment.onToggleBookmark(message)
        override fun onShare(message: Message) = this@ChatFragment.onShare(message)
        override fun onMore(anchor: View, message: Message, isLastAssistant: Boolean) =
            this@ChatFragment.onMore(anchor, message, isLastAssistant)
        override fun onLongPress(message: Message) = this@ChatFragment.onLongPress(message)
        override fun onTap(message: Message): Boolean = this@ChatFragment.onTap(message)
        override fun onFollowUp(text: String) = this@ChatFragment.onFollowUp(text)
    }

    private val messageAdapter = ChatTabMessageAdapter(messageActions)
    private val drawerAdapter = ChatDrawerAdapter(
        onOpenConversation = { conversation ->
            chatVm.selectConversation(conversation.id)
            closeDrawer()
        },
        onConversationMore = { anchor, conversation -> showConversationMenu(anchor, conversation) },
        onOpenHit = { message ->
            if (message.conversationId == chatVm.conversationId.value) {
                val index = messageAdapter.currentList.indexOfFirst { it.key == message.id }
                if (index >= 0) _binding?.rvChatMessages?.scrollToPosition(index)
            } else {
                pendingScrollMessageId = message.id
                chatVm.selectConversation(message.conversationId)
            }
            closeDrawer()
        }
    )

    private enum class DrawerMode { CHATS, ARCHIVED, BOOKMARKS }

    private val drawerMode = MutableStateFlow(DrawerMode.CHATS)
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private var drawerOpen = false
    private var editingMessageId: String? = null
    private var pendingScrollMessageId: String? = null
    private var lastRowCount = 0
    private var lastRowKey: String? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    /** Same action as the original send path: start Jarvis' live session when it is not running. */
    private val startSession: () -> Unit = { (activity as? MainActivity)?.handleMicOrOrbClick() }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                drawerOpen -> closeDrawer()
                selection.value.isNotEmpty() -> clearSelection()
                editingMessageId != null -> cancelEditing()
            }
            updateBackCallback()
        }
    }

    private val pickAttachments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && ::chatVm.isInitialized) chatVm.attachFiles(uris)
    }

    private var pendingExport: Pair<String, String>? = null

    private val exportLaunchers = ExportFormat.entries.associateWith { format ->
        registerForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri ->
            val target = pendingExport
            pendingExport = null
            if (uri == null || target == null || !::chatVm.isInitialized) return@registerForActivityResult
            lifecycleScope.launch {
                val result = chatVm.exportToUri(uri, target.first, target.second, format)
                toast(if (result.isSuccess) "Exported as ${format.label}" else "Export failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        chatVm = ViewModelProvider(this)[JarvisChatTabViewModel::class.java]
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        // Dark Bento (2026-11-05 bento-all): panelDrawer keeps its rounded glass drawable
        // (bg_bento_x_chat_drawer) instead of a flat dynamic colour.

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val isImeVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            val density = resources.displayMetrics.density
            val topInset = statusBarInsets.top
            val safeTopPadding = if (topInset > 0) topInset + (6 * density).toInt() else (14 * density).toInt()
            val bottomPadding = if (isImeVisible) {
                imeInsets.bottom
            } else {
                (100 * density).toInt()
            }
            binding.chatContent.setPadding(
                (16 * density).toInt(),
                safeTopPadding,
                (16 * density).toInt(),
                bottomPadding
            )
            // Drawer header gets the same safe top inset so it never sits under the status bar.
            binding.panelDrawer.setPadding(
                binding.panelDrawer.paddingLeft,
                safeTopPadding,
                binding.panelDrawer.paddingRight,
                binding.panelDrawer.paddingBottom
            )
            (activity as? MainActivity)?.setBottomDockVisibility(!isImeVisible, animated = true)
            if (isImeVisible) {
                val count = messageAdapter.itemCount
                if (count > 0) {
                    binding.rvChatMessages.post {
                        binding.rvChatMessages.scrollToPosition(count - 1)
                    }
                }
            }
            insets
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        // Setup Chat List
        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.adapter = messageAdapter
        binding.rvDrawer.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDrawer.adapter = drawerAdapter

        // Send Message
        binding.btnSendMessage.setOnClickListener {
            sendMessage()
        }

        // New Chat (a fresh conversation; Jarvis' stored history is kept)
        binding.btnChatNew.setOnClickListener {
            clearSelection()
            cancelEditing()
            chatVm.newConversation()
            toast("New chat")
        }

        // Menu: conversation drawer (long-press: back to Home, as the button used to do)
        binding.btnChatMenu.setOnClickListener { openDrawer() }
        binding.btnChatMenu.setOnLongClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_HOME)
            true
        }

        // Header: actions for the current conversation
        binding.layoutChatHeader.setOnClickListener { showCurrentConversationMenu(it) }

        // Persona chip: pick one of Jarvis' personalities (the same preference Settings sets)
        binding.chipPersona.setOnClickListener { showPersonaPicker() }

        // Mode chip: pick Jarvis' AI engine (the same selector as Settings > AI engine)
        binding.chipMode.setOnClickListener { showAiModePicker() }
        refreshChipLabels()
        // Long-press the Code chip: open Code Studio (AI coding IDE)
        binding.chipMode.setOnLongClickListener {
            com.jarvis.assistant.codestudio.CodeStudioActivity.open(requireContext())
            true
        }

        binding.btnAttach.setOnClickListener { showAttachMenu(it) }
        binding.btnCancelEditing.setOnClickListener { cancelEditing() }

        setupSelectionBar()
        setupDrawer()
        observeChat()
        consumePendingPrompt()

        // Automatically scroll chat up when user types or focuses input
        binding.etMessageInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val count = messageAdapter.itemCount
                if (count > 0) {
                    binding.rvChatMessages.post {
                        binding.rvChatMessages.scrollToPosition(count - 1)
                    }
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        binding.etMessageInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val count = messageAdapter.itemCount
                if (count > 0) {
                    binding.rvChatMessages.postDelayed({
                        binding.rvChatMessages.scrollToPosition(count - 1)
                    }, 120)
                }
            }
        }
        binding.etMessageInput.setOnClickListener {
            val count = messageAdapter.itemCount
            if (count > 0) {
                binding.rvChatMessages.postDelayed({
                    binding.rvChatMessages.scrollToPosition(count - 1)
                }, 120)
            }
        }
        binding.rvChatMessages.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) {
                val count = messageAdapter.itemCount
                if (count > 0) {
                    binding.rvChatMessages.post {
                        binding.rvChatMessages.scrollToPosition(count - 1)
                    }
                }
            }
        }
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    private fun sendMessage() {
        val text = binding.etMessageInput.text?.toString()?.trim() ?: ""
        val editing = editingMessageId
        if (editing != null) {
            if (text.isBlank()) return
            chatVm.editAndResend(editing, text, startSession)
            binding.etMessageInput.text?.clear()
            cancelEditing()
            return
        }
        if (chatVm.send(text, startSession)) {
            binding.etMessageInput.text?.clear()
            binding.rvChatMessages.post {
                val count = messageAdapter.itemCount
                if (count > 0) binding.rvChatMessages.scrollToPosition(count - 1)
            }
        }
    }

    private fun startEditing(message: Message) {
        editingMessageId = message.id
        binding.layoutEditingBanner.visibility = View.VISIBLE
        putInComposer(message.content)
        updateBackCallback()
    }

    private fun cancelEditing() {
        if (_binding == null) return
        editingMessageId = null
        binding.layoutEditingBanner.visibility = View.GONE
        updateBackCallback()
    }

    private fun putInComposer(text: String) {
        binding.etMessageInput.setText(text)
        binding.etMessageInput.setSelection(text.length)
        binding.etMessageInput.requestFocus()
    }

    /** Prompt-library hand-off ("Send to model" in the Prompts screen). */
    private fun consumePendingPrompt() {

        // Automatically scroll chat up when user types or focuses input
        binding.etMessageInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val count = messageAdapter.itemCount
                if (count > 0) {
                    binding.rvChatMessages.post {
                        binding.rvChatMessages.scrollToPosition(count - 1)
                    }
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        binding.etMessageInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val count = messageAdapter.itemCount
                if (count > 0) {
                    binding.rvChatMessages.postDelayed({
                        binding.rvChatMessages.scrollToPosition(count - 1)
                    }, 120)
                }
            }
        }
        binding.etMessageInput.setOnClickListener {
            val count = messageAdapter.itemCount
            if (count > 0) {
                binding.rvChatMessages.postDelayed({
                    binding.rvChatMessages.scrollToPosition(count - 1)
                }, 120)
            }
        }
        binding.rvChatMessages.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) {
                val count = messageAdapter.itemCount
                if (count > 0) {
                    binding.rvChatMessages.post {
                        binding.rvChatMessages.scrollToPosition(count - 1)
                    }
                }
            }
        }
        if (_binding == null) return
        val prompt = PendingPrompt.text ?: return
        PendingPrompt.text = null
        if (prompt.isNotBlank()) putInComposer(prompt)
    }

    // ── Observation ─────────────────────────────────────────────────────────

    private fun observeChat() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        chatVm.messages,
                        chatVm.conversationId,
                        JarvisChatBridge.awaitingConversationId,
                        JarvisConversationService.liveAssistantResponse,
                        selection
                    ) { messages, convId, awaiting, live, selected ->
                        buildRows(messages, convId, awaiting, live, selected)
                    }.collect { rows -> submitRows(rows) }
                }
                launch {
                    chatVm.conversation.collect { conversation ->
                        binding.tvChatTitle.text = conversation?.title ?: "New chat"
                    }
                }
                launch {
                    chatVm.conversationId.collect {
                        // Switching chats drops a selection that belonged to the previous one.
                        if (selection.value.isNotEmpty()) clearSelection()
                    }
                }
                launch {
                    combine(chatVm.pendingAttachments, chatVm.attachmentProgress) { a, p -> a to p }
                        .collect { (attachments, progress) -> renderAttachments(attachments, progress) }
                }
                launch {
                    combine(
                        chatVm.activeConversations,
                        chatVm.archivedConversations,
                        chatVm.bookmarkedMessages,
                        chatVm.searchResults,
                        drawerMode
                    ) { active, archived, bookmarked, search, mode ->
                        DrawerInputs(active, archived, bookmarked, search, mode)
                    }.combine(chatVm.conversationId) { inputs, convId -> buildDrawerRows(inputs, convId) }
                        .collect { rows ->
                            drawerAdapter.submitList(rows)
                            binding.tvDrawerEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                        }
                }
                launch {
                    selection.collect { selected -> renderSelectionBar(selected) }
                }
                launch {
                    JarvisChatBridge.notices.collect { toast(it) }
                }
            }
        }
    }

    private fun buildRows(
        messages: List<Message>,
        convId: String,
        awaiting: String?,
        live: String,
        selected: Set<String>
    ): List<ChatTabRow> {
        val awaitingHere = awaiting != null && awaiting == convId && convId.isNotBlank()
        val lastAssistantId = messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }?.id
        val rows = ArrayList<ChatTabRow>(messages.size + 1)
        messages.forEach { m ->
            rows += ChatTabRow.Item(
                message = m,
                selected = m.id in selected,
                isLastAssistant = m.id == lastAssistantId && !awaitingHere,
                showFollowUps = m.id == lastAssistantId && !awaitingHere && selected.isEmpty()
            )
        }
        if (awaitingHere) rows += ChatTabRow.Streaming(live)
        return rows
    }

    private fun submitRows(rows: List<ChatTabRow>) {
        messageAdapter.submitList(rows) {
            val b = _binding ?: return@submitList
            b.tvChatEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            val scrollTarget = pendingScrollMessageId
            if (scrollTarget != null) {
                val index = rows.indexOfFirst { it.key == scrollTarget }
                if (index >= 0) {
                    b.rvChatMessages.scrollToPosition(index)
                    pendingScrollMessageId = null
                    lastRowCount = rows.size
                    lastRowKey = rows.lastOrNull()?.key
                    return@submitList
                }
            }
            val lastKey = rows.lastOrNull()?.key
            val streaming = rows.lastOrNull() is ChatTabRow.Streaming
            if (rows.isNotEmpty() && (rows.size != lastRowCount || lastKey != lastRowKey || streaming)) {
                b.rvChatMessages.smoothScrollToPosition(rows.size - 1)
            }
            lastRowCount = rows.size
            lastRowKey = lastKey
        }
    }

    // ── Attachments (+ button) ──────────────────────────────────────────────

    private fun showAttachMenu(anchor: View) {
        if (!chatVm.attachmentsEnabled()) {
            toast("Chat attachments are turned off in Settings")
            return
        }
        PopupMenu(requireContext(), anchor).apply {
            menu.add("Document (PDF, Word, PowerPoint, Excel, text)").setOnMenuItemClickListener {
                pickAttachments.launch(DOCUMENT_MIME_TYPES)
                true
            }
            menu.add("Image (text read by OCR)").setOnMenuItemClickListener {
                pickAttachments.launch(arrayOf("image/*"))
                true
            }
            show()
        }
    }

    private fun renderAttachments(attachments: List<ChatAttachment>, progress: String?) {
        val b = _binding ?: return
        b.tvAttachmentProgress.visibility = if (progress != null) View.VISIBLE else View.GONE
        b.tvAttachmentProgress.text = progress.orEmpty()
        b.layoutAttachmentChips.removeAllViews()
        b.scrollAttachmentChips.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
        val density = resources.displayMetrics.density
        val primary = requireContext().jarvisThemeColor(R.attr.themeTextPrimary, android.graphics.Color.WHITE)
        val muted = requireContext().jarvisThemeColor(R.attr.themeTextMuted, android.graphics.Color.GRAY)
        attachments.forEach { att ->
            val chip = TextView(requireContext()).apply {
                text = buildString {
                    append(att.label)
                    when {
                        att.isFailed -> append(" · failed")
                        att.fromOcr -> append(" · OCR")
                    }
                    append("   ✕")
                }
                textSize = 12f
                setTextColor(if (att.isFailed) muted else primary)
                setBackgroundResource(R.drawable.bg_set_chip) // 2026-11-05 redesign: outline pill
                setPadding((12 * density).toInt(), (7 * density).toInt(), (12 * density).toInt(), (7 * density).toInt())
                contentDescription = "Remove ${att.name}"
                setOnClickListener { chatVm.removeAttachment(att.id) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * density).toInt() }
            b.layoutAttachmentChips.addView(chip, lp)
        }
        b.etMessageInput.hint = if (attachments.isNotEmpty()) "Ask Jarvis about the attached files" else "Message Jarvis"
    }

    // ── Message actions ─────────────────────────────────────────────────────

    private fun onCopy(message: Message) {
        val label = if (message.role == MessageRole.USER) "User message" else "Jarvis response"
        copyToClipboard(label, message.content)
    }

    private fun onEdit(message: Message) {
        // Original Jarvis behaviour: put the message back into the composer.
        putInComposer(message.content)
    }

    private fun onPlay(message: Message) {
        Toast.makeText(requireContext(), "Playing response...", Toast.LENGTH_SHORT).show()
        speak(message.content)
    }

    private fun onRegenerate(message: Message) {
        chatVm.regenerateLast(startSession)
    }

    private fun onToggleBookmark(message: Message) {
        chatVm.toggleBookmark(message)
        toast(if (message.isBookmarked) "Bookmark removed" else "Bookmarked")
    }

    private fun onShare(message: Message) {
        ConversationSharer.shareSingleMessage(requireContext(), message.content)
    }

    private fun onMore(anchor: View, message: Message, isLastAssistant: Boolean) {
        PopupMenu(requireContext(), anchor).apply {
            if (message.role == MessageRole.USER) {
                menu.add("Edit & resend").setOnMenuItemClickListener { startEditing(message); true }
            } else {
                if (isLastAssistant) {
                    menu.add("Regenerate").setOnMenuItemClickListener { onRegenerate(message); true }
                }
                menu.add("Read aloud").setOnMenuItemClickListener { onPlay(message); true }
            }
            menu.add("Copy").setOnMenuItemClickListener { onCopy(message); true }
            menu.add(if (message.isBookmarked) "Remove bookmark" else "Bookmark")
                .setOnMenuItemClickListener { onToggleBookmark(message); true }
            menu.add("Share").setOnMenuItemClickListener { onShare(message); true }
            menu.add("Select").setOnMenuItemClickListener { toggleSelected(message.id); true }
            menu.add("Delete").setOnMenuItemClickListener {
                confirm("Delete message", "Delete this message from the chat?", "Delete") {
                    chatVm.deleteMessages(listOf(message.id))
                }
                true
            }
            show()
        }
    }

    private fun onLongPress(message: Message) {
        toggleSelected(message.id)
    }

    private fun onTap(message: Message): Boolean {
        if (selection.value.isEmpty()) return false
        toggleSelected(message.id)
        return true
    }

    private fun onFollowUp(text: String) {
        chatVm.send(text, startSession)
    }

    // ── Multi-select ────────────────────────────────────────────────────────

    private fun toggleSelected(id: String) {
        val current = selection.value
        selection.value = if (id in current) current - id else current + id
        updateBackCallback()
    }

    private fun clearSelection() {
        selection.value = emptySet()
        updateBackCallback()
    }

    private fun selectedMessages(): List<Message> {
        val ids = selection.value
        return chatVm.messages.value.filter { it.id in ids }
    }

    private fun setupSelectionBar() {
        binding.btnSelectionClose.setOnClickListener { clearSelection() }
        binding.btnSelectionCopy.setOnClickListener {
            val text = selectedMessages().joinToString("\n\n") { m ->
                (if (m.role == MessageRole.USER) "You: " else "Jarvis: ") + m.content
            }
            copyToClipboard("Jarvis chat", text)
            clearSelection()
        }
        binding.btnSelectionShare.setOnClickListener {
            val text = ConversationExporter.export(chatVm.currentTitle(), selectedMessages(), ExportFormat.MARKDOWN)
            ConversationSharer.shareText(requireContext(), text, "Share messages")
            clearSelection()
        }
        binding.btnSelectionBookmark.setOnClickListener {
            chatVm.bookmarkAll(selection.value)
            toast("Bookmarked ${selection.value.size}")
            clearSelection()
        }
        binding.btnSelectionDelete.setOnClickListener {
            val ids = selection.value
            confirm("Delete messages", "Delete ${ids.size} selected message(s)?", "Delete") {
                chatVm.deleteMessages(ids)
                clearSelection()
            }
        }
    }

    private fun renderSelectionBar(selected: Set<String>) {
        val b = _binding ?: return
        val active = selected.isNotEmpty()
        b.layoutSelectionBar.visibility = if (active) View.VISIBLE else View.GONE
        b.tvSelectionCount.text = "${selected.size} selected"
        val headerVisibility = if (active) View.INVISIBLE else View.VISIBLE
        b.btnChatMenu.visibility = headerVisibility
        b.layoutChatHeader.visibility = headerVisibility
        b.btnChatNew.visibility = headerVisibility
    }

    // ── Conversation drawer ─────────────────────────────────────────────────

    private data class DrawerInputs(
        val active: List<Conversation>,
        val archived: List<Conversation>,
        val bookmarked: List<Message>,
        val search: ChatTabSearchResults,
        val mode: DrawerMode
    )

    private fun buildDrawerRows(inputs: DrawerInputs, currentId: String): List<ChatDrawerRow> {
        val rows = ArrayList<ChatDrawerRow>()
        val query = chatVm.searchQuery.value.trim()
        if (query.isNotEmpty()) {
            if (inputs.search.conversations.isNotEmpty()) {
                rows += ChatDrawerRow.Section("Chats")
                inputs.search.conversations.forEach { rows += ChatDrawerRow.Conv(it, it.id == currentId) }
            }
            if (inputs.search.hits.isNotEmpty()) {
                rows += ChatDrawerRow.Section("Messages")
                inputs.search.hits.forEach { (message, title) -> rows += ChatDrawerRow.Hit(message, title, query) }
            }
            return rows
        }
        when (inputs.mode) {
            DrawerMode.CHATS -> {
                val pinned = inputs.active.filter { it.isPinned }
                val recent = inputs.active.filterNot { it.isPinned }
                if (pinned.isNotEmpty()) {
                    rows += ChatDrawerRow.Section("Pinned")
                    pinned.forEach { rows += ChatDrawerRow.Conv(it, it.id == currentId) }
                }
                if (recent.isNotEmpty()) {
                    rows += ChatDrawerRow.Section("Recent")
                    recent.forEach { rows += ChatDrawerRow.Conv(it, it.id == currentId) }
                }
            }
            DrawerMode.ARCHIVED -> inputs.archived.forEach { rows += ChatDrawerRow.Conv(it, it.id == currentId) }
            DrawerMode.BOOKMARKS -> inputs.bookmarked.forEach {
                rows += ChatDrawerRow.Hit(it, chatVm.titleOf(it.conversationId), "")
            }
        }
        return rows
    }

    private fun setupDrawer() {
        binding.viewDrawerScrim.setOnClickListener { closeDrawer() }
        binding.btnDrawerClose.setOnClickListener { closeDrawer() }
        binding.chipDrawerNewChat.setOnClickListener {
            chatVm.newConversation()
            closeDrawer()
        }
        binding.chipDrawerChats.setOnClickListener { setDrawerMode(DrawerMode.CHATS) }
        binding.chipDrawerBookmarks.setOnClickListener { setDrawerMode(DrawerMode.BOOKMARKS) }
        binding.chipDrawerArchived.setOnClickListener { setDrawerMode(DrawerMode.ARCHIVED) }
        binding.chipDrawerHome.setOnClickListener {
            closeDrawer()
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_HOME)
        }
        binding.btnDrawerClearJarvisHistory.setOnClickListener {
            // The original "New Chat" action: clears Jarvis' own history (the Jarvis chat + imported history); other chats are kept.
            confirm(
                "Clear Jarvis history",
                "Clear Jarvis' stored voice history (the Jarvis chat and the imported history)? Your other chats are kept.",
                "Clear"
            ) { JarvisApp.instance.chatRepository.clearHistory() }
        }
        binding.etDrawerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                chatVm.searchQuery.value = q
                binding.btnDrawerSearchClear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                binding.tvDrawerTitle.text = if (q.isBlank()) drawerTitle(drawerMode.value) else "Search"
            }
        })
        binding.btnDrawerSearchClear.setOnClickListener { binding.etDrawerSearch.setText("") }
        setDrawerMode(drawerMode.value)
    }

    private fun drawerTitle(mode: DrawerMode) = when (mode) {
        DrawerMode.CHATS -> "Conversations"
        DrawerMode.ARCHIVED -> "Archived"
        DrawerMode.BOOKMARKS -> "Bookmarks"
    }

    private fun setDrawerMode(mode: DrawerMode) {
        drawerMode.value = mode
        val b = _binding ?: return
        // Dark Bento pills: selected = accent fill + on-accent text, idle = plate + hairline.
        val onAccent = com.jarvis.assistant.util.ThemeManager.onAccentColor(requireContext())
        val primary = requireContext().jarvisThemeColor(R.attr.themeTextPrimary, android.graphics.Color.WHITE)
        listOf(
            b.chipDrawerChats to DrawerMode.CHATS,
            b.chipDrawerBookmarks to DrawerMode.BOOKMARKS,
            b.chipDrawerArchived to DrawerMode.ARCHIVED
        ).forEach { (chip, chipMode) ->
            val on = mode == chipMode
            chip.setBackgroundResource(if (on) R.drawable.bg_bento_x_chip_on else R.drawable.bg_set_chip)
            chip.setTextColor(if (on) onAccent else primary)
        }
        if (chatVm.searchQuery.value.isBlank()) b.tvDrawerTitle.text = drawerTitle(mode)
    }

    private fun openDrawer(focusSearch: Boolean = false) {
        val b = _binding ?: return
        clearSelection()
        // Dark Bento: drawer keeps its XML glass panel background (no flat recolour on open).
        drawerOpen = true
        b.layoutDrawerOverlay.visibility = View.VISIBLE
        val width = b.panelDrawer.width.takeIf { it > 0 } ?: (310 * resources.displayMetrics.density).toInt()
        b.panelDrawer.translationX = -width.toFloat()
        b.viewDrawerScrim.alpha = 0f
        b.panelDrawer.animate().translationX(0f).setDuration(220).start()
        b.viewDrawerScrim.animate().alpha(1f).setDuration(220).start()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (focusSearch) {
            b.etDrawerSearch.requestFocus()
            imm.showSoftInput(b.etDrawerSearch, InputMethodManager.SHOW_IMPLICIT)
        } else {
            // The composer usually still has focus; don't leave its keyboard covering the drawer.
            b.etMessageInput.clearFocus()
            imm.hideSoftInputFromWindow(b.root.windowToken, 0)
        }
        updateBackCallback()
    }

    private fun closeDrawer(animated: Boolean = true) {
        val b = _binding ?: return
        if (!drawerOpen) return
        drawerOpen = false
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.etDrawerSearch.windowToken, 0)
        if (!animated) {
            b.layoutDrawerOverlay.visibility = View.GONE
        } else {
            val width = b.panelDrawer.width.toFloat()
            b.viewDrawerScrim.animate().alpha(0f).setDuration(180).start()
            b.panelDrawer.animate().translationX(-width).setDuration(180).withEndAction {
                if (!drawerOpen) _binding?.layoutDrawerOverlay?.visibility = View.GONE
            }.start()
        }
        updateBackCallback()
    }

    private fun updateBackCallback() {
        backCallback.isEnabled = drawerOpen || selection.value.isNotEmpty() || editingMessageId != null
    }

    // ── Conversation actions ────────────────────────────────────────────────

    private fun showCurrentConversationMenu(anchor: View) {
        val current = chatVm.conversation.value
        if (current != null) {
            showConversationMenu(anchor, current)
            return
        }
        PopupMenu(requireContext(), anchor).apply {
            menu.add("Search chats").setOnMenuItemClickListener { openDrawer(focusSearch = true); true }
            menu.add("All conversations").setOnMenuItemClickListener { openDrawer(); true }
            show()
        }
    }

    private fun showConversationMenu(anchor: View, conversation: Conversation) {
        val isCurrent = conversation.id == chatVm.conversationId.value
        PopupMenu(requireContext(), anchor).apply {
            menu.add("Rename").setOnMenuItemClickListener { promptRename(conversation); true }
            menu.add(if (conversation.isPinned) "Unpin" else "Pin")
                .setOnMenuItemClickListener { chatVm.togglePin(conversation); true }
            menu.add(if (conversation.isArchived) "Unarchive" else "Archive")
                .setOnMenuItemClickListener { chatVm.toggleArchive(conversation); true }
            menu.add("Duplicate").setOnMenuItemClickListener {
                chatVm.duplicate(conversation.id)
                closeDrawer()
                true
            }
            val export = menu.addSubMenu("Export to file")
            ExportFormat.entries.forEach { format ->
                export.add(format.label).setOnMenuItemClickListener {
                    pendingExport = conversation.id to conversation.title
                    exportLaunchers.getValue(format).launch(ConversationExporter.suggestedFileName(conversation.title, format))
                    true
                }
            }
            val share = menu.addSubMenu("Share")
            ExportFormat.entries.filter { it.isText }.forEach { format ->
                share.add(format.label).setOnMenuItemClickListener {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val text = chatVm.exportText(conversation.id, conversation.title, format)
                        ConversationSharer.shareText(requireContext(), text, "Share ${conversation.title}")
                    }
                    true
                }
            }
            menu.add("Statistics").setOnMenuItemClickListener { showStats(conversation); true }
            if (isCurrent) {
                menu.add("Search chats").setOnMenuItemClickListener { openDrawer(focusSearch = true); true }
                menu.add("All conversations").setOnMenuItemClickListener { openDrawer(); true }
            }
            menu.add("Delete").setOnMenuItemClickListener {
                confirm("Delete conversation", "Delete \"${conversation.title}\" and its messages?", "Delete") {
                    chatVm.delete(conversation.id)
                }
                true
            }
            show()
        }
    }

    private fun promptRename(conversation: Conversation) {
        val input = EditText(requireContext()).apply {
            setText(conversation.title)
            setSelection(text.length)
            maxLines = 1
        }
        val container = FrameLayout(requireContext()).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Rename conversation")
            .setView(container)
            .setPositiveButton("Rename") { _, _ -> chatVm.rename(conversation.id, input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Real numbers only: counted from the stored messages and the measured reply time. */
    private fun showStats(conversation: Conversation) {
        viewLifecycleOwner.lifecycleScope.launch {
            val msgs = chatVm.messagesOf(conversation.id)
            val fromUser = msgs.count { it.role == MessageRole.USER }
            val fromJarvis = msgs.count { it.role == MessageRole.ASSISTANT }
            val voice = msgs.count { it.origin == MessageOrigin.VOICE }
            val words = msgs.sumOf { m -> m.content.split(Regex("\\s+")).count { it.isNotBlank() } }
            val chars = msgs.sumOf { it.content.length }
            val attachments = msgs.count { it.attachmentsJson.isNotBlank() && it.attachmentsJson != "[]" }
            val bookmarks = msgs.count { it.isBookmarked }
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val latency = JarvisChatBridge.lastReplyLatencyMs.value
            val body = buildString {
                append("Messages: ").append(msgs.size).append('\n')
                append("From you: ").append(fromUser).append('\n')
                append("From Jarvis: ").append(fromJarvis).append('\n')
                append("Voice turns: ").append(voice).append('\n')
                append("Words: ").append(words).append('\n')
                append("Characters: ").append(chars).append('\n')
                append("Messages with attachments: ").append(attachments).append('\n')
                append("Bookmarked: ").append(bookmarks).append('\n')
                append("Started: ").append(df.format(Date(conversation.createdAt))).append('\n')
                append("Last activity: ").append(df.format(Date(msgs.lastOrNull()?.timestamp ?: conversation.updatedAt)))
                if (latency != null) {
                    append("\nLast Jarvis reply time (this session): ")
                    append(String.format(Locale.US, "%.1f s", latency / 1000.0))
                }
            }
            if (_binding == null) return@launch
            AlertDialog.Builder(requireContext())
                .setTitle(conversation.title)
                .setMessage(body)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun speak(text: String) {
        val clean = text
            .replace(Regex("```[\\s\\S]*?```"), " code block. ")
            .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("[*_#>`|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isEmpty()) return
        val engine = tts
        if (engine != null && ttsReady) {
            if (engine.isSpeaking) engine.stop()
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "jarvis_chat_play")
            return
        }
        pendingSpeech = clean
        if (engine != null) return
        tts = TextToSpeech(requireContext().applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            val queued = pendingSpeech
            pendingSpeech = null
            if (ttsReady && queued != null) {
                tts?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "jarvis_chat_play")
            } else if (!ttsReady) {
                activity?.runOnUiThread { toast("Text-to-speech is not available") }
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("Copied to clipboard")
    }

    // Persona / AI engine chips

    private fun refreshChipLabels() {
        val b = _binding ?: return
        val prefs = JarvisApp.instance.preferences
        b.tvChipPersona.text = personaLabel(prefs.personality)
        val engineLabel = AiModelMode.fromString(prefs.aiModelMode).displayName.substringBefore(" (")
        b.tvChipMode.text = engineLabel
        b.tvChatSubtitle.text = "Online · $engineLabel"
    }

    private fun personaLabel(personality: String): String =
        personality.removeSuffix(" Mode").trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            .ifBlank { "Jarvis" }

    private fun showPersonaPicker() {
        val prefs = JarvisApp.instance.preferences
        val current = prefs.personality
        val options = GeminiConstants.SUPPORTED_PERSONALITIES.let { list ->
            if (list.any { it.equals(current, ignoreCase = true) }) list else list + current
        }
        val checked = options.indexOfFirst { it.equals(current, ignoreCase = true) }
        AlertDialog.Builder(requireContext())
            .setTitle("Personality")
            .setSingleChoiceItems(options.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                val picked = options[which]
                if (MariaPersona.isActive(picked)) {
                    MariaPersona.activate(requireContext(), prefs)
                } else {
                    // Leaving Maria: drop her name so Jarvis doesn't keep calling itself MARIA.
                    if (MariaPersona.isActive(prefs.personality) && prefs.assistantName == MariaPersona.NAME) {
                        prefs.assistantName = "JARVIS"
                    }
                    prefs.personality = picked
                }
                refreshChipLabels()
                toast("Personality: ${personaLabel(picked)} (applies to the next Jarvis session)")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAiModePicker() {
        val app = JarvisApp.instance
        val prefs = app.preferences
        val modes = AiModelMode.entries
        val current = AiModelMode.fromString(prefs.aiModelMode)
        AlertDialog.Builder(requireContext())
            .setTitle("AI engine")
            .setSingleChoiceItems(modes.map { it.displayName }.toTypedArray(), modes.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                val mode = modes[which]
                app.modelRouter.setMode(mode)
                prefs.aiModelMode = mode.name
                prefs.voiceEngineType = mode.name
                refreshChipLabels()
                toast("AI engine: ${mode.displayName}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(title: String, message: String, action: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(text: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            consumePendingPrompt()
            refreshChipLabels()

        // Automatically scroll chat up when user types or focuses input
        binding.etMessageInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val count = messageAdapter.itemCount
                if (count > 0) {
                    binding.rvChatMessages.post {
                        binding.rvChatMessages.scrollToPosition(count - 1)
                    }
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        binding.etMessageInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val count = messageAdapter.itemCount
                if (count > 0) {
                    binding.rvChatMessages.postDelayed({
                        binding.rvChatMessages.scrollToPosition(count - 1)
                    }, 120)
                }
            }
        }
        binding.etMessageInput.setOnClickListener {
            val count = messageAdapter.itemCount
            if (count > 0) {
                binding.rvChatMessages.postDelayed({
                    binding.rvChatMessages.scrollToPosition(count - 1)
                }, 120)
            }
        }
        binding.rvChatMessages.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) {
                val count = messageAdapter.itemCount
                if (count > 0) {
                    binding.rvChatMessages.post {
                        binding.rvChatMessages.scrollToPosition(count - 1)
                    }
                }
            }
        }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            consumePendingPrompt()

        // Automatically scroll chat up when user types or focuses input
        binding.etMessageInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val count = messageAdapter.itemCount
                if (count > 0) {
                    binding.rvChatMessages.post {
                        binding.rvChatMessages.scrollToPosition(count - 1)
                    }
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        binding.etMessageInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val count = messageAdapter.itemCount
                if (count > 0) {
                    binding.rvChatMessages.postDelayed({
                        binding.rvChatMessages.scrollToPosition(count - 1)
                    }, 120)
                }
            }
        }
        binding.etMessageInput.setOnClickListener {
            val count = messageAdapter.itemCount
            if (count > 0) {
                binding.rvChatMessages.postDelayed({
                    binding.rvChatMessages.scrollToPosition(count - 1)
                }, 120)
            }
        }
        binding.rvChatMessages.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) {
                val count = messageAdapter.itemCount
                if (count > 0) {
                    binding.rvChatMessages.post {
                        binding.rvChatMessages.scrollToPosition(count - 1)
                    }
                }
            }
        }
        } else if (hidden && _binding != null) {
            closeDrawer(animated = false)
            clearSelection()
            binding.root.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        drawerOpen = false
        _binding = null
    }

    companion object {
        /** Files offered by the + button (AndroLLM attachment parsers: PDF, Office, text). */
        private val DOCUMENT_MIME_TYPES = arrayOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain", "text/markdown", "text/x-markdown", "text/csv", "text/comma-separated-values",
            "application/json", "text/html", "application/epub+zip"
        )
    }
}
