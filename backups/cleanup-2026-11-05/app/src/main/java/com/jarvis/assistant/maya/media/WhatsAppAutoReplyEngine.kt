package com.jarvis.assistant.maya.media

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.AiTextSynthesizer
import com.jarvis.assistant.automation.notification.InterceptedNotification
import com.jarvis.assistant.automation.notification.JarvisNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * WhatsApp auto-reply / customer-support / per-contact handling, Maya style.
 *
 * Wired additively from [JarvisNotificationListener.onNotificationPosted] via [onNotification].
 * Replies go through the notification's own RemoteInput action (no screen automation needed).
 * The signature line "— auto-reply by <assistant>" is always appended and cannot be removed.
 */
object WhatsAppAutoReplyEngine {

    private const val TAG = "WaAutoReply"
    private const val MIN_GAP_PER_SENDER_MS = 90_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastReplyAt = ConcurrentHashMap<String, Long>()
    private val sent = AtomicInteger(0)
    private val log = ArrayDeque<JSONObject>()

    data class ReplyLogEntry(val sender: String, val incoming: String, val reply: String, val at: Long)

    fun repliesSent(): Int = sent.get()

    fun recentLog(): List<JSONObject> = synchronized(log) { log.toList() }

    private fun assistantName(): String = runCatching { JarvisApp.instance.preferences.assistantName }.getOrDefault("JARVIS")

    private fun isGroupMessage(n: InterceptedNotification): Boolean {
        // WhatsApp group notifications carry "Sender: text" bodies or "Sender @ Group" titles.
        return n.sender.contains(" @ ") || n.sender.contains(": ") || Regex("^[^:]{1,40}: ").containsMatchIn(n.text)
    }

