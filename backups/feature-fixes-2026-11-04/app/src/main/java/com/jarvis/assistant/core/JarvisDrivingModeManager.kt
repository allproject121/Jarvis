package com.jarvis.assistant.core

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Driving Mode & Hands-Free Call Sentinel for Jarvis.
 * Automatically engages when connected to vehicle Bluetooth audio.
 * Provides voice-guided caller ID announcement and hands-free call control.
 */
class JarvisDrivingModeManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "JarvisDrivingMode"

        @Volatile
        private var instance: JarvisDrivingModeManager? = null

        fun getInstance(context: Context): JarvisDrivingModeManager {
            return instance ?: synchronized(this) {
                instance ?: JarvisDrivingModeManager(context.applicationContext).also { instance = it }
            }
        }
    }

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

            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val deviceClass = device.bluetoothClass?.deviceClass ?: 0
                    val isCarAudio = deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE ||
                            (device.name?.contains("car", ignoreCase = true) == true)

                    Log.i(TAG, "Bluetooth device connected: ${device.name} (isCarAudio=$isCarAudio)")
                    val prefs = JarvisApp.instance.preferences
                    if (isCarAudio && prefs.isDrivingModeAutoCarEnabled) {
                        setDrivingMode(true)
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.i(TAG, "Bluetooth device disconnected: ${device.name}")
                    if (_isDrivingMode.value) {
                        setDrivingMode(false)
                    }
                }
            }
        }
    }

    init {
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

    fun setDrivingMode(enabled: Boolean) {
        _isDrivingMode.value = enabled
        Log.i(TAG, "Driving Mode toggled: $enabled")
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

    /**
     * Announces incoming call and awaits voice direction if driving mode is active.
     */
    fun onIncomingCall(callerNameOrNumber: String) {
        val prefs = JarvisApp.instance.preferences
        if (!prefs.isCallerIdAnnouncementEnabled && !_isDrivingMode.value) return

        Log.i(TAG, "Announcing incoming call from: $callerNameOrNumber")
        if (JarvisConversationService.isSessionOn.value) {
            JarvisConversationService.sendTextMessage(
                "[CALL SENTINEL: Incoming telephone call from '$callerNameOrNumber'. Announce clearly to user: 'Sir, incoming call from $callerNameOrNumber. Would you like me to answer or decline?']"
            )
        }
    }

    /**
     * Answers ringing phone call (requires ANSWER_PHONE_CALLS permission).
     */
    fun answerCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            return try {
                telecomManager?.acceptRingingCall()
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
     * Declines/ends ringing phone call.
     */
    fun declineCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            return try {
                telecomManager?.endCall()
                Log.i(TAG, "Incoming call declined via TelecomManager.")
                true
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing ANSWER_PHONE_CALLS permission: ${e.message}")
                false
            }
        }
        return false
    }
}
