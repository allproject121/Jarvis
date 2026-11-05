package com.jarvis.assistant.maya.business

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** content.json - every draft / approved / scheduled / published item. calendar.json mirrors the scheduled ones. */
class ContentStore(private val context: Context) {
    companion object {
        const val FILE = "content.json"
        const val CALENDAR_FILE = "calendar.json"
    }

    fun all(): List<ContentItem> = JsonFiles.toList(JsonFiles.readArray(context, FILE)).map { ContentItem.fromJson(it) }

    fun get(id: String): ContentItem? {
        val q = id.trim()
        if (q.isBlank()) return null
        val items = all()
        return items.firstOrNull { it.id.equals(q, ignoreCase = true) }
            ?: items.firstOrNull { it.title.equals(q, ignoreCase = true) }
            ?: items.firstOrNull { it.title.contains(q, ignoreCase = true) }
    }

    fun add(items: List<ContentItem>) {
        if (items.isEmpty()) return
        JsonFiles.updateArray(context, FILE) { arr ->
            val out = JSONArray()
            items.forEach { out.put(it.toJson()) }
            for (i in 0 until arr.length()) out.put(arr.get(i))
            out
        }
    }

    fun update(item: ContentItem) {
        JsonFiles.updateArray(context, FILE) { arr ->
            val out = JSONArray()
            var replaced = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id") == item.id) { out.put(item.toJson()); replaced = true } else out.put(o)
            }
            if (!replaced) out.put(item.toJson())
            out
        }
        syncCalendar()
    }

    fun delete(id: String): Boolean {
        var removed = false
        JsonFiles.updateArray(context, FILE) { arr ->
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id").equals(id, ignoreCase = true)) removed = true else out.put(o)
            }
            out
        }
        if (removed) syncCalendar()
        return removed
    }

    fun scheduled(): List<ContentItem> = all().filter { it.scheduledAt > 0L && it.status != ContentItem.STATUS_PUBLISHED }.sortedBy { it.scheduledAt }

    /** Keeps calendar.json (Maya's file name) as a projection of the scheduled items. */
    private fun syncCalendar() {
        val arr = JSONArray()
        scheduled().forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("platform", it.platform).put("kind", it.kind)
                .put("scheduledAt", it.scheduledAt).put("date", DateTimeParse.formatDate(it.scheduledAt)).put("status", it.status))
        }
        JsonFiles.writeArray(context, CALENDAR_FILE, arr)
    }
}

object ContentEngine {
    private const val TAG = "ContentEngine"

    suspend fun createContent(
        context: Context,
        kinds: List<String>,
        platforms: List<String>,
        count: Int,
        topic: String,
        pillar: String,
        extraBrief: String = ""
    ): List<ContentItem> {
        val profile = BusinessProfileStore(context).load()
        val safeKinds = kinds.map { it.lowercase().trim().replace(' ', '_') }.filter { it.isNotBlank() }.ifEmpty { listOf("post") }
        val safePlatforms = platforms.map { it.lowercase().trim() }.filter { it.isNotBlank() }
            .ifEmpty { profile.platforms.split(',', '/', ' ').map { it.trim().lowercase() }.filter { it in ContentItem.PLATFORMS }.ifEmpty { listOf("instagram") } }
        val n = count.coerceIn(1, 12)
        val prompt = buildString {
            append(profile.promptBlock()).append("\n\n")
            append("TASK: write ").append(n).append(" content item(s). Kinds: ").append(safeKinds.joinToString(", "))
            append(". Platforms: ").append(safePlatforms.joinToString(", ")).append(".\n")
            if (topic.isNotBlank()) append("Topic / brief: ").append(topic).append('\n')
            if (pillar.isNotBlank()) append("Content pillar: ").append(pillar).append('\n')
            if (extraBrief.isNotBlank()) append(extraBrief).append('\n')
            append("Rotate kinds and platforms across the items. A reel_script is 15-60 s with SCENE lines; a carousel has 5-8 SLIDE lines ")
            append("(slide 1 = hook, last slide = CTA). Captions in the business's content language (default Hinglish, Roman script). ")
            append("Only make claims from the profile.\n\n")
            append(BusinessPrompts.CONTENT_FORMAT)
        }
        val raw = BusinessRuntime.llm(context, BusinessPrompts.SYSTEM_MARKETER, prompt, 0.8, 2200)
        var items = ContentItemParser.parse(raw, safeKinds.first(), safePlatforms.first(), topic)
        if (items.isEmpty() && raw.isNotBlank()) {
            // Model ignored the format: keep the text as a single draft so nothing is lost.
            items = listOf(ContentItem(kind = safeKinds.first(), platform = safePlatforms.first(), pillar = pillar,
                title = topic.ifBlank { "Draft" }.take(60), caption = raw.take(2200), topic = topic))
        }
        if (items.isNotEmpty()) ContentStore(context).add(items)
        return items
    }

