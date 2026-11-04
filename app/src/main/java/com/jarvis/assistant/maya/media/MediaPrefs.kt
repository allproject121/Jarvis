package com.jarvis.assistant.maya.media

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings for the Maya "media & comms" pack (WP9).
 *
 * Plain values live in SharedPreferences `maya_media_prefs`; secrets (Pollinations token,
 * OpenAI image key, YouTube Data API key, Google Places key) live in
 * EncryptedSharedPreferences `maya_media_secure` (plain fallback when the keystore is unavailable).
 */
class MediaPrefs private constructor(context: Context) {

    companion object {
        private const val TAG = "MediaPrefs"
        const val PREFS_NAME = "maya_media_prefs"
        const val SECURE_NAME = "maya_media_secure"

        // Image generation
        const val PROVIDER_POLLINATIONS = "pollinations"
        const val PROVIDER_GEMINI_IMAGE = "gemini_image"
        const val PROVIDER_IMAGEN = "imagen"
        const val PROVIDER_OPENAI = "openai"
        val IMAGE_PROVIDERS = listOf(PROVIDER_POLLINATIONS, PROVIDER_GEMINI_IMAGE, PROVIDER_IMAGEN, PROVIDER_OPENAI)

        private const val KEY_IMAGE_PROVIDER = "image_provider"
        private const val KEY_IMAGE_SIZE = "image_size"
        private const val KEY_GEMINI_IMAGE_MODEL = "gemini_image_model"
        private const val KEY_IMAGEN_MODEL = "imagen_model"
        private const val KEY_OPENAI_IMAGE_MODEL = "openai_image_model"
        private const val KEY_RECENT_IMAGES = "recent_images"

        private const val SKEY_POLLINATIONS_TOKEN = "pollinations_token"
        private const val SKEY_OPENAI_KEY = "openai_image_key"
        private const val SKEY_YOUTUBE_KEY = "youtube_api_key"
        private const val SKEY_MAPS_KEY = "maps_api_key"

        // WhatsApp auto-reply
        private const val KEY_AR_ENABLED = "autoreply_enabled"
        private const val KEY_AR_STYLE = "autoreply_style"
        private const val KEY_AR_AWAY = "autoreply_away_message"
        private const val KEY_AR_SUPPORT = "autoreply_support_mode"
        private const val KEY_AR_HANDLED = "autoreply_handled_contacts"

        // Music
        private const val KEY_MUSIC_APP = "default_music_app"
        private const val KEY_RECENT_SONGS = "recent_songs"

        // Commentary
        private const val KEY_COMMENTARY_GAME = "commentary_game"
        private const val KEY_COMMENTARY_FPS = "commentary_fps"

        const val DEFAULT_GEMINI_IMAGE_MODEL = "gemini-2.5-flash-image"
        const val FALLBACK_GEMINI_IMAGE_MODEL = "gemini-2.0-flash-preview-image-generation"
        const val DEFAULT_IMAGEN_MODEL = "imagen-4.0-generate-001"
        const val DEFAULT_OPENAI_IMAGE_MODEL = "gpt-image-1"

        @Volatile
        private var instance: MediaPrefs? = null

        fun get(context: Context): MediaPrefs =
            instance ?: synchronized(this) {
                instance ?: MediaPrefs(context.applicationContext).also { instance = it }
            }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secure: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Encrypted media prefs unavailable, falling back to plain storage: ${e.message}")
        context.getSharedPreferences("${SECURE_NAME}_fallback", Context.MODE_PRIVATE)
    }

    // ---------------- Image generation ----------------

    var imageProvider: String
        get() = prefs.getString(KEY_IMAGE_PROVIDER, PROVIDER_POLLINATIONS) ?: PROVIDER_POLLINATIONS
        set(value) {
            val clean = value.trim().lowercase()
            prefs.edit().putString(KEY_IMAGE_PROVIDER, if (clean in IMAGE_PROVIDERS) clean else PROVIDER_POLLINATIONS).apply()
        }

    /** Default size token: "square" | "portrait" | "landscape" | "instagram_story" | "WxH". */
    var imageSize: String
        get() = prefs.getString(KEY_IMAGE_SIZE, "square") ?: "square"
        set(value) = prefs.edit().putString(KEY_IMAGE_SIZE, value.trim().ifBlank { "square" }).apply()

    var geminiImageModel: String
        get() = prefs.getString(KEY_GEMINI_IMAGE_MODEL, "")?.ifBlank { DEFAULT_GEMINI_IMAGE_MODEL } ?: DEFAULT_GEMINI_IMAGE_MODEL
        set(value) = prefs.edit().putString(KEY_GEMINI_IMAGE_MODEL, value.trim()).apply()

    var imagenModel: String
        get() = prefs.getString(KEY_IMAGEN_MODEL, "")?.ifBlank { DEFAULT_IMAGEN_MODEL } ?: DEFAULT_IMAGEN_MODEL
        set(value) = prefs.edit().putString(KEY_IMAGEN_MODEL, value.trim()).apply()

