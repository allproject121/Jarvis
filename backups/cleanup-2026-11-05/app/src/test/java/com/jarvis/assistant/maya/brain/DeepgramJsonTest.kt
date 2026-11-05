package com.jarvis.assistant.maya.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepgramJsonTest {

    @Test
    fun interimResultParsed() {
        val msg = DeepgramJson.parse(
            """{"type":"Results","channel_index":[0,1],"duration":1.2,"start":0.0,"is_final":false,"speech_final":false,
               "channel":{"alternatives":[{"transcript":"hello jar","confidence":0.71,"words":[]}]}}"""
        )!!
        assertTrue(msg.isResult)
        assertFalse(msg.isFinal)
        assertFalse(msg.speechFinal)
        assertEquals("hello jar", msg.transcript)
        assertEquals(0.71, msg.confidence, 1e-6)
    }

    @Test
    fun finalSpeechResultParsed() {
        val msg = DeepgramJson.parse(
            """{"type":"Results","is_final":true,"speech_final":true,
               "channel":{"alternatives":[{"transcript":" Hello Jarvis, torch on karo. ","confidence":0.98}]}}"""
        )!!
        assertTrue(msg.isFinal)
        assertTrue(msg.speechFinal)
        assertEquals("Hello Jarvis, torch on karo.", msg.transcript)
    }

    @Test
    fun utteranceEndAndErrorFrames() {
        assertTrue(DeepgramJson.parse("""{"type":"UtteranceEnd","channel":[0,1],"last_word_end":2.4}""")!!.isUtteranceEnd)
        val err = DeepgramJson.parse("""{"type":"Error","description":"Invalid credentials"}""")!!
        assertTrue(err.isError)
        assertEquals("Invalid credentials", err.detail)
        assertEquals("Metadata", DeepgramJson.parse("""{"type":"Metadata","request_id":"x"}""")!!.type)
        assertNull(DeepgramJson.parse("not json"))
    }

    @Test
    fun listenUrlMatchesMaya() {
        val url = DeepgramJson.listenUrl(300)
        assertTrue(url.startsWith("wss://api.deepgram.com/v1/listen?model=nova-3&language=multi&encoding=linear16&sample_rate=16000"))
        assertTrue(url.contains("smart_format=true"))
        assertTrue(url.contains("interim_results=true"))
        assertTrue(url.contains("endpointing=300"))
    }

    @Test
    fun floatToPcm16IsLittleEndian() {
        val bytes = DeepgramJson.floatToPcm16(floatArrayOf(0f, 1f, -1f))
        assertEquals(6, bytes.size)
        assertEquals(0, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        assertEquals(0xFF, bytes[2].toInt() and 0xFF)
        assertEquals(0x7F, bytes[3].toInt() and 0xFF)
        assertEquals(0x01, bytes[4].toInt() and 0xFF)
        assertEquals(0x80, bytes[5].toInt() and 0xFF)
    }
}
