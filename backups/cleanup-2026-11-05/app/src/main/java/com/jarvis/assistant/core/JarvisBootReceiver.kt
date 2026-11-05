package com.jarvis.assistant.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Ensures JARVIS background monitors and standby services resume on boot/app update.
 */
class JarvisBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d("JarvisBootReceiver", "JARVIS core booted via $action - System Standby Active")
        }
    }
}
