package com.jarvis.assistant.maya.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenPolicyTest {

    private fun d(mode: ListenMode, speaker: SpeakerKind, sensitive: Boolean, ownerSeen: Boolean = false, warned: Boolean = false, tool: String = "send_sms") =
        ListenPolicy.decide(mode, tool, speaker, sensitive, ownerSeen, warned)

    @Test
    fun ownerIsAlwaysAllowed() {
        for (m in ListenMode.values()) {
            assertFalse(d(m, SpeakerKind.OWNER, sensitive = true).block)
        }
    }

    @Test
    fun everyoneModeAllowsAllButBlockedPeople() {
        assertFalse(d(ListenMode.EVERYONE, SpeakerKind.STRANGER, true).block)
        assertFalse(d(ListenMode.EVERYONE, SpeakerKind.UNVERIFIED, true).block)
        val blocked = d(ListenMode.EVERYONE, SpeakerKind.BLOCKED, true)
        assertTrue(blocked.block && blocked.lock)
        assertFalse(d(ListenMode.EVERYONE, SpeakerKind.BLOCKED, false).block)
    }

    @Test
    fun ownerOnlyBlocksSensitiveFromOthersAndLocksOnlyConfirmedStrangers() {
        val stranger = d(ListenMode.OWNER_ONLY, SpeakerKind.STRANGER, true)
        assertTrue(stranger.block); assertTrue(stranger.lock); assertTrue(stranger.warn)

        val family = d(ListenMode.OWNER_ONLY, SpeakerKind.FAMILY, true)
        assertTrue(family.block); assertTrue(family.lock)

        val unverified = d(ListenMode.OWNER_ONLY, SpeakerKind.UNVERIFIED, true)
        assertTrue(unverified.block); assertFalse("never lock without a confirmed non-owner", unverified.lock)

        // Just talking (non-sensitive) never blocks or locks.
        val chat = d(ListenMode.OWNER_ONLY, SpeakerKind.STRANGER, false, tool = "get_weather")
        assertFalse(chat.block); assertFalse(chat.lock)
    }

    @Test
    fun warningIsOneTime() {
        assertTrue(d(ListenMode.OWNER_ONLY, SpeakerKind.STRANGER, true, warned = false).warn)
        val second = d(ListenMode.OWNER_ONLY, SpeakerKind.STRANGER, true, warned = true)
        assertTrue(second.block); assertFalse(second.warn)
    }

    @Test
    fun ownerAndFamilyTrustsFamilyOnly() {
        assertFalse(d(ListenMode.OWNER_AND_FAMILY, SpeakerKind.FAMILY, true).block)
        assertTrue(d(ListenMode.OWNER_AND_FAMILY, SpeakerKind.OTHER_KNOWN, true).block)
        assertTrue(d(ListenMode.OWNER_AND_FAMILY, SpeakerKind.STRANGER, true).lock)
    }

    @Test
    fun nobodyUntilOwnerBlocksEverythingUntilOwnerSeen() {
        val before = d(ListenMode.NOBODY_UNTIL_OWNER, SpeakerKind.STRANGER, false, ownerSeen = false, tool = "play_music")
        assertTrue(before.block); assertFalse(before.lock)
        val sensitiveBefore = d(ListenMode.NOBODY_UNTIL_OWNER, SpeakerKind.STRANGER, true, ownerSeen = false)
        assertTrue(sensitiveBefore.lock)
        assertFalse(d(ListenMode.NOBODY_UNTIL_OWNER, SpeakerKind.UNVERIFIED, true, ownerSeen = false).lock)

        // After the owner was heard: behaves like owner + family.
        assertFalse(d(ListenMode.NOBODY_UNTIL_OWNER, SpeakerKind.STRANGER, false, ownerSeen = true, tool = "play_music").block)
        assertFalse(d(ListenMode.NOBODY_UNTIL_OWNER, SpeakerKind.FAMILY, true, ownerSeen = true).block)
        assertTrue(d(ListenMode.NOBODY_UNTIL_OWNER, SpeakerKind.STRANGER, true, ownerSeen = true).block)
    }

    @Test
    fun statusAndLockToolsAlwaysAllowed() {
        assertFalse(d(ListenMode.OWNER_ONLY, SpeakerKind.BLOCKED, true, tool = "lock_device").block)
        assertFalse(d(ListenMode.NOBODY_UNTIL_OWNER, SpeakerKind.STRANGER, false, tool = "get_listen_mode").block)
    }

    @Test
    fun sensitiveClassification() {
        assertTrue(ListenPolicy.isSensitive("wipe_device", false))
        assertTrue(ListenPolicy.isSensitive("anything", true))
        assertFalse(ListenPolicy.isSensitive("get_weather", false))
    }

    @Test
    fun modeParsing() {
        assertEquals(ListenMode.OWNER_ONLY, ListenMode.fromKey("owner only"))
        assertEquals(ListenMode.OWNER_AND_FAMILY, ListenMode.fromKey("owner+family"))
        assertEquals(ListenMode.NOBODY_UNTIL_OWNER, ListenMode.fromKey("nobody_until_owner"))
        assertEquals(ListenMode.EVERYONE, ListenMode.fromKey("Everyone"))
        assertEquals(null, ListenMode.fromKey("banana"))
    }

    @Test
    fun speakerKindFromRole() {
        assertEquals(SpeakerKind.STRANGER, SpeakerKind.fromRole(null))
        assertEquals(SpeakerKind.OWNER, SpeakerKind.fromRole("boss"))
        assertEquals(SpeakerKind.OTHER_KNOWN, SpeakerKind.fromRole("guest"))
    }
}
