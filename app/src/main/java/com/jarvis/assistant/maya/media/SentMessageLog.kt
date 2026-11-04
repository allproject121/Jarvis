package com.jarvis.assistant.maya.media

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Remembers every WhatsApp message Jarvis sends (contact, number, text, time), so a later
 * "jo message kuch din pehle bheja tha wo delete kar do" can find the exact message.
 * Written by [com.jarvis.assistant.util.WhatsAppMessenger] on each successful send.
 */
object SentMessageLog {

    private const val TAG = "SentMessageLog"
    private const val FILE = "maya_sent_messages.json"
    private const val MAX = 300

    /** WhatsApp only offers "Delete for everyone" for about 60 hours after sending. */
    const val DELETE_FOR_EVERYONE_WINDOW_MS = 60L * 60 * 60 * 1000

    data class Entry(
        val contact: String,
        val digits: String,
        val text: String,
        val pkg: String,
        val sentAt: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("contact", contact).put("digits", digits).put("text", text)
            .put("pkg", pkg).put("sentAt", sentAt)

        companion object {
            fun fromJson(o: JSONObject) = Entry(
                o.optString("contact"), o.optString("digits"), o.optString("text"),
                o.optString("pkg", "com.whatsapp"), o.optLong("sentAt")
            )
        }
    }

    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    fun all(ctx: Context): List<Entry> = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Entry::fromJson) }
        } catch (e: Exception) {
            Log.w(TAG, "read failed: ${e.message}"); emptyList()
        }
    }

    fun record(ctx: Context, contact: String, digits: String, text: String, pkg: String) {
        if (text.isBlank()) return
        try {
            synchronized(lock) {
                val list = all(ctx).toMutableList()
                list.add(Entry(contact, digits, text.trim(), pkg, System.currentTimeMillis()))
                val trimmed = list.takeLast(MAX)
                file(ctx).writeText(JSONArray(trimmed.map { it.toJson() }).toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "record failed: ${e.message}")
        }
    }

    /**
     * Picks the sent message the user means. Newest first; filters by contact (name or number
     * fragment) and/or a text hint (any word of it). Pure logic for testing.
     */
    fun find(entries: List<Entry>, contact: String?, textHint: String?): Entry? {
        val c = contact?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val cDigits = c.filter { it.isDigit() }
        val words = textHint?.lowercase(Locale.ROOT)?.split(Regex("\\s+"))
            ?.map { it.trim('.', ',', '?', '!', '"', '\'') }?.filter { it.length >= 3 }.orEmpty()
        return entries.sortedByDescending { it.sentAt }.firstOrNull { e ->
            val contactOk = c.isEmpty() ||
                e.contact.lowercase(Locale.ROOT).contains(c) ||
                c.contains(e.contact.lowercase(Locale.ROOT)) ||
                (cDigits.length >= 4 && e.digits.endsWith(cDigits.takeLast(10)))
            val textOk = words.isEmpty() || words.any { e.text.lowercase(Locale.ROOT).contains(it) }
            contactOk && textOk
        }
    }

    fun canDeleteForEveryone(entry: Entry, now: Long = System.currentTimeMillis()): Boolean =
        now - entry.sentAt < DELETE_FOR_EVERYONE_WINDOW_MS
}
