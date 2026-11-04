package com.jarvis.assistant.security.guardian

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ui.home.MainActivity

/**
 * Keeps Touch Guard alive after Jarvis' UI is closed.
 *
 * Started by [TouchGuardManager.arm], stopped by [TouchGuardManager.disarm]. While running it
 * shows a low-priority "Jarvis Touch Guard armed" notification with a Disarm action, keeps the
 * process (and the accelerometer listener owned by TouchGuardManager) alive, holds a partial
 * wake lock so sensor events keep arriving with the screen off, and re-arms Touch Guard from
 * preferences if the system restarts the service after killing the process.
 *
 * Foreground type: specialUse (motion sentry) + camera when CAMERA is granted, so the intruder
 * selfie can still be taken from the background on Android 11+.
 */
class GuardianForegroundService : Service() {

    companion object {
        private const val TAG = "GuardianFgService"
        private const val CHANNEL_ARMED = "jarvis_touch_guard_armed"
        private const val CHANNEL_ALERT = "jarvis_touch_guard_alert"
        private const val NOTIFICATION_ID_ARMED = 0x4A54 // "JT"
        private const val NOTIFICATION_ID_ALERT = 0x4A55
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L

        const val ACTION_START = "com.jarvis.assistant.guardian.START"
        const val ACTION_DISARM = "com.jarvis.assistant.guardian.DISARM"

        fun start(context: Context) {
            val intent = Intent(context, GuardianForegroundService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (e: Exception) {
                // Missing manifest entry or background-start restriction: Touch Guard still works
                // while the app process is alive.
                Log.w(TAG, "Could not start Touch Guard foreground service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.applicationContext.stopService(Intent(context.applicationContext, GuardianForegroundService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop Touch Guard foreground service: ${e.message}")
            }
        }

        /** High-priority "phone moved" alert - used when the intruder photo could not be taken. */
        @SuppressLint("MissingPermission") // checked below
        fun postMovedAlert(context: Context, detail: String) {
            val appContext = context.applicationContext
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(TAG, "POST_NOTIFICATIONS not granted - cannot show Touch Guard alert")
                    return
                }
                ensureChannels(appContext)
                val notification = NotificationCompat.Builder(appContext, CHANNEL_ALERT)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("Touch Guard: phone moved")
                    .setContentText(detail)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setContentIntent(openAppIntent(appContext))
                    .setAutoCancel(true)
                    .build()
                NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID_ALERT, notification)
            } catch (e: Exception) {
                Log.w(TAG, "Touch Guard alert notification failed: ${e.message}")
            }
        }

        private fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ARMED) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ARMED, "Touch Guard (armed)", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Shown while Touch Guard is protecting the phone" }
                )
            }
            if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ALERT, "Touch Guard alerts", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "Phone moved while Touch Guard was armed" }
                )
            }
        }

        private fun openAppIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISARM) {
            // Notification "Disarm" button. disarm() calls stop(), which ends this service.
            TouchGuardManager.disarm(this)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startAsForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Process was killed and the system restarted us (null intent): re-arm from preferences.
        if (!TouchGuardManager.isArmed()) {
            val prefs = JarvisApp.instance.preferences
            if (prefs.isTouchGuardArmed) {
                TouchGuardManager.arm(this, prefs.isTouchGuardStealth, startService = false)
            }
            if (!TouchGuardManager.isArmed()) {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        acquireWakeLock()
        return START_STICKY
    }

    private fun startAsForeground(): Boolean {
        ensureChannels(this)
        val disarmIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GuardianForegroundService::class.java).setAction(ACTION_DISARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ARMED)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Jarvis Touch Guard armed")
            .setContentText("Moving the phone triggers the alarm and intruder photo.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openAppIntent(this))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disarm", disarmIntent)
            .build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return tryStartForeground { startForeground(NOTIFICATION_ID_ARMED, notification) }
        }

        val baseType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        }
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            baseType != ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        ) {
            // Camera type lets the intruder selfie run while Jarvis is in the background. It is only
            // allowed when started from the foreground, so fall back to specialUse alone if refused.
            if (tryStartForeground {
                    startForeground(NOTIFICATION_ID_ARMED, notification, baseType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
                }) return true
        }
        return tryStartForeground { startForeground(NOTIFICATION_ID_ARMED, notification, baseType) }
    }

    private inline fun tryStartForeground(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground failed: ${e.message}")
        false
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:TouchGuard").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Touch Guard wake lock failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }
}
