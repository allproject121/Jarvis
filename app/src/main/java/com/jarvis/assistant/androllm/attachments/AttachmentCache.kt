package com.jarvis.assistant.androllm.attachments

import android.content.Context
import com.jarvis.assistant.androllm.AndroLlmPaths
import java.io.File

/**
 * Conversation-scoped attachment cache (ported from AndroLLM). Each
 * conversation owns a directory under [AndroLlmPaths.attachmentsDir] holding
 * its private file copies. Deleting a conversation removes its directory.
 */
object AttachmentCache {

    private fun root(context: Context): File = AndroLlmPaths.attachmentsDir(context)

    /** The cache directory for [conversationId] (created on demand). */
    fun cacheDir(context: Context, conversationId: String): File {
        val safeId = conversationId.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "unassigned" }
        val dir = File(root(context), safeId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Removes every cached file of one conversation (called on conversation delete). */
    fun clearConversation(context: Context, conversationId: String) {
        if (conversationId.isBlank()) return
        val safeId = conversationId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        File(root(context), safeId).deleteRecursively()
    }

    /** Removes the entire attachment cache (user-triggered cleanup). */
    fun clearAll(context: Context) {
        root(context).listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Total bytes currently cached (for the settings "storage used" row). */
    fun totalBytes(context: Context): Long {
        val root = root(context)
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
