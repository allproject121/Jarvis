package com.jarvis.assistant.maya.business

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.AiTextSynthesizer
import com.jarvis.assistant.ai.provider.AiToolCall
import com.jarvis.assistant.automation.dispatcher.JarvisToolDispatcher
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.util.ActivityLauncherHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Shared background scope for every long-running business job (missions, research, plans). */
object BusinessRuntime {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    const val TAG = "MayaBusiness"

    /** Hinglish note Maya appends to every suggestion. */
    const val SUGGESTION_NOTE = "Ye AI suggestion hai - approve karne ke baad hi schedule ya publish hoga."

    suspend fun llm(
        context: Context,
        system: String,
        prompt: String,
        temperature: Double = 0.6,
        maxTokens: Int = 1800
    ): String = try {
        AiTextSynthesizer.synthesizeText(context, system, prompt, temperature, maxTokens, fallback = { "" }).trim()
    } catch (e: Exception) {
        Log.w(TAG, "LLM call failed: ${e.message}")
        ""
    }

    data class SearchHit(val title: String, val snippet: String, val url: String)
    data class SearchOutcome(val query: String, val answer: String, val hits: List<SearchHit>)

    /**
     * Runs the dispatcher's `search_web` pipeline. The dispatcher holds a non-reentrant mutex
     * while a tool runs, so this must ONLY be called from a background job that was launched
     * by a tool handler - never synchronously inside the handler itself.
     */
    suspend fun searchWeb(context: Context, query: String): SearchOutcome {
        return try {
            val call = AiToolCall("biz-" + System.nanoTime(), "search_web", JSONObject().put("query", query))
            val res = JarvisToolDispatcher.getInstance(context).dispatchTool(call)
            val json = res.resultJson
            val hits = ArrayList<SearchHit>()
            val arr = json.optJSONArray("results")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    hits.add(SearchHit(o.optString("title"), o.optString("snippet"), o.optString("url")))
                }
            }
            val answer = json.optString("answer", json.optString("message", json.optString("result", "")))
            SearchOutcome(query, answer, hits)
        } catch (e: Exception) {
            Log.w(TAG, "search_web failed for '$query': ${e.message}")
            SearchOutcome(query, "", emptyList())
        }
    }
}

/** Compact search context block for LLM prompts. */
fun BusinessRuntime.SearchOutcome.asContext(maxChars: Int = 2500): String {
    val sb = StringBuilder()
    sb.append("Query: ").append(query).append('\n')
    if (hits.isEmpty()) sb.append(answer.take(maxChars)).append('\n')
    for (h in hits) {
        sb.append("- ").append(h.title)
        if (h.snippet.isNotBlank()) sb.append(": ").append(h.snippet.take(300))
        if (h.url.isNotBlank()) sb.append(" [").append(h.url).append("]")
        sb.append('\n')
        if (sb.length > maxChars) break
    }
    return sb.toString()
}

/** Tiny thread-safe JSON file store in `context.filesDir` (business.json, content.json, ...). */
object JsonFiles {
    private val locks = ConcurrentHashMap<String, Any>()
    private fun lock(name: String): Any = locks.getOrPut(name) { Any() }

    fun readArray(context: Context, name: String): JSONArray = synchronized(lock(name)) {
        try {
            val f = File(context.filesDir, name)
            if (!f.exists()) JSONArray() else JSONArray(f.readText())
        } catch (e: Exception) {
            Log.w(BusinessRuntime.TAG, "read $name failed: ${e.message}")
            JSONArray()
        }
    }

