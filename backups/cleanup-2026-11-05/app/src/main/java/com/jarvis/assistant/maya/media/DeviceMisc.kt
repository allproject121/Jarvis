package com.jarvis.assistant.maya.media

import android.Manifest
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.ai.AiTextSynthesizer
import com.jarvis.assistant.util.ActivityLauncherHelper
import com.jarvis.assistant.util.ContactCaller
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Misc Maya device tools that Jarvis/AndroLLM do not already cover. */
object DeviceMisc {

    private const val TAG = "MayaDeviceMisc"

    private fun launch(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ActivityLauncherHelper.startActivitySafely(context, intent) }.getOrDefault(false)
    }

    // ---------------- time / date ----------------

    fun currentTime(timezone: String?): JSONObject {
        val tz = timezone?.trim()?.takeIf { it.isNotBlank() }?.let { resolveTz(it) } ?: TimeZone.getDefault()
        val now = Date()
        val fmt = SimpleDateFormat("h:mm a", Locale.ENGLISH).apply { timeZone = tz }
        val day = SimpleDateFormat("EEEE", Locale.ENGLISH).apply { timeZone = tz }
        return JSONObject()
            .put("time", fmt.format(now))
            .put("timezone", tz.id)
            .put("day", day.format(now))
            .put("message", "Abhi ${fmt.format(now)} baje hain (${tz.id}), ${day.format(now)}.")
    }

    private fun resolveTz(raw: String): TimeZone {
        val aliases = mapOf(
            "india" to "Asia/Kolkata", "ist" to "Asia/Kolkata", "delhi" to "Asia/Kolkata", "mumbai" to "Asia/Kolkata",
            "dubai" to "Asia/Dubai", "uae" to "Asia/Dubai", "london" to "Europe/London", "uk" to "Europe/London",
            "new york" to "America/New_York", "usa" to "America/New_York", "est" to "America/New_York",
            "california" to "America/Los_Angeles", "pst" to "America/Los_Angeles", "tokyo" to "Asia/Tokyo",
            "japan" to "Asia/Tokyo", "singapore" to "Asia/Singapore", "sydney" to "Australia/Sydney",
            "paris" to "Europe/Paris", "germany" to "Europe/Berlin", "berlin" to "Europe/Berlin", "utc" to "UTC", "gmt" to "GMT",
            "nepal" to "Asia/Kathmandu", "pakistan" to "Asia/Karachi", "bangladesh" to "Asia/Dhaka", "canada" to "America/Toronto"
        )
        val key = raw.lowercase(Locale.ROOT)
        aliases[key]?.let { return TimeZone.getTimeZone(it) }
        TimeZone.getAvailableIDs().firstOrNull { it.equals(raw, true) || it.substringAfterLast('/').replace('_', ' ').equals(raw, true) }
            ?.let { return TimeZone.getTimeZone(it) }
        return TimeZone.getTimeZone(raw)
    }

    fun currentDate(): JSONObject {
        val now = Date()
        val full = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(now)
        val cal = Calendar.getInstance()
        return JSONObject()
            .put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now))
            .put("day", SimpleDateFormat("EEEE", Locale.ENGLISH).format(now))
            .put("day_of_year", cal.get(Calendar.DAY_OF_YEAR))
            .put("week_of_year", cal.get(Calendar.WEEK_OF_YEAR))
            .put("message", "Aaj $full hai.")
    }

    // ---------------- timers ----------------

    fun setTimer(context: Context, seconds: Int, label: String?): Pair<Boolean, String> {
        if (seconds <= 0) return false to "Kitne der ka timer? (e.g. '5 minutes')"
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtMost(86_399))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label.trim())
        }
        return if (launch(context, intent)) true to "Timer set for ${TimerParsing.describe(seconds)}${if (!label.isNullOrBlank()) " ($label)" else ""}."
        else false to "No clock app accepted the timer."
    }

    fun cancelTimer(context: Context): Pair<Boolean, String> {
        // There is no public "cancel timer" intent; dismiss the ringing timer if any, else open the timer list.
        val dismiss = Intent("android.intent.action.DISMISS_TIMER")
        if (launch(context, dismiss)) return true to "Dismissed the running timer."
        val show = Intent(AlarmClock.ACTION_SHOW_TIMERS)
        return if (launch(context, show)) true to "Android doesn't let apps cancel timers directly — opened your timers so you can stop it."
        else false to "Couldn't reach the clock app's timers."
    }

    // ---------------- sound / screen ----------------

    fun setRingerMode(context: Context, mode: String): Pair<Boolean, String> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val m = mode.lowercase(Locale.ROOT)
        val target = when {
            m.contains("silent") || m.contains("mute") || m.contains("chup") -> AudioManager.RINGER_MODE_SILENT
            m.contains("vib") -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (target == AudioManager.RINGER_MODE_SILENT && !nm.isNotificationPolicyAccessGranted) {
            launch(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return false to "Silent mode needs Do-Not-Disturb access for Jarvis — opened that setting. Vibrate mode works without it."
        }
        return try {
            am.ringerMode = target
            true to "Phone is now on ${when (target) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal ring" }}."
        } catch (e: SecurityException) {
            launch(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            false to "Android blocked the ringer change; grant Do-Not-Disturb access to Jarvis."
        }
    }

    fun setAutoBrightness(context: Context, enabled: Boolean): Pair<Boolean, String> {
        if (!Settings.System.canWrite(context)) {
            launch(context, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            return false to "Jarvis needs 'Modify system settings' permission — opened it for you."
        }
        return try {
            Settings.System.putInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            true to "Auto brightness ${if (enabled) "on" else "off"}."
        } catch (e: Exception) {
            false to "Couldn't change auto brightness: ${e.message}"
        }
    }

    /** Routes call/voice audio to the earpiece (enabled) or back to normal speaker routing. */
    @Suppress("DEPRECATION")
    fun setEarpieceMode(context: Context, enabled: Boolean): Pair<Boolean, String> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            if (enabled) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    val ear = am.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    if (ear != null) {
                        am.setCommunicationDevice(ear)
                    } else {
                        am.isSpeakerphoneOn = false
                    }
                } else {
                    am.isSpeakerphoneOn = false
                }
                true to "Earpiece mode on — audio now plays from the earpiece."
            } else {
                if (android.os.Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                am.mode = AudioManager.MODE_NORMAL
                true to "Earpiece mode off — back to the speaker."
            }
        } catch (e: Exception) {
            false to "Couldn't switch audio route: ${e.message}"
        }
    }

    fun openSettingsPanel(context: Context, which: String): Pair<Boolean, String> {
        val (action, label) = when (which) {
            "wifi" -> (if (android.os.Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS) to "Wi-Fi"
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"
            "hotspot" -> "android.settings.TETHER_SETTINGS" to "Hotspot & tethering"
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS to "Airplane mode"
            else -> Settings.ACTION_SETTINGS to "Settings"
        }
        if (launch(context, Intent(action))) return true to "Opened $label settings."
        val fallback = if (which == "hotspot") Settings.ACTION_WIRELESS_SETTINGS else Settings.ACTION_SETTINGS
        return if (launch(context, Intent(fallback))) true to "Opened settings (look for $label)." else false to "Couldn't open $label settings."
    }

    // ---------------- contacts ----------------

    private fun hasWriteContacts(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun contactIdFor(context: Context, name: String): Pair<Long, String>? {
        return try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?", arrayOf("%${name.trim()}%"), null
            )?.use { c ->
                var best: Pair<Long, String>? = null
                while (c.moveToNext()) {
                    val n = c.getString(1) ?: continue
                    val p = c.getLong(0) to n
                    if (n.equals(name.trim(), true)) return p
                    if (best == null) best = p
                }
                best
            }
        } catch (e: Exception) {
            null
        }
    }

    fun findEmail(context: Context, name: String): JSONObject {
        if (!ContactCaller.hasContactsPermission(context)) return JSONObject().put("ok", false).put("message", "Contacts permission is needed.")
        val out = JSONArray()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} LIKE ?", arrayOf("%${name.trim()}%"), null
            )?.use { c ->
                while (c.moveToNext() && out.length() < 10) out.put(JSONObject().put("name", c.getString(0)).put("email", c.getString(1)))
            }
        } catch (e: Exception) {
            Log.w(TAG, "findEmail failed: ${e.message}")
        }
        val msg = if (out.length() == 0) "No email address saved for '$name'." else
            (0 until out.length()).joinToString("; ") { "${out.getJSONObject(it).optString("name")}: ${out.getJSONObject(it).optString("email")}" }
        return JSONObject().put("ok", out.length() > 0).put("results", out).put("message", msg)
    }

    fun saveContact(context: Context, name: String, number: String, email: String?): Pair<Boolean, String> {
        if (hasWriteContacts(context)) {
            try {
                val ops = arrayListOf(
                    android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
                    android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name.trim()).build(),
                    android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number.trim())
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build()
                )
                if (!email.isNullOrBlank()) ops.add(
                    android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.trim()).build()
                )
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                return true to "Saved $name ($number) to contacts."
            } catch (e: Exception) {
                Log.w(TAG, "direct save failed, falling back to editor: ${e.message}")
            }
        }
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, name.trim())
            putExtra(ContactsContract.Intents.Insert.PHONE, number.trim())
            if (!email.isNullOrBlank()) putExtra(ContactsContract.Intents.Insert.EMAIL, email.trim())
            putExtra("finishActivityOnSaveCompleted", true)
        }
        return if (launch(context, intent)) true to "Opened the new-contact form with $name / $number filled in — tap Save."
        else false to "Couldn't open the contacts app."
    }

    fun editContact(context: Context, name: String, field: String, value: String): Pair<Boolean, String> {
        val found = contactIdFor(context, name) ?: return false to "No contact named '$name'."
        val (id, display) = found
        val uri = android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, ContactsContract.Contacts.CONTENT_ITEM_TYPE)
            when (field.lowercase(Locale.ROOT)) {
                "phone", "number", "mobile" -> putExtra(ContactsContract.Intents.Insert.PHONE, value)
                "email", "mail" -> putExtra(ContactsContract.Intents.Insert.EMAIL, value)
                "name" -> putExtra(ContactsContract.Intents.Insert.NAME, value)
                "company", "work" -> putExtra(ContactsContract.Intents.Insert.COMPANY, value)
                "note", "notes" -> putExtra(ContactsContract.Intents.Insert.NOTES, value)
                "address" -> putExtra(ContactsContract.Intents.Insert.POSTAL, value)
                else -> putExtra(ContactsContract.Intents.Insert.NOTES, "$field: $value")
            }
            putExtra("finishActivityOnSaveCompleted", true)
        }
        return if (launch(context, intent)) true to "Opened $display's contact with $field = \"$value\" filled in — tap Save to confirm."
        else false to "Couldn't open the contact editor."
    }

    fun setFavorite(context: Context, name: String, starred: Boolean): Pair<Boolean, String> {
        val found = contactIdFor(context, name) ?: return false to "No contact named '$name'."
        val (id, display) = found
        if (hasWriteContacts(context)) {
            try {
                val values = ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0) }
                val n = context.contentResolver.update(ContactsContract.Contacts.CONTENT_URI, values, "${ContactsContract.Contacts._ID}=?", arrayOf(id.toString()))
                if (n > 0) return true to "$display ${if (starred) "added to" else "removed from"} favourites."
            } catch (e: Exception) {
                Log.w(TAG, "star update failed: ${e.message}")
            }
        }
        val view = Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id))
        return if (launch(context, view)) true to "Opened $display's contact — tap the ☆ star to ${if (starred) "add" else "remove"} favourite (Jarvis lacks contacts-write permission)."
        else false to "Couldn't open the contact."
    }

    // ---------------- maps ----------------

    fun showOnMap(context: Context, place: String): Pair<Boolean, String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place.trim())))
        return if (launch(context, intent)) true to "Showing $place on the map." else
            (launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(place)))) to "Opened $place in maps.")
    }

    private val http by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }

    /** Google Places text search when a key is set; otherwise opens a geo: search. */
    suspend fun searchNearby(context: Context, query: String): JSONObject = withContext(Dispatchers.IO) {
        val key = MediaPrefs.get(context).mapsApiKey
        if (key.isNotBlank()) {
            try {
                val url = "https://maps.googleapis.com/maps/api/place/textsearch/json?query=" + Uri.encode("$query near me") + "&key=$key"
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val status = json.optString("status")
                    if (status == "OK") {
                        val results = json.optJSONArray("results") ?: JSONArray()
                        val arr = JSONArray()
                        for (i in 0 until minOf(6, results.length())) {
                            val r = results.getJSONObject(i)
                            arr.put(JSONObject().put("name", r.optString("name")).put("address", r.optString("formatted_address"))
                                .put("rating", r.optDouble("rating", 0.0)).put("open_now", r.optJSONObject("opening_hours")?.optBoolean("open_now")))
                        }
                        val msg = (0 until arr.length()).joinToString("\n") { i ->
                            val r = arr.getJSONObject(i)
                            "${i + 1}. ${r.optString("name")} — ${r.optString("address")}" + (if (r.optDouble("rating") > 0) " (★${r.optDouble("rating")})" else "")
                        }
                        return@withContext JSONObject().put("ok", true).put("results", arr).put("message", "Nearby '$query':\n$msg")
                    }
                    Log.w(TAG, "Places status $status: ${json.optString("error_message")}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Places search failed: ${e.message}")
            }
        }
        val ok = launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))))
        JSONObject().put("ok", ok).put("message", if (ok) "Opened maps searching for '$query' nearby." + (if (key.isBlank()) " (Add a Maps key in Media Studio to get the list read out.)" else "") else "No maps app found.")
    }

    // ---------------- files & documents ----------------

    fun openFile(context: Context, ref: String): Pair<Boolean, String> {
        val r = MediaFiles.resolveToUri(context, ref) ?: return false to "File not found: $ref"
        return if (MediaFiles.openViewer(context, r.first, r.second)) true to "Opened ${ref.substringAfterLast('/')}." else false to "No app can open this file type (${r.second})."
    }

    suspend fun readDocument(context: Context, path: String, maxChars: Int = 6000): JSONObject = withContext(Dispatchers.IO) {
        val file = File(path).takeIf { it.exists() }
            ?: MediaFiles.findFiles(context, path, 1).firstOrNull()?.let { File(it.path) }
            ?: return@withContext JSONObject().put("ok", false).put("message", "Document not found: $path")
        val ext = file.extension.lowercase(Locale.ROOT)
        val text = try {
            when (ext) {
                "pdf" -> {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    PDDocument.load(file).use { doc -> PDFTextStripper().apply { endPage = minOf(doc.numberOfPages, 30) }.getText(doc) }
                }
                "txt", "md", "csv", "log", "json", "xml", "html", "htm", "kt", "java", "py", "js" -> file.readText()
                else -> return@withContext JSONObject().put("ok", false).put("message", "I can read txt/pdf/markdown/csv/json documents; '.$ext' isn't supported.")
            }
        } catch (e: Exception) {
            return@withContext JSONObject().put("ok", false).put("message", "Couldn't read ${file.name}: ${e.message}")
        }
        val clean = text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (clean.isBlank()) return@withContext JSONObject().put("ok", false).put("message", "${file.name} has no extractable text (it may be a scanned PDF).")
        JSONObject().put("ok", true).put("file", file.absolutePath).put("chars", clean.length).put("truncated", clean.length > maxChars)
            .put("text", clean.take(maxChars))
            .put("message", "Read ${file.name} (${clean.length} characters${if (clean.length > maxChars) ", first $maxChars shown" else ""}).")
    }

    // ---------------- fun ----------------

    private val JOKES = listOf(
        "Teacher: Homework kahan hai? Student: Sir, WiFi ne kha liya — signal hi itna weak tha!",
        "Why do programmers prefer dark mode? Because light attracts bugs.",
        "Mummy: Beta phone chhodo, padhai karo. Beta: Mummy, padhai bhi phone pe hi hai. Mummy: Phir phone mujhe do, main padhti hoon.",
        "I told my phone a joke about battery... it didn't get it, it was at 1%.",
        "Doctor: Aapko aaram ki zaroorat hai. Patient: Par mere boss? Doctor: Unhe bhi — aapse.",
        "Why did the scarecrow win an award? He was outstanding in his field."
    )

    suspend fun tellJoke(context: Context, topic: String?): String {
        val fallback = JOKES[(System.currentTimeMillis() % JOKES.size).toInt()]
        return try {
            AiTextSynthesizer.synthesizeText(
                context,
                "You are a witty assistant. Tell ONE short, clean, family-friendly joke in Hinglish (or English if the topic is in English). Output only the joke.",
                "Tell a joke" + (if (!topic.isNullOrBlank()) " about $topic" else "") + ". Make it fresh, max 3 lines.",
                0.95, 120
            ) { fallback }.trim().ifBlank { fallback }
        } catch (e: Exception) {
            fallback
        }
    }
}
