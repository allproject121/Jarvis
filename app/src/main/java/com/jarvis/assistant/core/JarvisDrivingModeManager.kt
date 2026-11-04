package com.jarvis.assistant.core

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Driving Mode & Hands-Free Call Sentinel for Jarvis.
 * Automatically engages when connected to vehicle Bluetooth audio.
 * Provides voice-guided caller ID announcement and hands-free call control.
 * The on/off state is persisted in AppPreferences so it survives an app restart.
 */
class JarvisDrivingModeManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "JarvisDrivingMode"
        private const val NOTIFICATION_CHANNEL_ID = "jarvis_driving_mode"
        private const val REJECT_NOTIFICATION_ID = 0x4A44 // "JD"
        private const val TTS_SAFETY_SHUTDOWN_MS = 20_000L

        @Volatile
        private var instance: JarvisDrivingModeManager? = null

        fun getInstance(context: Context): JarvisDrivingModeManager {
            return instance ?: synchronized(this) {
                instance ?: JarvisDrivingModeManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: AppPreferences
        get() = JarvisApp.instance.preferences

    private val _isDrivingMode = MutableStateFlow(false)
    val isDrivingMode: StateFlow<Boolean> = _isDrivingMode.asStateFlow()

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            // name / class / address need BLUETOOTH_CONNECT on Android 12+.
            val deviceName = try { device.name } catch (_: SecurityException) { null }
            val deviceAddress = try { device.address } catch (_: SecurityException) { null }

            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val deviceClass = try { device.bluetoothClass?.deviceClass ?: 0 } catch (_: SecurityException) { 0 }
                    val isCarAudio = deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE ||
                            (deviceName?.contains("car", ignoreCase = true) == true)

                    Log.i(TAG, "Bluetooth device connected: $deviceName (isCarAudio=$isCarAudio)")
                    // Only auto-engage when currently off, so a manual enable stays manual.
                    if (isCarAudio && prefs.isDrivingModeAutoCarEnabled && !_isDrivingMode.value) {
                        applyDrivingMode(true, carDeviceAddress = deviceAddress.orEmpty())
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.i(TAG, "Bluetooth device disconnected: $deviceName")
                    if (DrivingCallPolicy.shouldDisableOnDisconnect(
                            isDriving = _isDrivingMode.value,
                            rememberedCarAddress = prefs.drivingCarDeviceAddress,
                            disconnectedAddress = deviceAddress
                        )
                    ) {
                        Log.i(TAG, "Car device that engaged driving mode disconnected - disengaging.")
                        applyDrivingMode(false, carDeviceAddress = "")
                    }
                }
            }
        }
    }

    init {
        // Restore the last persisted state. Only the flag is restored here - the voice session
        // is not auto-started from Application.onCreate (background FGS starts are restricted).
        _isDrivingMode.value = try { prefs.drivingModeEnabled } catch (_: Throwable) { false }
        Log.i(TAG, "Driving Mode restored: ${_isDrivingMode.value}")

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            context.registerReceiver(bluetoothReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering bluetooth receiver: ${e.message}")
        }
    }

    /** Manual on/off (Settings, SuperCore, voice command, tool). Never auto-disabled by Bluetooth. */
    fun setDrivingMode(enabled: Boolean) {
        applyDrivingMode(enabled, carDeviceAddress = "")
    }

    private fun applyDrivingMode(enabled: Boolean, carDeviceAddress: String) {
        _isDrivingMode.value = enabled
        try {
            prefs.drivingModeEnabled = enabled
            prefs.drivingCarDeviceAddress = if (enabled) carDeviceAddress else ""
        } catch (e: Throwable) {
            Log.w(TAG, "Could not persist driving mode state: ${e.message}")
        }
        Log.i(TAG, "Driving Mode toggled: $enabled" + if (carDeviceAddress.isNotBlank()) " (car=$carDeviceAddress)" else "")
        if (enabled && !JarvisConversationService.isSessionOn.value) {
            val intent = Intent(context, JarvisConversationService::class.java).apply {
                action = JarvisConversationService.ACTION_START_CONVERSATION
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }
    }

    // ---- Incoming-call options (exposed for a Settings UI row) ----

    /** AppPreferences.DRIVING_CALL_MODE_ANNOUNCE or AppPreferences.DRIVING_CALL_MODE_AUTO_REJECT_SMS. */
    var drivingCallMode: String
        get() = prefs.drivingCallMode
        set(value) { prefs.drivingCallMode = value }

    var isAutoRejectSmsEnabled: Boolean
        get() = prefs.drivingCallMode == AppPreferences.DRIVING_CALL_MODE_AUTO_REJECT_SMS
        set(value) {
            prefs.drivingCallMode = if (value) AppPreferences.DRIVING_CALL_MODE_AUTO_REJECT_SMS
            else AppPreferences.DRIVING_CALL_MODE_ANNOUNCE
        }

    var autoReplyText: String
        get() = prefs.drivingAutoReplyText
        set(value) { prefs.drivingAutoReplyText = value }

    /**
     * Handles an incoming call (called by JarvisPhoneReceiver on RINGING).
     * - Driving + AUTO_REJECT_SMS: ends the call and texts the caller (falls back to announcing
     *   when ANSWER_PHONE_CALLS is missing or the reject fails).
     * - Otherwise announces the caller through the live voice session, or a standalone TTS
     *   instance when no session is running.
     */
    fun onIncomingCall(callerNameOrNumber: String) {
        val driving = _isDrivingMode.value

        if (driving && prefs.drivingCallMode == AppPreferences.DRIVING_CALL_MODE_AUTO_REJECT_SMS) {
            if (rejectAndReply(callerNameOrNumber)) return
            Log.w(TAG, "Auto-reject unavailable - falling back to announcing the caller.")
        }

        if (!prefs.isCallerIdAnnouncementEnabled && !driving) return

        Log.i(TAG, "Announcing incoming call from: $callerNameOrNumber")
        if (JarvisConversationService.isSessionOn.value) {
            JarvisConversationService.sendTextMessage(
                "[CALL SENTINEL: Incoming telephone call from '$callerNameOrNumber'. Announce clearly to user: 'Sir, incoming call from $callerNameOrNumber. Would you like me to answer or decline?']"
            )
        } else {
            speakStandalone("Sir, incoming call from $callerNameOrNumber.")
        }
    }

    /**
     * Ends the ringing call and sends the driving auto-reply SMS. Returns false (without side
     * effects) when the call could not be rejected, so the caller can be announced instead.
     */
    private fun rejectAndReply(callerNumber: String): Boolean {
        if (!hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
            Log.w(TAG, "ANSWER_PHONE_CALLS not granted - cannot auto-reject.")
            return false
        }
        if (!declineCall()) return false

        val smsStatus = when {
            !DrivingCallPolicy.isDialableNumber(callerNumber) -> "no SMS (hidden number)"
            !hasPermission(Manifest.permission.SEND_SMS) -> "SMS not sent (SMS permission missing)"
            sendSms(callerNumber, prefs.drivingAutoReplyText) -> "SMS sent"
            else -> "SMS failed"
        }
        val summary = "Rejected call from $callerNumber while driving, $smsStatus"
        Log.i(TAG, summary)
        postNotification(summary)
        return true
    }

    @SuppressLint("MissingPermission") // permission checked by the caller / SecurityException caught
    private fun sendSms(number: String, text: String): Boolean {
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            } ?: return false
            val parts = smsManager.divideMessage(text)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(number, null, text, null, null)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Driving auto-reply SMS failed: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission") // permission checked by the caller / SecurityException caught
    private fun postNotification(text: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(NOTIFICATION_CHANNEL_ID, "Driving Mode", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "Calls handled while driving" }
                )
            }
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_call_missed)
                .setContentTitle("Driving Mode")
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(REJECT_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Driving notification failed: ${e.message}")
        }
    }

    /** One-shot announcement with its own TextToSpeech instance; shut down once spoken. */
    private fun speakStandalone(text: String) {
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            var tts: TextToSpeech? = null
            var released = false
            val release = {
                if (!released) {
                    released = true
                    try { tts?.shutdown() } catch (_: Exception) {}
                }
            }
            try {
                tts = TextToSpeech(context) { status ->
                    val engine = tts
                    if (status != TextToSpeech.SUCCESS || engine == null) {
                        Log.w(TAG, "Standalone TTS init failed (status=$status)")
                        mainHandler.post { release() }
                    } else {
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) { mainHandler.post { release() } }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) { mainHandler.post { release() } }
                        })
                        val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_driving_call")
                        if (queued != TextToSpeech.SUCCESS) mainHandler.post { release() }
                    }
                }
                // Safety net in case the engine never reports completion.
                mainHandler.postDelayed({ release() }, TTS_SAFETY_SHUTDOWN_MS)
            } catch (e: Exception) {
                Log.w(TAG, "Standalone TTS unavailable: ${e.message}")
                release()
            }
        }
    }

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Answers ringing phone call (requires ANSWER_PHONE_CALLS permission).
     */
    @SuppressLint("MissingPermission") // permission checked by the caller / SecurityException caught
    fun answerCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return false
            return try {
                telecomManager.acceptRingingCall()
                Log.i(TAG, "Incoming call answered via TelecomManager.")
                true
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing ANSWER_PHONE_CALLS permission: ${e.message}")
                false
            }
        }
        return false
    }

    /**
     * Declines/ends ringing phone call. Returns TelecomManager's real result.
     */
    @SuppressLint("MissingPermission") // permission checked by the caller / SecurityException caught
    fun declineCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return false
            return try {
                @Suppress("DEPRECATION")
                val ended = telecomManager.endCall()
                Log.i(TAG, "Incoming call decline via TelecomManager: ended=$ended")
                ended
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing ANSWER_PHONE_CALLS permission: ${e.message}")
                false
            }
        }
        return false
    }
}
