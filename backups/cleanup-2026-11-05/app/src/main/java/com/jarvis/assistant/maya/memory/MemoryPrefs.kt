package com.jarvis.assistant.maya.memory

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Small preference holder for the Maya memory parity features:
 * incognito (do not store this session), auto-save (extract memories from
 * conversations automatically) and the daily-quote cache.
 *
 * Kept out of [com.jarvis.assistant.data.preferences.AppPreferences] on purpose
 * (that file is owned by another work package); the backing SharedPreferences
 * file is `maya_memory_prefs`.
 */
object MemoryPrefs {

    private const val FILE = "maya_memory_prefs"
    private const val KEY_INCOGNITO = "memory_incognito"
    private const val KEY_AUTO_SAVE = "memory_auto_save"
    private const val KEY_QUOTE_DATE = "daily_quote_date"
    private const val KEY_QUOTE_TEXT = "daily_quote_text"
    private const val KEY_WIKI_LAST_RUN = "wiki_last_run"

    private val _incognitoFlow = MutableStateFlow(false)
    private val _autoSaveFlow = MutableStateFlow(true)

    /** True while the user asked Jarvis not to store this session. */
    val incognitoFlow: StateFlow<Boolean> get() = _incognitoFlow

    /** True when facts may be auto-extracted from ordinary conversation. */
    val autoSaveFlow: StateFlow<Boolean> get() = _autoSaveFlow

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isIncognito(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INCOGNITO, false).also { _incognitoFlow.value = it }

    fun setIncognito(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_INCOGNITO, enabled).apply()
        _incognitoFlow.value = enabled
    }

    fun isAutoSave(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SAVE, true).also { _autoSaveFlow.value = it }

    fun setAutoSave(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SAVE, enabled).apply()
        _autoSaveFlow.value = enabled
    }

    /** Memories/episodes may be written only when not incognito. */
    fun canStore(context: Context): Boolean = !isIncognito(context)

    fun cachedQuote(context: Context, dateKey: String): String? {
        val p = prefs(context)
        return if (p.getString(KEY_QUOTE_DATE, "") == dateKey) {
            p.getString(KEY_QUOTE_TEXT, null)?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    fun cacheQuote(context: Context, dateKey: String, text: String) {
        prefs(context).edit().putString(KEY_QUOTE_DATE, dateKey).putString(KEY_QUOTE_TEXT, text).apply()
    }

    fun wikiLastRun(context: Context): Long = prefs(context).getLong(KEY_WIKI_LAST_RUN, 0L)

    fun setWikiLastRun(context: Context, ts: Long) {
        prefs(context).edit().putLong(KEY_WIKI_LAST_RUN, ts).apply()
    }
}
