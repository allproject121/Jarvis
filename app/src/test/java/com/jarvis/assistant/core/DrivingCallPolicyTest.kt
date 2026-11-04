package com.jarvis.assistant.core

import com.jarvis.assistant.automation.command.CommandParser
import com.jarvis.assistant.automation.command.JarvisCommand
import com.jarvis.assistant.data.preferences.AppPreferences
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying [DrivingCallPolicy] decisions and the driving-mode voice phrases.
 */
class DrivingCallPolicyTest {

    @Test
    fun testDialableNumbers() {
        assertTrue(DrivingCallPolicy.isDialableNumber("+91 98765-43210"))
        assertTrue(DrivingCallPolicy.isDialableNumber("(022) 1234 5678"))
        assertFalse(DrivingCallPolicy.isDialableNumber("Unknown Caller"))
        assertFalse(DrivingCallPolicy.isDialableNumber("Private"))
        assertFalse(DrivingCallPolicy.isDialableNumber(""))
        assertFalse(DrivingCallPolicy.isDialableNumber(null))
    }

    @Test
    fun testDisconnectOnlyDisablesForRememberedCar() {
        val car = "AA:BB:CC:DD:EE:FF"
        assertTrue(DrivingCallPolicy.shouldDisableOnDisconnect(true, car, "aa:bb:cc:dd:ee:ff"))
        // Another device (e.g. earbuds) disconnecting must not turn driving mode off.
        assertFalse(DrivingCallPolicy.shouldDisableOnDisconnect(true, car, "11:22:33:44:55:66"))
        // Manual enable (no remembered car) is never auto-disabled.
        assertFalse(DrivingCallPolicy.shouldDisableOnDisconnect(true, "", car))
        assertFalse(DrivingCallPolicy.shouldDisableOnDisconnect(false, car, car))
    }

    @Test
    fun testNormalizeCallMode() {
        assertEquals(AppPreferences.DRIVING_CALL_MODE_AUTO_REJECT_SMS, DrivingCallPolicy.normalizeCallMode("auto_reject_sms"))
        assertEquals(AppPreferences.DRIVING_CALL_MODE_AUTO_REJECT_SMS, DrivingCallPolicy.normalizeCallMode("AUTO_REJECT_SMS"))
        assertEquals(AppPreferences.DRIVING_CALL_MODE_ANNOUNCE, DrivingCallPolicy.normalizeCallMode("announce"))
        assertNull(DrivingCallPolicy.normalizeCallMode(""))
        assertNull(DrivingCallPolicy.normalizeCallMode("banana"))
    }

    @Test
    fun testStatusMessage() {
        assertEquals("Driving mode is OFF.", DrivingCallPolicy.statusMessage(false, AppPreferences.DRIVING_CALL_MODE_ANNOUNCE))
        assertTrue(DrivingCallPolicy.statusMessage(true, AppPreferences.DRIVING_CALL_MODE_AUTO_REJECT_SMS).contains("SMS"))
        assertTrue(DrivingCallPolicy.statusMessage(true, AppPreferences.DRIVING_CALL_MODE_ANNOUNCE).contains("announce"))
    }

    @Test
    fun testDrivingModeVoicePhrases() {
        for (phrase in listOf("driving mode on", "drive mode on", "driving mode chalu karo", "turn on driving mode", "jarvis driving mode on")) {
            val parsed = CommandParser.parse(phrase, false, false)
            assertEquals(phrase, JarvisCommand.SetDrivingMode(true), parsed.command)
        }
        for (phrase in listOf("driving mode off", "drive mode off", "driving mode band karo", "turn off driving mode")) {
            val parsed = CommandParser.parse(phrase, false, false)
            assertEquals(phrase, JarvisCommand.SetDrivingMode(false), parsed.command)
        }
    }
}
