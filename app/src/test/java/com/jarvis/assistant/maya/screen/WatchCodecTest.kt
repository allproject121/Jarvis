package com.jarvis.assistant.maya.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchCodecTest {

    @Test
    fun persistenceRoundTrip() {
        val watches = listOf(
            Watch(1, "screen_text", "Your video is ready", "video ban gaya — \"bata dena\"", 1_700_000_000_000L),
            Watch(7, "app_opens", "Instagram", "", 1_700_000_100_000L, baseline = "com.instagram.android"),
            Watch(9, "battery", "below 20", "charger lagana", 1_700_000_200_000L)
        )
        val json = WatchCodec.encode(watches)
        val back = WatchCodec.decode(json)
        assertEquals(watches, back)
    }

    @Test
    fun decodeToleratesGarbageAndUnknownKinds() {
        assertTrue(WatchCodec.decode(null).isEmpty())
        assertTrue(WatchCodec.decode("").isEmpty())
        assertTrue(WatchCodec.decode("not json").isEmpty())
        val mixed = "{\"version\":1,\"watches\":[{\"id\":2,\"kind\":\"app\",\"value\":\"YouTube\"},{\"id\":3,\"kind\":\"telepathy\",\"value\":\"x\"}]}"
        val list = WatchCodec.decode(mixed)
        assertEquals(1, list.size)
        assertEquals("app_opens", list[0].kind)
        assertEquals("", list[0].reason)
    }

    @Test
    fun normalizesKinds() {
        assertEquals("screen_text", WatchCodec.normalizeKind("Screen Text"))
        assertEquals("headset", WatchCodec.normalizeKind("headphones"))
        assertEquals("wifi", WatchCodec.normalizeKind("wi-fi"))
        assertEquals("charger", WatchCodec.normalizeKind("charging"))
        assertNull(WatchCodec.normalizeKind("xyz"))
        assertNull(WatchCodec.normalizeKind(null))
    }

    @Test
    fun parsesBatteryConditions() {
        assertEquals(BatteryCondition(20, true), WatchCodec.parseBatteryCondition("below 20"))
        assertEquals(BatteryCondition(80, false), WatchCodec.parseBatteryCondition("above 80"))
        assertEquals(BatteryCondition(100, false), WatchCodec.parseBatteryCondition("full"))
        assertEquals(BatteryCondition(15, true), WatchCodec.parseBatteryCondition("15%"))
        assertEquals(BatteryCondition(20, true), WatchCodec.parseBatteryCondition("low"))
        assertNull(WatchCodec.parseBatteryCondition(""))
        assertNull(WatchCodec.parseBatteryCondition("whenever"))
    }

    @Test
    fun describeIsHumanReadable() {
        val w = Watch(4, "screen_text", "Done", "render ho gaya", 0L)
        assertEquals("#4 screen pe 'Done' dikhe → render ho gaya", w.describe())
    }

    @Test
    fun eventTriggerWordsResolve() {
        assertEquals(EventTriggers.ALL.size, EventTriggers.resolve("sab").size)
        assertEquals(listOf("battery_low"), EventTriggers.resolve("battery low"))
        assertEquals(listOf("charger_in", "charger_out"), EventTriggers.resolve("charging"))
        assertEquals(listOf("headset_in", "headset_out"), EventTriggers.resolve("headphone wala band kar do"))
        assertEquals(listOf("charger_in"), EventTriggers.resolve("charger lag gaya"))
        assertEquals(listOf("battery_low"), EventTriggers.resolve("battery low pe bata dena"))
        assertEquals(listOf("wifi_in", "wifi_out"), EventTriggers.resolve("wifi"))
        assertTrue(EventTriggers.resolve("telepathy").isEmpty())
    }

    @Test
    fun eventLinesComeFromMaya() {
        assertEquals("Battery full ho gayi, charger nikaal do.", EventTriggers.line("battery_full", 0L))
        assertEquals("Charger lag gaya.", EventTriggers.line("charger_in", 5L))
    }
}
