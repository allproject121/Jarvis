package com.jarvis.assistant.ui.facelock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Enforces the Protocol 7 app lock. Fed by JarvisAccessibilityService on every
 * TYPE_WINDOW_STATE_CHANGED event: when a locked app comes to the foreground and
 * has not been unlocked in this visit, the JarvisAppLockActivity is shown over it.
 */
object AppLockEnforcer {

    private const val TAG = "JarvisAppLockGate"
    private const val LAUNCH_DEBOUNCE_MS = 1_000L
    private const val LAUNCHER_CACHE_MS = 60_000L

    /**
     * Windows from these packages appear *over* the current app (shade, IME, permission
     * dialogs, share sheet, the lock screen itself) - they never lock and never end an
     * unlocked session.
     */
    private val TRANSIENT_PACKAGES = setOf(
        "com.android.systemui",
        "android",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.intentresolver"
    )

    @Volatile private var lastLockLaunchAt = 0L
    @Volatile private var launcherPackages: Set<String> = emptySet()
    @Volatile private var launcherPackagesAt = 0L

    fun onWindowStateChanged(context: Context, pkg: String?) {
        if (pkg.isNullOrBlank()) return
        if (!AppLockStore.isEnabled(context)) return
        if (pkg == context.packageName || pkg in TRANSIENT_PACKAGES || pkg == defaultImePackage(context)) return

        // A real app change: leaving the session-unlocked app re-locks it.
        AppLockStore.onForegroundPackage(pkg)

        if (isLauncher(context, pkg)) return
        if (!AppLockStore.isLocked(context, pkg)) return
        if (AppLockStore.isSessionUnlocked(pkg)) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastLockLaunchAt < LAUNCH_DEBOUNCE_MS) return
        lastLockLaunchAt = now

        try {
            val label = try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { "" }
            val intent = Intent(context, JarvisAppLockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(JarvisAppLockActivity.EXTRA_LOCKED_PACKAGE, pkg)
                putExtra(JarvisAppLockActivity.EXTRA_APP_LABEL, label)
            }
            context.startActivity(intent)
            Log.i(TAG, "Locked app in foreground, showing Protocol 7 lock: $pkg")
        } catch (e: Exception) {
            Log.e(TAG, "Could not show app lock for $pkg: ${e.message}")
        }
    }

    private fun defaultImePackage(context: Context): String? = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
    } catch (_: Exception) { null }

    private fun isLauncher(context: Context, pkg: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (launcherPackages.isEmpty() || now - launcherPackagesAt > LAUNCHER_CACHE_MS) {
            launcherPackages = try {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                context.packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
                    .mapNotNull { it.activityInfo?.packageName }
                    .toSet()
            } catch (_: Exception) { emptySet() }
            launcherPackagesAt = now
        }
        return pkg in launcherPackages
    }
}
