package com.jarvis.assistant.ui.compose.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.androllm.attachments.model.AttachmentType
import com.jarvis.assistant.androllm.attachments.model.ChatAttachment
import com.jarvis.assistant.androllm.chat.model.Conversation
import com.jarvis.assistant.androllm.chat.model.MessageOrigin
import com.jarvis.assistant.ui.compose.components.CloudBugdroidLogo
import com.jarvis.assistant.ui.compose.components.CloudCapsuleButton
import com.jarvis.assistant.ui.compose.components.LampDot
import com.jarvis.assistant.ui.compose.theme.DeskPillShape
import com.jarvis.assistant.ui.compose.theme.DeskSlipShape
import com.jarvis.assistant.ui.compose.theme.ledger
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val NOT_AVAILABLE = "Not available in Jarvis"

internal fun copyToClipboard(context: Context, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Message", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

internal fun showNotAvailable(context: Context, feature: String) {
    Toast.makeText(context, "$feature: not available in Jarvis", Toast.LENGTH_SHORT).show()
}

// ─────────────────────────────────────────────────────────────────────────────
// Message card
// ─────────────────────────────────────────────────────────────────────────────

/**
 * An adaptive message card — ported from AndroLLM. Spring entrance, tail on
 * the speaker's edge, collapsible long replies and a per-message action bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageCard(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    showBadge: Boolean = false,
    badgeLabel: String = "",
    messageAnimations: Boolean = true,
    selected: Boolean = false,
    selectionActive: Boolean = false,
    canRegenerate: Boolean = false,
    onRegenerate: () -> Unit = {},
    onEditPrompt: () -> Unit = {},
    onToggleBookmark: () -> Unit = {},
    onDelete: () -> Unit = {},
    onStop: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    val context = LocalContext.current
    val isUser = message.role == ChatRole.USER
    val isAssistant = message.role == ChatRole.ASSISTANT

    val formattedTime = remember(message.timestamp) {
        if (message.timestamp > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)) else ""
    }

    val entrance = remember(message.id) { Animatable(0f) }
    LaunchedEffect(message.id, isStreaming) {
        if (messageAnimations && !isStreaming) {
            entrance.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        } else {
            entrance.snapTo(1f)
        }
    }
    val liftPx = with(LocalDensity.current) { 22.dp.toPx() }

    var expanded by remember(message.id) { mutableStateOf(false) }
    val isCollapsible = isAssistant && !isStreaming && message.content.length > 900
    val displayContent = if (isCollapsible && !expanded) truncateAtWord(message.content, 640) else message.content

    val cardShape = if (isUser) {
        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 8.dp)
    } else {
        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 8.dp)
    }
    val selectionTint = if (selected) MaterialTheme.ledger.lampAmber.copy(alpha = 0.12f) else Color.Transparent
    val bgColor = if (isUser) MaterialTheme.ledger.deskWalnutRaised else MaterialTheme.ledger.deskWalnut

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            if (!isUser) {
                Box(modifier = Modifier.padding(top = 10.dp)) { LampDot(size = 7.dp, lit = true) }
                Spacer(modifier = Modifier.width(10.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .padding(start = 4.dp, bottom = 5.dp)
                        .then(if (isUser) Modifier.align(Alignment.End) else Modifier)
                ) {
                    Text(
                        text = if (isUser) "YOU" else "JARVIS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = if (isUser) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskInk
                        )
                    )
                    if (formattedTime.isNotEmpty()) {
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                        )
                    }
                    if (isAssistant && showBadge && badgeLabel.isNotBlank()) {
                        BackendBadge(label = badgeLabel)
                    }
                    if (message.origin == MessageOrigin.VOICE) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Voice message",
                            tint = MaterialTheme.ledger.deskInk,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    if (message.isBookmarked) {
                        Icon(
                            Icons.Filled.Bookmark,
                            contentDescription = "Bookmarked",
                            tint = MaterialTheme.ledger.lampAmber,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                Surface(
                    shape = cardShape,
                    color = bgColor,
                    border = BorderStroke(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) MaterialTheme.ledger.lampAmber else MaterialTheme.ledger.deskHairline
                    ),
                    shadowElevation = if (selected) 4.dp else 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = entrance.value
                            val s = 0.97f + 0.03f * entrance.value
                            scaleX = s
                            scaleY = s
                            translationY = (1f - entrance.value) * liftPx
                        }
                        .clip(cardShape)
                        .combinedClickable(
                            onClick = { if (selectionActive) onClick() },
                            onLongClick = { if (selectionActive) onClick() else onLongPress() }
                        )
                ) {
                    Box(modifier = Modifier.background(selectionTint)) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (!isUser) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        MarkdownRenderer(
                                            markdownText = displayContent,
                                            textColor = MaterialTheme.ledger.deskPaper
                                        )
                                    }
                                    if (isStreaming) BlinkingCursor()
                                }
                            } else if (message.content.isNotBlank()) {
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        lineHeight = 22.sp,
                                        color = MaterialTheme.ledger.deskPaper
                                    )
                                )
                            }

                            if (message.attachments.isNotEmpty() && !isStreaming) {
                                Spacer(modifier = Modifier.height(6.dp))
                                AttachmentCards(
                                    attachments = message.attachments,
                                    onOpen = { openAttachment(context, it) }
                                )
                            }

                            if (isCollapsible) {
                                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = 2.dp)) {
                                    Text(
                                        text = if (expanded) "Show less" else "Read more",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            color = MaterialTheme.ledger.lampDeep,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                if (!selectionActive) {
                    Row(
                        modifier = Modifier
                            .padding(top = 5.dp, start = 4.dp)
                            .then(if (isUser) Modifier.align(Alignment.End) else Modifier),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (isStreaming) {
                            CardIconButton(Icons.Filled.Stop, "Stop", MaterialTheme.ledger.lampDeep) { onStop() }
                        } else {
                            CardIconButton(Icons.Filled.ContentCopy, "Copy", MaterialTheme.ledger.deskInk) {
                                copyToClipboard(context, message.content, "Copied text")
                            }
                            if (isUser) {
                                CardIconButton(Icons.Filled.Edit, "Edit & resend", MaterialTheme.ledger.deskInk) { onEditPrompt() }
                            } else {
                                if (canRegenerate) {
                                    CardIconButton(Icons.Filled.Refresh, "Regenerate", MaterialTheme.ledger.deskInk) { onRegenerate() }
                                }
                                CardIconButton(
                                    if (message.isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                    if (message.isBookmarked) "Remove bookmark" else "Bookmark",
                                    if (message.isBookmarked) MaterialTheme.ledger.lampAmber else MaterialTheme.ledger.deskInk
                                ) { onToggleBookmark() }
                                CardIconButton(Icons.Filled.Share, "Share", MaterialTheme.ledger.deskInk) {
                                    ConversationSharer.shareSingleMessage(context, message.content)
                                }
                            }
                            CardIconButton(Icons.Filled.Delete, "Delete message", MaterialTheme.ledger.deskInk) { onDelete() }
                        }
                    }
                }
            }
        }

        if (!isStreaming) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, MaterialTheme.ledger.deskHairlineSoft, Color.Transparent)
                        )
                    )
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun BackendBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.ledger.deskWalnutRaised,
        border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline)
    ) {
        Text(
            text = label.uppercase(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.ledger.deskInk
            )
        )
    }
}

@Composable
private fun BlinkingCursor(modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(
            targetValue = 0.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 520, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
    }
    Box(
        modifier = modifier
            .padding(start = 3.dp, bottom = 3.dp)
            .size(width = 3.dp, height = 16.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .background(MaterialTheme.ledger.lampDeep, RoundedCornerShape(1.5.dp))
    )
}

@Composable
private fun CardIconButton(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(imageVector = icon, contentDescription = description, tint = tint, modifier = Modifier.size(15.dp))
    }
}

private fun truncateAtWord(text: String, max: Int): String {
    if (text.length <= max) return text
    val cut = text.take(max)
    val end = maxOf(cut.lastIndexOf(' '), cut.lastIndexOf('\n')).takeIf { it > max / 2 } ?: max
    val truncated = cut.take(end).trimEnd()
    val codeFences = Regex("```").findAll(truncated).count()
    val mathFences = Regex("\\$\\$").findAll(truncated).count()
    return buildString {
        append(truncated)
        if (codeFences % 2 == 1) append("\n```")
        if (mathFences % 2 == 1) append("\n$$")
        append(" …")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Composer
// ─────────────────────────────────────────────────────────────────────────────

/** MIME types offered by the "Files" picker (ported from AndroLLM ComposeInputArea). */
private val ATTACHMENT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/plain", "text/markdown", "text/x-markdown", "text/csv", "text/comma-separated-values",
    "application/json", "text/html", "application/epub+zip", "image/*"
)

