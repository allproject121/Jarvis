package com.jarvis.assistant.maya.brain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainJsonTest {

    @Test
    fun openAiToolCallsAreParsed() {
        val body = """
            {"id":"chatcmpl-1","model":"llama-3.3-70b-versatile",
             "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
               "tool_calls":[
                 {"id":"call_a","type":"function","function":{"name":"set_flashlight","arguments":"{\"enabled\":true}"}},
                 {"id":"call_b","type":"function","function":{"name":"get_battery","arguments":""}}
               ]}}],
             "usage":{"prompt_tokens":120,"completion_tokens":18}}
        """.trimIndent()
        val turn = BrainJson.parseOpenAiResponse(JSONObject(body))
        assertTrue(turn.hasToolCalls)
        assertEquals(2, turn.toolCalls.size)
        assertEquals("call_a", turn.toolCalls[0].id)
        assertEquals("set_flashlight", turn.toolCalls[0].name)
        assertTrue(turn.toolCalls[0].arguments.getBoolean("enabled"))
        assertEquals(0, turn.toolCalls[1].arguments.length())
        assertEquals("tool_calls", turn.finishReason)
        assertEquals("llama-3.3-70b-versatile", turn.model)
        assertEquals(120, turn.promptTokens)
        assertEquals("", turn.text)
    }

    @Test
    fun openAiPlainAnswerAndContentParts() {
        val plain = JSONObject("""{"choices":[{"message":{"role":"assistant","content":"Haanji sir, torch on hai."}}]}""")
        val t1 = BrainJson.parseOpenAiResponse(plain)
        assertFalse(t1.hasToolCalls)
        assertEquals("Haanji sir, torch on hai.", t1.text)

        val parts = JSONObject("""{"choices":[{"message":{"role":"assistant","content":[{"type":"text","text":"Hello "},{"type":"text","text":"world"}]}}]}""")
        assertEquals("Hello world", BrainJson.parseOpenAiResponse(parts).text)
    }

    @Test
    fun argumentsWrappedInFencesAreRecovered() {
        val args = BrainJson.parseArguments("```json\n{\"query\":\"weather\"}\n```")
        assertEquals("weather", args.getString("query"))
        assertEquals(0, BrainJson.parseArguments("not json").length())
        assertEquals(0, BrainJson.parseArguments(null).length())
    }

    @Test
    fun anthropicToolUseIsParsed() {
        val body = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-3-5-haiku-latest",
             "content":[
               {"type":"text","text":"Checking the weather."},
               {"type":"tool_use","id":"toolu_01","name":"search_web","input":{"query":"Delhi weather"}}
             ],
             "stop_reason":"tool_use","usage":{"input_tokens":50,"output_tokens":20}}
        """.trimIndent()
        val turn = BrainJson.parseAnthropicResponse(JSONObject(body))
        assertEquals("Checking the weather.", turn.text)
        assertEquals(1, turn.toolCalls.size)
        assertEquals("toolu_01", turn.toolCalls[0].id)
        assertEquals("search_web", turn.toolCalls[0].name)
        assertEquals("Delhi weather", turn.toolCalls[0].arguments.getString("query"))
        assertEquals("tool_use", turn.finishReason)
        assertEquals(50, turn.promptTokens)
        // The raw assistant message keeps the original blocks so it can be echoed back.
        assertEquals(2, turn.rawAssistantMessage!!.getJSONArray("content").length())
    }

    @Test
    fun openAiToolsConvertToAnthropicShape() {
        val tool = JSONObject("""{"type":"function","function":{"name":"set_flashlight","description":"Torch","parameters":{"type":"object","properties":{"enabled":{"type":"boolean"}},"required":["enabled"]}}}""")
        val out = BrainJson.openAiToolsToAnthropic(listOf(tool))
        assertEquals(1, out.length())
        val t = out.getJSONObject(0)
        assertEquals("set_flashlight", t.getString("name"))
        assertEquals("object", t.getJSONObject("input_schema").getString("type"))
        val results = BrainJson.anthropicToolResults(listOf("toolu_01" to JSONObject().put("success", true)))
        assertEquals("user", results.getString("role"))
        assertEquals("tool_result", results.getJSONArray("content").getJSONObject(0).getString("type"))
    }

    @Test
    fun errorBodiesAreReadable() {
        assertEquals("Invalid API key", BrainJson.extractErrorMessage("""{"error":{"message":"Invalid API key","code":401}}"""))
        assertEquals("quota", BrainJson.extractErrorMessage("""{"error":"quota"}"""))
    }
}
