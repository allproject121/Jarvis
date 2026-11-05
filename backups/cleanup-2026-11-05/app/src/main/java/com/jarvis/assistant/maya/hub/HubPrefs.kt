package com.jarvis.assistant.maya.hub

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Settings for the Maya HUB (Termux recipes, PC <-> Phone hub, Maya Home).
 *
 * Plain settings live in SharedPreferences "maya_hub_prefs"; secrets (relay token, PC pairing
 * secret) live in EncryptedSharedPreferences "maya_hub_secure" (same MasterKey pattern as
 * [com.jarvis.assistant.data.preferences.AppPreferences]).
 */
class HubPrefs private constructor(context: Context) {

    companion object {
        private const val TAG = "HubPrefs"
        const val PREFS_NAME = "maya_hub_prefs"
        const val SECURE_PREFS_NAME = "maya_hub_secure"

        const val KEY_PC_HOST = "pc_host"
        const val KEY_PC_PORT = "pc_port"
        const val KEY_PC_LINKED = "pc_linked"
        const val KEY_PC_NAME = "pc_name"
        const val KEY_PC_AUTO_CONNECT = "pc_auto_connect"
        const val KEY_PC_LAST_SYNC = "pc_last_memory_sync"
        const val KEY_TERMUX_LAST_JOB = "termux_last_job"
        const val KEY_TERMUX_DEFAULT_TIMEOUT = "termux_default_timeout_sec"

        const val SECURE_KEY_RELAY_TOKEN = "relay_token"
        const val SECURE_KEY_PAIR_SECRET = "pc_pair_secret"
        const val SECURE_KEY_PHONE_JOIN_CODE = "phone_join_code"

        const val DEFAULT_PC_PORT = 8766

        @Volatile
        private var instance: HubPrefs? = null

        fun get(context: Context): HubPrefs =
            instance ?: synchronized(this) {
                instance ?: HubPrefs(context.applicationContext).also { instance = it }
            }
    }

    private val appContext: Context = context.applicationContext

    val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val securePrefs: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Secure hub storage could not be initialized", e)
            null
        }
    }

    // ── PC hub ───────────────────────────────────────────────────────────────

    var pcHost: String
        get() = prefs.getString(KEY_PC_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PC_HOST, value.trim()).apply()

    var pcPort: Int
        get() = prefs.getInt(KEY_PC_PORT, DEFAULT_PC_PORT)
        set(value) = prefs.edit().putInt(KEY_PC_PORT, value).apply()

    var pcLinked: Boolean
        get() = prefs.getBoolean(KEY_PC_LINKED, false)
        set(value) = prefs.edit().putBoolean(KEY_PC_LINKED, value).apply()

    var pcName: String
        get() = prefs.getString(KEY_PC_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PC_NAME, value).apply()

    var pcAutoConnect: Boolean
        get() = prefs.getBoolean(KEY_PC_AUTO_CONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_PC_AUTO_CONNECT, value).apply()

    var lastMemorySyncAt: Long
        get() = prefs.getLong(KEY_PC_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_PC_LAST_SYNC, value).apply()

    /** Session token handed back by the PC relay after a successful pair. */
    var relayToken: String
        get() = securePrefs?.getString(SECURE_KEY_RELAY_TOKEN, "") ?: ""
        set(value) {
            securePrefs?.edit()?.putString(SECURE_KEY_RELAY_TOKEN, value)?.apply()
        }

    /** The 6-digit join code last used to pair (kept so re-pairing after a restart is silent). */
    var pairSecret: String
        get() = securePrefs?.getString(SECURE_KEY_PAIR_SECRET, "") ?: ""
        set(value) {
            securePrefs?.edit()?.putString(SECURE_KEY_PAIR_SECRET, value)?.apply()
        }

    /** Join code this phone shows so the PC side can verify it is talking to the right phone. */
    var phoneJoinCode: String
        get() = securePrefs?.getString(SECURE_KEY_PHONE_JOIN_CODE, "") ?: ""
        set(value) {
            securePrefs?.edit()?.putString(SECURE_KEY_PHONE_JOIN_CODE, value)?.apply()
        }

    fun clearPcLink() {
        prefs.edit()
            .putBoolean(KEY_PC_LINKED, false)
            .putString(KEY_PC_NAME, "")
            .apply()
        securePrefs?.edit()
            ?.remove(SECURE_KEY_RELAY_TOKEN)
            ?.remove(SECURE_KEY_PAIR_SECRET)
            ?.apply()
    }

    // ── Termux ───────────────────────────────────────────────────────────────

    var lastTermuxJobId: String
        get() = prefs.getString(KEY_TERMUX_LAST_JOB, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TERMUX_LAST_JOB, value).apply()

    var termuxDefaultTimeoutSec: Int
        get() = prefs.getInt(KEY_TERMUX_DEFAULT_TIMEOUT, 60)
        set(value) = prefs.edit().putInt(KEY_TERMUX_DEFAULT_TIMEOUT, value).apply()
}
