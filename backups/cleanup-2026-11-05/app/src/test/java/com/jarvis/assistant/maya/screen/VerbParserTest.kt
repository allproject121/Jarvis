package com.jarvis.assistant.maya.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerbParserTest {

    @Test
    fun parsesJsonInsideCodeFence() {
        val raw = "```json\n{\"verb\":\"tap_id\",\"id\":7,\"say\":\"send dabaya\"}\n```"
        val v = VerbParser.parse(raw)
        assertNotNull(v)
        assertEquals("tap_id", v!!.verb)
        assertEquals(7, v.id)
        assertEquals("send dabaya", v.say)
    }

    @Test
    fun parsesJsonSurroundedByProseWithBracesInStrings() {
        val raw = "Sure! Here's the action: {\"verb\": \"type\", \"text\": \"hello {name}\", \"realistic\": true} Hope that helps."
        val v = VerbParser.parse(raw)!!
        assertEquals("type", v.verb)
        assertEquals("hello {name}", v.text)
        assertTrue(v.realistic)
    }

    @Test
    fun missingFieldsGetDefaults() {
        val v = VerbParser.parse("{\"verb\":\"scroll\"}")!!
        assertEquals("scroll", v.verb)
        assertEquals("down", v.direction)
        assertEquals("device", v.space)
        assertEquals(20_000L, v.timeoutMs)
        assertNull(v.id)
        assertNull(v.text)
        assertFalse(v.realistic)
    }

    @Test
    fun directionComesFromVerbSuffix() {
        val v = VerbParser.parse("{\"verb\":\"swipe_up\"}")!!
        assertEquals("swipe", v.verb)
        assertEquals("up", v.direction)
    }

    @Test
    fun hashNumberAsTextBecomesTapId() {
        val v = VerbParser.parse("{\"verb\":\"tap_text\",\"text\":\"#12\"}")!!
        assertEquals("tap_id", v.verb)
        assertEquals(12, v.id)
    }

    @Test
    fun aliasesAndKeyVariantsAreAccepted() {
        val v = VerbParser.parse("{\"action\":\"click\",\"text\":\"OK\"}")!!
        assertEquals("tap_text", v.verb)
        assertEquals("OK", v.text)
    }

    @Test
    fun coordinatesAcceptStringsAndDoubles() {
        val v = VerbParser.parse("{\"verb\":\"tap_coords\",\"x\":\"540\",\"y\":1200.6,\"space\":\"image\"}")!!
        assertEquals("tap_coords", v.verb)
        assertEquals(540, v.x)
        assertEquals(1200, v.y)
        assertEquals("image", v.space)
    }

    @Test
    fun waitForKeepsConditionAndTimeout() {
        val v = VerbParser.parse("{\"verb\":\"wait_for\",\"condition\":\"Your video is ready\",\"timeout_ms\":600000}")!!
        assertEquals("wait_for", v.verb)
        assertEquals("Your video is ready", v.condition)
        assertEquals(600_000L, v.timeoutMs)
    }

    @Test
    fun unbalancedObjectIsRepaired() {
        val v = VerbParser.parse("{\"verb\":\"go_back\"")
        assertNotNull(v)
        assertEquals("go_back", v!!.verb)
    }

    @Test
    fun bareTerminalVerbWithoutJson() {
        assertEquals("task_done", VerbParser.parse("task_done")!!.verb)
        assertEquals("go_home", VerbParser.parse("  go_home  ")!!.verb)
    }

    @Test
    fun garbageReturnsNull() {
        assertNull(VerbParser.parse("I cannot do that"))
        assertNull(VerbParser.parse(""))
        assertNull(VerbParser.parse(null))
        assertNull(VerbParser.parse("{\"foo\":1}"))
    }

    @Test
    fun extractFindsFirstBalancedObject() {
        val json = VerbParser.extractJsonObject("noise {\"a\":{\"b\":1}} more {\"c\":2}")
        assertEquals("{\"a\":{\"b\":1}}", json)
    }

    @Test
    fun actionKeyIdentifiesRepeats() {
        val a = VerbParser.parse("{\"verb\":\"tap_id\",\"id\":7}")!!
        val b = VerbParser.parse("```{\"verb\":\"tap_id\",\"id\":7,\"say\":\"phir se\"}```")!!
        assertEquals(a.actionKey(), b.actionKey())
        val t = VerbParser.parse("{\"verb\":\"type\",\"text\":\"Hello\"}")!!
        assertEquals("type:hello", t.actionKey())
    }

    @Test
    fun splitStepsHandlesNumberedLinesAndSemicolons() {
        assertEquals(listOf("open WhatsApp", "tap Amit", "type hi"), VerbParser.splitSteps("1. open WhatsApp\n2) tap Amit\n- type hi"))
        assertEquals(listOf("open Settings", "tap Wi-Fi"), VerbParser.splitSteps("open Settings; tap Wi-Fi"))
        assertTrue(VerbParser.splitSteps(null).isEmpty())
    }
}