    /** Entry point called for every intercepted messaging notification. Never throws. */
    fun onNotification(context: Context, n: InterceptedNotification) {
        try {
            if (!n.packageName.startsWith("com.whatsapp")) return
            if (n.isCall || n.replyAction == null || n.remoteInput == null) return
            if (n.text.isBlank() || n.sender.isBlank() || n.sender == "Someone") return
            if (n.text.startsWith(AutoReplySignature.PREFIX)) return
            val prefs = MediaPrefs.get(context)
            val rule = prefs.handlingRuleFor(n.sender)
            if (!prefs.autoReplyEnabled && rule == null) return
            if (isGroupMessage(n)) return
            val now = System.currentTimeMillis()
            val last = lastReplyAt[n.sender.lowercase(Locale.ROOT)] ?: 0L
            if (now - last < MIN_GAP_PER_SENDER_MS) return
            lastReplyAt[n.sender.lowercase(Locale.ROOT)] = now
            scope.launch {
                val reply = composeReply(context, n.sender, n.text, rule)
                val ok = sendViaRemoteInput(context, n, reply)
                if (ok) {
                    sent.incrementAndGet()
                    synchronized(log) {
                        log.addFirst(JSONObject().put("sender", n.sender).put("incoming", n.text).put("reply", reply).put("at", now))
                        while (log.size > 30) log.removeLast()
                    }
                    Log.i(TAG, "Auto-replied to ${n.sender}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "onNotification error: ${e.message}")
        }
    }

    /** Builds the reply text (AI when available, otherwise the away message) and applies the signature. */
    suspend fun composeReply(context: Context, sender: String, incoming: String, rule: String?): String {
        val prefs = MediaPrefs.get(context)
        val name = assistantName()
        val owner = runCatching { JarvisApp.instance.preferences.userName }.getOrDefault("").ifBlank { "the owner" }
        val away = prefs.autoReplyAwayMessage.ifBlank { "$owner abhi available nahi hai, jaldi reply karega." }
        val style = prefs.autoReplyStyle.ifBlank { "short, polite, same language as the incoming message (Hinglish if Hinglish)" }
        val system = buildString {
            append("You are $name, the phone assistant of $owner, auto-replying on WhatsApp because $owner is away. ")
            append("Write ONLY the reply text (1-3 sentences, no quotes, no preamble). Style: $style. ")
            if (prefs.autoReplySupportMode) {
                append("Customer-support mode: answer politely like a support agent for $owner's business, acknowledge the request, ")
                append("ask for any missing detail, and promise a follow-up. Never invent prices or commitments. ")
            } else {
                append("Default away message to convey if nothing specific applies: \"$away\". ")
            }
            if (!rule.isNullOrBlank()) append("Special instructions for this contact ($sender): $rule. ")
            append("Never promise anything on $owner's behalf beyond these instructions. Do not add a signature; it is added automatically.")
        }
        val prompt = "Incoming WhatsApp message from $sender: \"${incoming.take(500)}\"\nReply:"
        val body = try {
            AiTextSynthesizer.synthesizeText(context, system, prompt, 0.6, 160) { away }
        } catch (e: Exception) {
            away
        }
        return AutoReplySignature.apply(body.trim().trim('"'), name)
    }

    private fun sendViaRemoteInput(context: Context, n: InterceptedNotification, reply: String): Boolean {
        val action = n.replyAction ?: return false
        val input = n.remoteInput ?: return false
        return try {
            val intent = Intent()
            val bundle = Bundle().apply { putCharSequence(input.resultKey, reply) }
            RemoteInput.addResultsToIntent(arrayOf(input), intent, bundle)
            action.actionIntent.send(context, 0, intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "RemoteInput send failed: ${e.message}")
            false
        }
    }

    // ---------------- tool-facing helpers ----------------

    fun settingsJson(context: Context): JSONObject {
        val p = MediaPrefs.get(context)
        return JSONObject().apply {
            put("enabled", p.autoReplyEnabled)
            put("style", p.autoReplyStyle)
            put("away_message", p.autoReplyAwayMessage)
            put("support_mode", p.autoReplySupportMode)
            put("signature", AutoReplySignature.line(assistantName()))
            put("signature_removable", false)
            put("handled_contacts", JSONObject(p.handledContacts() as Map<*, *>))
            put("replies_sent", sent.get())
            put("recent", JSONArray(recentLog().take(5)))
        }
    }

    /** Draft a reply to [sender]'s latest message(s) without sending. */
    suspend fun smartReply(context: Context, sender: String): Pair<Boolean, JSONObject> {
        val msgs = JarvisNotificationListener.recentNotifications.value
            .filter { !it.isCall && it.sender.contains(sender, ignoreCase = true) }
            .take(5)
        if (msgs.isEmpty()) return false to JSONObject().put("message", "I don't have any recent message from $sender in the notification shade.")
        val thread = msgs.reversed().joinToString("\n") { "${it.sender}: ${it.text}" }
        val owner = runCatching { JarvisApp.instance.preferences.userName }.getOrDefault("").ifBlank { "the user" }
        val draft = AiTextSynthesizer.synthesizeText(
            context,
            "You draft WhatsApp replies for $owner. Reply in the same language/tone as the thread, 1-2 sentences, no quotes, no preamble.",
            "Thread:\n$thread\n\nDraft $owner's reply:",
            0.7, 120
        ) { "Haan, bataata hoon thodi der me." }
        val top = msgs.first()
        return true to JSONObject()
            .put("message", "Draft reply to ${top.sender}: \"${draft.trim()}\". Say 'send it' and I'll reply with reply_to_message.")
            .put("draft", draft.trim())
            .put("contact", top.sender)
            .put("app", top.appLabel)
            .put("last_message", top.text)
    }

    /** Voice-note handling: only answers when a transcript is actually available. */
    suspend fun replyToVoiceNote(context: Context, sender: String): Pair<Boolean, JSONObject> {
        val msgs = JarvisNotificationListener.recentNotifications.value
            .filter { !it.isCall && it.sender.contains(sender, ignoreCase = true) }
        val voice = msgs.firstOrNull { isVoiceNote(it.text) }
            ?: return false to JSONObject().put("message", "I don't see a voice note from $sender in recent notifications.")
        val transcript = extractTranscript(voice.text)
        if (transcript.isNullOrBlank()) {
            return false to JSONObject()
                .put("message", "$sender ne voice note bheja hai, lekin uska transcript available nahi hai (WhatsApp ne text nahi diya). Chat khol ke sun lo, ya mujhe batao kya reply karna hai.")
                .put("has_transcript", false)
        }
        val draft = AiTextSynthesizer.synthesizeText(
            context,
            "You draft a short WhatsApp reply to a transcribed voice note. Same language as the transcript, 1-2 sentences, no quotes.",
            "Voice note from $sender: \"$transcript\"\nReply:", 0.7, 120
        ) { "Theek hai, sun liya. Thodi der me baat karte hain." }
        return true to JSONObject()
            .put("message", "Voice note transcript: \"$transcript\". Draft reply: \"${draft.trim()}\". Say 'send' to reply.")
            .put("has_transcript", true).put("transcript", transcript).put("draft", draft.trim()).put("contact", voice.sender)
    }

    private fun isVoiceNote(text: String): Boolean {
        val t = text.lowercase(Locale.ROOT)
        return t.contains("voice message") || t.contains("voice note") || t.contains("audio") || t.contains("🎤") || t.contains("🎧")
    }

    private fun extractTranscript(text: String): String? {
        // WhatsApp transcripts (when enabled) appear as: "🎤 Voice message (0:12): <text>" or "Transcript: <text>"
        val m = Regex("(?i)(?:transcript|voice message\\s*\\([^)]*\\))\\s*[:\\-]\\s*(.+)$").find(text)
        val t = m?.groupValues?.get(1)?.trim()
        return if (t.isNullOrBlank() || t.length < 3) null else t
    }
}
