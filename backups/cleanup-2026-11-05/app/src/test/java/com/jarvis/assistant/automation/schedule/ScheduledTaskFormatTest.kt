package com.jarvis.assistant.automation.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class ScheduledTaskFormatTest {

    private val utc = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 11, 4, 10, 0).toInstant(utc).toEpochMilli()

    private fun task(kind: String, label: String, minutesFromNow: Long) =
        ScheduledTask(kind, label, now + minutesFromNow * 60_000L, now, "test")

    @Test
    fun keepsOnlyUpcomingSortedByTime() {
        val items = listOf(task("alarm", "Wake", 90), task("timer", "Tea", 5), task("reminder", "Old", -10))
        val upcoming = ScheduledTaskFormat.upcoming(items, now)
        assertEquals(listOf("Tea", "Wake"), upcoming.map { it.label })
    }

    @Test
    fun addPrunesExpiredAndCaps() {
        var list = listOf(task("reminder", "Expired", -1))
        list = ScheduledTaskFormat.add(list, task("timer", "New", 10), now)
        assertEquals(listOf("New"), list.map { it.label })

        var many = emptyList<ScheduledTask>()
        for (i in 1..(ScheduledTaskFormat.MAX_ITEMS + 5)) many = ScheduledTaskFormat.add(many, task("timer", "t$i", i.toLong()), now)
        assertEquals(ScheduledTaskFormat.MAX_ITEMS, many.size)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val items = listOf(task("focus_timer", "Focus time", 25), task("alarm", "Gym", 600))
        val decoded = ScheduledTaskFormat.decode(ScheduledTaskFormat.encode(items))
        assertEquals(items, decoded)
        assertTrue(ScheduledTaskFormat.decode("not json").isEmpty())
        assertTrue(ScheduledTaskFormat.decode(null).isEmpty())
    }

    @Test
    fun describesHonestly() {
        val empty = ScheduledTaskFormat.describe(emptyList(), now, utc)
        assertTrue(empty, empty.contains("nothing scheduled"))

        val text = ScheduledTaskFormat.describe(listOf(task("focus_timer", "Focus time", 25), task("alarm", "Gym", 90)), now, utc)
        assertTrue(text, text.startsWith("Jarvis has 2 upcoming item(s):"))
        assertTrue(text, text.contains("- Focus timer \"Focus time\" at Wed 4 Nov 10:25 (in 25 min)"))
        assertTrue(text, text.contains("- Alarm \"Gym\" at Wed 4 Nov 11:30 (in 1 h 30 min)"))
    }
}
