package com.jarvis.assistant.attachments

/**
 * Model representing an attached image, document, or audio file in a conversational turn.
 */
data class ChatAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val fileUri: String,
    val filePath: String,
    val sizeBytes: Long,
    val extractedText: String = "",
    val isImage: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
