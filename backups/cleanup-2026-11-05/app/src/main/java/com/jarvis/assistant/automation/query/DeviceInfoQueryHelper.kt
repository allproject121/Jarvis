package com.jarvis.assistant.automation.query

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale

/**
 * Information-query engine for device-level telemetry, contact counts,
 * and application usage statistics.
 *
 * Implements strict, honest data reporting:
 * - Checks runtime permissions before accessing Contacts or Usage Stats.
 * - Computes exact local-day boundaries for usage statistics.
 * - Accurately distinguishes device-synced WhatsApp contacts from raw address book.
 * - Never invents or assumes data when permissions or services are unavailable.
 */
object DeviceInfoQueryHelper {

    private const val TAG = "DeviceInfoQueryHelper"

    // ── 1. CONTACT COUNT ────────────────────────────────────────────────────────

    sealed class ContactCountResult {
        data class Success(val count: Int, val message: String) : ContactCountResult()
        object PermissionDenied : ContactCountResult()
        data class Error(val reason: String) : ContactCountResult()
    }

    fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Queries the actual count of contacts in the device Contacts Provider.
     */
    fun getPhoneContactsCount(context: Context): ContactCountResult {
        if (!hasContactsPermission(context)) {
            Log.w(TAG, "getPhoneContactsCount: READ_CONTACTS permission not granted.")
            return ContactCountResult.PermissionDenied
        }

        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null
            )

