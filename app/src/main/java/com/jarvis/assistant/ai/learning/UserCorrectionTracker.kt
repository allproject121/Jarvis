package com.jarvis.assistant.ai.learning

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * An individual behavioral lesson learned from user correction feedback.
 */
data class LearnedCorrection(
    val id: String = java.util.UUID.randomUUID().toString(),
    val triggerPattern: String,
    val correctedInstruction: String,
    val rawUserFeedback: String,
    val occurrenceCount: Int = 1,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * On-Device Adaptive Self-Learning Engine:
 * Listens for user corrections (e.g., "Nahi Jarvis, aise nahi", "Don't do X, do Y instead"),
 * extracts the implicit rule, persists it, and injects it into prompt memory so Jarvis never repeats mistakes.
 */
class UserCorrectionTracker private constructor(context: Context) {

    companion object {
        private const val TAG = "UserCorrectionTracker"
        private const val PREFS_NAME = "jarvis_learned_corrections"
        private const val KEY_CORRECTIONS_JSON = "corrections_list"

        @Volatile
        private var instance: UserCorrectionTracker? = null

        fun getInstance(context: Context): UserCorrectionTracker {
            return instance ?: synchronized(this) {
                instance ?: UserCorrectionTracker(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _corrections = MutableStateFlow<List<LearnedCorrection>>(emptyList())
    val corrections: StateFlow<List<LearnedCorrection>> = _corrections.asStateFlow()

    init {
        loadStoredCorrections()
    }

    private fun loadStoredCorrections() {
        val json = prefs.getString(KEY_CORRECTIONS_JSON, null)
        if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<LearnedCorrection>>() {}.type
                val loaded: List<LearnedCorrection> = gson.fromJson(json, type)
                _corrections.value = loaded
                Log.i(TAG, "Loaded ${loaded.size} persistent learned corrections.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed loading corrections: ${e.message}")
            }
        }
    }

    private fun saveCorrections(list: List<LearnedCorrection>) {
        _corrections.value = list
        prefs.edit().putString(KEY_CORRECTIONS_JSON, gson.toJson(list)).apply()
    }

    /**
     * Inspects user speech for correction indicators in English, Hindi, and Hinglish.
     */
    fun analyzeSpeechForCorrection(userSpeech: String, lastAssistantResponse: String): Boolean {
        val clean = userSpeech.trim()
        val lower = clean.lowercase(Locale.ROOT)

        val isCorrection = lower.startsWith("nahi ") || lower.startsWith("no ") ||
                lower.startsWith("not ") || lower.contains("aise nahi") ||
                lower.contains("maine bola tha") || lower.contains("i told you to") ||
                lower.contains("don't do that") || lower.contains("instead of")

        if (!isCorrection) return false

        // Extract instruction
        val rule = clean
            .replace(Regex("(?i)^(nahi|no|not|arre nahi|arre yaar)[,!\\s]*"), "")
            .trim()

        if (rule.length < 5) return false

        recordLearnedRule(
            trigger = lastAssistantResponse.take(50),
            correctedInstruction = rule,
            rawFeedback = clean
        )
        return true
    }

    fun recordLearnedRule(trigger: String, correctedInstruction: String, rawFeedback: String) {
        val current = _corrections.value.toMutableList()
        val existingIndex = current.indexOfFirst {
            it.correctedInstruction.equals(correctedInstruction, ignoreCase = true)
        }

        if (existingIndex >= 0) {
            val old = current[existingIndex]
            current[existingIndex] = old.copy(
                occurrenceCount = old.occurrenceCount + 1,
                timestamp = System.currentTimeMillis()
            )
            Log.i(TAG, "Reinforced existing learned correction: '$correctedInstruction' (count=${old.occurrenceCount + 1})")
        } else {
            val newCorrection = LearnedCorrection(
                triggerPattern = trigger,
                correctedInstruction = correctedInstruction,
                rawUserFeedback = rawFeedback
            )
            current.add(newCorrection)
            Log.i(TAG, "Learned NEW user preference rule: '$correctedInstruction'")
        }

        saveCorrections(current)
    }

    /**
     * Formats all learned corrections into a dynamic few-shot rule block for prompt injection.
     */
    fun buildLearnedCorrectionContext(): String {
        val list = _corrections.value
        if (list.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("[USER-SPECIFIC ADAPTIVE CORRECTIONS & LEARNED RULES]:\n")
        for (item in list.takeLast(6)) {
            sb.append("- Preference: ").append(item.correctedInstruction).append("\n")
        }
        return sb.toString().trim()
    }

    fun clearAllLearnedCorrections() {
        saveCorrections(emptyList())
    }
}
