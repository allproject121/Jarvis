package com.jarvis.assistant.automation

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Intelligent Notification Listener Service for JARVIS.
 *
 * Capabilities:
 * - Detects incoming WhatsApp, Telegram, and SMS messages with sender and text.
 * - Captures RemoteInput free-form actions to allow Jarvis to reply directly to messages by voice.
 * - Detects incoming phone calls and identifies callers.
 */
class JarvisNotificationListener : NotificationListenerService() {

    data class ReceivedMessage(
        val packageName: String,
        val sender: String,
        val message: String,
        val timestamp: Long,
        val isGroup: Boolean = false,
        val groupTitle: String? = null
    )

    data class RemoteInputReply(
        val pendingIntent: PendingIntent,
        val remoteInput: RemoteInput,
        val sender: String,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "JarvisNotification"

        @Volatile
        var instance: JarvisNotificationListener? = null
            private set

        val recentMessages = CopyOnWriteArrayList<ReceivedMessage>()
        val replyActionMap = ConcurrentHashMap<String, RemoteInputReply>()

        @Volatile
        var latestCaller: String? = null
            private set

        val TARGET_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.samsung.android.dialer",
            "com.instagram.android"
        )

        fun isConnected(): Boolean = instance != null

        /**
         * Replies directly to an incoming WhatsApp or messaging contact using Android RemoteInput.
         * The message is sent instantly in the background without needing to launch or open WhatsApp.
         */
        fun replyToMessage(context: Context, senderQuery: String, replyText: String): Boolean {
            val query = senderQuery.trim().lowercase(Locale.ROOT)
            val entry = replyActionMap.entries.firstOrNull { (sender, _) ->
                sender.lowercase(Locale.ROOT).contains(query) || query.contains(sender.lowercase(Locale.ROOT))
            } ?: replyActionMap.entries.maxByOrNull { it.value.timestamp }

            if (entry == null) {
                Log.w(TAG, "No RemoteInput action cached for sender: $senderQuery")
                return false
            }

            val reply = entry.value
            return try {
                val fillIntent = Intent()
                val bundle = Bundle().apply {
                    putCharSequence(reply.remoteInput.resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(arrayOf(reply.remoteInput), fillIntent, bundle)
                reply.pendingIntent.send(context, 0, fillIntent)
                Log.i(TAG, "Successfully sent RemoteInput voice reply to ${reply.sender}: $replyText")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending RemoteInput reply to ${reply.sender}: ${e.message}", e)
                false
            }
        }

        fun getLatestMessage(): ReceivedMessage? {
            return recentMessages.lastOrNull()
        }

        fun getMessagesFrom(senderQuery: String): List<ReceivedMessage> {
            val q = senderQuery.trim().lowercase(Locale.ROOT)
            return recentMessages.filter { it.sender.lowercase(Locale.ROOT).contains(q) }
        }

        fun clearHistory() {
            recentMessages.clear()
            replyActionMap.clear()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "JarvisNotificationListener connected")
        refreshActiveNotifications()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        try {
            requestRebind(ComponentName(this, JarvisNotificationListener::class.java))
        } catch (ignored: Exception) {}
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        processNotification(sbn, isNew = true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val category = sbn.notification?.category
        if (category == Notification.CATEGORY_CALL) {
            latestCaller = null
        }
    }

    private fun refreshActiveNotifications() {
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                processNotification(sbn, isNew = false)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error fetching active notifications: ${e.message}")
        }
    }

    private fun processNotification(sbn: StatusBarNotification, isNew: Boolean) {
        val pkg = sbn.packageName ?: return
        if (pkg !in TARGET_PACKAGES) return

        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        // 1. Detect incoming phone calls
        if (notif.category == Notification.CATEGORY_CALL || pkg.contains("dialer")) {
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val caller = title ?: text
            if (!caller.isNullOrBlank()) {
                latestCaller = caller.trim()
                Log.i(TAG, "Detected incoming call from: $latestCaller")
            }
        }

        // 2. Extract message contents
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)

        if (!title.isNullOrBlank() && !text.isNullOrBlank()) {
            // Filter noise like "checking for new messages" or "WhatsApp"
            val textLower = text.lowercase(Locale.ROOT)
            val titleLower = title.lowercase(Locale.ROOT)
            if (titleLower == "whatsapp" || titleLower == "whatsapp business" ||
                textLower.contains("checking for new") || textLower.contains("waiting for this message") ||
                textLower.matches(Regex("""^\d+\s+(new\s+)?messages?\b.*"""))
            ) {
                return
            }

            val msg = ReceivedMessage(
                packageName = pkg,
                sender = title,
                message = text,
                timestamp = sbn.postTime,
                isGroup = isGroup,
                groupTitle = convTitle
            )

            // Avoid immediate duplicate entries
            val isDuplicate = recentMessages.any {
                it.sender == msg.sender && it.message == msg.message && Math.abs(it.timestamp - msg.timestamp) < 3000
            }
            if (!isDuplicate) {
                recentMessages.add(msg)
                while (recentMessages.size > 60) {
                    recentMessages.removeAt(0)
                }
                Log.i(TAG, "Captured incoming message from ${msg.sender}: ${msg.message}")
            }
        }

        // 3. Extract RemoteInput for auto-reply
        val actions = notif.actions
        if (actions != null) {
            for (action in actions) {
                val remoteInputs = action.remoteInputs ?: continue
                for (remoteInput in remoteInputs) {
                    if (remoteInput.allowFreeFormInput) {
                        val senderName = title ?: convTitle ?: pkg
                        val replyObj = RemoteInputReply(
                            pendingIntent = action.actionIntent,
                            remoteInput = remoteInput,
                            sender = senderName,
                            timestamp = sbn.postTime
                        )
                        replyActionMap[senderName] = replyObj
                        Log.d(TAG, "Cached RemoteInput reply action for: $senderName")
                        break
                    }
                }
            }
        }
    }
}