    fun writeArray(context: Context, name: String, arr: JSONArray) = synchronized(lock(name)) {
        try {
            val f = File(context.filesDir, name)
            val tmp = File(context.filesDir, "$name.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.writeText(arr.toString()); tmp.delete() }
        } catch (e: Exception) {
            Log.w(BusinessRuntime.TAG, "write $name failed: ${e.message}")
        }
    }

    fun readObject(context: Context, name: String): JSONObject = synchronized(lock(name)) {
        try {
            val f = File(context.filesDir, name)
            if (!f.exists()) JSONObject() else JSONObject(f.readText())
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun writeObject(context: Context, name: String, obj: JSONObject) = synchronized(lock(name)) {
        try {
            File(context.filesDir, name).writeText(obj.toString())
        } catch (e: Exception) {
            Log.w(BusinessRuntime.TAG, "write $name failed: ${e.message}")
        }
    }

    /** Updates one array file atomically with respect to other callers of this helper. */
    fun updateArray(context: Context, name: String, block: (JSONArray) -> JSONArray) = synchronized(lock(name)) {
        writeArray(context, name, block(readArray(context, name)))
    }

    fun toList(arr: JSONArray): List<JSONObject> {
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        return out
    }
}

/** The owner's business, as Maya keeps it: approved facts the assistant must not go beyond. */
data class BusinessProfile(
    val name: String = "",
    val type: String = "",        // what you sell / do
    val audience: String = "",
    val location: String = "",
    val tone: String = "",
    val platforms: String = "",
    val claims: String = "",      // claims the owner allows in copy
    val phone: String = "",
    val website: String = "",
    val hours: String = "",
    val language: String = "Hinglish",
    val offers: String = "",
    val reviewLink: String = "",
    val updatedAt: Long = 0L
) {
    fun isEmpty(): Boolean = name.isBlank() && type.isBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("type", type); put("audience", audience); put("location", location)
        put("tone", tone); put("platforms", platforms); put("claims", claims); put("phone", phone)
        put("website", website); put("hours", hours); put("language", language); put("offers", offers)
        put("reviewLink", reviewLink); put("updatedAt", updatedAt)
    }

    /** Compact block injected into every content / marketing prompt. */
    fun promptBlock(): String {
        if (isEmpty()) return "BUSINESS PROFILE: (none saved - keep claims generic, invent nothing)"
        val sb = StringBuilder("BUSINESS PROFILE (approved facts - do not invent services, prices or offers beyond these):\n")
        fun line(k: String, v: String) { if (v.isNotBlank()) sb.append("- ").append(k).append(": ").append(v).append('\n') }
        line("Name", name); line("What we sell / do", type); line("Target audience", audience); line("Location", location)
        line("Tone", tone); line("Platforms", platforms); line("Claims allowed", claims); line("Current offers", offers)
        line("Phone", phone); line("Website", website); line("Hours", hours); line("Content language", language)
        return sb.toString().trimEnd()
    }

    fun render(): String {
        val sb = StringBuilder()
        sb.append("# ").append(name.ifBlank { "Business" }).append('\n')
        fun line(k: String, v: String) { sb.append("- ").append(k).append(": ").append(v.ifBlank { "-" }).append('\n') }
        line("Type / what you sell", type); line("Audience", audience); line("Location", location); line("Tone", tone)
        line("Platforms", platforms); line("Claims allowed", claims); line("Offers", offers); line("Phone", phone)
        line("Website", website); line("Hours", hours); line("Language", language); line("Review link", reviewLink)
        return sb.toString().trimEnd()
    }

    companion object {
        val FIELDS = listOf("name", "type", "audience", "location", "tone", "platforms", "claims", "phone", "website", "hours", "language", "offers", "review_link")

        fun fromJson(o: JSONObject) = BusinessProfile(
            name = o.optString("name"), type = o.optString("type"), audience = o.optString("audience"),
            location = o.optString("location"), tone = o.optString("tone"), platforms = o.optString("platforms"),
            claims = o.optString("claims"), phone = o.optString("phone"), website = o.optString("website"),
            hours = o.optString("hours"), language = o.optString("language", "Hinglish"), offers = o.optString("offers"),
            reviewLink = o.optString("reviewLink"), updatedAt = o.optLong("updatedAt")
        )
    }
}

class BusinessProfileStore(private val context: Context) {
    companion object {
        const val FILE = "business.json"
        @Volatile private var cached: BusinessProfile? = null
    }

    fun load(): BusinessProfile {
        cached?.let { return it }
        val p = BusinessProfile.fromJson(JsonFiles.readObject(context, FILE))
        cached = p
        return p
    }

    fun save(p: BusinessProfile) {
        val stamped = p.copy(updatedAt = System.currentTimeMillis())
        cached = stamped
        JsonFiles.writeObject(context, FILE, stamped.toJson())
    }

    fun clear() {
        cached = BusinessProfile()
        JsonFiles.writeObject(context, FILE, JSONObject())
    }

    /** Applies `key=value` pairs (Maya's `fields='type=...; location=...'` style) plus named args. */
    fun applyFields(base: BusinessProfile, fields: Map<String, String>): BusinessProfile {
        var p = base
        for ((kRaw, v) in fields) {
            val k = kRaw.trim().lowercase().replace(' ', '_')
            val value = v.trim()
            if (value.isBlank()) continue
            p = when (k) {
                "name", "business", "business_name" -> p.copy(name = value)
                "type", "what", "what_you_sell", "services", "service", "sell", "kaam" -> p.copy(type = value)
                "audience", "target", "target_audience", "customers" -> p.copy(audience = value)
                "location", "city", "sheher", "area" -> p.copy(location = value)
                "tone", "voice", "style" -> p.copy(tone = value)
                "platforms", "platform", "channels" -> p.copy(platforms = value)
                "claims", "claims_allowed", "allowed_claims", "usp" -> p.copy(claims = value)
                "phone", "number", "mobile", "whatsapp" -> p.copy(phone = value)
                "website", "site", "url" -> p.copy(website = value)
                "hours", "timing", "timings", "open_hours" -> p.copy(hours = value)
                "language", "lang", "content_language" -> p.copy(language = value)
                "offers", "offer", "current_offers" -> p.copy(offers = value)
                "review_link", "reviewlink", "google_review", "review_url" -> p.copy(reviewLink = value)
                else -> p
            }
        }
        return p
    }

    fun parseFieldString(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (raw.isBlank()) return out
        // Accept JSON objects too.
        val t = raw.trim()
        if (t.startsWith("{")) {
            try {
                val o = JSONObject(t)
                for (k in o.keys()) out[k] = o.optString(k)
                return out
            } catch (_: Exception) { }
        }
        for (part in t.split(';', '\n')) {
            val idx = part.indexOfFirst { it == '=' || it == ':' }
            if (idx <= 0) continue
            out[part.substring(0, idx).trim()] = part.substring(idx + 1).trim()
        }
        return out
    }
}

/** Notifications + "[SYSTEM EVENT]" injection into the live session when a background job ends. */
object BusinessEvents {
    const val CHANNEL_ID = "jarvis_business"
    private const val TAG = "BusinessEvents"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "JARVIS Business", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Content reminders, research, missions and price alerts"
                    }
                )
            }
        }
    }

    fun notify(context: Context, id: Int, title: String, text: String, tab: String? = null) {
        try {
            ensureChannel(context)
            val open = Intent(context, BusinessActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (tab != null) putExtra(BusinessActivity.EXTRA_TAB, tab)
            }
            val pi = PendingIntent.getActivity(
                context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sparkle)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
        } catch (e: Exception) {
            Log.w(TAG, "notify failed: ${e.message}")
        }
    }

    /** Injects a system-event line into the live Gemini session if one is on. Returns true if sent. */
    fun injectSystemEvent(text: String): Boolean {
        return try {
            if (JarvisConversationService.isSessionOn.value) {
                JarvisConversationService.sendTextMessage("[SYSTEM EVENT] $text")
                true
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "inject failed: ${e.message}")
            false
        }
    }

    fun notifyAndInject(context: Context, id: Int, title: String, text: String, event: String, tab: String? = null) {
        notify(context, id, title, text, tab)
        injectSystemEvent(event)
    }
}

