package com.jarvis.assistant.ai.local

import com.jarvis.assistant.ai.compat.ChatTemplateRenderer
import com.jarvis.assistant.ai.compat.ModelFamily
import com.jarvis.assistant.data.model.ChatTurn
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Offline brain output guard (Nov 5, 2026): small on-device models wrote fake turns such as
 * "i can help with technical questions</|im_end|>\n<|im_start|>user\nit's always" into replies.
 */
class OfflineBrainOutputGuardTest {

    @Test
    fun leakedChatMlTurnIsCutFromReply() {
        val raw = "i can help with technical questions</|im_end|>\n<|im_start|>user\nit's always"
        assertEquals("i can help with technical questions", ToolCallParser.cleanLocalModelText(raw))
        assertEquals("i can help with technical questions".length, ToolCallParser.findTurnLeak(raw))
    }

    @Test
    fun leakedDeepSeekAndLlamaTurnsAreCut() {
        assertEquals("Hello Sir", ToolCallParser.cleanLocalModelText("Hello Sir<｜User｜>what next"))
        assertEquals("Hi", ToolCallParser.cleanLocalModelText("Hi<|eot_id|><|start_header_id|>user"))
    }

    @Test
    fun streamHoldsBackPartialMarker() {
        // "<|im_" could become "<|im_start|>", so it must not be shown yet.
        assertEquals("Namaste Sir ", ToolCallParser.streamVisibleText("Namaste Sir <|im_", final = false))
        // A real "<" in text that cannot start a marker is shown.
        assertEquals("2 < 3", ToolCallParser.streamVisibleText("2 < 3", final = false))
    }

    @Test
    fun streamHidesThinkingAndToolPayload() {
        val deepSeek = "<think>\nuser wants torch\n</think>\n\nTorch on kar diya."
        assertEquals("Torch on kar diya.", ToolCallParser.streamVisibleText(deepSeek, final = true))
        assertEquals("", ToolCallParser.streamVisibleText("<think>\nstill thinking", final = false))
        assertEquals("Ok. ", ToolCallParser.streamVisibleText("Ok. <tool_call>{\"name\": \"set_fl", final = false))
        assertEquals("", ToolCallParser.streamVisibleText("{\"name\": \"open_app\", \"argu", final = false))
    }

    @Test
    fun loneClosingThinkDropsReasoningBeforeIt() {
        assertEquals("Answer", ToolCallParser.stripThinking("reasoning here</think>Answer").trim())
    }

    @Test
    fun deepSeekPromptUsesItsOwnTemplate() {
        val prompt = ChatTemplateRenderer.render(
            family = ModelFamily.DEEPSEEK,
            cleanPrompt = "torch on karo",
            systemPrompt = "You are JARVIS.",
            memoryContext = "",
            chatHistory = emptyList(),
            tools = listOf(JSONObject().put("name", "set_flashlight"))
        )
        assertFalse(prompt.contains("<|im_start|>"))
        assertTrue(prompt.contains("<｜User｜>torch on karo<｜Assistant｜><think>\n"))
        assertTrue(prompt.contains("<tool_call>"))
    }

    @Test
    fun llamaPromptExplainsToolCallFormat() {
        val prompt = ChatTemplateRenderer.render(
            family = ModelFamily.LLAMA3,
            cleanPrompt = "open youtube",
            systemPrompt = "",
            memoryContext = "",
            chatHistory = emptyList(),
            tools = listOf(JSONObject().put("name", "open_app"))
        )
        assertTrue(prompt.contains("<tool_call>"))
        assertFalse(prompt.contains("<|begin_of_text|>"))
    }

    @Test
    fun historyWithLeakedTagsIsCleanedBeforeReuse() {
        val history = listOf(ChatTurn(userTranscript = "hi", jarvisResponse = "hello</|im_end|>\n<|im_start|>user\nfake"))
        val prompt = ChatTemplateRenderer.render(ModelFamily.QWEN2P5, "next", "", "", history, emptyList())
        assertFalse(prompt.contains("fake"))
        assertFalse(prompt.contains("</|im_end|>"))
    }
}
