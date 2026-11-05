package com.jarvis.assistant.ui.compose.feature.chat

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.router.AiModelMode
import com.jarvis.assistant.androllm.chat.export.ConversationExporter
import com.jarvis.assistant.androllm.chat.export.ExportFormat
import com.jarvis.assistant.androllm.chat.model.Conversation
import com.jarvis.assistant.ui.compose.components.CloudAtmosphericBackground
import com.jarvis.assistant.ui.compose.feature.prompts.PendingPrompt
import com.jarvis.assistant.ui.compose.navigation.ComposeRoutes
import com.jarvis.assistant.ui.compose.theme.ledger
import kotlinx.coroutines.launch

/**
 * Entry point for the Compose chat screen (route [ComposeRoutes.CHAT]).
 * Multi-conversation chat backed by the AndroLLM chat store + Jarvis ModelRouter
 * through [ChatViewModel].
 */
@Composable
fun ChatRoute(navigate: (String) -> Unit, back: () -> Unit) {
    val viewModel: ChatViewModel = viewModel { ChatViewModel(JarvisApp.instance) }
    ChatScreen(viewModel = viewModel, navigate = navigate, back = back)
}

@Composable
private fun ChatScreen(
    viewModel: ChatViewModel,
    navigate: (String) -> Unit,
    back: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val listState = rememberLazyListState()

    // A prompt handed over by the Prompts screen goes into the composer (not auto-sent).
    var inputMessageText by rememberSaveable {
        mutableStateOf(PendingPrompt.text?.also { PendingPrompt.text = null } ?: "")
    }
    LaunchedEffect(Unit) {
        PendingPrompt.text?.let {
            inputMessageText = it
            PendingPrompt.text = null
        }
    }
    // Settings may have toggled the attachment flag while we were away.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshFlags()
        onPauseOrDispose { }
    }

    var editPromptMsgOpen by remember { mutableStateOf<ChatMessage?>(null) }
    var editPromptText by remember { mutableStateOf("") }
    var exportDialogOpen by remember { mutableStateOf(false) }
    var debugInfo by remember { mutableStateOf<ChatDebugInfo?>(null) }
    var samplerDialogOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var multiSelectIds by remember { mutableStateOf(setOf<String>()) }
    var statsExpanded by remember { mutableStateOf(false) }

    val messages = uiState.messages
    val streamingText = uiState.streamingText
    val isGenerating = uiState.isGenerating
    val lastAssistantId = messages.lastOrNull { it.role == ChatRole.ASSISTANT }?.id
    val lastMessageIsAssistant = messages.lastOrNull()?.role == ChatRole.ASSISTANT
    val currentConversation = uiState.conversation
    val turnCount = messages.count { it.role == ChatRole.USER }

    val isWide = LocalConfiguration.current.screenWidthDp >= 840

    BackHandler(enabled = uiState.isSearchOpen) { viewModel.toggleSearch(false) }
    BackHandler(enabled = !uiState.isSearchOpen && multiSelectIds.isNotEmpty()) { multiSelectIds = emptySet() }
    BackHandler(enabled = !isWide && drawerState.isOpen) { scope.launch { drawerState.close() } }

    // Selection never survives a conversation switch.
    LaunchedEffect(uiState.conversationId) { multiSelectIds = emptySet() }

    val isAtBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total == 0 || lastVisible >= total - 2
        }
    }
    val lastContentIndex = (messages.size + (if (isGenerating) 1 else 0)).coerceAtLeast(1) - 1

    var initialScrollDone by remember(uiState.conversationId) { mutableStateOf(false) }
    LaunchedEffect(messages.size, uiState.isLoaded, uiState.conversationId) {
        if (!uiState.isLoaded || messages.isEmpty() || uiState.scrollToMessageId != null) return@LaunchedEffect
        if (!initialScrollDone) {
            listState.scrollToItem(messages.size - 1)
            initialScrollDone = true
        } else if (isAtBottom || isGenerating) {
            listState.animateScrollToItem(lastContentIndex)
        }
    }
    LaunchedEffect(streamingText) {
        if (isGenerating && streamingText.isNotEmpty() && isAtBottom) {
            listState.animateScrollToItem(lastContentIndex)
        }
    }
    LaunchedEffect(inputMessageText) {
        if (inputMessageText.isNotEmpty() && messages.isNotEmpty()) {
            listState.animateScrollToItem(lastContentIndex)
        }
    }
    // Jump to a message picked from search once its conversation has loaded.
    LaunchedEffect(uiState.scrollToMessageId, messages) {
        val target = uiState.scrollToMessageId ?: return@LaunchedEffect
        val index = messages.indexOfFirst { it.id == target }
        if (index >= 0) {
            listState.scrollToItem(index)
            initialScrollDone = true
            viewModel.consumeScrollTarget()
        }
    }

    // One "save as file" launcher per export format (each needs its own MIME type).
    var pendingExport by remember { mutableStateOf<ExportFormat?>(null) }
    val saveLaunchers = ExportFormat.entries.associateWith { format ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri ->
            val chosen = pendingExport
            pendingExport = null
            if (uri != null && chosen != null) {
                scope.launch {
                    viewModel.exportToUri(uri, chosen)
                        .onSuccess { Toast.makeText(context, "Exported as ${chosen.label}", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    val drawerContent: @Composable () -> Unit = {
        ConversationDrawerContent(
            pinnedConversations = uiState.pinnedConversations,
            recentConversations = uiState.recentConversations,
            archivedConversations = uiState.archivedConversations,
            selectedConversationId = uiState.conversationId,
            providerName = uiState.providerName,
            modeLabel = uiState.mode.displayName,
            providerActive = isGenerating || uiState.stats != null,
            onSelectConversation = { id ->
                viewModel.selectConversation(id)
                closeDrawer()
            },
            onNewChat = {
                viewModel.newConversation()
                closeDrawer()
            },
            onPinToggle = { viewModel.togglePinConversation(it) },
            onRenameChat = {
                renameTarget = it
                renameText = it.title
            },
            onDuplicateChat = {
                viewModel.duplicateConversation(it.id)
                closeDrawer()
            },
            onArchiveToggle = { viewModel.toggleArchiveConversation(it) },
            onDeleteChat = { deleteTarget = it },
            onOpenSearch = {
                viewModel.toggleSearch(true)
                closeDrawer()
            },
            onOpenSettings = {
                closeDrawer()
                navigate(ComposeRoutes.SETTINGS)
            },
            onOpenModels = {
                closeDrawer()
                navigate(ComposeRoutes.MODELS)
            },
            onBack = {
                closeDrawer()
                back()
            }
        )
    }

    val chatBody: @Composable () -> Unit = {
        CloudAtmosphericBackground {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    ChatTopBar(
                        conversationTitle = uiState.conversationTitle,
                        statusLabel = when {
                            isGenerating -> "Generating Â· ${uiState.providerName}"
                            uiState.offlineFallbackActive -> "Offline fallback Â· ${uiState.providerName}"
                            else -> uiState.mode.displayName
                        },
                        conversation = currentConversation,
                        currentMode = uiState.mode,
                        onSelectMode = { viewModel.setMode(it) },
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onOpenSearch = { viewModel.toggleSearch(true) },
                        onNewChat = { viewModel.newConversation() },
                        onRename = { conv ->
                            renameTarget = conv
                            renameText = conv.title
                        },
                        onTogglePin = { viewModel.togglePinConversation(it) },
                        onDuplicate = { viewModel.duplicateConversation(it.id) },
                        onToggleArchive = { viewModel.toggleArchiveConversation(it) },
                        onDelete = { deleteTarget = it },
                        onExport = { exportDialogOpen = true },
                        onDebugInfo = { debugInfo = viewModel.debugInfo() },
                        onOpenSampler = { samplerDialogOpen = true },
                        showDrawerButton = !isWide
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (!uiState.isLoaded) {
                        ChatSkeletonLoading(modifier = Modifier.fillMaxSize())
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            uiState.errorMessage?.let { error ->
                                ChatNoticeCard(
                                    title = "Notice",
                                    body = error,
                                    actionLabel = "Models",
                                    onAction = { navigate(ComposeRoutes.MODELS) },
                                    onDismiss = { viewModel.dismissError() }
                                )
                            }
                            if (uiState.offlineFallbackActive && uiState.errorMessage == null) {
                                ChatNoticeCard(
                                    title = "Offline fallback active",
                                    body = "The preferred engine was unavailable, so Jarvis answered with the on-device model.",
                                    actionLabel = "Models",
                                    onAction = { navigate(ComposeRoutes.MODELS) }
                                )
                            }

                            if (messages.isEmpty() && !isGenerating) {
                                NewChatEmptyState(
                                    onSuggestionClick = { viewModel.sendMessage(it) },
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(messages, key = { it.id }) { msg ->
                                        val selectionActive = multiSelectIds.isNotEmpty()
                                        MessageCard(
                                            message = msg,
                                            showBadge = msg.id == lastAssistantId,
                                            badgeLabel = msg.modelId ?: uiState.providerName,
                                            selected = msg.id in multiSelectIds,
                                            selectionActive = selectionActive,
                                            canRegenerate = msg.id == lastAssistantId && lastMessageIsAssistant && !isGenerating,
                                            onRegenerate = { viewModel.regenerateLastResponse() },
                                            onEditPrompt = {
                                                editPromptMsgOpen = msg
                                                editPromptText = msg.content
                                            },
                                            onToggleBookmark = { viewModel.toggleBookmarkMessage(msg.id, msg.isBookmarked) },
                                            onDelete = { viewModel.deleteMessage(msg.id) },
                                            onClick = {
                                                multiSelectIds = if (msg.id in multiSelectIds) {
                                                    multiSelectIds - msg.id
                                                } else {
                                                    multiSelectIds + msg.id
                                                }
                                            },
                                            onLongPress = { multiSelectIds = multiSelectIds + msg.id }
                                        )
                                    }

                                    if (uiState.toolEvents.isNotEmpty()) {
                                        item(key = "tool_invocations") {
                                            ToolInvocationCards(
                                                toolEvents = uiState.toolEvents,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    if (isGenerating && streamingText.isNotEmpty()) {
                                        item(key = "streaming_bubble") {
                                            val streamingTimestamp = remember { System.currentTimeMillis() }
                                            MessageCard(
                                                message = ChatMessage(
                                                    id = "streaming",
                                                    role = ChatRole.ASSISTANT,
                                                    content = streamingText,
                                                    timestamp = streamingTimestamp
                                                ),
                                                isStreaming = true,
                                                showBadge = true,
                                                badgeLabel = uiState.providerName,
                                                messageAnimations = false,
                                                onStop = { viewModel.cancelGeneration() }
                                            )
                                        }
                                    }

                                    if (isGenerating && streamingText.isEmpty()) {
                                        item(key = "thinking_indicator") {
                                            TypingAndThinkingIndicator(
                                                statusText = "Routing to ${uiState.mode.displayName}â€¦"
                                            )
                                        }
                                    }

                                    if (!isGenerating && lastMessageIsAssistant) {
                                        item(key = "smart_replies") {
                                            SmartReplyChips(onSend = { viewModel.sendMessage(it) })
                                        }
                                    }
                                }
                            }

                            GenerationStatsPanel(
                                stats = uiState.stats,
                                turnCount = turnCount.coerceAtMost(ChatViewModel.MAX_HISTORY_TURNS),
                                turnCapacity = ChatViewModel.MAX_HISTORY_TURNS,
                                isGenerating = isGenerating,
                                modeLabel = uiState.mode.displayName,
                                providerName = uiState.providerName,
                                offlineFallback = uiState.offlineFallbackActive,
                                expanded = statsExpanded,
                                onToggleExpanded = { statsExpanded = !statsExpanded },
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            if (uiState.pendingAttachments.isNotEmpty() || uiState.attachmentsProcessing) {
                                PendingAttachmentChips(
                                    attachments = uiState.pendingAttachments,
                                    processing = uiState.attachmentsProcessing,
                                    progressLabel = uiState.attachmentProgress,
                                    onRemove = { viewModel.removeAttachment(it) }
                                )
                            }

                            ComposeInputArea(
                                text = inputMessageText,
                                onTextChanged = { inputMessageText = it },
                                onSendMessage = { text ->
                                    viewModel.sendMessage(text)
                                    inputMessageText = ""
                                },
                                onStopGeneration = { viewModel.cancelGeneration() },
                                isGenerating = isGenerating,
                                attachmentsEnabled = uiState.attachmentsEnabled,
                                hasPendingAttachments = uiState.pendingAttachments.any { it.isReady },
                                attachmentsProcessing = uiState.attachmentsProcessing,
                                onAttachFiles = { viewModel.attachFiles(it) }
                            )
                        }
                    }

                    if (multiSelectIds.isNotEmpty()) {
                        val selected = messages.filter { it.id in multiSelectIds }
                        val selectedText = selected.joinToString("\n\n---\n\n") { it.content }
                        SelectionActionBar(
                            count = selected.size,
                            onCopy = { copyToClipboard(context, selectedText, "Copied ${selected.size} messages") },
                            onShare = { ConversationSharer.shareText(context, selectedText, "Share messages") },
                            onDelete = { confirmDeleteSelected = true },
                            onClose = { multiSelectIds = emptySet() },
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }

                    AnimatedVisibility(
                        visible = uiState.isLoaded && !isAtBottom && (messages.isNotEmpty() || isGenerating),
                        enter = fadeIn() + slideInVertically(initialOffsetY = { 40 }) + scaleIn(initialScale = 0.8f),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { 40 }) + scaleOut(targetScale = 0.8f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 90.dp, end = 20.dp)
                    ) {
                        FloatingActionButton(
                            onClick = { scope.launch { listState.animateScrollToItem(lastContentIndex) } },
                            containerColor = MaterialTheme.ledger.lampAmber,
                            contentColor = MaterialTheme.ledger.inkOnLamp,
                            elevation = FloatingActionButtonDefaults.elevation(2.dp),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.size(46.dp)
                        ) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "Scroll to bottom")
                        }
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isWide) {
            PermanentNavigationDrawer(drawerContent = drawerContent) { chatBody() }
        } else {
            ModalNavigationDrawer(drawerState = drawerState, drawerContent = drawerContent) { chatBody() }
        }

        if (uiState.isSearchOpen) {
            SearchOverlay(
                query = uiState.searchQuery,
                onQueryChanged = { viewModel.updateSearchQuery(it) },
                matchingConversations = uiState.searchConversations,
                matchingHits = uiState.searchHits,
                onSelectConversation = { id ->
                    viewModel.selectConversation(id)
                    viewModel.toggleSearch(false)
                },
                onSelectHit = { viewModel.openSearchHit(it) },
                onDismiss = { viewModel.toggleSearch(false) }
            )
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Chat") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(120) },
                    singleLine = true,
                    label = { Text("Title") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        viewModel.renameConversation(target.id, renameText)
                        renameTarget = null
                    }
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Chat") },
            text = {
                Text(
                    "Delete \"${target.title}\" and its ${target.messageCount} messages? " +
                        "Jarvis' classic chat history is not affected."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteConversation(target.id)
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.ledger.emberRed) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    if (confirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text("Delete Messages") },
            text = { Text("Delete ${multiSelectIds.size} selected message(s) from this conversation?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMessages(multiSelectIds)
                    multiSelectIds = emptySet()
                    confirmDeleteSelected = false
                }) { Text("Delete", color = MaterialTheme.ledger.emberRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteSelected = false }) { Text("Cancel") } }
        )
    }

    editPromptMsgOpen?.let { target ->
        AlertDialog(
            onDismissRequest = { editPromptMsgOpen = null },
            title = { Text("Edit Prompt") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = editPromptText,
                        onValueChange = { editPromptText = it },
                        maxLines = 4,
                        label = { Text("User Prompt") }
                    )
                    Text(
                        "Messages after this prompt are removed and a new reply is generated.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.ledger.deskInk
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isGenerating && editPromptText.isNotBlank(),
                    onClick = {
                        viewModel.editUserPrompt(target.id, editPromptText)
                        editPromptMsgOpen = null
                    }
                ) { Text("Submit & Regenerate") }
            },
            dismissButton = { TextButton(onClick = { editPromptMsgOpen = null }) { Text("Cancel") } }
        )
    }

    if (exportDialogOpen) {
        val hasContent = uiState.rawMessages.isNotEmpty()
        AlertDialog(
            onDismissRequest = { exportDialogOpen = false },
            title = { Text("Export Conversation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (hasContent) "Share as text or save as a file:" else "Nothing to export yet.")
                    ExportFormat.entries.forEach { format ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${format.label} (.${format.extension})",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (format.isText) {
                                TextButton(
                                    enabled = hasContent,
                                    onClick = {
                                        exportDialogOpen = false
                                        ConversationSharer.shareConversation(context, uiState.conversationTitle, uiState.rawMessages, format)
                                    }
                                ) { Text("Share") }
                            }
                            TextButton(
                                enabled = hasContent,
                                onClick = {
                                    exportDialogOpen = false
                                    pendingExport = format
                                    try {
                                        saveLaunchers.getValue(format).launch(
                                            ConversationExporter.suggestedFileName(uiState.conversationTitle, format)
                                        )
                                    } catch (e: Exception) {
                                        pendingExport = null
                                        Toast.makeText(context, "No file picker available: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) { Text("Save") }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { exportDialogOpen = false }) { Text("Close") } }
        )
    }

    debugInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { debugInfo = null },
            title = { Text("Engine Debug") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DebugRow("Mode", info.mode)
                    DebugRow("Last provider", info.provider)
                    DebugRow("Offline fallback", info.offlineFallback.toString())
                    DebugRow("Network", if (info.networkAvailable) "available" else "offline")
                    DebugRow("Local engine", info.localEngineState)
                    DebugRow("Local host endpoint", info.localEndpoint)
                    DebugRow("Local host model", info.localHostModel)
                    DebugRow("OpenAI key", if (info.openAiKeyConfigured) "configured" else "missing")
                    DebugRow("Gemini key", if (info.geminiKeyConfigured) "configured" else "missing")
                    DebugRow("Conversation", info.conversationId)
                    DebugRow("Messages in chat", info.conversationMessages.toString())
                    DebugRow("Jarvis history turns", info.storedTurns.toString())
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { debugInfo = null }) { Text("Close") } }
        )
    }

    if (samplerDialogOpen) {
        AlertDialog(
            onDismissRequest = { samplerDialogOpen = false },
            title = { Text("Sampler Settings") },
            text = {
                Text(
                    "Not available in Jarvis. Temperature, top-p and token limits are managed by each AI provider " +
                        "(ChatGPT, Gemini, Local Host, on-device). Choose the engine with the cloud button or in Settings."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    samplerDialogOpen = false
                    navigate(ComposeRoutes.SETTINGS)
                }) { Text("Open Settings") }
            },
            dismissButton = { TextButton(onClick = { samplerDialogOpen = false }) { Text("Close") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmartReplyChips(onSend: (String) -> Unit) {
    val suggestions = remember {
        listOf(
            "Explain this in more detail",
            "Give me a concrete example",
            "Summarise the key points",
            "What are the trade-offs?"
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "FOLLOW UP",
            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.forEach { suggestion ->
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.ledger.deskWalnutRaised,
                    border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline),
                    modifier = Modifier
                        .shadow(1.dp, RoundedCornerShape(999.dp))
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onSend(suggestion) }
                ) {
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.ledger.deskInk,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionActionBar(
    count: Int,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.ledger.deskWalnutRaised,
        border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline),
        shadowElevation = 4.dp,
        modifier = modifier.padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close selection", tint = MaterialTheme.ledger.deskInk)
            }
            Text(
                text = if (count == 1) "1 selected" else "$count selected",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.ledger.deskInk
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            )
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy selected", tint = MaterialTheme.ledger.deskInk)
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = "Share selected", tint = MaterialTheme.ledger.deskInk)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete selected", tint = MaterialTheme.ledger.emberRed)
            }
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.ledger.deskInk,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.ledger.deskPaper,
            modifier = Modifier.weight(0.6f)
        )
    }
}

private val MODE_MENU_ORDER = listOf(
    AiModelMode.AUTO_HYBRID,
    AiModelMode.CHATGPT,
    AiModelMode.GEMINI_LIVE,
    AiModelMode.LOCAL_HOST,
    AiModelMode.LOCAL_OFFLINE,
    AiModelMode.HERMES_AGENT
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    conversationTitle: String,
    statusLabel: String,
    conversation: Conversation?,
    currentMode: AiModelMode,
    onSelectMode: (AiModelMode) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onNewChat: () -> Unit,
    onRename: (Conversation) -> Unit,
    onTogglePin: (Conversation) -> Unit,
    onDuplicate: (Conversation) -> Unit,
    onToggleArchive: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
    onExport: () -> Unit,
    onDebugInfo: () -> Unit,
    onOpenSampler: () -> Unit,
    showDrawerButton: Boolean
) {
    val context: Context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var modeMenuExpanded by remember { mutableStateOf(false) }
    val onDevice = currentMode == AiModelMode.LOCAL_OFFLINE || currentMode == AiModelMode.LOCAL_HOST

    /** Conversation actions need a saved conversation (a blank "New Chat" has none yet). */
    fun withConversation(action: (Conversation) -> Unit) {
        menuExpanded = false
        if (conversation != null) action(conversation)
        else Toast.makeText(context, "Send a message first to save this chat", Toast.LENGTH_SHORT).show()
    }

    TopAppBar(
        title = {
            Column {
                Text(
                    text = conversationTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.ledger.deskPaper)
                )
                Text(
                    text = statusLabel.uppercase(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.lampDeep)
                )
            }
        },
        navigationIcon = {
            if (showDrawerButton) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Filled.Menu, contentDescription = "Open Drawer", tint = MaterialTheme.ledger.deskPaper)
                }
            }
        },
        actions = {
            Box {
                IconButton(onClick = { modeMenuExpanded = true }) {
                    Icon(
                        imageVector = if (onDevice) Icons.Filled.Cloud else Icons.Filled.CloudDone,
                        contentDescription = "AI engine: ${currentMode.displayName}",
                        tint = if (onDevice) MaterialTheme.ledger.deskPaper else MaterialTheme.ledger.lampDeep
                    )
                }
                DropdownMenu(expanded = modeMenuExpanded, onDismissRequest = { modeMenuExpanded = false }) {
                    MODE_MENU_ORDER.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.displayName) },
                            onClick = {
                                modeMenuExpanded = false
                                onSelectMode(mode)
                            },
                            trailingIcon = {
                                if (mode == currentMode) {
                                    Icon(Icons.Filled.Check, contentDescription = "Active", tint = MaterialTheme.ledger.lampDeep)
                                }
                            }
                        )
                    }
                }
            }
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Search", tint = MaterialTheme.ledger.deskPaper)
            }
            IconButton(onClick = onOpenSampler) {
                Icon(Icons.Filled.Tune, contentDescription = "Sampler settings", tint = MaterialTheme.ledger.deskPaper)
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = MaterialTheme.ledger.deskPaper)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("New Chat") },
                        onClick = { menuExpanded = false; onNewChat() },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename Chat") },
                        onClick = { withConversation(onRename) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (conversation?.isPinned == true) "Unpin Chat" else "Pin Chat") },
                        onClick = { withConversation(onTogglePin) },
                        leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate Chat") },
                        onClick = { withConversation(onDuplicate) },
                        leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (conversation?.isArchived == true) "Unarchive Chat" else "Archive Chat") },
                        onClick = { withConversation(onToggleArchive) },
                        leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Export") },
                        onClick = { menuExpanded = false; onExport() },
                        leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Debug Info") },
                        onClick = { menuExpanded = false; onDebugInfo() },
                        leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Chat", color = MaterialTheme.ledger.emberRed) },
                        onClick = { withConversation(onDelete) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.ledger.emberRed) }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
    )
}