/** Human date/time parsing for `schedule_post`, `kpi_log` etc. */
object DateTimeParse {
    private val ISO_PATTERNS = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "dd-MM-yyyy HH:mm", "dd/MM/yyyy HH:mm", "yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy")
    private val TIME = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|baje)?", RegexOption.IGNORE_CASE)

    fun formatDate(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
    fun formatDateTime(millis: Long): String = SimpleDateFormat("EEE dd MMM, HH:mm", Locale.US).format(Date(millis))

    fun parseDate(raw: String?, default: Long = System.currentTimeMillis()): String {
        if (raw.isNullOrBlank()) return formatDate(default)
        val t = raw.trim().lowercase()
        val cal = Calendar.getInstance()
        when {
            t == "today" || t == "aaj" -> return formatDate(cal.timeInMillis)
            t == "yesterday" || t == "kal" -> { cal.add(Calendar.DAY_OF_YEAR, -1); return formatDate(cal.timeInMillis) }
        }
        val ms = parseDateTime(raw) ?: return formatDate(default)
        return formatDate(ms)
    }

    /** Returns epoch millis or null. Handles ISO, "today 18:00", "tomorrow 9am", "in 2 hours", "kal 10 baje". */
    fun parseDateTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim()
        t.toLongOrNull()?.let { if (it > 1_000_000_000_000L) return it }
        for (p in ISO_PATTERNS) {
            try {
                val f = SimpleDateFormat(p, Locale.US).apply { isLenient = false }
                val d = f.parse(t)
                if (d != null) return d.time
            } catch (_: Exception) { }
        }
        val lower = t.lowercase()
        val cal = Calendar.getInstance()
        val rel = Regex("in\\s+(\\d+)\\s*(min|minute|minutes|hour|hours|hr|hrs|day|days|ghante|ghanta)").find(lower)
            ?: Regex("(\\d+)\\s*(min|minute|minutes|hour|hours|hr|hrs|day|days|ghante|ghanta)\\s*(baad|later|me)").find(lower)
        if (rel != null) {
            val n = rel.groupValues[1].toInt()
            val unit = rel.groupValues[2]
            when {
                unit.startsWith("min") -> cal.add(Calendar.MINUTE, n)
                unit.startsWith("h") || unit.startsWith("gh") -> cal.add(Calendar.HOUR_OF_DAY, n)
                else -> cal.add(Calendar.DAY_OF_YEAR, n)
            }
            return cal.timeInMillis
        }
        var dayOffset = 0
        when {
            lower.contains("day after") || lower.contains("parso") -> dayOffset = 2
            lower.contains("tomorrow") || lower.startsWith("kal") || lower.contains(" kal ") -> dayOffset = 1
            lower.contains("today") || lower.contains("aaj") -> dayOffset = 0
            lower.contains("next week") -> dayOffset = 7
        }
        val timePart = lower.replace(Regex("today|tomorrow|aaj|kal|parso|day after|next week|at|ko|subah|shaam|raat"), " ").trim()
        val m = TIME.find(timePart)
        if (m == null && dayOffset == 0 && !lower.contains("today") && !lower.contains("aaj")) return null
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        var hour = 9
        var minute = 0
        if (m != null) {
            hour = m.groupValues[1].toInt()
            minute = m.groupValues[2].toIntOrNull() ?: 0
            val ampm = m.groupValues[3].lowercase()
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            if (ampm.isBlank() || ampm == "baje") {
                if (lower.contains("shaam") || lower.contains("evening") || lower.contains("raat") || lower.contains("night")) {
                    if (hour < 12) hour += 12
                }
            }
        }
        cal.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        cal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (dayOffset == 0 && cal.timeInMillis < System.currentTimeMillis() && m != null && !lower.contains("today") && !lower.contains("aaj")) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}

