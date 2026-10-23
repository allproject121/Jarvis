package com.jarvis.assistant.ai.local

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying [ToolCallParser] for extracting tool calls from local SLM text streams.
 */
class ToolCallParserTest {

    @Test
    fun testParseXmlToolCall() {
        val llmOutput = """
            Certainly sir, turning on your flashlight right away.
            <tool_call>{"name": "set_flashlight", "arguments": {"enabled": true}}</tool_call>
        """.trimIndent()

        val toolCall = ToolCallParser.parseToolCall(llmOutput)
        assertNotNull(toolCall)
        assertEquals("set_flashlight", toolCall?.functionName)
        assertEquals(true, toolCall?.arguments?.getBoolean("enabled"))

        val clean = ToolCallParser.cleanConversationalText(llmOutput)
        assertTrue(clean.contains("Certainly sir, turning on your flashlight"))
        assertFalse(clean.contains("<tool_call>"))
    }

    @Test
    fun testParseMarkdownJsonToolCall() {
        val llmOutput = """
            I am increasing the media volume now.
            ```json
            {"name": "set_volume", "arguments": {"percent": 80}}
            ```
        """.trimIndent()

        val toolCall = ToolCallParser.parseToolCall(llmOutput)
        assertNotNull(toolCall)
        assertEquals("set_volume", toolCall?.functionName)
        assertEquals(80, toolCall?.arguments?.getInt("percent"))

        val clean = ToolCallParser.cleanConversationalText(llmOutput)
        assertEquals("I am increasing the media volume now.", clean)
    }

    @Test
    fun testPlainConversationalTextHasNoToolCalls() {
        val text = "Good evening Roshan Sir. All systems are operating within nominal parameters."
        val toolCall = ToolCallParser.parseToolCall(text)
        assertNull(toolCall)

        val clean = ToolCallParser.cleanConversationalText(text)
        assertEquals(text, clean)
    }

    @Test
    fun testMalformedJsonDoesNotCrash() {
        val text = "Attempting: <tool_call>{invalid json here</tool_call>"
        val toolCall = ToolCallParser.parseToolCall(text)
        assertNull(toolCall)
    }
}