/**
 * The writing slip — the composer. The paperclip opens AndroLLM's attach menu
 * (Files / Images / Gallery) when chat attachments are enabled. AndroLLM's
 * "Camera" entry is omitted: it needs a FileProvider, which Jarvis' manifest
 * does not declare. Voice conversation lives on the dedicated Voice screen.
 */
@Composable
internal fun ComposeInputArea(
    text: String,
    onTextChanged: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit,
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxCharacterLimit: Int = 4096,
    attachmentsEnabled: Boolean = false,
    hasPendingAttachments: Boolean = false,
    attachmentsProcessing: Boolean = false,
    onAttachFiles: (List<Uri>) -> Unit = {}
) {
    val context = LocalContext.current
    var attachMenuOpen by remember { mutableStateOf(false) }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) onAttachFiles(uris) }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) onAttachFiles(uris) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris -> if (uris.isNotEmpty()) onAttachFiles(uris) }

    val canSend = !attachmentsProcessing && (text.isNotBlank() || hasPendingAttachments)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = DeskSlipShape,
        color = MaterialTheme.ledger.deskWalnutRaised,
        border = BorderStroke(
            1.dp,
            if (text.isNotEmpty()) MaterialTheme.ledger.lampAmber.copy(alpha = 0.45f) else MaterialTheme.ledger.deskHairline
        ),
        shadowElevation = 10.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box {
                    IconButton(
                        onClick = {
                            if (attachmentsEnabled) {
                                attachMenuOpen = true
                            } else {
                                Toast.makeText(
                                    context,
                                    "Chat attachments are turned off (Settings → Chat)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        enabled = !isGenerating && !attachmentsProcessing,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = if (attachmentsEnabled) "Attach file" else "Attach file (disabled in Settings)",
                            tint = if (attachmentsEnabled) MaterialTheme.ledger.deskInk
                            else MaterialTheme.ledger.deskInkFaint.copy(alpha = 0.45f)
                        )
                    }
                    DropdownMenu(expanded = attachMenuOpen, onDismissRequest = { attachMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Files") },
                            onClick = {
                                attachMenuOpen = false
                                fileLauncher.launch(ATTACHMENT_MIME_TYPES)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Images") },
                            onClick = {
                                attachMenuOpen = false
                                imageLauncher.launch("image/*")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Gallery") },
                            onClick = {
                                attachMenuOpen = false
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= maxCharacterLimit) onTextChanged(it) },
                    placeholder = {
                        Text(
                            text = if (hasPendingAttachments) "Ask about the attached files…" else "Message Jarvis…",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.ledger.deskInk)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp),
                    shape = DeskPillShape,
                    maxLines = 5,
                    enabled = enabled && !isGenerating,
                    trailingIcon = {
                        if (text.isNotEmpty() && !isGenerating) {
                            IconButton(onClick = { onTextChanged("") }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = "Clear input",
                                    tint = MaterialTheme.ledger.deskInk
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.ledger.deskPaper,
                        unfocusedTextColor = MaterialTheme.ledger.deskPaper,
                        cursorColor = MaterialTheme.ledger.lampAmber
                    )
                )

                if (text.isEmpty() && !isGenerating && !hasPendingAttachments) {
                    IconButton(
                        onClick = { showNotAvailable(context, "Voice input in text chat") },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = "Voice input ($NOT_AVAILABLE)",
                            tint = MaterialTheme.ledger.deskInkFaint.copy(alpha = 0.45f)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isGenerating -> MaterialTheme.ledger.emberRed
                                canSend -> MaterialTheme.ledger.lampAmber
                                else -> MaterialTheme.ledger.deskWalnutRaised
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = {
                            if (isGenerating) onStopGeneration() else if (canSend) onSendMessage(text)
                        },
                        enabled = isGenerating || canSend
                    ) {
                        AnimatedContent(
                            targetState = isGenerating,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "SendStopToggle"
                        ) { generating ->
                            if (generating) {
                                Icon(
                                    imageVector = Icons.Filled.Stop,
                                    contentDescription = "Stop generating",
                                    tint = MaterialTheme.ledger.inkOnLamp,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send message",
                                    tint = if (canSend) MaterialTheme.ledger.inkOnLamp else MaterialTheme.ledger.deskInk,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (text.length > maxCharacterLimit * 0.7) {
                Text(
                    text = "${text.length} / $maxCharacterLimit",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (text.length >= maxCharacterLimit) MaterialTheme.ledger.emberRed else MaterialTheme.ledger.deskInk,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 16.dp, top = 2.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Attachments
// ─────────────────────────────────────────────────────────────────────────────

/** Pending attachment chips above the composer (ported from AndroLLM ChatScreen). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PendingAttachmentChips(
    attachments: List<ChatAttachment>,
    processing: Boolean,
    progressLabel: String,
    onRemove: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        attachments.forEach { attachment ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.ledger.deskWalnutRaised,
                border = BorderStroke(
                    1.dp,
                    if (attachment.isFailed) MaterialTheme.ledger.emberRed.copy(alpha = 0.6f) else MaterialTheme.ledger.deskHairline
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Icon(
                        imageVector = if (attachment.type == AttachmentType.IMAGE) Icons.Filled.Image else Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.ledger.deskInk,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            text = attachment.name,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.ledger.deskPaper,
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp)
                        )
                        Text(
                            text = when {
                                attachment.isFailed -> "Failed · ${attachment.error ?: "unknown error"}"
                                attachment.isReady -> buildString {
                                    append(ChatAttachment.formatSize(attachment.sizeBytes))
                                    if (attachment.fromOcr) append(" · OCR")
                                }
                                else -> "Processing…"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (attachment.isFailed) MaterialTheme.ledger.emberRed else MaterialTheme.ledger.deskInk
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp)
                        )
                    }
                    IconButton(onClick = { onRemove(attachment.id) }, modifier = Modifier.size(26.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove ${attachment.name}",
                            tint = MaterialTheme.ledger.deskInk,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
        if (processing) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.ledger.deskWalnutRaised,
                border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline)
            ) {
                Text(
                    text = if (progressLabel.isBlank()) "Processing…" else "Processing $progressLabel",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 260.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** Attachment cards rendered under a message that carried files (ported from AndroLLM MessageCard). */
@Composable
private fun AttachmentCards(
    attachments: List<ChatAttachment>,
    onOpen: (ChatAttachment) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        attachments.forEach { attachment ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.ledger.deskWalnutRaised,
                border = BorderStroke(
                    0.5.dp,
                    if (attachment.isFailed) MaterialTheme.ledger.emberRed.copy(alpha = 0.5f) else MaterialTheme.ledger.deskHairline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = attachment.isReady && attachment.sourceUri.isNotBlank()) { onOpen(attachment) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (attachment.type == AttachmentType.IMAGE) Icons.Filled.Image else Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.ledger.deskInk,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = attachment.name,
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = MaterialTheme.ledger.deskPaper,
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when {
                                attachment.isFailed -> "Failed to process"
                                else -> buildString {
                                    append(ChatAttachment.formatSize(attachment.sizeBytes))
                                    if (attachment.pageCount > 1) append(" · ").append(attachment.pageCount).append(" pages")
                                    if (attachment.fromOcr) append(" · OCR")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (attachment.isFailed) MaterialTheme.ledger.emberRed else MaterialTheme.ledger.deskInk
                            )
                        )
                    }
                    if (attachment.isReady) {
                        Text(
                            text = attachment.type.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.ledger.lampDeep,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Thinking indicator
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun TypingAndThinkingIndicator(
    modifier: Modifier = Modifier,
    statusText: String = "Thinking…"
) {
    val statuses = remember(statusText) {
        listOf(statusText, "Searching memory…", "Preparing answer…").distinct()
    }
    var index by remember(statuses) { mutableIntStateOf(0) }
    LaunchedEffect(statuses) {
        while (true) {
            delay(1800)
            index = (index + 1) % statuses.size
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LampDot(size = 9.dp, lit = true)
        Spacer(modifier = Modifier.width(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AnimatedContent(
                targetState = statuses[index.coerceIn(0, statuses.size - 1)],
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                label = "thinking status"
            ) { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                )
            }
            LampPulsingDots()
        }
    }
}

@Composable
private fun LampPulsingDots() {
    val dots = listOf(
        remember { Animatable(0.35f) },
        remember { Animatable(0.35f) },
        remember { Animatable(0.35f) }
    )
    dots.forEachIndexed { index, animatable ->
        LaunchedEffect(animatable) {
            delay(index * 160L)
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 650, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        dots.forEach { animatable ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .scale(animatable.value)
                    .clip(CircleShape)
                    .background(MaterialTheme.ledger.lampGlow.copy(alpha = 0.5f + animatable.value * 0.5f))
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state / banners
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewChatEmptyState(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val suggestions = listOf(
        "Explain quantum computing simply",
        "Write a Kotlin function for binary search",
        "Summarize the history of AI",
        "Create a weekly workout routine"
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.ledger.deskWalnutRaised)
                .border(1.dp, MaterialTheme.ledger.deskHairline, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            LampDot(size = 12.dp, lit = true)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Ready when you are.",
            style = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.ledger.deskPaper),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Jarvis routes your message to the active AI engine. Write below, or take one of these lines.",
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.ledger.deskInk),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            suggestions.forEach { suggestion ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.ledger.deskWalnutRaised)
                        .border(1.dp, MaterialTheme.ledger.deskHairline, RoundedCornerShape(18.dp))
                        .clickable { onSuggestionClick(suggestion) }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = suggestion.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                }
            }
        }
    }
}

/** A walnut slip on the ember edge — used for engine notices and errors. */
@Composable
internal fun ChatNoticeCard(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(DeskSlipShape)
            .background(MaterialTheme.ledger.deskWalnutRaised)
            .border(1.dp, MaterialTheme.ledger.emberRed.copy(alpha = 0.5f), DeskSlipShape)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LampDot(size = 10.dp, lit = false)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.ledger.deskPaper))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (actionLabel != null) {
                Spacer(modifier = Modifier.width(8.dp))
                CloudCapsuleButton(text = actionLabel, onClick = onAction)
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Clear, contentDescription = "Dismiss", tint = MaterialTheme.ledger.deskInk)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Generation stats panel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Compact pill + expandable telemetry panel. All values are measured by the
 * chat ViewModel (wall-clock latency, streamed chunks/characters) or read from
 * Jarvis' ModelRouter — token counts are not exposed by Jarvis' providers.
 */
@Composable
internal fun GenerationStatsPanel(
    stats: ChatGenerationStats?,
    turnCount: Int,
    turnCapacity: Int,
    isGenerating: Boolean,
    modeLabel: String,
    providerName: String,
    offlineFallback: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.ledger.deskWalnutRaised,
            border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline),
            shadowElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onToggleExpanded)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "History $turnCount / $turnCapacity turns",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.ledger.deskInk
                        )
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (stats != null && (isGenerating || stats.charCount > 0)) {
                            Text(
                                text = String.format(Locale.US, "%.0f chars/s", stats.charsPerSecond),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isGenerating) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isGenerating) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskInk
                                )
                            )
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (expanded) "Collapse stats" else "Expand stats",
                            tint = MaterialTheme.ledger.deskInk,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                if (turnCapacity > 0) {
                    val fraction = (turnCount.toFloat() / turnCapacity).coerceIn(0f, 1f)
                    Spacer(modifier = Modifier.height(7.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(MaterialTheme.ledger.deskHairline, RoundedCornerShape(999.dp)),
                        color = if (fraction > 0.85f) MaterialTheme.ledger.emberRed else MaterialTheme.ledger.lampAmber,
                        trackColor = Color.Transparent
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.ledger.deskWalnutRaised,
                border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (isGenerating) {
                        TelemetryRow("Generating", "${stats?.chunkCount ?: 0} chunks", MaterialTheme.ledger.lampDeep)
                    }
                    TelemetryRow("Mode", modeLabel)
                    TelemetryRow("Provider", stats?.providerName?.ifBlank { null } ?: providerName, MaterialTheme.ledger.lampDeep)
                    TelemetryRow("Offline fallback", if (offlineFallback) "Active" else "No")
                    TelemetryRow("Latency", formatMillis(stats?.totalMs))
                    TelemetryRow("First chunk", formatMillis(stats?.firstTokenMs))
                    TelemetryRow("Streamed", "${stats?.chunkCount ?: 0} chunks · ${stats?.charCount ?: 0} chars")
                    TelemetryRow("Stop reason", stats?.stopReason?.ifBlank { null } ?: "—")
                    TelemetryRow("Tokens", "Not reported by Jarvis providers")
                }
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String, accent: Color = MaterialTheme.ledger.deskInk) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.ledger.deskInk))
        Text(
            text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, color = accent),
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

private fun formatMillis(ms: Long?): String {
    if (ms == null || ms <= 0) return "—"
    return if (ms < 1000) "$ms ms" else String.format(Locale.US, "%.2f s", ms / 1000f)
}

// ─────────────────────────────────────────────────────────────────────────────
// Skeleton
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun ChatSkeletonLoading(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "chatSkeleton")
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "skeletonShimmer"
    )
    val ledger = MaterialTheme.ledger
    val brush = Brush.horizontalGradient(
        colors = listOf(ledger.deskWalnutDeep, ledger.deskWalnutRaised, ledger.deskWalnutDeep),
        startX = -300f + shimmer * 900f,
        endX = 300f + shimmer * 900f
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.92f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBlock(0.18f, 12.dp, RoundedCornerShape(6.dp), brush)
            SkeletonBlock(1f, 14.dp, RoundedCornerShape(8.dp), brush)
            SkeletonBlock(0.85f, 14.dp, RoundedCornerShape(8.dp), brush)
            SkeletonBlock(0.55f, 14.dp, RoundedCornerShape(8.dp), brush)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .align(Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SkeletonBlock(0.7f, 14.dp, RoundedCornerShape(18.dp), brush)
            SkeletonBlock(0.42f, 14.dp, RoundedCornerShape(18.dp), brush)
        }
    }
}

@Composable
private fun SkeletonBlock(widthFraction: Float, height: Dp, shape: RoundedCornerShape, brush: Brush) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(shape)
            .background(brush)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Search overlay
// ─────────────────────────────────────────────────────────────────────────────

/** Searches conversation titles AND message content across every conversation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchOverlay(
    query: String,
    onQueryChanged: (String) -> Unit,
    matchingConversations: List<Conversation>,
    matchingHits: List<ChatSearchHit>,
    onSelectConversation: (String) -> Unit,
    onSelectHit: (ChatSearchHit) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        containerColor = MaterialTheme.ledger.deskNight,
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChanged,
                        placeholder = { Text("Search chats and messages...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChanged("") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear")
                                }
                            }
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.ledger.deskNight)
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when {
                query.isBlank() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Type to search conversation titles and messages",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.ledger.deskInk
                    )
                }
                matchingConversations.isEmpty() && matchingHits.isEmpty() ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No matching results found for '$query'",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.ledger.deskInk
                        )
                    }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    if (matchingConversations.isNotEmpty()) {
                        item {
                            Text(
                                text = "CONVERSATIONS (${matchingConversations.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(matchingConversations, key = { "conv_${it.id}" }) { conv ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectConversation(conv.id) },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = highlightText(conv.title, query),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${conv.messageCount} messages" + if (conv.isArchived) " · archived" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }
                    if (matchingHits.isNotEmpty()) {
                        item {
                            Text(
                                text = "MESSAGES (${matchingHits.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        items(matchingHits, key = { "msg_${it.message.id}" }) { hit ->
                            val msg = hit.message
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectHit(hit) },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = (if (msg.role == ChatRole.USER) "YOU" else "JARVIS") + " · " + hit.conversationTitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = highlightText(snippetAround(msg.content, query), query),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Trims long messages so the first match is visible inside the 3-line preview. */
private fun snippetAround(text: String, query: String): String {
    val index = text.indexOf(query, ignoreCase = true)
    if (index < 80) return text
    return "…" + text.substring((index - 60).coerceAtLeast(0))
}

private fun highlightText(text: String, query: String) = buildAnnotatedString {
    append(text)
    if (query.isNotBlank()) {
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        var startIndex = lowerText.indexOf(lowerQuery)
        while (startIndex >= 0) {
            val endIndex = (startIndex + lowerQuery.length).coerceAtMost(text.length)
            addStyle(
                SpanStyle(background = Color(0xFFFFFFFF), color = Color(0xFF000000), fontWeight = FontWeight.Bold),
                startIndex,
                endIndex
            )
            startIndex = lowerText.indexOf(lowerQuery, endIndex)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Conversation drawer
// ─────────────────────────────────────────────────────────────────────────────

/** Sidebar drawer for conversation management (ported from AndroLLM ConversationDrawer). */
@Composable
internal fun ConversationDrawerContent(
    pinnedConversations: List<Conversation>,
    recentConversations: List<Conversation>,
    archivedConversations: List<Conversation>,
    selectedConversationId: String,
    providerName: String,
    modeLabel: String,
    providerActive: Boolean,
    onSelectConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onPinToggle: (Conversation) -> Unit,
    onRenameChat: (Conversation) -> Unit,
    onDuplicateChat: (Conversation) -> Unit,
    onArchiveToggle: (Conversation) -> Unit,
    onDeleteChat: (Conversation) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var archivedExpanded by remember { mutableStateOf(false) }

    ModalDrawerSheet(
        modifier = modifier.width(320.dp),
        drawerContainerColor = MaterialTheme.ledger.deskWalnut
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CloudBugdroidLogo(size = 28.dp, showMoon = false)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Jarvis",
                            style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.ledger.deskPaper)
                        )
                        Text(
                            text = "AI ASSISTANT · CHAT",
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                        )
                    }
                }
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "Search chats", tint = MaterialTheme.ledger.deskPaper)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            CloudCapsuleButton(
                text = "New Chat",
                onClick = onNewChat,
                icon = Icons.Filled.Add,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (pinnedConversations.isNotEmpty()) {
                    item {
                        DrawerSectionLabel("PINNED CHATS", MaterialTheme.ledger.lampDeep)
                    }
                    items(pinnedConversations, key = { "pinned_${it.id}" }) { conv ->
                        ConversationDrawerItem(
                            conversation = conv,
                            isSelected = conv.id == selectedConversationId,
                            onSelect = { onSelectConversation(conv.id) },
                            onPinToggle = { onPinToggle(conv) },
                            onRename = { onRenameChat(conv) },
                            onDuplicate = { onDuplicateChat(conv) },
                            onArchiveToggle = { onArchiveToggle(conv) },
                            onDelete = { onDeleteChat(conv) }
                        )
                    }
                    item {
                        HorizontalDivider(color = MaterialTheme.ledger.deskHairline, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }

                item { DrawerSectionLabel("RECENT CHATS", MaterialTheme.ledger.deskInk) }

                if (recentConversations.isEmpty()) {
                    item {
                        Text(
                            text = "No recent chats",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.ledger.deskInk,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    items(recentConversations, key = { "recent_${it.id}" }) { conv ->
                        ConversationDrawerItem(
                            conversation = conv,
                            isSelected = conv.id == selectedConversationId,
                            onSelect = { onSelectConversation(conv.id) },
                            onPinToggle = { onPinToggle(conv) },
                            onRename = { onRenameChat(conv) },
                            onDuplicate = { onDuplicateChat(conv) },
                            onArchiveToggle = { onArchiveToggle(conv) },
                            onDelete = { onDeleteChat(conv) }
                        )
                    }
                }

                if (archivedConversations.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { archivedExpanded = !archivedExpanded }
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DrawerSectionLabel("ARCHIVED (${archivedConversations.size})", MaterialTheme.ledger.deskInk)
                            Spacer(modifier = Modifier.weight(1f))
                            Icon(
                                if (archivedExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (archivedExpanded) "Collapse archived" else "Expand archived",
                                tint = MaterialTheme.ledger.deskInk,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (archivedExpanded) {
                        items(archivedConversations, key = { "archived_${it.id}" }) { conv ->
                            ConversationDrawerItem(
                                conversation = conv,
                                isSelected = conv.id == selectedConversationId,
                                onSelect = { onSelectConversation(conv.id) },
                                onPinToggle = { onPinToggle(conv) },
                                onRename = { onRenameChat(conv) },
                                onDuplicate = { onDuplicateChat(conv) },
                                onArchiveToggle = { onArchiveToggle(conv) },
                                onDelete = { onDeleteChat(conv) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
            Spacer(modifier = Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.ledger.deskWalnutRaised,
                    border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenModels() }
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        LampDot(size = 8.dp, lit = providerActive)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = providerName,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = MaterialTheme.ledger.deskPaper,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "MODE · ${modeLabel.uppercase()}",
                                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                DrawerLinkRow(Icons.Filled.Settings, "Settings", onOpenSettings)
                DrawerLinkRow(Icons.Filled.Home, "Back", onBack)
            }
        }
    }
}

@Composable
private fun DrawerSectionLabel(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(color = color, fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun DrawerLinkRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.ledger.deskPaper)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.ledger.deskPaper,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

@Composable
private fun ConversationDrawerItem(
    conversation: Conversation,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onPinToggle: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onArchiveToggle: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    NavigationDrawerItem(
        label = {
            Text(
                text = conversation.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        },
        selected = isSelected,
        onClick = onSelect,
        icon = {
            Icon(
                imageVector = if (conversation.isPinned) Icons.Filled.PushPin else Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = if (conversation.isPinned) MaterialTheme.ledger.lampAmber else MaterialTheme.ledger.deskInk,
                modifier = Modifier.size(20.dp)
            )
        },
        badge = {
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.ledger.deskInk,
                        modifier = Modifier.size(16.dp)
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(if (conversation.isPinned) "Unpin" else "Pin") },
                        onClick = { menuExpanded = false; onPinToggle() },
                        leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menuExpanded = false; onRename() },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        onClick = { menuExpanded = false; onDuplicate() },
                        leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (conversation.isArchived) "Unarchive" else "Archive") },
                        onClick = { menuExpanded = false; onArchiveToggle() },
                        leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.ledger.emberRed) },
                        onClick = { menuExpanded = false; onDelete() },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.ledger.emberRed) }
                    )
                }
            }
        },
        shape = RoundedCornerShape(8.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.ledger.deskWalnutRaised,
            unselectedContainerColor = Color.Transparent
        ),
        modifier = Modifier.height(44.dp)
    )
}
