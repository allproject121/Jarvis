package com.jarvis.assistant.maya.guardian

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings for the Maya-derived guardian features (SharedPreferences "maya_guardian_prefs").
 * Kept separate from AppPreferences on purpose (that file is owned by another work package).
 */
class GuardianPrefs(context: Context) {

    companion object {
        const val PREFS_NAME = "maya_guardian_prefs"

        const val KEY_FACE_LOCK_ENABLED = "face_lock_enabled"
        const val KEY_FACE_THRESHOLD = "face_threshold"
        const val KEY_FACE_LOCK_FAILED_ATTEMPTS = "face_lock_failed_attempts"
        const val KEY_FACE_LOCK_LAST_ATTEMPT_AT = "face_lock_last_attempt_at"
        const val KEY_LISTEN_MODE = "listen_mode"
        const val KEY_LISTEN_OWNER_SEEN = "listen_owner_seen_since_arm"
        const val KEY_GOD_MODE = "god_mode"
        const val KEY_GOD_MODE_SINCE = "god_mode_since"
        const val KEY_WATCHMAN_MODE = "watchman_mode"
        const val KEY_GALLERY_INDEXED_AT = "gallery_indexed_at"

        const val WATCHMAN_OFF = "off"
        const val WATCHMAN_PHOTO = "photo"
        const val WATCHMAN_PHOTO_SIREN = "photo_siren"
        val WATCHMAN_MODES = listOf(WATCHMAN_OFF, WATCHMAN_PHOTO, WATCHMAN_PHOTO_SIREN)
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var faceLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_FACE_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FACE_LOCK_ENABLED, value).apply()

    var faceThreshold: Float
        get() = prefs.getFloat(KEY_FACE_THRESHOLD, FaceMatcher.DEFAULT_THRESHOLD)
            .coerceIn(FaceMatcher.MIN_THRESHOLD, FaceMatcher.MAX_THRESHOLD)
        set(value) = prefs.edit()
            .putFloat(KEY_FACE_THRESHOLD, value.coerceIn(FaceMatcher.MIN_THRESHOLD, FaceMatcher.MAX_THRESHOLD))
            .apply()

    var faceLockFailedAttempts: Int
        get() = prefs.getInt(KEY_FACE_LOCK_FAILED_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_FACE_LOCK_FAILED_ATTEMPTS, value).apply()

    var faceLockLastAttemptAt: Long
        get() = prefs.getLong(KEY_FACE_LOCK_LAST_ATTEMPT_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_FACE_LOCK_LAST_ATTEMPT_AT, value).apply()

    var listenModeKey: String
        get() = prefs.getString(KEY_LISTEN_MODE, ListenMode.EVERYONE.key) ?: ListenMode.EVERYONE.key
        set(value) = prefs.edit().putString(KEY_LISTEN_MODE, value).apply()

    /** nobody_until_owner: becomes true the first time the owner's voice is verified after arming. */
    var listenOwnerSeenSinceArm: Boolean
        get() = prefs.getBoolean(KEY_LISTEN_OWNER_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_LISTEN_OWNER_SEEN, value).apply()

    var godMode: Boolean
        get() = prefs.getBoolean(KEY_GOD_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_GOD_MODE, value)
            .putLong(KEY_GOD_MODE_SINCE, if (value) System.currentTimeMillis() else 0L).apply()

    val godModeSince: Long get() = prefs.getLong(KEY_GOD_MODE_SINCE, 0L)

    var watchmanMode: String
        get() = prefs.getString(KEY_WATCHMAN_MODE, WATCHMAN_OFF)?.takeIf { it in WATCHMAN_MODES } ?: WATCHMAN_OFF
        set(value) = prefs.edit().putString(KEY_WATCHMAN_MODE, if (value in WATCHMAN_MODES) value else WATCHMAN_OFF).apply()

    var galleryIndexedAt: Long
        get() = prefs.getLong(KEY_GALLERY_INDEXED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_GALLERY_INDEXED_AT, value).apply()
}
