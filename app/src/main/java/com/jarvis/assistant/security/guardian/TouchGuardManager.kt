package com.jarvis.assistant.security.guardian

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.jarvis.assistant.JarvisApp
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * JARVIS Touch Guard Sentinel:
 * Monitors device movement via accelerometer when armed.
 * Triggers alarm and front-camera intruder capture when picked up or moved.
 * While armed, [GuardianForegroundService] keeps the process (and this listener) alive
 * after Jarvis' UI is closed.
 */
object TouchGuardManager : SensorEventListener {
    private const val TAG = "TouchGuardManager"
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var isArmed = false
    private var isStealth = false
    private var mediaPlayer: MediaPlayer? = null
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var isFirstReading = true
    private var lastTriggerAt = 0L
    private const val TRIGGER_COOLDOWN_MS = 10_000L
    private const val PHOTO_TIMEOUT_MS = 8_000L

    fun isArmed(): Boolean = isArmed
    fun isStealth(): Boolean = isStealth

    /** Wall-clock time Touch Guard was last armed (0 = never in this process). */
    @Volatile var armedAtMs: Long = 0L
        private set
    /** Motion magnitude of the most recent trigger (for the incident log). */
    @Volatile private var lastTriggerMagnitude: Float = 0f

    /**
     * @param startService false only when called by GuardianForegroundService itself (re-arm after
     * a process restart) - the service is already running in that case.
     */
    fun arm(context: Context, stealth: Boolean = false, startService: Boolean = true) {
        if (isArmed) return
        val appContext = context.applicationContext
        sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (accelerometer == null) {
            Toast.makeText(appContext, "Accelerometer unavailable on this hardware", Toast.LENGTH_SHORT).show()
            return
        }

        isArmed = true
        isStealth = stealth
        isFirstReading = true
        armedAtMs = System.currentTimeMillis()

        sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        JarvisApp.instance.preferences.isTouchGuardArmed = true
        JarvisApp.instance.preferences.isTouchGuardStealth = stealth
        if (startService) GuardianForegroundService.start(appContext)

        if (!stealth) {
            Toast.makeText(appContext, "Touch Guard is ARMED. Phone is protected.", Toast.LENGTH_LONG).show()
        }
    }

    fun disarm(context: Context) {
        isArmed = false
        sensorManager?.unregisterListener(this)
        stopAlarm()
        JarvisApp.instance.preferences.isTouchGuardArmed = false
        GuardianForegroundService.stop(context)
        Toast.makeText(context.applicationContext, "Touch Guard DISARMED", Toast.LENGTH_SHORT).show()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isArmed || event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (isFirstReading) {
            lastX = x
            lastY = y
            lastZ = z
            isFirstReading = false
            return
        }

        val deltaX = x - lastX
        val deltaY = y - lastY
        val deltaZ = z - lastZ
        val magnitude = sqrt((deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ).toDouble())

        if (magnitude > 3.2) {
            Log.w(TAG, "Touch Guard movement alert triggered! Magnitude: $magnitude")
            lastTriggerMagnitude = magnitude.toFloat()
            triggerAlarm()
        }

        lastX = x
        lastY = y
        lastZ = z
    }

    private fun triggerAlarm() {
        val app = JarvisApp.instance
        // Every sample above the threshold lands here while the phone is moving: run the
        // capture / notification once per cooldown window instead of re-opening the camera.
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt >= TRIGGER_COOLDOWN_MS) {
            lastTriggerAt = now
            captureOrNotify(app)
        }

        if (!isStealth) {
            startSirenAlarm(app)
        }
    }

    /**
     * Takes the intruder selfie. Background camera access is blocked on Android 11+ unless the
     * guardian service runs with the camera type, so when no photo arrives (error or timeout)
     * a high-priority "phone moved" notification is posted instead of failing silently.
     */
    /** Maya Watchman: incident log + face check (com.jarvis.assistant.maya.guardian). Never throws. */
    private fun reportToWatchman(context: Context, photo: java.io.File?) {
        try {
            com.jarvis.assistant.maya.guardian.WatchmanFaceMonitor.onTouchGuardTriggered(context, photo, lastTriggerMagnitude)
        } catch (e: Throwable) {
            Log.w(TAG, "Watchman report failed: ${e.message}")
        }
    }

    private fun captureOrNotify(context: Context) {
        val settled = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            if (settled.compareAndSet(false, true)) {
                Log.w(TAG, "Intruder photo timed out - posting movement alert")
                reportToWatchman(context, null)
                GuardianForegroundService.postMovedAlert(context, "Someone moved your phone. Intruder photo could not be taken (camera unavailable in background).")
            }
        }
        handler.postDelayed(timeout, PHOTO_TIMEOUT_MS)
        try {
            WatchmanSecurityManager(context).captureIntruderPhoto { photo ->
                if (!settled.compareAndSet(false, true)) return@captureIntruderPhoto
                handler.removeCallbacks(timeout)
                reportToWatchman(context, photo)
                if (photo != null) {
                    Log.i(TAG, "Touch Guard captured intruder photo: ${photo.name}")
                } else {
                    GuardianForegroundService.postMovedAlert(context, "Someone moved your phone. Intruder photo could not be taken (camera unavailable in background).")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in intruder photo capture: ${e.message}")
            if (settled.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                reportToWatchman(context, null)
                GuardianForegroundService.postMovedAlert(context, "Someone moved your phone. Intruder photo failed: ${e.message}")
            }
        }
    }

    private fun startSirenAlarm(context: Context) {
        if (mediaPlayer?.isPlaying == true) return
        try {
            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alertUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start Touch Guard siren: ${e.message}")
        }
    }

    fun stopAlarm() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