    var openAiImageModel: String
        get() = prefs.getString(KEY_OPENAI_IMAGE_MODEL, "")?.ifBlank { DEFAULT_OPENAI_IMAGE_MODEL } ?: DEFAULT_OPENAI_IMAGE_MODEL
        set(value) = prefs.edit().putString(KEY_OPENAI_IMAGE_MODEL, value.trim()).apply()

    var pollinationsToken: String
        get() = secure.getString(SKEY_POLLINATIONS_TOKEN, "")?.trim() ?: ""
        set(value) = putSecure(SKEY_POLLINATIONS_TOKEN, value)

    var openAiImageKey: String
        get() = secure.getString(SKEY_OPENAI_KEY, "")?.trim() ?: ""
        set(value) = putSecure(SKEY_OPENAI_KEY, value)

    var youtubeApiKey: String
        get() = secure.getString(SKEY_YOUTUBE_KEY, "")?.trim() ?: ""
        set(value) = putSecure(SKEY_YOUTUBE_KEY, value)

    var mapsApiKey: String
        get() = secure.getString(SKEY_MAPS_KEY, "")?.trim() ?: ""
        set(value) = putSecure(SKEY_MAPS_KEY, value)

    /** Most recent generated image paths/URIs (newest first, max 40). */
    fun recentImages(): List<String> = readList(KEY_RECENT_IMAGES)

    fun addRecentImage(pathOrUri: String) {
        if (pathOrUri.isBlank()) return
        val list = recentImages().toMutableList()
        list.remove(pathOrUri)
        list.add(0, pathOrUri)
        writeList(KEY_RECENT_IMAGES, list.take(40))
    }

    // ---------------- WhatsApp auto-reply ----------------

    var autoReplyEnabled: Boolean
        get() = prefs.getBoolean(KEY_AR_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AR_ENABLED, value).apply()

    /** Free-text style hint, e.g. "short, friendly, Hinglish". */
    var autoReplyStyle: String
        get() = prefs.getString(KEY_AR_STYLE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AR_STYLE, value.trim()).apply()

    var autoReplyAwayMessage: String
        get() = prefs.getString(KEY_AR_AWAY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AR_AWAY, value.trim()).apply()

    /** Customer-support mode: answer FAQs about the user's business instead of a plain away line. */
    var autoReplySupportMode: Boolean
        get() = prefs.getBoolean(KEY_AR_SUPPORT, false)
        set(value) = prefs.edit().putBoolean(KEY_AR_SUPPORT, value).apply()

    /** Per-contact handling rules: contact name (lowercase) -> instructions. */
    fun handledContacts(): Map<String, String> {
        val raw = prefs.getString(KEY_AR_HANDLED, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val out = linkedMapOf<String, String>()
            obj.keys().forEach { k -> out[k] = obj.optString(k, "") }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setHandledContact(contact: String, instructions: String) {
        val map = handledContacts().toMutableMap()
        map[contact.trim().lowercase()] = instructions.trim()
        prefs.edit().putString(KEY_AR_HANDLED, JSONObject(map as Map<*, *>).toString()).apply()
    }

    fun removeHandledContact(contact: String): Boolean {
        val map = handledContacts().toMutableMap()
        val removed = map.remove(contact.trim().lowercase()) != null
        prefs.edit().putString(KEY_AR_HANDLED, JSONObject(map as Map<*, *>).toString()).apply()
        return removed
    }

    fun handlingRuleFor(contact: String): String? {
        val key = contact.trim().lowercase()
        if (key.isBlank()) return null
        val map = handledContacts()
        map[key]?.let { return it }
        return map.entries.firstOrNull { key.contains(it.key) || it.key.contains(key) }?.value
    }

    // ---------------- Music ----------------

    /** "youtube_music" | "spotify" | "gaana" | "youtube" | "jiosaavn" | "" (ask / system default). */
    var defaultMusicApp: String
        get() = prefs.getString(KEY_MUSIC_APP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MUSIC_APP, value.trim().lowercase()).apply()

    fun recentSongs(): List<String> = readList(KEY_RECENT_SONGS)

    fun addRecentSong(title: String) {
        if (title.isBlank()) return
        val list = recentSongs().toMutableList()
        list.removeAll { it.equals(title, ignoreCase = true) }
        list.add(0, title.trim())
        writeList(KEY_RECENT_SONGS, list.take(30))
    }

    // ---------------- Commentary ----------------

    var commentaryGame: String
        get() = prefs.getString(KEY_COMMENTARY_GAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_COMMENTARY_GAME, value.trim()).apply()

    var commentaryFps: Int
        get() = prefs.getInt(KEY_COMMENTARY_FPS, 2)
        set(value) = prefs.edit().putInt(KEY_COMMENTARY_FPS, value.coerceIn(1, 4)).apply()

    // ---------------- helpers ----------------

    private fun putSecure(key: String, value: String) {
        val clean = value.trim()
        if (clean.isEmpty()) secure.edit().remove(key).apply() else secure.edit().putString(key, clean).apply()
    }

    private fun readList(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeList(key: String, values: List<String>) {
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
    }
}
