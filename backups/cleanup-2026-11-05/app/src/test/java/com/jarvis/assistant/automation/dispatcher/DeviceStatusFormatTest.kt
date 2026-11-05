package com.jarvis.assistant.automation.dispatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class DeviceStatusFormatTest {

    @Test
    fun formatsBytes() {
        assertEquals("512 B", DeviceStatusFormat.formatBytes(512))
        assertEquals("1.0 KB", DeviceStatusFormat.formatBytes(1024))
        assertEquals("1.5 MB", DeviceStatusFormat.formatBytes(1536L * 1024))
        assertEquals("64.0 GB", DeviceStatusFormat.formatBytes(64L * 1024 * 1024 * 1024))
        assertEquals("unknown", DeviceStatusFormat.formatBytes(-1))
    }

    @Test
    fun formatsDurations() {
        assertEquals("under a minute", DeviceStatusFormat.formatDuration(30_000))
        assertEquals("25 min", DeviceStatusFormat.formatDuration(25 * 60_000L))
        assertEquals("2 h", DeviceStatusFormat.formatDuration(120 * 60_000L))
        assertEquals("1 h 5 min", DeviceStatusFormat.formatDuration(65 * 60_000L))
    }

    @Test
    fun storageSummaryAndPercent() {
        val gb = 1024L * 1024 * 1024
        assertEquals(75, DeviceStatusFormat.usedPercent(128 * gb, 32 * gb))
        assertEquals(0, DeviceStatusFormat.usedPercent(0, 0))
        val s = DeviceStatusFormat.storageSummary(128 * gb, 32 * gb, 5L * 1024 * 1024)
        assertTrue(s, s.contains("96.0 GB used of 128.0 GB"))
        assertTrue(s, s.contains("75% full"))
        assertTrue(s, s.contains("32.0 GB free"))
        assertTrue(s, s.contains("Jarvis cache: 5.0 MB"))
    }

    @Test
    fun batteryLabelsAndSummary() {
        assertEquals("USB", DeviceStatusFormat.plugLabel(2))
        assertEquals("AC charger", DeviceStatusFormat.plugLabel(1))
        assertNull(DeviceStatusFormat.plugLabel(0))
        assertEquals("good", DeviceStatusFormat.healthLabel(2))
        assertEquals("overheating", DeviceStatusFormat.healthLabel(3))
        assertEquals(31.5, DeviceStatusFormat.temperatureCelsius(315)!!, 0.001)
        assertNull(DeviceStatusFormat.temperatureCelsius(Int.MIN_VALUE))

        val charging = DeviceStatusFormat.batterySummary(62, true, false, "USB", 31.5, "good", 45 * 60_000L)
        assertEquals("Battery is at 62%, charging via USB. About 45 min to full. Temperature 31.5°C. Health: good.", charging)

        val idle = DeviceStatusFormat.batterySummary(80, false, false, null, null, "unknown", null)
        assertEquals("Battery is at 80%, not charging.", idle)

        val full = DeviceStatusFormat.batterySummary(100, true, true, "AC charger", null, "good", 0)
        assertTrue(full, full.contains("fully charged"))
        assertFalse(full, full.contains("to full"))
    }

    @Test
    fun normalizesDndModes() {
        assertEquals("priority", DeviceStatusFormat.normalizeDndMode(null))
        assertEquals("priority", DeviceStatusFormat.normalizeDndMode(""))
        assertEquals("alarms", DeviceStatusFormat.normalizeDndMode("Alarms only"))
        assertEquals("total_silence", DeviceStatusFormat.normalizeDndMode("total silence"))
        assertNull(DeviceStatusFormat.normalizeDndMode("banana"))
    }

    @Test
    fun parsesCalendarStartTimes() {
        val ist = ZoneId.of("Asia/Kolkata")
        val expected = LocalDateTime.of(2026, 11, 5, 15, 30).atZone(ist).toInstant().toEpochMilli()
        assertEquals(expected, DeviceStatusFormat.parseEventStart("2026-11-05 15:30", ist))
        assertEquals(expected, DeviceStatusFormat.parseEventStart("2026-11-05T15:30", ist))
        assertEquals(expected, DeviceStatusFormat.parseEventStart("2026-11-05T15:30:00+05:30", ZoneOffset.UTC))
        assertEquals(expected, DeviceStatusFormat.parseEventStart("2026-11-05T10:00:00Z", ist))
        assertNull(DeviceStatusFormat.parseEventStart("tomorrow evening", ist))
        assertNull(DeviceStatusFormat.parseEventStart("", ist))
    }

    @Test
    fun filtersWhatsAppMessages() {
        val items = listOf(
            DeviceStatusFormat.MessageItem("com.whatsapp", "Mom", "Call me", 100),
            DeviceStatusFormat.MessageItem("org.telegram.messenger", "Mom", "Telegram msg", 300),
            DeviceStatusFormat.MessageItem("com.whatsapp.w4b", "Client A", "Invoice?", 200),
            DeviceStatusFormat.MessageItem("com.whatsapp", "WhatsApp", "5 messages from 2 chats", 400),
            DeviceStatusFormat.MessageItem("com.whatsapp", "Rahul", "On my way", 50)
        )
        val all = DeviceStatusFormat.filterWhatsApp(items, null, 10)
        assertEquals(listOf("Client A", "Mom", "Rahul"), all.map { it.sender })

        val mom = DeviceStatusFormat.filterWhatsApp(items, "mom", 10)
        assertEquals(1, mom.size)
        assertEquals("Call me", mom[0].text)

        assertEquals(1, DeviceStatusFormat.filterWhatsApp(items, null, 1).size)
        assertTrue(DeviceStatusFormat.filterWhatsApp(items, "nobody", 10).isEmpty())
    }
}
