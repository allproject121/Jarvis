package com.jarvis.assistant.ui.compose.feature.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.attachments.AttachmentCache
import com.jarvis.assistant.androllm.attachments.AttachmentSettingsStore
import com.jarvis.assistant.androllm.attachments.model.AttachmentSettings
import com.jarvis.assistant.androllm.chat.AndroLlmChatStore
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.theme.ledger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Chat" settings for the Compose (AndroLLM) chat: attachments, attachment
 * processing limits/cache and chat storage (shared with Jarvis' voice history).
 *
 * Chat attachments default to ON ([AndroLlmFlags.CHAT_ATTACHMENTS]) because
 * they are purely user-initiated (nothing runs until a file is picked), use
 * no microphone/background work, and never change routing.
 */
@Composable
fun ChatSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attachmentStore = remember { AttachmentSettingsStore.get(context) }
    val settings by attachmentStore.settings.collectAsState(initial = AttachmentSettings())

    var attachmentsEnabled by remember {
        mutableStateOf(AndroLlmFlags.isEnabled(context, AndroLlmFlags.CHAT_ATTACHMENTS, default = true))
    }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var cacheRevision by remember { mutableStateOf(0) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var confirmDeleteChats by remember { mutableStateOf(false) }

    LaunchedEffect(cacheRevision) {
        cacheBytes = withContext(Dispatchers.IO) { AttachmentCache.totalBytes(context) }
    }

    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            ToggleRow(
                icon = Icons.Filled.AttachFile,
                title = "Chat attachments",
                subtitle = "Attach PDF, Word, PowerPoint, Excel, EPUB, text or images; extracted text (OCR for images) is added to your prompt",
                checked = attachmentsEnabled,
                onCheckedChange = {
                    attachmentsEnabled = it
                    AndroLlmFlags.setEnabled(context, AndroLlmFlags.CHAT_ATTACHMENTS, it)
                }
            )
            SliderSetting(
                label = { "Max attachments per message: ${it.toInt()}" },
                value = settings.maxAttachmentsPerMessage.toFloat(),
                valueRange = AttachmentSettings.MAX_ATTACHMENTS_MIN.toFloat()..AttachmentSettings.MAX_ATTACHMENTS_MAX.toFloat(),
                steps = AttachmentSettings.MAX_ATTACHMENTS_MAX - AttachmentSettings.MAX_ATTACHMENTS_MIN - 1,
                onCommit = { value ->
                    scope.launch { attachmentStore.update { it.copy(maxAttachmentsPerMessage = value.toInt()) } }
                }
            )
            SliderSetting(
                label = { "Max file size: ${it.toInt()} MB" },
                value = (settings.maxAttachmentBytes / (1024 * 1024)).toFloat().coerceIn(1f, 50f),
                valueRange = 1f..50f,
                steps = 48,
                onCommit = { value ->
                    scope.launch {
                        attachmentStore.update { it.copy(maxAttachmentBytes = value.toLong() * 1024 * 1024) }
                    }
                }
            )
            ToggleRow(
                icon = Icons.Filled.Badge,
                title = "Keep original file names",
                subtitle = "Off: attachments are stored and shown to the model as \"attachment.ext\"",
                checked = settings.preserveFilenames,
                onCheckedChange = { checked ->
                    scope.launch { attachmentStore.update { it.copy(preserveFilenames = checked) } }
                }
            )
            SettingRow(
                icon = Icons.Filled.CleaningServices,
                title = "Clear attachment cache",
                value = formatBytes(cacheBytes),
                onClick = { confirmClearCache = true }
            )

            HorizontalDivider(color = MaterialTheme.ledger.deskHairline, modifier = Modifier.padding(vertical = 4.dp))

            SettingRow(
                icon = Icons.Filled.DeleteForever,
                title = "Delete all chats",
                value = "Includes Jarvis' voice history",
                onClick = { confirmDeleteChats = true }
            )
            Text(
                text = "Chats and Jarvis' voice history live in one database on this device. Voice turns are added to the open chat automatically, and everything here feeds Jarvis' memory.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.ledger.deskInk,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }

    if (confirmClearCache) {
        ConfirmDialog(
            title = "Clear attachment cache?",
            message = "Removes the private copies of attached files. Extracted text already saved in chats is kept.",
            confirmLabel = "Clear",
            onDismiss = { confirmClearCache = false },
            onConfirm = {
                confirmClearCache = false
                scope.launch {
                    withContext(Dispatchers.IO) { AttachmentCache.clearAll(context) }
                    cacheRevision++
                }
            }
        )
    }

    if (confirmDeleteChats) {
        ConfirmDialog(
            title = "Delete all chats?",
            message = "Permanently deletes every conversation, including Jarvis' voice history.",
            confirmLabel = "Delete all",
            onDismiss = { confirmDeleteChats = false },
            onConfirm = {
                confirmDeleteChats = false
                scope.launch {
                    val result = AndroLlmChatStore.conversationRepository(context).deleteAll()
                    // The history window is a view over this database: reset it as well.
                    runCatching { JarvisApp.instance.chatRepository.clearHistory() }
                    withContext(Dispatchers.IO) { AttachmentCache.clearAll(context) }
                    cacheRevision++
                    Toast.makeText(
                        context,
                        if (result.isSuccess) "All chats deleted" else "Delete failed: ${result.exceptionOrNull()?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}
