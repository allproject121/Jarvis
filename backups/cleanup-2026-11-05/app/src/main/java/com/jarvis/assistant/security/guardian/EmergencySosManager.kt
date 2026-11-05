package com.jarvis.assistant.security.guardian

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * JARVIS Emergency SOS Dispatcher:
 * Broadcasts emergency alert, sounds loud siren, and dispatches location coordinates.
 */
object EmergencySosManager {
    private const val TAG = "EmergencySosManager"
    private var mediaPlayer: MediaPlayer? = null
    private var isSosActive = false

    fun isSosActive(): Boolean = isSosActive

    fun triggerSos(context: Context, locationString: String = "") {
        isSosActive = true
        val prefs = JarvisApp.instance.preferences

        if (prefs.isEmergencySosSirenEnabled) {
            startSiren(context)
        }

        // Send SMS to emergency contacts
        val contacts = configuredContacts()
        if (contacts.isEmpty()) {
            Toast.makeText(context, "No emergency contacts configured — SOS message not sent.", Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "SMS permission not granted — SOS message not sent.", Toast.LENGTH_LONG).show()
            return
        }
        val locationSuffix = if (locationString.isNotBlank()) "\nLocation: $locationString" else ""
        val message = "EMERGENCY ALERT: I need immediate assistance! Please contact me or emergency services.$locationSuffix\n- Sent via Jarvis SOS"

        try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            var sent = 0
            for (contact in contacts) {
                try {
                    // Location + maps link can exceed one SMS segment.
                    val parts = smsManager?.divideMessage(message)
                    if (parts != null && parts.size > 1) {
                        smsManager?.sendMultipartTextMessage(contact, null, parts, null, null)
                    } else {
                        smsManager?.sendTextMessage(contact, null, message, null, null)
                    }
                    if (smsManager != null) sent++
                    Log.i(TAG, "Sent SOS text to $contact")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send SMS to $contact: ${e.message}")
                }
            }
            Toast.makeText(context, "EMERGENCY SOS SENT TO $sent OF ${contacts.size} CONTACTS", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "SMS dispatch error: ${e.message}")
            Toast.makeText(context, "SOS Alert triggered!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSiren(context: Context) {
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
            Log.e(TAG, "Could not start SOS siren: ${e.message}")
        }
    }

    /** Configured emergency numbers (Settings › Emergency SOS), trimmed and non-blank. */
    fun configuredContacts(): List<String> =
        JarvisApp.instance.preferences.emergencySosContacts
            .split(",").map { it.trim() }.filter { it.length >= 3 }

    /**
     * Fetches the device's current location with the platform LocationManager
     * (no Play Services). API 30+: getCurrentLocation on GPS and network
     * providers, first fix wins; older: requestSingleUpdate. Falls back to the
     * freshest last-known fix after [timeoutMs]. [onResult] runs on the main
     * thread with null when no location permission / provider / fix is available.
     */
    @SuppressLint("MissingPermission")
    fun fetchCurrentLocation(context: Context, timeoutMs: Long = 10_000L, onResult: (Location?) -> Unit) {
        val appCtx = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val hasFine = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if ((!hasFine && !hasCoarse) || lm == null) {
            main.post { onResult(null) }
            return
        }

        val candidates = if (hasFine) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER)
        }
        val providers = candidates.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        fun lastKnown(): Location? = (providers + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

        val done = AtomicBoolean(false)
        fun finish(location: Location?) {
            if (!done.compareAndSet(false, true)) return
            val result = location ?: lastKnown()
            main.post { onResult(result) }
        }

        if (providers.isEmpty()) {
            finish(null)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signals = providers.map { CancellationSignal() }
            val pending = AtomicInteger(providers.size)
            providers.forEachIndexed { i, provider ->
                try {
                    lm.getCurrentLocation(provider, signals[i], ContextCompat.getMainExecutor(appCtx)) { loc ->
                        if (loc != null) {
                            signals.forEach { runCatching { it.cancel() } }
                            finish(loc)
                        } else if (pending.decrementAndGet() == 0) {
                            finish(null)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getCurrentLocation($provider) failed: ${e.message}")
                    if (pending.decrementAndGet() == 0) finish(null)
                }
            }
            main.postDelayed({
                signals.forEach { runCatching { it.cancel() } }
                finish(null)
            }, timeoutMs)
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    runCatching { lm.removeUpdates(this) }
                    finish(location)
                }
            }
            providers.forEach { provider ->
                try {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    Log.w(TAG, "requestSingleUpdate($provider) failed: ${e.message}")
                }
            }
            main.postDelayed({
                runCatching { lm.removeUpdates(listener) }
                finish(null)
            }, timeoutMs)
        }
    }

    /** "lat, lng (±N m)\nhttps://maps.google.com/?q=lat,lng" or "location unavailable". */
    fun formatLocation(location: Location?): String {
        if (location == null) return "location unavailable"
        val lat = String.format(Locale.US, "%.6f", location.latitude)
        val lng = String.format(Locale.US, "%.6f", location.longitude)
        val accuracy = if (location.hasAccuracy()) " (±${location.accuracy.toInt()} m)" else ""
        return "$lat, $lng$accuracy\nhttps://maps.google.com/?q=$lat,$lng"
    }

    fun stopSos() {
        isSosActive = false
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}
    }
}
