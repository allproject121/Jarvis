package com.jarvis.assistant.maya.guardian

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WipeGuardTest {

    private val phrase = WipeGuard.CONFIRM_PHRASE

    @Test
    fun refusesWithoutDeviceAdmin() {
        val v = WipeGuard.evaluate(adminActive = false, confirmPhrase = phrase, ownerGenuinelyVerified = true)
        assertFalse(v.allowed)
        assertTrue(v.reason.contains("device administrator"))
    }

    @Test
    fun refusesWrongOrMissingPhrase() {
        for (p in listOf(null, "", "wipe my phone now", "WIPE MY PHONE", "yes")) {
            val v = WipeGuard.evaluate(true, p, true)
            assertFalse("phrase '$p' must be refused", v.allowed)
            assertTrue(v.reason.contains(phrase))
        }
    }

    @Test
    fun refusesWithoutGenuineOwnerVoice() {
        val v = WipeGuard.evaluate(true, phrase, ownerGenuinelyVerified = false)
        assertFalse(v.allowed)
        assertTrue(v.reason.contains("voice"))
    }

    @Test
    fun allowsOnlyWhenAllChecksPass() {
        assertTrue(WipeGuard.evaluate(true, "  $phrase ", true).allowed)
    }
}
