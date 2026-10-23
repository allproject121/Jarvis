package com.jarvis.assistant.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.jarvis.assistant.voice.PocketTtsEngine

/**
 * JARVIS Phone Call Sentinel.
 * Detects incoming calls and performs voice caller announcement via Pocket-TTS.
 */
class JarvisPhoneReceiver : BroadcastReceiver() {

    companion object {
        private var lastState = TelephonyManager.EXTRA_STATE_IDLE
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            if (state == TelephonyManager.EXTRA_STATE_RINGING && lastState != TelephonyManager.EXTRA_STATE_RINGING) {
                Log.d("JarvisPhoneReceiver", "Incoming call from: $number")
                val callerText = if (!number.isNullOrBlank()) "Incoming call from $number, Sir." else "Incoming call, Sir."
                try {
                    PocketTtsEngine.getInstance(context).speak(callerText)
                } catch (e: Exception) {
                    Log.w("JarvisPhoneReceiver", "TTS caller announcement notice: ${e.message}")
                }
                JarvisDrivingModeManager.getInstance(context).onIncomingCall(number ?: "Unknown Caller")
            }
            lastState = state
        }
    }
}
