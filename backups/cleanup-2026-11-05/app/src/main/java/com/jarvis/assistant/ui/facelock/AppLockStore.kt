package com.jarvis.assistant.ui.facelock

import android.content.Context
import android.content.SharedPreferences

/**
 * JARVIS Protocol 7 app-lock state.
 *
 * - Persisted (SharedPreferences): master switch + the set of locked package names.
 * - In memory: the "unlocked until left" session. After a successful verification the
 *   package stays unlocked until a *different* package comes to the foreground
 *   (see [onForegroundPackage]); a process restart simply re-locks everything.
 */
object AppLockStore {

    private const val PREFS_NAME = "jarvis_app_lock"
    private const val KEY_ENABLED = "app_lock_enabled"
    private const val KEY_LOCKED_PACKAGES = "app_lock_locked_packages"

    @Volatile
    private var sessionUnlockedPackage: String? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) clearSession()
    }

    fun lockedPackages(context: Context): Set<String> =
        // Copy: the set returned by SharedPreferences must not be modified.
        prefs(context).getStringSet(KEY_LOCKED_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun isLocked(context: Context, pkg: String): Boolean = pkg in lockedPackages(context)

    fun setLocked(context: Context, pkg: String, locked: Boolean) {
        val updated = lockedPackages(context).toMutableSet()
        if (locked) updated.add(pkg) else updated.remove(pkg)
        prefs(context).edit().putStringSet(KEY_LOCKED_PACKAGES, updated).apply()
        if (!locked && sessionUnlockedPackage == pkg) clearSession()
    }

    // ---- "Unlocked until left" session ----

    fun markSessionUnlocked(pkg: String) {
        sessionUnlockedPackage = pkg
    }

    fun isSessionUnlocked(pkg: String): Boolean = sessionUnlockedPackage == pkg

    /** Called for every real foreground app change; leaving the unlocked app re-locks it. */
    fun onForegroundPackage(pkg: String) {
        val unlocked = sessionUnlockedPackage ?: return
        if (unlocked != pkg) sessionUnlockedPackage = null
    }

    fun clearSession() {
        sessionUnlockedPackage = null
    }
}
