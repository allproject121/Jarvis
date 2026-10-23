package com.jarvis.assistant.automation.playbook

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.InputStreamReader

data class PlaybookMeta(
    val id: String,
    val name: String,
    val description: String,
    val file: String,
    val trigger_phrases: List<String> = emptyList(),
    var markdownContent: String = ""
)

data class PlaybookIndex(
    val version: Int = 1,
    val skills: List<PlaybookMeta> = emptyList()
)

/**
 * Manages Installed Autonomous Skills & Execution Playbooks loaded from assets/playbooks/.
 */
class PlaybookRepository(private val context: Context) {

    companion object {
        private const val TAG = "PlaybookRepository"
        private const val INDEX_PATH = "playbooks/index.json"

        @Volatile
        private var instance: PlaybookRepository? = null

        fun getInstance(context: Context): PlaybookRepository {
            return instance ?: synchronized(this) {
                instance ?: PlaybookRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private val cachedPlaybooks = mutableListOf<PlaybookMeta>()

    init {
        loadAllPlaybooks()
    }

    @Synchronized
    fun loadAllPlaybooks(): List<PlaybookMeta> {
        cachedPlaybooks.clear()
        try {
            context.assets.open(INDEX_PATH).use { input ->
                val index = gson.fromJson(InputStreamReader(input), PlaybookIndex::class.java)
                index.skills.forEach { meta ->
                    try {
                        context.assets.open("playbooks/${meta.file}").bufferedReader().use { reader ->
                            meta.markdownContent = reader.readText()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load playbook content for ${meta.file}: ${e.message}")
                    }
                    cachedPlaybooks.add(meta)
                }
                Log.i(TAG, "Loaded ${cachedPlaybooks.size} production playbooks from assets.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading playbooks index: ${e.message}", e)
        }
        return cachedPlaybooks.toList()
    }

    fun getAllPlaybooks(): List<PlaybookMeta> = cachedPlaybooks.toList()

    fun findPlaybookByIntent(utterance: String): PlaybookMeta? {
        val lower = utterance.trim().lowercase()
        return cachedPlaybooks.firstOrNull { meta ->
            meta.trigger_phrases.any { phrase -> lower.contains(phrase.lowercase()) } ||
                    lower.contains(meta.name.lowercase()) ||
                    lower.contains(meta.id.lowercase())
        }
    }

    /**
     * Generates a concise summary directive of all active playbooks to inject into Gemini's system prompt.
     */
    fun getPlaybooksDirective(): String {
        if (cachedPlaybooks.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n[INSTALLED SKILLS & AUTONOMOUS PLAYBOOKS (JARVIS ENGINE)]:\n")
        sb.append("You have access to 8 high-priority autonomous execution playbooks:\n")
        cachedPlaybooks.forEachIndexed { i, p ->
            sb.append("${i + 1}. **${p.name}** (`${p.id}`): ${p.description}\n")
            sb.append("   - Triggers: ${p.trigger_phrases.joinToString(", ")}\n")
        }
        sb.append("When any of these user intents are detected, follow the designated playbook flow immediately and without hesitation.\n")
        return sb.toString()
    }
}
