package com.jarvis.assistant.security.guardian

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.util.Log
import android.widget.Toast
import com.jarvis.assistant.JarvisApp
import kotlin.math.sqrt

/**
 * JARVIS Touch Guard Sentinel:
 * Monitors device movement via accelerometer when armed.
 * Triggers alarm and front-camera intruder capture when picked up or moved.
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

    fun isArmed(): Boolean = isArmed
    fun isStealth(): Boolean = isStealth

    fun arm(context: Context, stealth: Boolean = false) {
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

        sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        JarvisApp.instance.preferences.isTouchGuardArmed = true
        JarvisApp.instance.preferences.isTouchGuardStealth = stealth

        if (!stealth) {
            Toast.makeText(appContext, "Touch Guard is ARMED. Phone is protected.", Toast.LENGTH_LONG).show()
        }
    }

    fun disarm(context: Context) {
        isArmed = false
        sensorManager?.unregisterListener(this)
        stopAlarm()
        JarvisApp.instance.preferences.isTouchGuardArmed = false
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
            triggerAlarm()
        }

        lastX = x
        lastY = y
        lastZ = z
    }

    private fun triggerAlarm() {
        val app = JarvisApp.instance
        try {
            WatchmanSecurityManager(app).captureIntruderPhoto { photo ->
                Log.i(TAG, "Touch Guard captured intruder photo: ${photo?.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in intruder photo capture: ${e.message}")
        }

        if (!isStealth) {
            startSirenAlarm(app)
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
