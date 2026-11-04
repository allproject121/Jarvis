package com.jarvis.assistant.maya.media

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerParsingTest {

    @Test
    fun plainNumbersAreSeconds() {
        assertEquals(90, TimerParsing.parseSeconds("90"))
        assertEquals(0, TimerParsing.parseSeconds(""))
        assertEquals(0, TimerParsing.parseSeconds(null))
    }

    @Test
    fun clockFormats() {
        assertEquals(90, TimerParsing.parseSeconds("1:30"))
        assertEquals(3723, TimerParsing.parseSeconds("1:02:03"))
    }

    @Test
    fun unitPhrases() {
        assertEquals(300, TimerParsing.parseSeconds("5 min"))
        assertEquals(4800, TimerParsing.parseSeconds("1h 20m"))
        assertEquals(45, TimerParsing.parseSeconds("45 seconds"))
        assertEquals(7200, TimerParsing.parseSeconds("2 hours"))
    }

    @Test
    fun hinglishPhrases() {
        assertEquals(120, TimerParsing.parseSeconds("do minute"))
        assertEquals(1800, TimerParsing.parseSeconds("aadha ghanta"))
    }

    @Test
    fun describes() {
        assertEquals("1 hour 1 minute 5 seconds", TimerParsing.describe(3665))
        assertEquals("2 minutes", TimerParsing.describe(120))
    }
}
