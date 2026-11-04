package com.jarvis.assistant.androllm.tools.impl

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Notification plumbing for reminders and alarms: two channels plus the
 * broadcast receiver that posts the notification when the AlarmManager
 * fires. The receiver must be declared in the app manifest:
 * `<receiver android:name=".androllm.tools.impl.ReminderNotifications$ReminderReceiver" android:exported="false" />`
 */
object ReminderNotifications {

    const val REMINDER_CHANNEL = "jarvis_reminders"
    const val ALARM_CHANNEL = "jarvis_alarms"
    const val EXTRA_TEXT = "extra_text"
    const val EXTRA_CHANNEL = "extra_channel"
    const val EXTRA_IS_ALARM = "extra_is_alarm"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

    private val requestCodes = AtomicInteger((System.currentTimeMillis() % 100_000).toInt() + 1000)

    fun nextRequestCode(): Int = requestCodes.incrementAndGet()

    /** True when this app may post notifications (permission + per-app toggle). */
    fun canNotify(context: Context): Boolean {
        val permissionOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permissionOk && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(REMINDER_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    REMINDER_CHANNEL,
                    "Reminders",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Scheduled reminder notifications" }
            )
        }
        if (nm.getNotificationChannel(ALARM_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    ALARM_CHANNEL,
                    "Alarms",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alarm notifications"
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                }
            )
        }
    }

    /**
     * Schedules [text] to be posted at [triggerAtMillis] on [channel]. Uses an
     * exact alarm when allowed, otherwise an inexact one (never throws for the
     * exact-alarm restriction).
     */
    fun schedule(
        context: Context,
        triggerAtMillis: Long,
        text: String,
        channel: String,
        isAlarm: Boolean
    ): Boolean {
        ensureChannels(context)
        val code = nextRequestCode()
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_TEXT, text)
            .putExtra(EXTRA_CHANNEL, channel)
            .putExtra(EXTRA_IS_ALARM, isAlarm)
            .putExtra(EXTRA_NOTIFICATION_ID, code)
        val pi = PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        return try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
            true
        } catch (e: SecurityException) {
            // Exact alarms blocked → inexact.
            runCatching { am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi) }.isSuccess
        }
    }

    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ensureChannels(context)
            val text = intent.getStringExtra(EXTRA_TEXT) ?: "Reminder"
            val isAlarm = intent.getBooleanExtra(EXTRA_IS_ALARM, false)
            val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: REMINDER_CHANNEL
            val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, nextRequestCode())
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(
                    context, id, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(if (isAlarm) android.R.drawable.ic_lock_idle_alarm else android.R.drawable.ic_dialog_info)
                .setContentTitle(if (isAlarm) "⏰ Alarm" else "Reminder")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(if (isAlarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
                .setPriority(if (isAlarm) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .apply { open?.let { setContentIntent(it) } }
                .build()
            if (canNotify(context)) {
                runCatching {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(id, notification)
                }
            }
        }
    }
}
