package com.jarvis.assistant.maya.screen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Shared process-wide helpers for the Maya screen pack (scope, live-session bridge, notices). */
object ScreenRuntime {

    private const val TAG = "MayaScreenRuntime"
    private const val CHANNEL_ID = "maya_screen_watches"
    private val notifId = AtomicInteger(47_100)

    /** Long-lived scope: automation runs must survive the Activity / tool call that started them. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun appContext(): Context? = try { JarvisApp.instance.applicationContext } catch (_: Throwable) { null }

    /** True only while a Gemini Live conversation is actually connected. */
    fun isLive(): Boolean = try {
        JarvisConversationService.isSessionOn.value && JarvisConversationService.connectionStatus.value == "LIVE"
    } catch (_: Throwable) {
        false
    }

    /** Injects one text turn into the live conversation (never throws). */
    fun inject(text: String): Boolean {
        if (!isLive()) return false
        return try {
            scope.launch(Dispatchers.Main) {
                try {
                    JarvisConversationService.sendTextMessage(text)
                } catch (e: Throwable) {
                    Log.w(TAG, "inject failed: ${e.message}")
                }
            }
            true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Clears a stale "cancelled" flag left in SerializedActionExecutor by an earlier cancelled
     * task, so macro steps are not rejected — but never while another agent task is running.
     */
    fun prepareUiActions() {
        try {
            val cur = com.jarvis.assistant.automation.agent.AgentTaskEngine.current()
            val terminal = setOf(
                com.jarvis.assistant.automation.agent.AgentTaskEngine.State.COMPLETED,
                com.jarvis.assistant.automation.agent.AgentTaskEngine.State.FAILED,
                com.jarvis.assistant.automation.agent.AgentTaskEngine.State.CANCELLED,
                com.jarvis.assistant.automation.agent.AgentTaskEngine.State.BLOCKED
            )
            val busy = (cur != null && cur.status !in terminal) || VerbLoopExecutor.running
            if (!busy) com.jarvis.assistant.automation.agent.SerializedActionExecutor.resetCancellation()
        } catch (_: Throwable) {}
    }

    /** True when the ringer is normal (Maya: alerts are not spoken on silent / vibrate). */
    fun canSpeakAloud(context: Context): Boolean = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.ringerMode == AudioManager.RINGER_MODE_NORMAL && am.mode != AudioManager.MODE_IN_CALL && am.mode != AudioManager.MODE_IN_COMMUNICATION
    } catch (_: Throwable) {
        true
    }

    /** Local fallback when the live session is off: notification + on-device TTS. */
    fun announceLocally(context: Context, title: String, text: String, speak: Boolean = true) {
        postNotification(context, title, text)
        if (speak && canSpeakAloud(context)) {
            try {
                com.jarvis.assistant.voice.PocketTtsEngine.speak(text, context.applicationContext)
            } catch (e: Throwable) {
                Log.w(TAG, "TTS failed: ${e.message}")
            }
        }
    }

    fun postNotification(context: Context, title: String, text: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Watches & phone events", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Things you asked to be told about (watch_for) and phone event announcements"
                    }
                )
            }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            val open = Intent(context, MacrosActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            NotificationManagerCompat.from(context).notify(notifId.incrementAndGet(), n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission missing: ${e.message}")
        } catch (e: Throwable) {
            Log.w(TAG, "notification failed: ${e.message}")
        }
    }
}
