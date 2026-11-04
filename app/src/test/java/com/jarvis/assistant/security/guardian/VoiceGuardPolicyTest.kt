package com.jarvis.assistant.security.guardian

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGuardPolicyTest {

    @Test
    fun riskyToolsNeedTheOwner() {
        for (name in listOf(
            "send_sms", "make_call", "handle_phone_call", "call_contact", "whatsapp_call",
            "send_whatsapp_message", "reply_to_message", "execute_connected_app_action",
            "run_shell_command", "create_dynamic_tool", "edit_dynamic_tool",
            "delete_dynamic_tool", "delete_memory", "termux_run", "upi_payment"
        )) {
            assertTrue(name, VoiceGuardPolicy.isRiskyTool(name, JSONObject()))
        }
    }

    @Test
    fun harmlessToolsAreNotGuarded() {
        for (name in listOf("get_weather", "set_flashlight", "get_battery_status", "search_youtube", "list_dynamic_tools", "find_contacts")) {
            assertFalse(name, VoiceGuardPolicy.isRiskyTool(name, JSONObject()))
        }
    }

    @Test
    fun drivingModeOnlyRiskyWhenAutoRejecting() {
        assertTrue(VoiceGuardPolicy.isRiskyTool("set_driving_mode", JSONObject().put("enabled", true).put("call_mode", "auto_reject_sms")))
        assertFalse(VoiceGuardPolicy.isRiskyTool("set_driving_mode", JSONObject().put("enabled", true).put("call_mode", "announce")))
        assertFalse(VoiceGuardPolicy.isRiskyTool("set_driving_mode", JSONObject().put("enabled", false)))
        assertFalse(VoiceGuardPolicy.isRiskyTool("set_driving_mode", null))
    }

    @Test
    fun ownerVerificationWindow() {
        val now = 1_000_000L
        assertFalse(VoiceGuardPolicy.isOwnerRecentlyVerified(now, lastVerifiedMs = 0L))
        assertTrue(VoiceGuardPolicy.isOwnerRecentlyVerified(now, lastVerifiedMs = now - 30_000L))
        assertTrue(VoiceGuardPolicy.isOwnerRecentlyVerified(now, lastVerifiedMs = now - 60_000L))
        assertFalse(VoiceGuardPolicy.isOwnerRecentlyVerified(now, lastVerifiedMs = now - 61_000L))
        assertFalse(VoiceGuardPolicy.isOwnerRecentlyVerified(now, lastVerifiedMs = now + 5_000L))
    }

    @Test
    fun messagesAreExact() {
        assertEquals("Voice Guard is armed: only the owner can do that", VoiceGuardPolicy.BLOCKED_MESSAGE)
        assertEquals("Voice Guard is armed — allow send_sms?", VoiceGuardPolicy.confirmationQuestion("send_sms"))
    }
}
