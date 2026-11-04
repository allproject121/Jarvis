package com.jarvis.assistant.core

import com.jarvis.assistant.data.preferences.AppPreferences

/**
 * Pure (Android-free) decision helpers for [JarvisDrivingModeManager], kept separate so they
 * can be unit-tested on the JVM.
 */
object DrivingCallPolicy {

    /**
     * True when [raw] looks like a real phone number we can text back.
     * Hidden / private / "Unknown Caller" values return false.
     */
    fun isDialableNumber(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val cleaned = raw.trim().replace(Regex("[\\s\\-().]"), "")
        val digits = cleaned.removePrefix("+")
        return digits.length >= 3 && digits.all { it.isDigit() }
    }

    /**
     * Driving mode is only auto-disabled when the device that disconnected is the same car
     * device that auto-enabled it. A manual enable (no remembered car address) is never
     * turned off by a Bluetooth disconnect.
     */
    fun shouldDisableOnDisconnect(
        isDriving: Boolean,
        rememberedCarAddress: String?,
        disconnectedAddress: String?
    ): Boolean {
        if (!isDriving) return false
        if (rememberedCarAddress.isNullOrBlank() || disconnectedAddress.isNullOrBlank()) return false
        return rememberedCarAddress.equals(disconnectedAddress, ignoreCase = true)
    }

    /** Spoken summary of the real driving-mode state after a toggle. */
    fun statusMessage(enabled: Boolean, callMode: String): String {
        if (!enabled) return "Driving mode is OFF."
        return if (callMode == AppPreferences.DRIVING_CALL_MODE_AUTO_REJECT_SMS) {
            "Driving mode is ON. Incoming calls will be rejected with an auto-reply SMS."
        } else {
            "Driving mode is ON. I'll announce incoming calls."
        }
    }

    /**
     * Maps user / tool input ("announce", "auto_reject_sms", "reject", ...) to one of the
     * stored call-mode constants. Returns null when [raw] is blank or not recognised.
     */
    fun normalizeCallMode(raw: String?): String? {
        val value = raw?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_') ?: return null
        return when {
            value.isEmpty() -> null
            value == "auto_reject_sms" || value == "auto_reject" || value == "reject" ||
                value == "reject_sms" || value.contains("sms") -> AppPreferences.DRIVING_CALL_MODE_AUTO_REJECT_SMS
            value == "announce" || value == "speak" -> AppPreferences.DRIVING_CALL_MODE_ANNOUNCE
            else -> null
        }
    }
}
