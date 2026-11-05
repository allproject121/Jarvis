package com.jarvis.assistant.ui.compose.feature.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.jarvis.assistant.androllm.attachments.model.ChatAttachment

/**
 * Share/open helpers for the Compose chat. Conversation serialisation lives in
 * [com.jarvis.assistant.androllm.chat.export.ConversationExporter].
 */
internal fun shareText(context: Context, text: String, chooserTitle: String) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(context, "Could not share: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Opens an attachment's original document (the SAF grant is persisted when the
 * file is attached). Jarvis declares no FileProvider, so the private cached copy
 * cannot be handed to other apps; when the original is gone a toast explains it.
 */
internal fun openAttachment(context: Context, attachment: ChatAttachment) {
    try {
        if (attachment.sourceUri.isBlank()) {
            Toast.makeText(context, "Original file is no longer available", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse(attachment.sourceUri)
        val mime = attachment.mimeType.ifBlank { "*/*" }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open ${attachment.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Share-sheet helpers (ported from AndroLLM feature/chat ConversationSharer):
 * a whole conversation in any [com.jarvis.assistant.androllm.chat.export.ExportFormat],
 * or a single message, as plain text through the system chooser.
 */
internal object ConversationSharer {

    fun shareText(context: Context, text: String, title: String = "Share Conversation") =
        com.jarvis.assistant.ui.compose.feature.chat.shareText(context, text, title)

    fun shareConversation(
        context: Context,
        conversationTitle: String,
        messages: List<com.jarvis.assistant.androllm.chat.model.Message>,
        format: com.jarvis.assistant.androllm.chat.export.ExportFormat =
            com.jarvis.assistant.androllm.chat.export.ExportFormat.MARKDOWN
    ) {
        val exportedText = com.jarvis.assistant.androllm.chat.export.ConversationExporter
            .export(conversationTitle, messages, format)
        shareText(context, exportedText, "Share $conversationTitle")
    }

    fun shareSingleMessage(context: Context, messageText: String) {
        shareText(context, messageText, "Share Message")
    }
}
