package com.jarvis.assistant.automation.crm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jarvis.assistant.core.JarvisReminderReceiver

/**
 * Automated Follow-up Drip Engine.
 * Schedules time-based follow-ups for customer leads using Android AlarmManager.
 * When the alarm fires, Jarvis reminds the user via HUD alert and voice speech.
 */
class LeadFollowupScheduler private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LeadFollowupScheduler"

        @Volatile
        private var instance: LeadFollowupScheduler? = null

        fun getInstance(context: Context): LeadFollowupScheduler {
            return instance ?: synchronized(this) {
                instance ?: LeadFollowupScheduler(context.applicationContext).also { instance = it }
            }
        }
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleFollowup(
        phone: String,
        leadName: String,
        message: String,
        delayMinutes: Int
    ): Boolean {
        val triggerAtMillis = System.currentTimeMillis() + (delayMinutes * 60 * 1000L)
        val requestCode = (phone.filter { it.isDigit() }.takeLast(8).toIntOrNull() ?: (System.currentTimeMillis() % 100000).toInt())

        val reminderText = "Follow up with $leadName ($phone): \"$message\""
        val intent = Intent(context, JarvisReminderReceiver::class.java).apply {
            putExtra("reminder_text", reminderText)
            putExtra("reminder_id", requestCode)
            putExtra("lead_phone", phone)
            putExtra("lead_name", leadName)
            putExtra("followup_message", message)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            Log.i(TAG, "Scheduled follow-up alarm for $leadName in $delayMinutes min (id=$requestCode)")

            // Also update CRM record status if lead exists
            LeadCrmManager.getInstance(context).updateLeadStatus(
                phone,
                "FOLLOW_UP_SCHEDULED",
                "Follow-up scheduled in $delayMinutes mins: $message"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule follow-up: ${e.message}", e)
            false
        }
    }
}
