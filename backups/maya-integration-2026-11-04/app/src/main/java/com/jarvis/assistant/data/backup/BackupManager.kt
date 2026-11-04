package com.jarvis.assistant.data.backup

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.memory.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * JARVIS Backup & Restore Engine:
 * Exports memories, conversations, and settings to an offline portable JSON backup.
 */
object BackupManager {
    private const val TAG = "BackupManager"
    private val gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun exportBackup(context: Context): Result<File> = withContext(Dispatchers.IO) {
        try {
            val app = JarvisApp.instance
            val prefs = app.preferences
            val root = JsonObject()

            // Metadata
            val meta = JsonObject()
            meta.addProperty("version", "5.2.34")
            meta.addProperty("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            meta.addProperty("user", prefs.userName)
            meta.addProperty("model", prefs.aiModel)
            meta.addProperty("persona", prefs.personality)
            meta.addProperty("voice", prefs.voice)
            root.add("metadata", meta)

            // Mem0 Memories
            val memories = app.memoryRepository.getAllMemories()
            root.add("memories", gson.toJsonTree(memories))

            // Chat Turns
            val chatHistory = prefs.loadChatHistory()
            root.add("chat_history", gson.toJsonTree(chatHistory))

            // Output File
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val backupFile = File(downloadsDir, "Jarvis_Backup_$timeStamp.json")

            FileOutputStream(backupFile).use { fos ->
                fos.write(gson.toJson(root).toByteArray(Charsets.UTF_8))
            }

            Log.i(TAG, "Exported backup to: ${backupFile.absolutePath}")
            Result.success(backupFile)
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun importBackup(context: Context, jsonFile: File): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val app = JarvisApp.instance
            val jsonString = FileInputStream(jsonFile).use { it.reader(Charsets.UTF_8).readText() }
            val root = JsonParser.parseString(jsonString).asJsonObject

            var count = 0
            if (root.has("memories")) {
                val memArray = root.getAsJsonArray("memories")
                for (elem in memArray) {
                    val obj = elem.asJsonObject
                    val content = obj.get("rawText")?.asString ?: obj.get("content")?.asString ?: continue
                    val category = obj.get("category")?.asString ?: MemoryEntity.CATEGORY_NOTES
                    app.memoryRepository.saveMemory(
                        rawText = content,
                        category = category
                    )
                    count++
                }
            }

            Log.i(TAG, "Imported $count memories from backup")
            Result.success(count)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}
