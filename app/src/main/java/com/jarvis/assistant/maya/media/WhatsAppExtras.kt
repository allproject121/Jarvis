package com.jarvis.assistant.maya.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.notification.JarvisNotificationListener
import com.jarvis.assistant.util.ActivityLauncherHelper
import com.jarvis.assistant.util.ContactCaller
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * WhatsApp features Maya had beyond Jarvis' built-ins (send_whatsapp_message / whatsapp_call /
 * whatsapp_read_messages / reply_to_message): file sending, number sending, draft-only typing,
 * on-screen chat reading, status and screenshot sharing.
 */
object WhatsAppExtras {

    private const val TAG = "WhatsAppExtras"
    const val PKG = "com.whatsapp"
    const val PKG_BUSINESS = "com.whatsapp.w4b"

    sealed class Target {
        data class Resolved(val displayName: String, val phone: String) : Target()
        data class Ambiguous(val query: String, val options: List<String>) : Target()
        data class NotFound(val query: String) : Target()
        object NoPermission : Target()
    }

    fun installedPackage(context: Context): String? = listOf(PKG, PKG_BUSINESS).firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) != null }.getOrDefault(false)
    }

    /** Resolves a contact name or raw number into a WhatsApp-ready phone number. */
    fun resolve(context: Context, contactOrNumber: String?): Target {
        val q = contactOrNumber?.trim().orEmpty()
        if (q.isBlank()) return Target.NotFound(q)
        if (PhoneNumbers.looksLikeNumber(q)) return Target.Resolved(q, PhoneNumbers.normalize(q))
        if (!ContactCaller.hasContactsPermission(context)) return Target.NoPermission
        val matches = ContactCaller.findMatches(context, q)
        if (matches.isEmpty()) return Target.NotFound(q)
        val exact = matches.firstOrNull { it.name.equals(q, ignoreCase = true) }
        val chosen = exact ?: if (matches.size == 1) matches.first() else null
        if (chosen == null) return Target.Ambiguous(q, matches.take(5).map { "${it.name} (${it.number})" })
        val phone = PhoneNumbers.normalize(chosen.number)
        if (phone.isBlank()) return Target.NotFound(q)
        return Target.Resolved(chosen.name, phone)
    }

    fun targetFailure(t: Target): String = when (t) {
        is Target.Ambiguous -> "Which ${t.query}? I found: ${t.options.joinToString(", ")}."
        is Target.NotFound -> "I couldn't find '${t.query}' in your contacts. Give me a phone number instead."
        Target.NoPermission -> "Contacts permission is needed to find that person."
        is Target.Resolved -> ""
    }

    // ---------------- send file ----------------

    /**
     * Shares a local file into a specific WhatsApp chat (ACTION_SEND + `jid` extra) and arms the
     * accessibility service to press Send. Returns a user-facing status.
     */
    fun sendFile(context: Context, target: Target.Resolved, fileRef: String?, caption: String?): Pair<Boolean, String> {
        val pkg = installedPackage(context) ?: return false to "WhatsApp is not installed."
        val resolved = MediaFiles.resolveToUri(context, fileRef)
            ?: return false to "I couldn't find that file (${fileRef ?: "latest photo"}). Give me the path or say 'last photo' / 'screenshot'."
        val (uri, mime) = resolved
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            setPackage(pkg)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra("jid", PhoneNumbers.jid(target.phone))
            if (!caption.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, caption.trim())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            Log.w(TAG, "grantUriPermission failed: ${e.message}")
        }
        return try {
            JarvisAccessibilityService.triggerWhatsAppSend(caption?.trim()?.ifBlank { null })
            val ok = ActivityLauncherHelper.startActivitySafely(context, intent)
            if (!ok) false to "Could not open WhatsApp's share screen."
            else true to "Sharing the file to ${target.displayName} on WhatsApp${if (!caption.isNullOrBlank()) " with caption \"${caption.trim()}\"" else ""}. " +
                (if (JarvisAccessibilityService.isRunning()) "I'll press Send automatically." else "Accessibility service is off, so please tap Send yourself.")
        } catch (e: Exception) {
            Log.e(TAG, "sendFile failed: ${e.message}", e)
            false to "Could not share the file: ${e.message}"
        }
    }

    // ---------------- send to number / write draft ----------------

    fun openChat(context: Context, phone: String, prefillText: String?, autoSend: Boolean): Pair<Boolean, String> {
        val pkg = installedPackage(context) ?: return false to "WhatsApp is not installed."
        val encoded = if (prefillText.isNullOrBlank()) "" else "&text=" + Uri.encode(prefillText.trim())
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$phone$encoded")).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return try {
            if (autoSend && !prefillText.isNullOrBlank()) JarvisAccessibilityService.triggerWhatsAppSend(prefillText.trim())
            val ok = ActivityLauncherHelper.startActivitySafely(context, intent)
            ok to (if (ok) "opened" else "Could not open WhatsApp.")
        } catch (e: Exception) {
            false to "Could not open WhatsApp: ${e.message}"
        }
    }

    // ---------------- read chat from screen ----------------

    /** Opens the chat (if a target is given), waits, then reads the visible message rows. */
    suspend fun readChat(context: Context, target: Target.Resolved?, count: Int): Pair<Boolean, JSONObject> {
        val svc = JarvisAccessibilityService.instance
            ?: return false to JSONObject().put("message", "Accessibility service is off; I can't read the chat screen. Enable Jarvis in Settings → Accessibility, or use whatsapp_read_messages for notifications.")
        if (target != null) {
            val (ok, msg) = openChat(context, target.phone, null, autoSend = false)
            if (!ok) return false to JSONObject().put("message", msg)
            delay(2600)
        }
        val root = svc.getActiveRootNode() ?: return false to JSONObject().put("message", "Nothing readable on screen right now.")
        val pkg = root.packageName?.toString().orEmpty()
        if (!pkg.startsWith("com.whatsapp")) {
            return false to JSONObject().put("message", "WhatsApp is not in the foreground (current app: $pkg).")
        }
        val lines = mutableListOf<String>()
        collectTexts(root, lines, 0)
        val cleaned = lines.map { it.trim() }
            .filter { it.length in 1..600 }
            .filterNot { it.equals("Type a message", true) || it.equals("Message", true) || it.equals("Voice message", true) }
            .distinct()
        val last = cleaned.takeLast(count.coerceIn(1, 60))
        val arr = JSONArray(last)
        val summary = if (last.isEmpty()) "The chat screen has no readable messages." else "Last ${last.size} lines from the ${target?.displayName ?: "open"} chat:\n" + last.joinToString("\n")
        return true to JSONObject().put("message", summary).put("lines", arr).put("contact", target?.displayName ?: "")
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 40) return
        val txt = node.text?.toString()
        if (!txt.isNullOrBlank()) out.add(txt)
        for (i in 0 until node.childCount) collectTexts(node.getChild(i), out, depth + 1)
    }

    // ---------------- status ----------------

    fun status(context: Context): JSONObject {
        val prefs = MediaPrefs.get(context)
        val recent = JarvisNotificationListener.recentNotifications.value.filter { it.packageName.startsWith("com.whatsapp") }
        return JSONObject().apply {
            put("installed_package", installedPackage(context) ?: JSONObject.NULL)
            put("accessibility_running", JarvisAccessibilityService.isRunning())
            put("notification_access", JarvisNotificationListener.isNotificationServiceEnabled(context))
            put("auto_reply_enabled", prefs.autoReplyEnabled)
            put("support_mode", prefs.autoReplySupportMode)
            put("away_message", prefs.autoReplyAwayMessage)
            put("handled_contacts", JSONArray(prefs.handledContacts().keys.toList()))
            put("recent_unread", JSONArray(recent.take(10).map { JSONObject().put("from", it.sender).put("text", it.text).put("at", it.timestamp) }))
            put("replies_sent_this_session", WhatsAppAutoReplyEngine.repliesSent())
        }
    }

    fun statusSummary(context: Context): String {
        val s = status(context)
        val sb = StringBuilder()
        sb.append(if (s.isNull("installed_package")) "WhatsApp is not installed. " else "WhatsApp (${s.optString("installed_package")}) is installed. ")
        sb.append("Accessibility ${if (s.optBoolean("accessibility_running")) "on" else "off"}, notification access ${if (s.optBoolean("notification_access")) "on" else "off"}. ")
        sb.append("Auto-reply ${if (s.optBoolean("auto_reply_enabled")) "ON" else "off"}")
        if (s.optBoolean("support_mode")) sb.append(" (customer-support mode)")
        sb.append(". ")
        val handled = s.optJSONArray("handled_contacts")
        if (handled != null && handled.length() > 0) sb.append("Handling chats for: ").append((0 until handled.length()).joinToString(", ") { handled.optString(it) }).append(". ")
        val unread = s.optJSONArray("recent_unread")
        if (unread != null && unread.length() > 0) {
            sb.append("Recent messages: ")
            sb.append((0 until minOf(3, unread.length())).joinToString("; ") { i -> val o = unread.optJSONObject(i); "${o.optString("from")}: ${o.optString("text").take(60)}" })
            sb.append(".")
        }
        return sb.toString().trim()
    }

    // ---------------- screenshot ----------------

    suspend fun sendScreenshot(context: Context, target: Target.Resolved, caption: String?): Pair<Boolean, String> {
        val bmp = JarvisAccessibilityService.captureScreenBitmap()
        val saved = if (bmp != null) MediaFiles.saveImage(context, bmp, "JARVIS_SHOT", quality = 90) else {
            val jpeg = JarvisAccessibilityService.captureScreenJpeg(85)
            if (jpeg != null) MediaFiles.saveImageBytes(context, jpeg, "JARVIS_SHOT") else null
        }
        bmp?.recycle()
        if (saved == null) return false to "Couldn't capture the screen (needs the accessibility service on Android 11+, or an active screen share)."
        delay(400)
        return sendFile(context, target, saved.uri.toString(), caption)
    }
}
