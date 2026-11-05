package com.jarvis.assistant.ai.local

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineToolRouterTest {

    private fun tool(name: String, desc: String, vararg args: String, required: List<String> = emptyList()): JSONObject {
        val props = JSONObject()
        args.forEach { props.put(it, JSONObject().put("type", "string")) }
        return JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))))
    }

    private val all = listOf(
        tool("set_alarm", "Sets an alarm clock for a time.", "time", required = listOf("time")),
        tool("get_weather", "Gets the current weather for a city.", "city"),
        tool("get_news", "Reads the latest news headlines.", "topic"),
        tool("save_note", "Saves a note for later.", "text", required = listOf("text")),
        tool("set_flashlight", "Turns the torch on or off.", "enabled"),
        tool("whatsapp_read_messages", "Reads recent WhatsApp messages.", "contact")
    )

    private fun names(q: String, exclude: Set<String> = emptySet()) =
        OfflineToolRouter.select(q, all, max = 3, exclude = exclude).map { it.getJSONObject("function").getString("name") }

    @Test
    fun hinglishRequestsFindTheRightTool() {
        assertEquals("set_alarm", names("kal subah 6 baje ka alarm lagao").first())
        assertEquals("get_weather", names("aaj mausam kaisa hai").first())
        assertEquals("get_news", names("aaj ki khabar sunao").first())
        assertEquals("save_note", names("ye note likho: doodh lana hai").first())
        assertEquals("whatsapp_read_messages", names("whatsapp ke message padho").first())
    }

    @Test
    fun plainChatGetsNoTools() {
        assertTrue(names("tum kaun ho").isEmpty())
        assertTrue(names("ek joke sunao").isEmpty())
    }

    @Test
    fun excludedToolsAreNotRepeated() {
        assertFalse(names("torch on karo", exclude = setOf("set_flashlight")).contains("set_flashlight"))
    }

    @Test
    fun toolLineIsCompact() {
        val line = OfflineToolRouter.toLine(all[0])
        assertEquals("- set_alarm(time: string) - Sets an alarm clock for a time.", line)
        assertTrue(OfflineToolRouter.toLine(all[1]).contains("city?: string"))
    }
}