    suspend fun writeVideoScript(context: Context, topic: String, lengthSeconds: Int, platform: String, audience: String): ContentItem? {
        val profile = BusinessProfileStore(context).load()
        val secs = lengthSeconds.coerceIn(15, 900)
        val prompt = buildString {
            append(profile.promptBlock()).append("\n\n")
            append("TASK: write ONE video script of about ").append(secs).append(" seconds for ").append(platform.ifBlank { "instagram" })
            append(" on: ").append(topic).append('\n')
            if (audience.isNotBlank()) append("Audience / vibe: ").append(audience).append('\n')
            append("Use KIND: reel_script. Give HOOK (first 2 seconds), then one SCENE line per shot in the form ")
            append("'time | what we see | on-screen text | voiceover' covering the whole length, then CAPTION, HASHTAGS, CTA.\n\n")
            append(BusinessPrompts.CONTENT_FORMAT)
        }
        val raw = BusinessRuntime.llm(context, BusinessPrompts.SYSTEM_MARKETER, prompt, 0.8, 2200)
        val item = ContentItemParser.parse(raw, "reel_script", platform.ifBlank { "instagram" }, topic).firstOrNull()
            ?: if (raw.isNotBlank()) ContentItem(kind = "reel_script", platform = platform.ifBlank { "instagram" }, title = topic.take(60), caption = raw.take(3000), topic = topic) else null
        if (item != null) ContentStore(context).add(listOf(item))
        return item
    }

    suspend fun suggestStoryIdeas(context: Context, topic: String, count: Int): String {
        val profile = BusinessProfileStore(context).load()
        val prompt = buildString {
            append(profile.promptBlock()).append("\n\n")
            append("Give ").append(count.coerceIn(1, 6)).append(" Instagram/WhatsApp STORY ideas for today")
            if (topic.isNotBlank()) append(" around: ").append(topic)
            append(". Each idea: one line 'IDEA n: <what the story shows> | on-screen text: <text> | sticker/poll: <optional>'. ")
            append("Simple Hinglish. A story is a photo/quote poster with short text - no AI image unless asked. ")
            append("Add a last line: 'Ye sirf idea hai - approve karo to likh dungi.'")
        }
        return BusinessRuntime.llm(context, BusinessPrompts.SYSTEM_MARKETER, prompt, 0.9, 600)
    }

    fun approve(context: Context, id: String): ContentItem? {
        val store = ContentStore(context)
        val item = store.get(id) ?: return null
        val updated = item.copy(status = if (item.scheduledAt > 0) ContentItem.STATUS_SCHEDULED else ContentItem.STATUS_APPROVED)
        store.update(updated)
        return updated
    }

    fun schedule(context: Context, id: String, at: Long): ContentItem? {
        val store = ContentStore(context)
        val item = store.get(id) ?: return null
        val updated = item.copy(status = ContentItem.STATUS_SCHEDULED, scheduledAt = at)
        store.update(updated)
        PostReminderReceiver.arm(context, updated)
        return updated
    }

    fun unschedule(context: Context, id: String): ContentItem? {
        val store = ContentStore(context)
        val item = store.get(id) ?: return null
        PostReminderReceiver.disarm(context, item)
        val updated = item.copy(status = ContentItem.STATUS_APPROVED, scheduledAt = 0L)
        store.update(updated)
        return updated
    }

    fun markPublished(context: Context, id: String): ContentItem? {
        val store = ContentStore(context)
        val item = store.get(id) ?: return null
        PostReminderReceiver.disarm(context, item)
        val updated = item.copy(status = ContentItem.STATUS_PUBLISHED, publishedAt = System.currentTimeMillis())
        store.update(updated)
        return updated
    }

