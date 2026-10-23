package com.jarvis.assistant.security.guardian

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import com.jarvis.assistant.JarvisApp

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
        val contacts = prefs.emergencySosContacts.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val locationSuffix = if (locationString.isNotBlank()) "\nLocation: $locationString" else ""
        val message = "EMERGENCY ALERT: I need immediate assistance! Please contact me or emergency services.$locationSuffix\n- Sent via Jarvis SOS"

        try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            for (contact in contacts) {
                if (contact.length >= 3) {
                    try {
                        smsManager?.sendTextMessage(contact, null, message, null, null)
                        Log.i(TAG, "Sent SOS text to $contact")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send SMS to $contact: ${e.message}")
                    }
                }
            }
            Toast.makeText(context, "EMERGENCY SOS DISPATCHED TO ${contacts.size} CONTACTS!", Toast.LENGTH_LONG).show()
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

    fun stopSos() {
        isSosActive = false
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}
    }
}
