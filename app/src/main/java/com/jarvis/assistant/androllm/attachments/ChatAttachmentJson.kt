package com.jarvis.assistant.androllm.attachments

import com.jarvis.assistant.androllm.attachments.model.ChatAttachment
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON codec for [ChatAttachment] lists stored on a message's
 * `attachmentsJson` column (ported from AndroLLM feature/chat).
 */
object ChatAttachmentJson {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun encodeToString(attachments: List<ChatAttachment>): String =
        if (attachments.isEmpty()) "" else json.encodeToString(attachments)

    fun decodeFromString(jsonText: String): List<ChatAttachment> =
        if (jsonText.isBlank()) emptyList()
        else runCatching { json.decodeFromString<List<ChatAttachment>>(jsonText) }.getOrDefault(emptyList())
}

/**
 * Packages a turn's ready attachments into the numbered context block that
 * AndroLLM injects before the user message (as a system turn). Jarvis'
 * ModelRouter takes a single system prompt, so the block is appended there —
 * the user prompt (and the router's RAG memory query) stays clean.
 */
object AttachmentPromptBuilder {

    /** Per-file cap for injected text (AndroLLM MAX_ATTACHMENT_CHARS_PER_FILE). */
    const val MAX_CHARS_PER_FILE = 12_000

    /** Smaller cap for the on-device model, whose context window is far shorter. */
    const val MAX_CHARS_PER_FILE_ON_DEVICE = 3_000

    fun build(attachments: List<ChatAttachment>, maxCharsPerFile: Int = MAX_CHARS_PER_FILE): String {
        val ready = attachments.filter { it.isReady && it.text.isNotBlank() }
        if (ready.isEmpty()) return ""
        return buildString {
            append("The user attached the following files to this message:\n")
            ready.forEachIndexed { index, att ->
                append("\n[File ").append(index + 1).append(": ").append(att.name)
                if (att.fromOcr) append(" (text extracted by OCR)")
                append("]\n")
                if (att.text.length > maxCharsPerFile) {
                    append(att.text.take(maxCharsPerFile))
                    append("\n[… truncated: ").append(att.text.length - maxCharsPerFile).append(" more characters]")
                } else {
                    append(att.text)
                }
                append("\n")
            }
        }
    }
}
