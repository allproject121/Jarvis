package com.jarvis.assistant.maya.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReplySignatureTest {

    @Test
    fun appendsSignature() {
        val out = AutoReplySignature.apply("Main meeting me hoon", "Jarvis")
        assertEquals("Main meeting me hoon\n— auto-reply by Jarvis", out)
        assertTrue(AutoReplySignature.hasSignature(out, "Jarvis"))
    }

    @Test
    fun neverDuplicatesSignature() {
        val modelText = "Busy hoon\n— auto-reply by Jarvis"
        val out = AutoReplySignature.apply(modelText, "Jarvis")
        assertEquals(1, Regex("auto-reply by").findAll(out).count())
    }

    @Test
    fun replacesForgedSignatureWithFixedOne() {
        val out = AutoReplySignature.apply("ok\n— auto-reply by SomeoneElse", "Jarvis")
        assertTrue(out.endsWith("— auto-reply by Jarvis"))
        assertTrue(!out.contains("SomeoneElse"))
    }

    @Test
    fun blankBodyStillSigned() {
        val out = AutoReplySignature.apply("   ", "")
        assertTrue(out.endsWith("— auto-reply by JARVIS"))
        assertTrue(out.lines().first().isNotBlank())
    }
}
