package com.jarvis.assistant.ai.local

import org.junit.Assert.*
import org.junit.Test

/**
 * Offline-brain cases for [ToolCallParser]: Qwen's "</tool_call>" stop word cuts the closing tag,
 * and Qwen3 thinking blocks must never be spoken or executed.
 */
class ToolCallParserFailoverTest {

    @Test
    fun parsesToolCallWithoutClosingTag() {
        val llmOutput = "Turning on the torch.\n<tool_call>\n{\"name\": \"set_flashlight\", \"arguments\": {\"enabled\": true}}"
        val toolCall = ToolCallParser.parseToolCall(llmOutput)
        assertNotNull(toolCall)
        assertEquals("set_flashlight", toolCall?.functionName)
        assertEquals(true, toolCall?.arguments?.getBoolean("enabled"))

        val clean = ToolCallParser.cleanConversationalText(llmOutput)
        assertEquals("Turning on the torch.", clean)
    }

    @Test
    fun nestedBracesInsideArgumentsStillParse() {
        val llmOutput = "<tool_call>{\"name\": \"send_whatsapp\", \"arguments\": {\"contact\": \"mummy\", \"message\": \"8 baje {aaunga}\"}}"
        val toolCall = ToolCallParser.parseToolCall(llmOutput)
        assertNotNull(toolCall)
        assertEquals("8 baje {aaunga}", toolCall?.arguments?.getString("message"))
    }

    @Test
    fun incompleteJsonIsNotParsedYet() {
        val partial = "<tool_call>{\"name\": \"set_flashlight\", \"arguments\": {\"enabled\": tr"
        assertNull(ToolCallParser.parseToolCall(partial))
        assertEquals("", ToolCallParser.cleanConversationalText(partial))
    }

    @Test
    fun toolCallInsideThinkingIsIgnored() {
        val llmOutput = "<think>Maybe <tool_call>{\"name\": \"reboot_device\", \"arguments\": {}}</tool_call> no.</think>Sir, I would not reboot."
        assertNull(ToolCallParser.parseToolCall(llmOutput))
        assertEquals("Sir, I would not reboot.", ToolCallParser.cleanConversationalText(llmOutput))
    }

    @Test
    fun unfinishedThinkingIsStripped() {
        val llmOutput = "Okay.<think>still reasoning about"
        assertEquals("Okay.", ToolCallParser.cleanConversationalText(llmOutput))
    }

    @Test
    fun strayClosingTagIsRemoved() {
        assertEquals("Done.", ToolCallParser.cleanConversationalText("Done.</tool_call>"))
    }
}
