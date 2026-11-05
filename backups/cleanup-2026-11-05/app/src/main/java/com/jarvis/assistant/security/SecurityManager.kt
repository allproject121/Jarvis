package com.jarvis.assistant.security

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlin.system.exitProcess

/**
 * JARVIS ASSISTANT — SECURITY MODULE
 * Developer       : Roshan
 * Copyright © 2026: Roshan. All Rights Reserved.
 * GitHub          : https://github.com/tiwariroshan121
 */
object SecurityManager {

    private const val TAG = "SecurityManager"
    const val DEVELOPER_NAME    = "Roshan"
    const val COPYRIGHT_NOTICE  = "Copyright © 2026 Roshan. All Rights Reserved."
    const val GITHUB_REPO       = "https://github.com/tiwariroshan121"
    const val LICENSE_NAME      = "TAMCL v1.0 — Content creators MUST credit Roshan"

    private const val EXPECTED_PACKAGE = "com.jarvis.assistant"

    fun verify(context: Context) {
        checkPackageName(context)
    }

    private fun checkPackageName(context: Context) {
        if (context.packageName != EXPECTED_PACKAGE) {
            showTamperAlert(
                context,
                "Clone Detected",
                "This is an unauthorised clone of the JARVIS app by $DEVELOPER_NAME.\n\n" +
                "Original: $EXPECTED_PACKAGE\n" +
                "Found   : ${context.packageName}\n\n" +
                "The app will now close. Please download the official version from:\n$GITHUB_REPO"
            )
        }
    }

    private fun showTamperAlert(context: Context, title: String, message: String) {
        Log.e(TAG, "SECURITY ALERT: $title — $message")
        try {
            if (context is Activity && !context.isFinishing && !context.isDestroyed) {
                AlertDialog.Builder(context)
                    .setTitle("⚠️ $title")
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("OK") { _, _ -> exitProcess(1) }
                    .show()
            } else {
                Handler(Looper.getMainLooper()).post {
                    try {
                        Toast.makeText(context.applicationContext, "⚠️ $title\n$message", Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {}
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    exitProcess(1)
                }, 3500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display tamper alert: ${e.message}")
            exitProcess(1)
        }
    }
}
