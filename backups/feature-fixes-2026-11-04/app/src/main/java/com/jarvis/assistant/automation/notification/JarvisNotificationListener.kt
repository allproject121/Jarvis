package com.jarvis.assistant.automation.notification

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Data model for intercepted chat & call notifications.
 */
data class InterceptedNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val sender: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isCall: Boolean = false,
    val replyAction: Notification.Action? = null,
    val remoteInput: RemoteInput? = null
)

/**
 * Smart Notification Interception & WhatsApp / Telegram Voice Reader Service.
 * Runs in the background as a registered NotificationListenerService.
 */
class JarvisNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "JarvisNotifListener"

        @Volatile
        var instance: JarvisNotificationListener? = null
            private set

        val TARGET_MESSAGING_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "org.thoughtcrime.securesms",
            "com.instagram.android"
        )

        val TARGET_CALL_PACKAGES = setOf(
            "com.google.android.dialer",
            "com.android.dialer",
            "com.samsung.android.dialer"
        )

        private val IGNORED_PHRASES = setOf(
            "new message", "new messages", "checking for new messages",
            "you may have new messages", "sending message", "waiting for message",
            "whatsapp web", "backup in progress", "syncing"
        )

        private val _recentNotifications = MutableStateFlow<List<InterceptedNotification>>(emptyList())
        val recentNotifications: StateFlow<List<InterceptedNotification>> = _recentNotifications.asStateFlow()

        private val notificationActionStore = ConcurrentHashMap<String, Notification.Action>()
        private val remoteInputStore = ConcurrentHashMap<String, RemoteInput>()

        fun isNotificationServiceEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            return flat != null && flat.contains(context.packageName)
        }

        fun sendDirectReply(context: Context, targetKeyOrSender: String, replyText: String): Boolean {
            val key = if (notificationActionStore.containsKey(targetKeyOrSender)) {
                targetKeyOrSender
            } else {
                // Find by sender name match
                _recentNotifications.value.firstOrNull {
                    it.sender.contains(targetKeyOrSender, ignoreCase = true) ||
                            targetKeyOrSender.contains(it.sender, ignoreCase = true)
                }?.key
            } ?: return false

            val action = notificationActionStore[key] ?: return false
            val input = remoteInputStore[key] ?: return false

            return try {
                val intent = Intent()
                val bundle = Bundle().apply {
                    putCharSequence(input.resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(arrayOf(input), intent, bundle)
                action.actionIntent.send(context, 0, intent)
                Log.i(TAG, "Direct reply sent successfully to $key: '$replyText'")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending direct reply: ${e.message}")
                false
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "JarvisNotificationListener connected successfully.")
    }

    override fun onListenerDisconnected() {
        if (instance == this) instance = null
        super.onListenerDisconnected()
        Log.i(TAG, "JarvisNotificationListener disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        val isMessaging = TARGET_MESSAGING_PACKAGES.contains(pkg)
        val isCall = TARGET_CALL_PACKAGES.contains(pkg)
        if (!isMessaging && !isCall) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

        if (title.isBlank() && text.isBlank()) return

        // Filter out noisy system status notifications
        val lowerText = text.lowercase(Locale.ROOT)
        if (IGNORED_PHRASES.any { lowerText.contains(it) }) return

        val appLabel = when (pkg) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "org.telegram.messenger" -> "Telegram"
            "com.google.android.apps.messaging", "com.android.mms" -> "SMS"
            "org.thoughtcrime.securesms" -> "Signal"
            "com.instagram.android" -> "Instagram"
            else -> "Phone"
        }

        // Find RemoteInput reply action if present
        var directReplyAction: Notification.Action? = null
        var replyRemoteInput: RemoteInput? = null
        val actions = notification.actions
        if (actions != null) {
            for (action in actions) {
                val remoteInputs = action.remoteInputs
                if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                    directReplyAction = action
                    replyRemoteInput = remoteInputs.first()
                    notificationActionStore[sbn.key] = action
                    remoteInputStore[sbn.key] = replyRemoteInput
                    break
                }
            }
        }

        val item = InterceptedNotification(
            key = sbn.key,
            packageName = pkg,
            appLabel = appLabel,
            sender = title.ifBlank { "Someone" },
            text = text,
            isCall = isCall,
            replyAction = directReplyAction,
            remoteInput = replyRemoteInput
        )

        val currentList = _recentNotifications.value.toMutableList()
        currentList.removeAll { it.key == item.key }
        currentList.add(0, item)
        if (currentList.size > 25) currentList.removeAt(currentList.size - 1)
        _recentNotifications.value = currentList

        Log.i(TAG, "[$appLabel] from $title: $text (hasReply=${directReplyAction != null})")

        // If Jarvis is active in live conversation and reading is enabled, inject notification event
        val prefs = JarvisApp.instance.preferences
        if (prefs.isWhatsAppReaderEnabled && isMessaging) {
            // If Jarvis session is on, speak or inform
            if (JarvisConversationService.isSessionOn.value) {
                JarvisConversationService.sendTextMessage(
                    "[SYSTEM EVENT: Incoming $appLabel message from '$title': '$text'. You may inform the user and ask if they wish to reply.]"
                )
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        notificationActionStore.remove(sbn.key)
        remoteInputStore.remove(sbn.key)
    }
}