            cursor?.use {
                val count = it.count
                ContactCountResult.Success(
                    count = count,
                    message = "You have $count contacts saved on your phone."
                )
            } ?: ContactCountResult.Error("Contacts Provider returned a null cursor.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException querying contacts: ${e.message}", e)
            ContactCountResult.PermissionDenied
        } catch (e: Exception) {
            Log.e(TAG, "Exception querying contacts: ${e.message}", e)
            ContactCountResult.Error(e.message ?: "Failed to query contacts.")
        }
    }

    // ── 2. WHATSAPP CONTACT COUNT ──────────────────────────────────────────────

    sealed class WhatsAppContactCountResult {
        data class Success(val count: Int, val explanation: String) : WhatsAppContactCountResult()
        object PermissionDenied : WhatsAppContactCountResult()
        data class Error(val reason: String) : WhatsAppContactCountResult()
    }

    /**
     * Queries the count of address book contacts synced with WhatsApp.
     * Note: Android Contacts Provider stores WhatsApp synced entries in RawContacts
     * with account_type = 'com.whatsapp' or 'com.whatsapp.w4b'.
     */
    fun getWhatsAppContactsCount(context: Context): WhatsAppContactCountResult {
        if (!hasContactsPermission(context)) {
            Log.w(TAG, "getWhatsAppContactsCount: READ_CONTACTS permission not granted.")
            return WhatsAppContactCountResult.PermissionDenied
        }

        return try {
            val uri = ContactsContract.RawContacts.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.RawContacts.CONTACT_ID,
                ContactsContract.RawContacts.ACCOUNT_TYPE
            )
            val selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE} IN (?, ?)"
            val selectionArgs = arrayOf("com.whatsapp", "com.whatsapp.w4b")

            val cursor = context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                null
            )

            cursor?.use {
                val distinctContactIds = mutableSetOf<String>()
                val contactIdIdx = it.getColumnIndex(ContactsContract.RawContacts.CONTACT_ID)

                while (it.moveToNext()) {
                    if (contactIdIdx >= 0) {
                        val id = it.getString(contactIdIdx)
                        if (!id.isNullOrBlank()) {
                            distinctContactIds.add(id)
                        }
                    }
                }

                val count = distinctContactIds.size
                val explanation = if (count > 0) {
                    "Found $count contacts synced with WhatsApp in your phone's address book. (Note: This reflects contacts synced to your phone; unsynced chat threads only in WhatsApp are not included)."
                } else {
                    "Found 0 synced WhatsApp contacts in your phone's address book. Either WhatsApp contact sync is disabled or no contacts are linked to WhatsApp."
                }

                WhatsAppContactCountResult.Success(count = count, explanation = explanation)
            } ?: WhatsAppContactCountResult.Error("RawContacts Provider returned a null cursor.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException querying WhatsApp contacts: ${e.message}", e)
            WhatsAppContactCountResult.PermissionDenied
        } catch (e: Exception) {
            Log.e(TAG, "Exception querying WhatsApp contacts: ${e.message}", e)
            WhatsAppContactCountResult.Error(e.message ?: "Failed to query WhatsApp contacts.")
        }
    }

    // ── 3. APP USAGE STATISTICS ────────────────────────────────────────────────

    sealed class AppUsageResult {
        data class Success(
            val appName: String,
            val packageName: String,
            val durationMs: Long,
            val formattedDuration: String,
            val summary: String
        ) : AppUsageResult()

        data class PermissionDenied(
            val settingsAction: String,
            val message: String
        ) : AppUsageResult()

        data class AppNotFound(val message: String) : AppUsageResult()
        data class Error(val reason: String) : AppUsageResult()
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Computes the timestamp for the start of today (00:00:00.000) in the user's local timezone.
     */
    fun getStartOfTodayMillis(now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Resolves app query string to candidate package names.
     */
    fun resolvePackagesForApp(appQuery: String): Set<String> {
        val clean = appQuery.lowercase(Locale.ROOT).trim()
        return when {
            clean.contains("whatsapp") -> setOf("com.whatsapp", "com.whatsapp.w4b")
            clean.contains("youtube") -> setOf("com.google.android.youtube")
            clean.contains("chrome") || clean.contains("browser") -> setOf("com.android.chrome")
            clean.contains("instagram") -> setOf("com.instagram.android")
            clean.contains("spotify") -> setOf("com.spotify.music")
            clean.contains("telegram") -> setOf("org.telegram.messenger")
            clean.contains("drive") -> setOf("com.google.android.apps.docs")
            clean.contains("photos") || clean.contains("gallery") -> setOf("com.google.android.apps.photos")
            clean.contains("gmail") -> setOf("com.google.android.gm")
            clean.contains("facebook") -> setOf("com.facebook.katana")
            clean.contains("settings") -> setOf("com.android.settings")
            else -> if (clean.contains(".")) setOf(clean) else emptySet()
        }
    }

    /**
     * Formats duration in milliseconds into human-readable string.
     */
    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "0 minutes"

        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 && minutes > 0 -> "$hours hr $minutes min"
            hours > 0 -> "$hours hr"
            minutes > 0 -> "$minutes min"
            else -> "$seconds sec"
        }
    }

    /**
     * Queries actual usage duration for the requested application for today.
     */
    fun getAppUsageDuration(
        context: Context,
        appQuery: String,
        now: Long = System.currentTimeMillis()
    ): AppUsageResult {
        if (!hasUsageStatsPermission(context)) {
            Log.w(TAG, "getAppUsageDuration: PACKAGE_USAGE_STATS permission not granted.")
            return AppUsageResult.PermissionDenied(
                settingsAction = Settings.ACTION_USAGE_ACCESS_SETTINGS,
                message = "Usage Access is required to check app usage time. Please grant 'Usage Access' for Jarvis in Settings > Security / Apps > Special Access > Usage Access."
            )
        }

        val targetPackages = resolvePackagesForApp(appQuery)
        val resolvedName = if (appQuery.isNotBlank()) appQuery.replaceFirstChar { it.uppercase() } else "App"

        if (targetPackages.isEmpty()) {
            return AppUsageResult.AppNotFound("Could not determine package name for '$appQuery'.")
        }

        return try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usageStatsManager == null) {
                return AppUsageResult.Error("UsageStatsManager is unavailable on this device.")
            }

            val startTime = getStartOfTodayMillis(now)
            val endTime = now

            // 1. Calculate using UsageEvents for higher accuracy across today's session boundaries
            val eventDuration = calculateDurationFromEvents(usageStatsManager, targetPackages, startTime, endTime)

            // 2. Query UsageStats aggregation as confirmation / fallback
            val statsList: List<UsageStats>? = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            var statsDuration = 0L
            var matchedPkg = targetPackages.first()
            if (!statsList.isNullOrEmpty()) {
                for (stat in statsList) {
                    if (targetPackages.contains(stat.packageName)) {
                        if (stat.totalTimeInForeground > 0L) {
                            statsDuration += stat.totalTimeInForeground
                            matchedPkg = stat.packageName
                        }
                    }
                }
            }

            val totalDurationMs = maxOf(eventDuration, statsDuration)
            val formatted = formatDuration(totalDurationMs)

            val summary = if (totalDurationMs > 0L) {
                "Today you have used $resolvedName for $formatted."
            } else {
                "You haven't used $resolvedName today yet (0 minutes recorded)."
            }

            AppUsageResult.Success(
                appName = resolvedName,
                packageName = matchedPkg,
                durationMs = totalDurationMs,
                formattedDuration = formatted,
                summary = summary
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception querying usage stats: ${e.message}", e)
            AppUsageResult.Error(e.message ?: "Failed to query app usage.")
        }
    }

    /**
     * Calculates duration by stepping through ACTIVITY_RESUMED and ACTIVITY_PAUSED events.
     */
    fun calculateDurationFromEvents(
        usageStatsManager: UsageStatsManager,
        targetPackages: Set<String>,
        startTime: Long,
        endTime: Long
    ): Long {
        return try {
            val events = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()

            var totalTime = 0L
            val appResumeTimes = mutableMapOf<String, Long>()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                if (!targetPackages.contains(pkg)) continue

                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        appResumeTimes[pkg] = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                        val resumeTime = appResumeTimes.remove(pkg)
                        if (resumeTime != null && event.timeStamp >= resumeTime) {
                            totalTime += (event.timeStamp - resumeTime)
                        }
                    }
                }
            }

            // If still in foreground right now
            for ((_, resumeTime) in appResumeTimes) {
                if (endTime >= resumeTime) {
                    totalTime += (endTime - resumeTime)
                }
            }

            totalTime
        } catch (e: Exception) {
            Log.w(TAG, "Error calculating duration from events: ${e.message}")
            0L
        }
    }
}
