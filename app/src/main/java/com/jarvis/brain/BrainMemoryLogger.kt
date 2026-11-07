package com.jarvis.brain

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline Brain Markdown Logger.
 * Har user query, LLM raw response, parsed action aur execution result ko ek
 * persistent .md file ("offline_brain.md") me save karta hai.
 *
 * File phone ke internal filesDir me aur public Documents/Jarvis folder dono me
 * maintain ki ja sakti hai, jisse user kisi bhi markdown viewer se read kar sake.
 */
class BrainMemoryLogger(private val context: Context) {

    private val mutex = Mutex()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // Internal app storage file
    val internalLogFile: File get() = File(context.filesDir, "offline_brain.md")

    // Public device storage file (accessible via file manager)
    val publicLogFile: File get() {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val jarvisDir = File(docsDir, "Jarvis").apply { mkdirs() }
        return File(jarvisDir, "offline_brain.md")
    }

    init {
        initMarkdownHeaderIfNeeded(internalLogFile)
        runCatching { initMarkdownHeaderIfNeeded(publicLogFile) }
    }

    private fun initMarkdownHeaderIfNeeded(file: File) {
        if (!file.exists() || file.length() == 0L) {
            file.parentFile?.mkdirs()
            file.writeText(
                """
                # 🧠 Jarvis Offline Brain - Activity & Conversation Log
                > 100% On-Device Autonomous Assistant Engine
                > Local Model: Uncensored GGUF (llama.cpp JNI)
                
                ---
                
                """.trimIndent() + "\n"
            )
        }
    }

    /**
     * Ek poori interaction entry ko markdown format me record karta hai.
     */
    suspend fun logInteraction(
        userInput: String,
        rawModelOutput: String,
        actionName: String,
        actionParams: Map<String, Any>,
        executionResult: String,
        durationMs: Long,
        isQuickMatch: Boolean = false,
        thinking: String = ""
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val timestamp = dateFormat.format(Date())
            val modeTag = if (isQuickMatch) "⚡ Regex QuickMatch (<1ms)" else "🧠 Local LLM Inference (${durationMs}ms)"

            val entry = buildString {
                append("### 🗓️ [$timestamp] Interaction\n")
                append("- **Mode**: `$modeTag`\n")
                append("- **User Input**: \"$userInput\"\n")
                if (thinking.isNotBlank()) {
                    append("- **Autonomous Thinking Process (`<think>`)**:\n")
                    append("  > ${thinking.replace("\n", "\n  > ")}\n")
                }
                if (!isQuickMatch && rawModelOutput.isNotBlank()) {
                    append("- **Raw LLM JSON Output**:\n")
                    append("  ```json\n")
                    append("  $rawModelOutput\n")
                    append("  ```\n")
                }
                append("- **Decided Action**: `$actionName`\n")
                if (actionParams.isNotEmpty()) {
                    append("- **Parameters**: `$actionParams`\n")
                }
                append("- **Execution Result**: `$executionResult`\n")
                append("\n---\n\n")
            }

            // Save to internal storage
            runCatching {
                internalLogFile.appendText(entry)
            }.onFailure { Log.e(TAG, "Failed writing to internal log: ${it.message}") }

            // Save to public storage if permitted
            runCatching {
                publicLogFile.appendText(entry)
            }.onFailure { Log.w(TAG, "Public log file write skipped: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "BrainMemoryLogger"
    }
}
