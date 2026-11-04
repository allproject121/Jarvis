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

    // ------------------------------------------------------------------------------------------
    // Maya business pack additions: drip sequences + cancellation (additive).
    // ------------------------------------------------------------------------------------------

    private val dripsFile: java.io.File get() = java.io.File(context.filesDir, "drips.json")

    /** Persisted drip steps so the Business screen can show what is pending. */
    data class DripStep(
        val phone: String,
        val leadName: String,
        val day: Int,
        val triggerAt: Long,
        val message: String,
        val requestCode: Int
    )

    private fun loadDrips(): MutableList<DripStep> = synchronized(this) {
        try {
            if (!dripsFile.exists()) return@synchronized mutableListOf<DripStep>()
            val arr = org.json.JSONArray(dripsFile.readText())
            val out = mutableListOf<DripStep>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(DripStep(o.optString("phone"), o.optString("leadName"), o.optInt("day"), o.optLong("triggerAt"), o.optString("message"), o.optInt("requestCode")))
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveDrips(list: List<DripStep>) = synchronized(this) {
        try {
            val arr = org.json.JSONArray()
            list.forEach {
                arr.put(org.json.JSONObject().put("phone", it.phone).put("leadName", it.leadName).put("day", it.day)
                    .put("triggerAt", it.triggerAt).put("message", it.message).put("requestCode", it.requestCode))
            }
            dripsFile.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "saveDrips failed: ${e.message}")
        }
    }

    /** Pending drip steps (future only), oldest first. */
    fun pendingDrips(phone: String? = null): List<DripStep> {
        val now = System.currentTimeMillis()
        val digits = phone?.filter { it.isDigit() }.orEmpty()
        return loadDrips().filter { it.triggerAt > now && (digits.isBlank() || it.phone.filter { c -> c.isDigit() }.endsWith(digits.takeLast(8))) }
            .sortedBy { it.triggerAt }
    }

    private fun pendingIntentFor(phone: String, leadName: String, message: String, requestCode: Int): PendingIntent {
        val reminderText = "Follow up with $leadName ($phone): \"$message\""
        val intent = Intent(context, JarvisReminderReceiver::class.java).apply {
            putExtra("reminder_text", reminderText)
            putExtra("reminder_id", requestCode)
            putExtra("lead_phone", phone)
            putExtra("lead_name", leadName)
            putExtra("followup_message", message)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    /**
     * Schedules a drip sequence: one reminder per entry in [days] (e.g. 0, 2, 5 = today, +2 days, +5 days
     * at [hourOfDay]). Day 0 fires in a few minutes. Each reminder notifies the owner - nothing is sent
     * to the lead automatically. Returns the number of steps armed.
     */
    fun scheduleDrip(phone: String, leadName: String, days: List<Int>, message: String, hourOfDay: Int = 10): Int {
        cancelDrip(phone)
        val steps = mutableListOf<DripStep>()
        val base = phone.filter { it.isDigit() }.takeLast(8).hashCode() and 0x00FF_FFFF
        val now = System.currentTimeMillis()
        for (day in days.distinct().sorted()) {
            val cal = java.util.Calendar.getInstance()
            val triggerAt: Long
            if (day <= 0) {
                triggerAt = now + 5 * 60 * 1000L
            } else {
                cal.add(java.util.Calendar.DAY_OF_YEAR, day)
                cal.set(java.util.Calendar.HOUR_OF_DAY, hourOfDay.coerceIn(6, 21))
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                triggerAt = cal.timeInMillis
            }
            val requestCode = 0x4000_0000 or ((base * 31 + day) and 0x0FFF_FFFF)
            val stepMessage = if (message.isBlank()) "Drip day $day follow-up" else "$message (drip day $day)"
            val pi = pendingIntentFor(phone, leadName, stepMessage, requestCode)
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
                steps.add(DripStep(phone, leadName, day, triggerAt, stepMessage, requestCode))
            } catch (e: Exception) {
                Log.e(TAG, "drip step $day failed: ${e.message}")
            }
        }
        if (steps.isNotEmpty()) {
            val all = loadDrips()
            all.addAll(steps)
            saveDrips(all)
            LeadCrmManager.getInstance(context).updateLeadStatus(
                phone, "FOLLOW_UP_SCHEDULED", "Drip set: days ${days.joinToString(", ")}"
            )
        }
        return steps.size
    }

    /** Cancels every pending drip step for [phone]. Returns how many were cancelled. */
    fun cancelDrip(phone: String): Int {
        val digits = phone.filter { it.isDigit() }.takeLast(8)
        if (digits.isBlank()) return 0
        val all = loadDrips()
        val mine = all.filter { it.phone.filter { c -> c.isDigit() }.endsWith(digits) }
        for (s in mine) {
            try { alarmManager.cancel(pendingIntentFor(s.phone, s.leadName, s.message, s.requestCode)) } catch (_: Exception) { }
        }
        if (mine.isNotEmpty()) saveDrips(all - mine.toSet())
        return mine.size
    }

    /** Schedules one follow-up at an absolute time (used by the Business screen). */
    fun scheduleFollowupAt(phone: String, leadName: String, message: String, triggerAtMillis: Long): Boolean {
        val delayMinutes = ((triggerAtMillis - System.currentTimeMillis()) / 60000L).toInt().coerceAtLeast(1)
        return scheduleFollowup(phone, leadName, message, delayMinutes)
    }
}
