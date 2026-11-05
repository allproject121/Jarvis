package com.jarvis.assistant.maya.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parsing tests for the ChatGPT export importer (no Android framework). */
class ChatGptImporterTest {

    @Test
    fun standardExportMappingShape() {
        val json = """
        [
          {"title":"t","mapping":{
            "a":{"message":{"author":{"role":"system"},"content":{"content_type":"text","parts":["ignore"]}}},
            "b":{"message":{"author":{"role":"user"},"content":{"content_type":"text","parts":["I live in Nashik and run a CCTV shop"]}}},
            "c":{"message":{"author":{"role":"assistant"},"content":{"content_type":"text","parts":["great"]}}},
            "d":{"message":{"author":{"role":"user"},"content":{"content_type":"text","parts":["Always reply in Hinglish"]}}}
          }}
        ]
        """.trimIndent()
        val (convos, turns) = ChatGptImporter.extractUserTurns(json)
        assertEquals(1, convos)
        assertEquals(listOf("I live in Nashik and run a CCTV shop", "Always reply in Hinglish"), turns)
    }

    @Test
    fun simpleMessagesShape() {
        val json = """
        {"conversations":[
          {"messages":[
            {"role":"user","content":"my name is Roshan"},
            {"role":"assistant","content":"hi Roshan"},
            {"role":"user","content":"I wake up at 6am"}
          ]}
        ]}
        """.trimIndent()
        val (convos, turns) = ChatGptImporter.extractUserTurns(json)
        assertEquals(1, convos)
        assertEquals(listOf("my name is Roshan", "I wake up at 6am"), turns)
    }

    @Test
    fun garbageInputIsEmpty() {
        val (c, t) = ChatGptImporter.extractUserTurns("not json at all")
        assertEquals(0, c)
        assertTrue(t.isEmpty())
    }
}
