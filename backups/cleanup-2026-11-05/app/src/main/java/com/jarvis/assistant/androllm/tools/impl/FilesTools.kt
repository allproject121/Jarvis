package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Lists recent files in the shared Downloads folder via MediaStore
 * (Android 10+ scoped storage — only files visible to this app are returned).
 * Older APIs fall back to the public directory.
 */
class ListDownloadsTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "list_downloads",
        description = "List recent files in the Downloads folder (name, size, when downloaded).",
        permission = ToolPermission.FILES,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val files = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 29) queryMediaStore() else legacyPublicDownloads()
        }
        if (files.isEmpty()) return ToolResult.Success("No recent downloads found.")
        val data = buildJsonObject {
            putJsonArray("files") {
                files.forEach { f ->
                    add(buildJsonObject {
                        put("name", f.name)
                        put("size_kb", f.bytes / 1024)
                        put("modified", f.modifiedAt)
                    })
                }
            }
        }
        return ToolResult.Success(
            files.joinToString("; ", limit = 15, truncated = "…") { "${it.name} (${it.bytes / 1024} KB)" },
            data
        )
    }

    private fun queryMediaStore(): List<FileEntry> = runCatching {
        if (Build.VERSION.SDK_INT < 29) return@runCatching emptyList<FileEntry>()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED
        )
        val entries = mutableListOf<FileEntry>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, null, null, "${MediaStore.Downloads.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
            while (cursor.moveToNext() && entries.size < 25) {
                entries += FileEntry(
                    name = cursor.getString(nameCol) ?: "file",
                    bytes = cursor.getLong(sizeCol),
                    modifiedAt = cursor.getLong(modCol) * 1000L
                )
            }
        }
        entries
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun legacyPublicDownloads(): List<FileEntry> = runCatching {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .listFiles()?.sortedByDescending { it.lastModified() }
            ?.take(25)
            ?.map { FileEntry(it.name, it.length(), it.lastModified()) }
            ?: emptyList()
    }.getOrDefault(emptyList())

    private data class FileEntry(val name: String, val bytes: Long, val modifiedAt: Long)
}

/**
 * Lists the assistant's own export / recording files — the artifacts the
 * export and voice-recorder tools produce ("where did my PDF go?"). Notes
 * live in Jarvis' memory store (see note_list), not in files.
 */
class ListAppFilesTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "list_app_files",
        description = "List files this assistant has created on the device (exports, recordings) with their paths.",
        permission = ToolPermission.FILES,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val entries = withContext(Dispatchers.IO) {
            val out = mutableListOf<Pair<String, Long>>()
            listOf(
                exportsDir(context),
                File(context.filesDir, "notes"),
                File(context.filesDir, "recordings")
            ).forEach { dir ->
                if (dir.isDirectory) {
                    dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach {
                        out += it.absolutePath to it.length()
                    }
                }
            }
            out
        }
        if (entries.isEmpty()) return ToolResult.Success("No files created by the assistant yet.")
        val data = buildJsonObject {
            putJsonArray("files") {
                entries.forEach { (path, bytes) ->
                    add(buildJsonObject { put("path", path); put("size_kb", bytes / 1024) })
                }
            }
        }
        return ToolResult.Success(
            entries.joinToString("; ", limit = 20, truncated = "…") { (path, _) -> path.substringAfterLast('/') },
            data
        )
    }
}
