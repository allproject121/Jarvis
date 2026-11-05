package com.jarvis.assistant.maya.screen

import android.content.Context
import android.content.SharedPreferences

/** Own settings file for the Maya screen pack (never touches AppPreferences). */
object ScreenPrefs {

    const val FILE = "maya_screen_prefs"
    private const val KEY_SCREEN_AWARENESS = "screen_awareness"
    private const val KEY_BATTERY_LOW_PCT = "ev_percent"
    private const val KEY_EVENTS_MASTER = "events_master"
    private const val KEY_EVENT_PREFIX = "ev_"
    private const val KEY_LAST_GOAL = "lab_last_goal"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun screenAwareness(context: Context): Boolean = prefs(context).getBoolean(KEY_SCREEN_AWARENESS, false)

    fun setScreenAwareness(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCREEN_AWARENESS, enabled).apply()
    }

    fun batteryLowPercent(context: Context): Int = prefs(context).getInt(KEY_BATTERY_LOW_PCT, 20).coerceIn(1, 99)

    fun setBatteryLowPercent(context: Context, pct: Int) {
        prefs(context).edit().putInt(KEY_BATTERY_LOW_PCT, pct.coerceIn(1, 99)).apply()
    }

    /** Master switch for all event announcements (default OFF so an update never starts talking unasked; set_event_trigger / the Macros screen turn it on). */
    fun eventsMaster(context: Context): Boolean = prefs(context).getBoolean(KEY_EVENTS_MASTER, false)

    fun setEventsMaster(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EVENTS_MASTER, enabled).apply()
    }

    fun isEventEnabled(context: Context, eventId: String): Boolean =
        prefs(context).getBoolean(KEY_EVENT_PREFIX + eventId, EventTriggers.defaultEnabled(eventId))

    fun setEventEnabled(context: Context, eventId: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EVENT_PREFIX + eventId, enabled).apply()
    }

    fun lastLabGoal(context: Context): String = prefs(context).getString(KEY_LAST_GOAL, "") ?: ""

    fun setLastLabGoal(context: Context, goal: String) {
        prefs(context).edit().putString(KEY_LAST_GOAL, goal).apply()
    }
}