/**
 * Share-sheet hand-off to the social apps. Nothing here publishes: the app opens with the text
 * (and optionally an image) loaded, and the OWNER presses the post button.
 */
object SocialShare {
    private const val TAG = "SocialShare"

    fun packageFor(platform: String): List<String> = when (platform.lowercase().trim()) {
        "instagram", "insta", "ig" -> listOf("com.instagram.android")
        "facebook", "fb" -> listOf("com.facebook.katana", "com.facebook.lite")
        "linkedin" -> listOf("com.linkedin.android")
        "x", "twitter" -> listOf("com.twitter.android")
        "whatsapp", "wa" -> listOf("com.whatsapp", "com.whatsapp.w4b")
        "youtube", "yt" -> listOf("com.google.android.youtube")
        "telegram" -> listOf("org.telegram.messenger")
        else -> emptyList()
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    fun installedPackageFor(context: Context, platform: String): String? =
        packageFor(platform).firstOrNull { isInstalled(context, it) }

    fun copyToClipboard(context: Context, label: String, text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (e: Exception) {
            Log.w(TAG, "clipboard failed: ${e.message}")
        }
    }

    /** Opens the platform app (or the chooser) with [text] in an ACTION_SEND. Returns the app that was opened. */
    fun shareText(context: Context, platform: String, text: String): String {
        val pkg = installedPackageFor(context, platform)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) setPackage(pkg)
        }
        return launch(context, intent, pkg, platform)
    }

    /** Shares an image file (gallery path or app file) with caption. */
    fun shareImage(context: Context, platform: String, imagePath: String, caption: String, story: Boolean = false): String {
        val uri = uriFor(context, imagePath) ?: return "FAILED: image nahi mila: $imagePath"
        val pkg = installedPackageFor(context, platform)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (imagePath.lowercase().endsWith(".mp4")) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (pkg != null) setPackage(pkg)
        }
        if (story && pkg == "com.instagram.android") {
            // Instagram's story intent: opens the story composer with the media loaded.
            val storyIntent = Intent("com.instagram.share.ADD_TO_STORY").apply {
                setDataAndType(uri, intent.type)
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (context.packageManager.resolveActivity(storyIntent, 0) != null) {
                return launch(context, storyIntent, pkg, platform)
            }
        }
        return launch(context, intent, pkg, platform)
    }

    private fun launch(context: Context, intent: Intent, pkg: String?, platform: String): String {
        return try {
            if (pkg != null && context.packageManager.resolveActivity(intent, 0) != null) {
                ActivityLauncherHelper.startActivitySafely(context, intent)
                pkg
            } else {
                intent.setPackage(null)
                val chooser = Intent.createChooser(intent, "Share to $platform").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                ActivityLauncherHelper.startActivitySafely(context, chooser)
                "chooser"
            }
        } catch (e: Exception) {
            Log.w(TAG, "share failed: ${e.message}")
            "FAILED: ${e.message}"
        }
    }

    private fun uriFor(context: Context, path: String): Uri? {
        if (path.startsWith("content://")) return Uri.parse(path)
        val file = File(path)
        if (!file.exists()) return null
        // 1. Our own FileProvider (internal + external app files, gallery files under allowed roots).
        try {
            return FileProvider.getUriForFile(context, context.packageName + ".maya.business.files", file)
        } catch (_: Exception) { }
        // 2. MediaStore lookup for gallery files.
        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.DATA + "=?",
                arrayOf(file.absolutePath),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    return Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), id.toString())
                }
            }
        } catch (_: Exception) { }
        return Uri.fromFile(file)
    }

    /** Opens a WhatsApp chat with [text] prefilled. The user presses send. */
    fun openWhatsAppChat(context: Context, phone: String, text: String): Boolean {
        var digits = phone.filter { it.isDigit() }
        if (digits.length == 10) digits = "91$digits"
        else if (digits.startsWith("0") && digits.length == 11) digits = "91" + digits.substring(1)
        if (digits.isBlank()) return false
        val url = "https://api.whatsapp.com/send?phone=$digits&text=" + Uri.encode(text)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { isInstalled(context, it) }
        if (pkg != null) intent.setPackage(pkg)
        return try {
            ActivityLauncherHelper.startActivitySafely(context, intent)
            true
        } catch (e: Exception) {
            try {
                intent.setPackage(null)
                ActivityLauncherHelper.startActivitySafely(context, intent)
                true
            } catch (_: Exception) { false }
        }
    }

    fun openEmail(context: Context, to: String, subject: String, body: String): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ActivityLauncherHelper.startActivitySafely(context, intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}

/** FileProvider with its own class name so it never collides with another provider in the manifest. */
class BusinessFileProvider : FileProvider()
