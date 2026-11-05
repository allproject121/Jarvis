package com.jarvis.assistant.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.voice.PocketTtsEngine

/**
 * JARVIS Tactical Protocol Reminders & Scheduled Alarms.
 * Dispatches high-priority HUD notification and triggers real-time voice speech playback.
 */
class JarvisReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminderText = intent.getStringExtra("reminder_text") ?: "Protocol Reminder"
        val reminderId = intent.getIntExtra("reminder_id", 101)

        val channelId = "jarvis_reminders"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "JARVIS Tactical Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Protocol alert & reminders"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        val tapIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle("JARVIS Protocol Reminder")
            .setContentText(reminderText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminderText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build()

        nm.notify(reminderId, notification)

        // Wake and speak reminder aloud
        try {
            PocketTtsEngine.getInstance(context).speak("Reminder, Sir: $reminderText")
        } catch (_: Exception) {}
    }
}
