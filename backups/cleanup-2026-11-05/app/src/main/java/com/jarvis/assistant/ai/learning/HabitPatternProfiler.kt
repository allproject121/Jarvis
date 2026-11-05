package com.jarvis.assistant.ai.learning

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar

/**
 * Habit entry tracking action frequency across hourly time blocks.
 */
data class HabitFrequency(
    val actionType: String,
    val hourlyCounts: MutableMap<Int, Int> = mutableMapOf() // hour (0..23) -> count
)

/**
 * On-Device Habit Pattern Profiler:
 * Learns daily user rhythms, recurring commands, and time-of-day preferences.
 */
class HabitPatternProfiler private constructor(context: Context) {

    companion object {
        private const val TAG = "HabitPatternProfiler"
        private const val PREFS_NAME = "jarvis_habit_profiles"
        private const val KEY_HABITS_JSON = "habits_map"

        @Volatile
        private var instance: HabitPatternProfiler? = null

        fun getInstance(context: Context): HabitPatternProfiler {
            return instance ?: synchronized(this) {
                instance ?: HabitPatternProfiler(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val habits = mutableMapOf<String, HabitFrequency>()

    init {
        loadHabits()
    }

    private fun loadHabits() {
        val json = prefs.getString(KEY_HABITS_JSON, null)
        if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<Map<String, HabitFrequency>>() {}.type
                val map: Map<String, HabitFrequency> = gson.fromJson(json, type)
                habits.putAll(map)
                Log.i(TAG, "Loaded ${habits.size} habit pattern profiles.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed loading habits: ${e.message}")
            }
        }
    }

    private fun saveHabits() {
        prefs.edit().putString(KEY_HABITS_JSON, gson.toJson(habits)).apply()
    }

    /**
     * Records an executed action with current system timestamp and hour.
     */
    fun recordAction(actionType: String) {
        val clean = actionType.trim().lowercase(java.util.Locale.ROOT)
        if (clean.isBlank()) return

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val habit = habits.getOrPut(clean) { HabitFrequency(actionType = clean) }
        val count = habit.hourlyCounts.getOrDefault(hour, 0)
        habit.hourlyCounts[hour] = count + 1

        Log.d(TAG, "Tracked habit '$clean' at hour $hour (count=${count + 1})")
        saveHabits()
    }

    /**
     * Predicts top probable actions for the current hour based on learned historical frequencies.
     */
    fun getHabitPredictionsForCurrentTime(): List<String> {
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val candidateActions = mutableListOf<Pair<String, Int>>()

        for ((action, freq) in habits) {
            val count = freq.hourlyCounts.getOrDefault(currentHour, 0)
            if (count >= 2) {
                candidateActions.add(Pair(action, count))
            }
        }

        candidateActions.sortByDescending { it.second }
        return candidateActions.take(3).map { it.first }
    }

    /**
     * Generates contextual habit hints for prompt injection.
     */
    fun buildHabitContext(): String {
        val predictions = getHabitPredictionsForCurrentTime()
        if (predictions.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("[LEARNED ROUTINE & PROACTIVE HABIT INSIGHTS]:\n")
        sb.append("At this hour, user typically engages in: ").append(predictions.joinToString(", ")).append(".\n")
        return sb.toString().trim()
    }

    /**
     * Total lifetime count per tracked action (across all hours).
     *
     * Used by the contact resolver to read back the call habits JARVIS learned
     * ("call_rahul_sharma_4321" → 7) instead of writing them and never
     * consulting them again.
     */
    fun getActionCountsSnapshot(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for ((action, freq) in habits) {
            val total = freq.hourlyCounts.values.sum()
            if (total > 0) counts[action] = total
        }
        return counts
    }
}
