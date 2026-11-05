package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.data.memory.MemoryRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * AndroLLM's note tools, re-homed onto Jarvis' notes: instead of a private
 * filesDir/notes folder (which Jarvis' create_note / list_notes would never
 * see) every note is a MemoryEntity in category Notes, keyed by its title —
 * exactly what Jarvis' create_note writes. Notes saved by either surface are
 * visible to both, and stay indexed for semantic memory search.
 */

private fun memory(context: Context): MemoryRepository? =
    runCatching { (context.applicationContext as? JarvisApp)?.memoryRepository }.getOrNull()

private fun notReady(): ToolResult =
    ToolResult.Failure("Jarvis' note storage is not ready yet — try again in a moment.")

/** Normalizes a title for matching ("Shopping list" == "shopping_list"). */
private fun normTitle(title: String): String =
    title.trim().lowercase().replace('_', ' ').replace(Regex("\\s+"), " ")

private suspend fun allNotes(repo: MemoryRepository): List<MemoryEntity> =
    repo.getAllMemories()
        .filter { it.category == MemoryEntity.CATEGORY_NOTES }
        .sortedByDescending { it.timestamp }

private suspend fun findNote(repo: MemoryRepository, title: String): MemoryEntity? {
    val wanted = normTitle(title)
    return allNotes(repo).firstOrNull { normTitle(it.key) == wanted }
}

/**
 * Saves a note (creates or overwrites by title).
 */
class NoteSaveTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "note_save",
        description = "Save a note by title (overwrites an existing note with the same title).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
                putJsonObject("content") { put("type", "string") }
            }
            putJsonArray("required") { add("title"); add("content") }
        },
        permission = ToolPermission.NOTES,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val title = ToolArgs.str(arguments, "title", "name")
            ?: return ToolResult.Failure("Missing required argument: title")
        val content = ToolArgs.str(arguments, "content", "text")
            ?: return ToolResult.Failure("Missing required argument: content")
        val repo = memory(context) ?: return notReady()
        return runCatching {
            val existing = findNote(repo, title)
            val rawText = "$title: $content"
            if (existing != null) {
                repo.updateMemory(
                    existing.copy(value = content, rawText = rawText, timestamp = System.currentTimeMillis())
                )
                ToolResult.Success("Note '$title' updated.", buildJsonObject { put("name", title); put("id", existing.id) })
            } else {
                val id = repo.saveMemory(
                    rawText = rawText,
                    category = MemoryEntity.CATEGORY_NOTES,
                    key = title,
                    value = content
                )
                ToolResult.Success("Note '$title' saved.", buildJsonObject { put("name", title); put("id", id) })
            }
        }.getOrElse { ToolResult.Failure("Could not save the note: ${it.message ?: it.javaClass.simpleName}") }
    }
}

/** Lists saved note titles (newest first). */
class NoteListTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "note_list",
        description = "List the titles of all saved notes.",
        permission = ToolPermission.NOTES,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val repo = memory(context) ?: return notReady()
        val notes = runCatching { allNotes(repo) }.getOrElse {
            return ToolResult.Failure("Could not read notes: ${it.message ?: it.javaClass.simpleName}")
        }
        if (notes.isEmpty()) return ToolResult.Success("No notes saved yet.")
        val titles = notes.map { it.key.ifBlank { it.rawText.take(30) } }
        val data = buildJsonObject {
            putJsonArray("notes") { titles.forEach { add(it) } }
        }
        return ToolResult.Success(titles.joinToString("; "), data)
    }
}

/** Reads a saved note's content. */
class NoteGetTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "note_get",
        description = "Read the content of a saved note by title.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
            }
            putJsonArray("required") { add("title") }
        },
        permission = ToolPermission.NOTES,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val title = ToolArgs.str(arguments, "title", "name")
            ?: return ToolResult.Failure("Missing required argument: title")
        val repo = memory(context) ?: return notReady()
        val note = runCatching { findNote(repo, title) }.getOrNull()
            ?: return ToolResult.Failure("No note named '$title'.")
        return ToolResult.Success(
            note.value.ifBlank { note.rawText },
            buildJsonObject { put("name", note.key); put("id", note.id) }
        )
    }
}

/** Deletes a saved note — a destructive action, always confirmed first. */
class NoteDeleteTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "note_delete",
        description = "Delete a saved note by title.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
            }
            putJsonArray("required") { add("title") }
        },
        permission = ToolPermission.NOTES,
        category = ToolCategory.PRODUCTIVITY,
        requiresConfirmation = true,
        confirmationPrompt = "delete the note {title}"
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val title = ToolArgs.str(arguments, "title", "name")
            ?: return ToolResult.Failure("Missing required argument: title")
        val repo = memory(context) ?: return notReady()
        val note = runCatching { findNote(repo, title) }.getOrNull()
            ?: return ToolResult.Failure("No note named '$title'.")
        if (!HighRiskConfirmation.ensureApproved(context, spec.name, "delete the note \"${note.key}\"")) {
            return HighRiskConfirmation.declined()
        }
        val ok = runCatching { repo.deleteMemoryById(note.id); true }.getOrDefault(false)
        return if (ok) ToolResult.Success("Deleted note '${note.key}'.") else ToolResult.Failure("Could not delete the note.")
    }
}
