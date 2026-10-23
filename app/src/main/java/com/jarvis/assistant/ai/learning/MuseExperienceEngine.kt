package com.jarvis.assistant.ai.learning

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * An individual operational trajectory recorded during task execution.
 */
data class ExecutionTrajectory(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val stepsTaken: List<String>,
    val wasSuccessful: Boolean,
    val errorOrFeedback: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * An atomic operational rule synthesized by MUSE post-execution reflection.
 */
data class MuseExperienceLesson(
    val id: String = UUID.randomUUID().toString(),
    val taskDomain: String,
    val triggerPattern: String,
    val operationalInsight: String,
    val confidenceScore: Float = 1.0f,
    val occurrences: Int = 1,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * MUSE (Self-Evolving & Experience-Driven Agent Core).
 * Implements trajectory reflection inspired by KnowledgeXLab/MUSE:
 *  1. Captures tool/macro execution trajectories.
 *  2. Autonomously analyzes failures or user corrections.
 *  3. Synthesizes atomic operational guidelines.
 *  4. Dynamically injects learned experiences into future task planning.
 */
class MuseExperienceEngine private constructor(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MuseExperienceEngine"
        private const val PREFS_NAME = "jarvis_muse_experience_store"
        private const val KEY_EXPERIENCES_JSON = "muse_lessons"

        @Volatile
        private var instance: MuseExperienceEngine? = null

        fun getInstance(context: Context, scope: CoroutineScope): MuseExperienceEngine {
            return instance ?: synchronized(this) {
                instance ?: MuseExperienceEngine(context.applicationContext, scope).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _experiences = MutableStateFlow<List<MuseExperienceLesson>>(emptyList())
    val experiences: StateFlow<List<MuseExperienceLesson>> = _experiences.asStateFlow()

    init {
        loadStoredExperiences()
    }

    private fun loadStoredExperiences() {
        val json = prefs.getString(KEY_EXPERIENCES_JSON, null)
        if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<MuseExperienceLesson>>() {}.type
                val loaded: List<MuseExperienceLesson> = gson.fromJson(json, type)
                _experiences.value = loaded
                Log.i(TAG, "MUSE: Loaded ${loaded.size} persistent experience lessons.")
            } catch (e: Exception) {
                Log.e(TAG, "MUSE: Failed loading lessons: ${e.message}")
            }
        }
    }

    private fun persistExperiences(list: List<MuseExperienceLesson>) {
        _experiences.value = list
        prefs.edit().putString(KEY_EXPERIENCES_JSON, gson.toJson(list)).apply()
    }

    /**
     * Records a completed or failed task trajectory and triggers reflection.
     */
    fun recordTrajectory(
        goal: String,
        stepsTaken: List<String>,
        wasSuccessful: Boolean,
        feedback: String? = null
    ) {
        val trajectory = ExecutionTrajectory(
            goal = goal.trim(),
            stepsTaken = stepsTaken,
            wasSuccessful = wasSuccessful,
            errorOrFeedback = feedback?.trim()
        )

        Log.i(TAG, "MUSE: Recorded trajectory for '${trajectory.goal}' (success=$wasSuccessful)")

        // Reflect if there was a failure or explicit user correction feedback
        if (!wasSuccessful || !feedback.isNullOrBlank()) {
            reflectOnTrajectory(trajectory)
        }
    }

    /**
     * Autonomous Trajectory Reflection:
     * Extracts an operational guideline from a failed step or corrective feedback.
     */
    private fun reflectOnTrajectory(trajectory: ExecutionTrajectory) {
        scope.launch(Dispatchers.Default) {
            val goalLower = trajectory.goal.lowercase(Locale.ROOT)
            val feedback = trajectory.errorOrFeedback.orEmpty().trim()

            // Determine domain
            val domain = when {
                goalLower.contains("torch") || goalLower.contains("flashlight") -> "Hardware/Flashlight"
                goalLower.contains("youtube") || goalLower.contains("video") || goalLower.contains("song") -> "Media/YouTube"
                goalLower.contains("whatsapp") || goalLower.contains("message") -> "Communication/WhatsApp"
                goalLower.contains("camera") || goalLower.contains("photo") -> "Hardware/Camera"
                goalLower.contains("macro") || goalLower.contains("chatgpt") || goalLower.contains("gemini") -> "Automation/Macros"
                goalLower.contains("smart home") || goalLower.contains("light") -> "IoT/SmartHome"
                else -> "General/Tasks"
            }

            val insight = if (feedback.isNotBlank()) {
                "User Feedback: $feedback"
            } else {
                "Action sequence failed at step: ${trajectory.stepsTaken.lastOrNull() ?: "unknown"}. Verify preconditions before repeating."
            }

            addOrUpdateLesson(
                domain = domain,
                triggerPattern = trajectory.goal.take(40),
                insight = insight
            )
        }
    }

    /**
     * Adds a synthesized lesson to the persistent experience store.
     */
    @Synchronized
    fun addOrUpdateLesson(domain: String, triggerPattern: String, insight: String) {
        val current = _experiences.value.toMutableList()
        val existingIndex = current.indexOfFirst {
            it.taskDomain.equals(domain, ignoreCase = true) &&
                    (it.triggerPattern.contains(triggerPattern, ignoreCase = true) || triggerPattern.contains(it.triggerPattern, ignoreCase = true))
        }

        if (existingIndex >= 0) {
            val old = current[existingIndex]
            val updated = old.copy(
                operationalInsight = insight,
                occurrences = old.occurrences + 1,
                timestamp = System.currentTimeMillis()
            )
            current[existingIndex] = updated
            Log.i(TAG, "MUSE: Reinforced lesson #$existingIndex in [$domain]: $insight")
        } else {
            val newLesson = MuseExperienceLesson(
                taskDomain = domain,
                triggerPattern = triggerPattern,
                operationalInsight = insight
            )
            current.add(0, newLesson)
            if (current.size > 50) current.removeAt(current.size - 1)
            Log.i(TAG, "MUSE: Synthesized new experience lesson in [$domain]: $insight")
        }

        persistExperiences(current)
    }

    /**
     * Builds structured MUSE experience context relevant to the incoming user request.
     */
    fun buildExperienceContext(userPrompt: String): String {
        val list = _experiences.value
        if (list.isEmpty()) return ""

        val promptLower = userPrompt.lowercase(Locale.ROOT)
        val relevant = list.filter { lesson ->
            promptLower.contains(lesson.taskDomain.substringAfter("/").lowercase(Locale.ROOT)) ||
                    lesson.triggerPattern.split(" ").any { word -> word.length > 3 && promptLower.contains(word.lowercase(Locale.ROOT)) }
        }.take(3)

        if (relevant.isEmpty()) return ""

        return buildString {
            append("[MUSE SELF-LEARNING EXPERIENCE - OPERATIONAL GUIDELINES]:\n")
            relevant.forEach { lesson ->
                append("• [${lesson.taskDomain}]: ${lesson.operationalInsight}\n")
            }
        }.trim()
    }

    /**
     * Clears all stored lessons.
     */
    fun clearAllExperiences() {
        persistExperiences(emptyList())
    }
}
