package com.jarvis.assistant.maya.ui

import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.GeminiConstants

/**
 * The Maya Intimate entry: an 18+ age confirmation, then the same closeness
 * choice the companion home always offered (Friendly / Romantic / Intense 18+),
 * stored in `isSecretSettingsEnabled` + `secretIntimacyLevel`.
 */
object MayaIntimateGate {

    private val LEVELS = arrayOf(
        "Friendly — sweet everyday companion",
        "Romantic — loving girlfriend (Pari)",
        "Intense (18+) — deep romantic register"
    )

    /** Run the gate; [onChosen] receives the chosen level (1..3) once the character is active. */
    fun open(context: Context, onChosen: (level: Int) -> Unit = {}) {
        if (MayaUiPrefs.isIntimateAgeConfirmed(context)) {
            showLevels(context, onChosen)
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("This mode is 18+")
            .setMessage(
                "Maya Intimate changes how close Maya is — Friendly, Romantic or Intense (18+).\n\n" +
                    "Answer the age question first: are you 18 or older?"
            )
            .setPositiveButton("I am 18 or older") { _, _ ->
                MayaUiPrefs.setIntimateAgeConfirmed(context, true)
                showLevels(context, onChosen)
            }
            .setNegativeButton("No") { _, _ ->
                Toast.makeText(context, "Maya Intimate stays locked.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showLevels(context: Context, onChosen: (Int) -> Unit) {
        val prefs = JarvisApp.instance.preferences
        val current = (prefs.secretIntimacyLevel - 1).coerceIn(0, 2)
        MaterialAlertDialogBuilder(context)
            .setTitle("How close Maya is")
            .setSingleChoiceItems(LEVELS, current) { dialog, which ->
                prefs.isSecretSettingsEnabled = true
                prefs.secretIntimacyLevel = which + 1
                prefs.jarvisActiveCharacter = MayaCharacters.MAYA_INTIMATE.prefName
                prefs.personality = GeminiConstants.PERSONALITY_COMPANION
                Toast.makeText(context, "Maya Intimate • ${LEVELS[which].substringBefore(" —")}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                onChosen(which + 1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Forget the age confirmation (e.g. from a reset). */
    fun reset(context: Context) = MayaUiPrefs.setIntimateAgeConfirmed(context, false)
}
