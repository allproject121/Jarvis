package com.jarvis.assistant.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.core.JarvisDrivingModeManager
import com.jarvis.assistant.data.preferences.AppPreferences

/**
 * Shared UI for the driving-mode incoming-call option (Settings > Driving Mode and SuperCore).
 * Binds to [JarvisDrivingModeManager.drivingCallMode] / [JarvisDrivingModeManager.autoReplyText].
 * The hosting screen owns the RequestMultiplePermissions launcher.
 */
object DrivingCallSettings {

    /** Needed to end the call and text the caller - without them auto-reject is reverted. */
    val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.SEND_SMS
    )

    /** Needed to learn the caller's number on Android 10+ (otherwise the SMS cannot be addressed). */
    private val NUMBER_PERMISSIONS = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG
    )

    fun isAutoReject(context: Context): Boolean =
        JarvisDrivingModeManager.getInstance(context).drivingCallMode == AppPreferences.DRIVING_CALL_MODE_AUTO_REJECT_SMS

    fun modeLabel(context: Context): String =
        if (isAutoReject(context)) "Auto-reject + SMS reply" else "Announce caller"

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Permissions to request before enabling auto-reject (empty = ready). */
    fun missingPermissions(context: Context): Array<String> =
        (REQUIRED_PERMISSIONS + NUMBER_PERMISSIONS).filterNot { granted(context, it) }.toTypedArray()

    fun setAnnounce(context: Context) {
        JarvisDrivingModeManager.getInstance(context).drivingCallMode = AppPreferences.DRIVING_CALL_MODE_ANNOUNCE
    }

    /**
     * Call from the permission launcher's result. Enables auto-reject when ANSWER_PHONE_CALLS and
     * SEND_SMS are granted, otherwise reverts to Announce. Returns the message to show the user.
     */
    fun applyAfterPermissionResult(context: Context): String {
        val manager = JarvisDrivingModeManager.getInstance(context)
        val missingRequired = REQUIRED_PERMISSIONS.filterNot { granted(context, it) }
        if (missingRequired.isNotEmpty()) {
            manager.drivingCallMode = AppPreferences.DRIVING_CALL_MODE_ANNOUNCE
            val names = missingRequired.joinToString(" and ") {
                if (it == Manifest.permission.SEND_SMS) "SMS" else "Phone call control"
            }
            return "Auto-reject needs $names permission - kept \"Announce caller\"."
        }
        manager.drivingCallMode = AppPreferences.DRIVING_CALL_MODE_AUTO_REJECT_SMS
        return if (NUMBER_PERMISSIONS.all { granted(context, it) }) {
            "Calls while driving will be rejected with an SMS reply."
        } else {
            "Auto-reject ON. Without Phone/Call-log permission the caller's number is hidden, so the SMS may not be sent."
        }
    }

    /**
     * Single-choice "When a call comes while driving" dialog.
     * [requestPermissions] is invoked with the missing permissions when the user picks auto-reject.
     */
    fun showCallModeDialog(
        context: Context,
        requestPermissions: (Array<String>) -> Unit,
        onChanged: () -> Unit
    ) {
        val options = arrayOf("Announce caller", "Auto-reject + SMS reply")
        MaterialAlertDialogBuilder(context)
            .setTitle("When a call comes while driving")
            .setSingleChoiceItems(options, if (isAutoReject(context)) 1 else 0) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    setAnnounce(context)
                    Toast.makeText(context, "Calls while driving: Announce caller", Toast.LENGTH_SHORT).show()
                    onChanged()
                } else {
                    val missing = missingPermissions(context)
                    if (missing.isEmpty()) {
                        Toast.makeText(context, applyAfterPermissionResult(context), Toast.LENGTH_LONG).show()
                        onChanged()
                    } else {
                        requestPermissions(missing)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showAutoReplyTextDialog(context: Context, onSaved: () -> Unit) {
        val manager = JarvisDrivingModeManager.getInstance(context)
        val input = EditText(context).apply {
            setText(manager.autoReplyText)
            setSelection(text.length)
            hint = AppPreferences.DEFAULT_DRIVING_AUTO_REPLY_TEXT
            minLines = 2
            setPadding(48, 24, 48, 24)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Auto-reply SMS text")
            .setMessage("Sent to callers rejected while you are driving.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                // Blank falls back to the default text (AppPreferences.drivingAutoReplyText).
                manager.autoReplyText = input.text.toString()
                Toast.makeText(context, "Auto-reply text saved", Toast.LENGTH_SHORT).show()
                onSaved()
            }
            .setNeutralButton("Default") { _, _ ->
                manager.autoReplyText = ""
                Toast.makeText(context, "Auto-reply text reset to default", Toast.LENGTH_SHORT).show()
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
