package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class JarvisKnowledgeBase(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("JarvisMemory", Context.MODE_PRIVATE)

    fun remember(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun recall(key: String): String? {
        return prefs.getString(key, null)
    }

    fun rememberAsync(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun forget(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clearAllMemory() {
        prefs.edit().clear().apply()
    }

    fun updateMood(mood: String) {
        val history = recall("mood_history") ?: "{}"
        val json = JSONObject(history)
        json.put(System.currentTimeMillis().toString(), mood)
        remember("mood_history", json.toString())
        remember("user_mood", mood)
    }

    fun trackAppLaunch(appName: String) {
        val stats = recall("app_usage_stats") ?: "{}"
        val json = JSONObject(stats)
        val count = json.optInt(appName, 0) + 1
        json.put(appName, count)
        remember("app_usage_stats", json.toString())
        val total = recall("total_commands")?.toIntOrNull() ?: 0
        remember("total_commands", (total + 1).toString())
    }

    fun getTopApps(limit: Int = 5): List<String> {
        val stats = recall("app_usage_stats") ?: return emptyList()
        val json = JSONObject(stats)
        return json.keys().asSequence().sortedByDescending { json.getInt(it) }.take(limit).toList()
    }

    fun trackCommand(command: String) {
        val history = recall("command_history") ?: "[]"
        val json = JSONArray(history)
        json.put(JSONObject().apply {
            put("cmd", command)
            put("time", System.currentTimeMillis())
        })
        if (json.length() > 100) {
            val trimmed = JSONArray()
            for (i in json.length() - 100 until json.length()) {
                trimmed.put(json.getJSONObject(i))
            }
            remember("command_history", trimmed.toString())
        } else {
            remember("command_history", json.toString())
        }
        val total = recall("total_commands")?.toIntOrNull() ?: 0
        remember("total_commands", (total + 1).toString())
    }

    fun getCommandHistory(): List<String> {
        val history = recall("command_history") ?: return emptyList()
        return try {
            val json = JSONArray(history)
            (0 until json.length()).map { json.getJSONObject(it).getString("cmd") }
        } catch (e: Exception) { emptyList() }
    }

    fun getRecentCommands(count: Int = 5): List<String> {
        return getCommandHistory().takeLast(count)
    }

    fun trackConversation(userMessage: String, jarvisResponse: String) {
        val history = recall("conversation_log") ?: "[]"
        val json = JSONArray(history)
        val entry = JSONObject()
        entry.put("user", userMessage)
        entry.put("jarvis", jarvisResponse)
        entry.put("time", System.currentTimeMillis())
        json.put(entry)
        if (json.length() > 200) {
            val trimmed = JSONArray()
            for (i in json.length() - 200 until json.length()) {
                trimmed.put(json.getJSONObject(i))
            }
            remember("conversation_log", trimmed.toString())
        } else {
            remember("conversation_log", json.toString())
        }
    }

    fun getConversationLog(): List<Pair<String, String>> {
        val log = recall("conversation_log") ?: return emptyList()
        return try {
            val json = JSONArray(log)
            (0 until json.length()).map {
                val obj = json.getJSONObject(it)
                obj.getString("user") to obj.getString("jarvis")
            }
        } catch (e: Exception) { emptyList() }
    }

    fun incrementUsageCounter(key: String) {
        val count = recall(key)?.toIntOrNull() ?: 0
        remember(key, (count + 1).toString())
    }

    fun getUsageCounter(key: String): Int {
        return recall(key)?.toIntOrNull() ?: 0
    }

    fun getTopCommands(limit: Int = 10): List<Pair<String, Int>> {
        val history = recall("command_history") ?: return emptyList()
        return try {
            val json = JSONArray(history)
            val freqMap = mutableMapOf<String, Int>()
            for (i in 0 until json.length()) {
                val cmd = json.getJSONObject(i).getString("cmd")
                val category = cmd.split(" ").firstOrNull() ?: cmd
                freqMap[category] = (freqMap[category] ?: 0) + 1
            }
            freqMap.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
        } catch (e: Exception) { emptyList() }
    }

    fun rememberContact(name: String, number: String) {
        val contacts = recall("saved_contacts") ?: "{}"
        val json = JSONObject(contacts)
        json.put(name.lowercase(), number)
        remember("saved_contacts", json.toString())
    }

    fun getContact(name: String): String? {
        val contacts = recall("saved_contacts") ?: return null
        return try {
            JSONObject(contacts).optString(name.lowercase(), null)
        } catch (e: Exception) { null }
    }

    fun getAllContacts(): Map<String, String> {
        val contacts = recall("saved_contacts") ?: return emptyMap()
        return try {
            val json = JSONObject(contacts)
            json.keys().asSequence().associateWith { json.getString(it) }
        } catch (e: Exception) { emptyMap() }
    }

    fun saveNote(title: String, content: String) {
        val notes = recall("user_notes") ?: "{}"
        val json = JSONObject(notes)
        val note = JSONObject()
        note.put("content", content)
        note.put("time", System.currentTimeMillis())
        json.put(title, note)
        remember("user_notes", json.toString())
    }

    fun getNote(title: String): String? {
        val notes = recall("user_notes") ?: return null
        return try {
            JSONObject(notes).getJSONObject(title).getString("content")
        } catch (e: Exception) { null }
    }

    fun deleteNote(title: String) {
        val notes = recall("user_notes") ?: return
        try {
            val json = JSONObject(notes)
            json.remove(title)
            remember("user_notes", json.toString())
        } catch (e: Exception) {}
    }

    fun getAllNotes(): Map<String, String> {
        val notes = recall("user_notes") ?: return emptyMap()
        return try {
            val json = JSONObject(notes)
            json.keys().asSequence().associateWith { json.getJSONObject(it).getString("content") }
        } catch (e: Exception) { emptyMap() }
    }

    fun addReminder(label: String, timeMillis: Long) {
        val reminders = recall("active_reminders") ?: "[]"
        val json = JSONArray(reminders)
        val reminder = JSONObject()
        reminder.put("label", label)
        reminder.put("time", timeMillis)
        reminder.put("created", System.currentTimeMillis())
        json.put(reminder)
        remember("active_reminders", json.toString())
    }

    fun getPendingReminders(): List<Pair<String, Long>> {
        val reminders = recall("active_reminders") ?: return emptyList()
        return try {
            val json = JSONArray(reminders)
            val now = System.currentTimeMillis()
            val pending = mutableListOf<Pair<String, Long>>()
            val active = JSONArray()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val time = obj.getLong("time")
                if (time > now) {
                    pending.add(obj.getString("label") to time)
                    active.put(obj)
                }
            }
            remember("active_reminders", active.toString())
            pending
        } catch (e: Exception) { emptyList() }
    }

    fun trackActivity(activity: String) {
        val history = recall("activity_history") ?: "[]"
        val json = JSONArray(history)
        json.put(JSONObject().apply {
            put("activity", activity)
            put("time", System.currentTimeMillis())
        })
        if (json.length() > 50) {
            val trimmed = JSONArray()
            for (i in json.length() - 50 until json.length()) {
                trimmed.put(json.getJSONObject(i))
            }
            remember("activity_history", trimmed.toString())
        } else {
            remember("activity_history", json.toString())
        }
    }

    fun getActivityHistory(): List<String> {
        val history = recall("activity_history") ?: return emptyList()
        return try {
            val json = JSONArray(history)
            (0 until json.length()).map { json.getJSONObject(it).getString("activity") }
        } catch (e: Exception) { emptyList() }
    }

    fun getFrequentActivityPattern(): String? {
        val history = recall("activity_history") ?: return null
        return try {
            val json = JSONArray(history)
            val freqMap = mutableMapOf<String, Int>()
            for (i in 0 until json.length()) {
                val act = json.getJSONObject(i).getString("activity")
                freqMap[act] = (freqMap[act] ?: 0) + 1
            }
            freqMap.maxByOrNull { it.value }?.key
        } catch (e: Exception) { null }
    }

    fun predictNextAction(): String? {
        val history = recall("command_history") ?: return null
        return try {
            val json = JSONArray(history)
            val freqMap = mutableMapOf<String, Int>()
            for (i in 0 until json.length()) {
                val cmd = json.getJSONObject(i).getString("cmd")
                val category = when {
                    cmd.contains("open") -> "open_app"
                    cmd.contains("play") -> "play_music"
                    cmd.contains("message") -> "send_message"
                    cmd.contains("call") -> "make_call"
                    cmd.contains("search") -> "web_search"
                    cmd.contains("youtube") -> "youtube"
                    cmd.contains("alarm") -> "alarm"
                    cmd.contains("timer") -> "timer"
                    cmd.contains("weather") -> "weather"
                    cmd.contains("news") -> "news"
                    else -> "general"
                }
                freqMap[category] = (freqMap[category] ?: 0) + 1
            }
            freqMap.maxByOrNull { it.value }?.key
        } catch (e: Exception) { null }
    }

    fun getStatistics(): String {
        val totalCommands = recall("total_commands")?.toIntOrNull() ?: 0
        val topApps = getTopApps(3)
        val topCmds = getTopCommands(3)
        val notes = getAllNotes().size
        val contacts = getAllContacts().size
        val convLength = recall("conversation_log")?.let {
            try { JSONArray(it).length() } catch (e: Exception) { 0 }
        } ?: 0

        return JSONObject().apply {
            put("totalCommands", totalCommands)
            put("topApps", JSONArray(topApps))
            put("topCommands", JSONArray(topCmds.map { "${it.first}: ${it.second} times" }))
            put("totalNotes", notes)
            put("totalContacts", contacts)
            put("conversationLength", convLength)
            put("userName", recall("user_name") ?: "Sir")
            put("userMood", recall("user_mood") ?: "Normal")
            put("preferredLanguage", recall("preferred_language") ?: "hindi")
            put("lastActive", recall("last_active_time") ?: "Unknown")
            put("frequentActivity", getFrequentActivityPattern() ?: "None")
            put("predictedAction", predictNextAction() ?: "None")
        }.toString()
    }

    fun updateLastActive() {
        remember("last_active_time", System.currentTimeMillis().toString())
    }

    fun updateScreenTime(durationMinutes: Int) {
        val total = recall("total_screen_time")?.toIntOrNull() ?: 0
        remember("total_screen_time", (total + durationMinutes).toString())
    }

    fun getScreenTime(): Int = recall("total_screen_time")?.toIntOrNull() ?: 0

    fun detectUsagePattern(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 0..5 -> "Late Night"
            in 6..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }
    }

    fun getTimeBasedGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..20 -> "Good Evening"
            else -> "Good Night"
        }
    }

    fun hasLearnedUserPreferences(): Boolean {
        return recall("user_name") != null || recall("preferred_language") != null
    }

    fun getMemorySize(): Int {
        return prefs.all.size
    }

    fun exportMemory(): String {
        val all = prefs.all
        val json = JSONObject()
        for ((key, value) in all) {
            json.put(key, value.toString())
        }
        return json.toString()
    }

    fun importMemory(data: String) {
        try {
            val json = JSONObject(data)
            val editor = prefs.edit()
            for (key in json.keys()) {
                editor.putString(key, json.getString(key))
            }
            editor.apply()
        } catch (e: Exception) {}
    }
}
