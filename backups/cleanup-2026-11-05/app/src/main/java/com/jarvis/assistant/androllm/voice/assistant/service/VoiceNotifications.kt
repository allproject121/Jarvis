package com.jarvis.assistant.androllm.voice.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.ui.home.MainActivity

/**
 * Foreground-service notification for the optional AndroLLM voice assistant
 * (ported from AndroLLM). Follows Jarvis's conventions (IMPORTANCE_LOW channel,
 * `ic_btn_speak_now`, ongoing, tap opens MainActivity, one "Stop" action) but uses
 * its own channel / id so it never replaces JarvisConversationService's
 * notification (`jarvis_conversation` / 41). Only one of the two services runs at a
 * time, so there is never more than one voice foreground notification.
 */
object VoiceNotifications {

    const val CHANNEL_ID = "androllm_voice_assistant"
    const val NOTIFICATION_ID = 43

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AndroLLM voice assistant",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Optional AndroLLM voice mode (keeps the wake word listening)"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        text: String = "Listening for \u201CHey Jarvis\u201D",
        showDisableAction: Boolean = true
    ): Notification {
        val disableIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, VoiceAssistantService::class.java).setAction(VoiceAssistantService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis · AndroLLM voice")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (showDisableAction) {
            builder.addAction(0, "Stop", disableIntent)
        }
        return builder.build()
    }
}
