package com.jarvis.assistant.maya.brain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterAutoPickTest {

    private val sample = """
        {"data":[
          {"id":"openai/gpt-4o","pricing":{"prompt":"0.0000025","completion":"0.00001"},"supported_parameters":["tools","temperature"],"context_length":128000},
          {"id":"meta-llama/llama-3.3-70b-instruct:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools","tool_choice"],"context_length":65536},
          {"id":"google/gemini-2.0-flash-exp:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools","max_tokens"],"context_length":1048576},
          {"id":"some/free-no-tools:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["temperature"],"context_length":8192},
          {"id":"zero/priced-model","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"],"context_length":32000},
          {"id":"","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"]}
        ]}
    """.trimIndent()

    @Test
    fun keepsOnlyFreeToolCapableModels() {
        val ids = OpenRouterAutoPick.filterFreeToolModels(JSONObject(sample)).map { it.id }
        assertEquals(3, ids.size)
        assertTrue("meta-llama/llama-3.3-70b-instruct:free" in ids)
        assertTrue("google/gemini-2.0-flash-exp:free" in ids)
        assertTrue("zero/priced-model" in ids)
        assertFalse("openai/gpt-4o" in ids)
        assertFalse("some/free-no-tools:free" in ids)
    }

    @Test
    fun preferredFamiliesRankFirst() {
        val ids = OpenRouterAutoPick.filterFreeToolModels(JSONObject(sample)).map { it.id }
        assertEquals("google/gemini-2.0-flash-exp:free", ids.first())
        assertEquals("zero/priced-model", ids.last())
    }

    @Test
    fun emptyOrMalformedListingYieldsNothing() {
        assertTrue(OpenRouterAutoPick.filterFreeToolModels(JSONObject("{}")).isEmpty())
        assertTrue(OpenRouterAutoPick.filterFreeToolModels(JSONObject("""{"data":[{"id":"x:free"}]}""")).isEmpty())
    }

    @Test
    fun negativeBalanceDetected() {
        assertTrue(OpenRouterAutoPick.isNegativeBalance("""{"error":{"message":"Your account balance is negative"}}"""))
        assertFalse(OpenRouterAutoPick.isNegativeBalance("""{"error":{"message":"Rate limit exceeded"}}"""))
    }
}
