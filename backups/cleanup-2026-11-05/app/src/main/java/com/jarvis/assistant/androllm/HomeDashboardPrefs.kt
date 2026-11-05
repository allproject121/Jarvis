package com.jarvis.assistant.androllm

import android.content.Context

/**
 * Whether Jarvis' Home shows the on-device AI dashboard section (local model status, engine
 * health, RAM + tokens/sec, throughput, storage, recent conversations, quick actions) below the
 * weather/telemetry cards. Default ON. Exposed as a toggle in Customization; HomeFragment re-reads
 * it on every resume / un-hide.
 */
object HomeDashboardPrefs {
    private const val PREFS = "androllm_home_dashboard"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
