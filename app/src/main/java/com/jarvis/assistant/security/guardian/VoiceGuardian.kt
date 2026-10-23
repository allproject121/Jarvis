package com.jarvis.assistant.security.guardian

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

enum class GuardianRole {
    BOSS,
    FAMILY,
    FRIEND,
    GUEST,
    BLOCKED
}

data class VoiceProfile(
    val name: String,
    val role: GuardianRole,
    val enrolledAt: Long = System.currentTimeMillis(),
    val modelId: String = "ecapa6s"
)

enum class ActionCategory {
    ADMIN_SECURITY,     // Voice enrollment, role changes, arm/disarm guard
    PHONE_AND_COMMS,    // Phone calls, SMS, WhatsApp messages, contacts
    SYSTEM_TERMINAL,    // Termux, app management, file changes
    ENTERTAINMENT_INFO  // YouTube, music, weather, web search, facts
}

class VoiceGuardian(private val context: Context) {
    companion object {
        private const val TAG = "VoiceGuardian"
        private const val PREFS_NAME = "jarvis_voice_guardian"
        private const val KEY_PROFILES = "voice_profiles"
        private const val KEY_IS_ARMED = "is_armed"
        private const val KEY_TOUCH_GUARD = "is_touch_guard_active"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val profiles = mutableMapOf<String, VoiceProfile>()

    init {
        loadProfiles()
        // Ensure default boss exists if empty
        if (profiles.isEmpty()) {
            profiles["Boss"] = VoiceProfile("Boss", GuardianRole.BOSS)
            saveProfiles()
        }
    }

    @Synchronized
    private fun loadProfiles() {
        profiles.clear()
        val json = prefs.getString(KEY_PROFILES, null)
        if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<Map<String, VoiceProfile>>() {}.type
                val loaded: Map<String, VoiceProfile> = gson.fromJson(json, type)
                profiles.putAll(loaded)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading voice profiles: ${e.message}")
            }
        }
    }

    @Synchronized
    private fun saveProfiles() {
        try {
            val json = gson.toJson(profiles)
            prefs.edit().putString(KEY_PROFILES, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving voice profiles: ${e.message}")
        }
    }

    fun listVoices(): List<VoiceProfile> = profiles.values.toList()

    fun enrollVoice(name: String, role: GuardianRole = GuardianRole.FAMILY): Boolean {
        profiles[name] = VoiceProfile(name, role)
        saveProfiles()
        Log.i(TAG, "Enrolled voice for $name with role $role")
        return true
    }

    fun removeVoice(name: String): Boolean {
        if (profiles.remove(name) != null) {
            saveProfiles()
            Log.i(TAG, "Removed voice profile $name")
            return true
        }
        return false
    }

    fun setVoiceRole(name: String, role: GuardianRole): Boolean {
        val existing = profiles[name] ?: return false
        profiles[name] = existing.copy(role = role)
        saveProfiles()
        Log.i(TAG, "Updated role for $name to $role")
        return true
    }

    fun getVoiceProfile(name: String): VoiceProfile? = profiles[name]

    var isArmed: Boolean
        get() = prefs.getBoolean(KEY_IS_ARMED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_ARMED, value).apply()

    var isTouchGuardActive: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_GUARD, false)
        set(value) = prefs.edit().putBoolean(KEY_TOUCH_GUARD, value).apply()

    /**
     * Determines whether an action is permitted for the given role.
     */
    fun isActionPermitted(role: GuardianRole, category: ActionCategory): Boolean {
        if (role == GuardianRole.BLOCKED) return false

        return when (category) {
            ActionCategory.ADMIN_SECURITY -> role == GuardianRole.BOSS
            ActionCategory.PHONE_AND_COMMS -> role == GuardianRole.BOSS || role == GuardianRole.FAMILY
            ActionCategory.SYSTEM_TERMINAL -> role == GuardianRole.BOSS
            ActionCategory.ENTERTAINMENT_INFO -> role == GuardianRole.BOSS || role == GuardianRole.FAMILY || role == GuardianRole.FRIEND
        }
    }
}