    /**
     * Hands an APPROVED item to the platform app: caption to the clipboard, share sheet opened with the
     * text. Returns Maya's hand-off message. The owner presses the post button; status stays as is until
     * they confirm ("ye post ho gaya" -> markPublished).
     */
    fun publish(context: Context, id: String, imagePath: String = "", force: Boolean = false): Pair<Boolean, String> {
        val store = ContentStore(context)
        val item = store.get(id) ?: return false to "Item '$id' nahi mila. content_calendar(action='show') se id dekho."
        if (!force && item.status == ContentItem.STATUS_DRAFT) {
            return false to "'${item.title}' abhi Draft hai - pehle approve karo (content_calendar action='approve'). ${BusinessRuntime.SUGGESTION_NOTE}"
        }
        val text = item.shareText()
        SocialShare.copyToClipboard(context, "Caption", text)
        val opened = if (imagePath.isNotBlank()) SocialShare.shareImage(context, item.platform, imagePath, text, story = item.kind == "story")
        else SocialShare.shareText(context, item.platform, text)
        if (opened.startsWith("FAILED")) {
            return false to "HANDED_OFF (partial) - app nahi khula ($opened), lekin caption clipboard me copy hai. ${BusinessPrompts.PUBLISH_HANDOFF}"
        }
        val appLabel = if (opened == "chooser") "share sheet" else item.platform
        val what = when (item.kind) {
            "reel_script" -> "HANDED_OFF - ye reel ka SCRIPT hai, video nahi. Caption aur hashtags clipboard me copy kar diye. Reel shoot karke 'post_to_social' se daal sakte ho. Maine kuch post NAHI kiya; ho jaaye to bolo 'ye post ho gaya'."
            else -> "HANDED_OFF - '${item.title}' ke liye $appLabel khul gaya hai, ${BusinessPrompts.PUBLISH_HANDOFF}"
        }
        return true to what
    }
}

/**
 * AlarmManager reminder for a scheduled post. When it fires it NOTIFIES the owner, copies the caption
 * and tells the live session - it never publishes unattended (phone is usually locked and a public post
 * cannot be undone).
 */
class PostReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PostReminderReceiver"
        const val ACTION = "com.jarvis.assistant.maya.business.POST_READY"
        const val EXTRA_ID = "content_id"

        private fun requestCode(item: ContentItem): Int = 0x5000_0000 or (item.id.hashCode() and 0x0FFF_FFFF)

        private fun pending(context: Context, item: ContentItem): PendingIntent {
            val intent = Intent(context, PostReminderReceiver::class.java).apply {
                action = ACTION
                putExtra(EXTRA_ID, item.id)
            }
            return PendingIntent.getBroadcast(context, requestCode(item), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun arm(context: Context, item: ContentItem) {
            if (item.scheduledAt <= 0L) return
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pending(context, item)
            try {
                val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
                if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.scheduledAt, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.scheduledAt, pi)
                Log.i(TAG, "Armed post reminder for ${item.id} at ${DateTimeParse.formatDateTime(item.scheduledAt)}")
            } catch (e: Exception) {
                Log.w(TAG, "arm failed: ${e.message}")
            }
        }

        fun disarm(context: Context, item: ContentItem) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(pending(context, item))
            } catch (_: Exception) { }
        }

        /** Re-arms every future scheduled post (call after boot / app start). */
        fun rearmAll(context: Context) {
            val now = System.currentTimeMillis()
            ContentStore(context).scheduled().filter { it.scheduledAt > now }.forEach { arm(context, it) }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val item = ContentStore(context).get(id) ?: return
        val text = item.shareText()
        SocialShare.copyToClipboard(context, "Caption", text)
        val title = "Post ready: ${item.title}"
        val body = "${item.platform} ${item.kind} - caption clipboard me hai. Business > Calendar se publish karo (post ka button aapko dabana hai)."
        val event = "Scheduled post ready hai: '${item.title}' (${item.platform} ${item.kind}, id ${item.id}). Caption clipboard me copy ho gaya. " +
            "Maine post NAHI kiya - user se poochho ki abhi publish_content(id='${item.id}') se app kholun?"
        BusinessEvents.notifyAndInject(context, 0x5100 + (id.hashCode() and 0xFF), title, body, event, BusinessActivity.TAB_CALENDAR)
    }
}
