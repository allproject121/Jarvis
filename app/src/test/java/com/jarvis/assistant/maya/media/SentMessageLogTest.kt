package com.jarvis.assistant.maya.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentMessageLogTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 10_000L * day
    private val entries = listOf(
        SentMessageLog.Entry("Rahul Sharma", "919812345678", "Kal meeting 5 baje hai", "com.whatsapp", now - 3 * day),
        SentMessageLog.Entry("Mummy", "919811111111", "Khana kha liya", "com.whatsapp", now - 1 * day),
        SentMessageLog.Entry("Rahul Sharma", "919812345678", "Payment bhej diya", "com.whatsapp", now - 2 * day)
    )

    @Test
    fun newestWhenNoFilter() {
        assertEquals("Khana kha liya", SentMessageLog.find(entries, null, null)?.text)
    }

    @Test
    fun byContactPicksNewestForThatContact() {
        assertEquals("Payment bhej diya", SentMessageLog.find(entries, "rahul", null)?.text)
    }

    @Test
    fun byContactAndTextHint() {
        assertEquals("Kal meeting 5 baje hai", SentMessageLog.find(entries, "Rahul", "meeting wala")?.text)
    }

    @Test
    fun byNumberFragment() {
        assertEquals("Khana kha liya", SentMessageLog.find(entries, "9811111111", null)?.text)
    }

    @Test
    fun noMatchIsNull() {
        assertNull(SentMessageLog.find(entries, "Priya", null))
    }

    @Test
    fun deleteForEveryoneWindow() {
        assertTrue(SentMessageLog.canDeleteForEveryone(entries[1], now))   // 1 day old
        assertFalse(SentMessageLog.canDeleteForEveryone(entries[0], now))  // 3 days old (> 60 h)
    }
}
