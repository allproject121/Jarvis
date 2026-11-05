package com.jarvis.assistant.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * JARVIS Phone Call Sentinel.
 * Detects incoming calls and hands them to JarvisDrivingModeManager, which decides whether to
 * announce the caller (voice session or standalone TTS) or, in driving mode, auto-reject + SMS.
 */
class JarvisPhoneReceiver : BroadcastReceiver() {

    companion object {
        private var lastState = TelephonyManager.EXTRA_STATE_IDLE
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            if (state == TelephonyManager.EXTRA_STATE_RINGING && lastState != TelephonyManager.EXTRA_STATE_RINGING) {
                Log.d("JarvisPhoneReceiver", "Incoming call from: $number")
                // The announcement itself lives in the manager so a call that is auto-rejected
                // while driving is not spoken first, and the caller-ID toggle is respected.
                try {
                    JarvisDrivingModeManager.getInstance(context)
                        .onIncomingCall(if (number.isNullOrBlank()) "Unknown Caller" else number)
                } catch (e: Exception) {
                    Log.w("JarvisPhoneReceiver", "Incoming call handling notice: ${e.message}")
                }
            }
            lastState = state
        }
    }
}
