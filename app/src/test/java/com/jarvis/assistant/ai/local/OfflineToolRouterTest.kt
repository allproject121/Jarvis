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
        tool("whatsapp_read_messages", "Reads recent WhatsApp messages.", "contact"),
        tool("get_battery", "Battery level.", "detail"),
        tool("get_wifi_status", "Wi-Fi status and current level.", "detail"),
        tool("get_battery_status", "Battery percent and charging.", "detail"),
        tool("send_whatsapp_message", "Sends a WhatsApp message.", "contact", "message")
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
    fun mathsQuestionGetsNoTools() {
        // Audit Nov 6: "kitna" pulled get_wifi_status & co into a maths question (+30 s).
        assertTrue(names("12 aur 15 ka guna kitna hota hai?").isEmpty())
    }

    @Test
    fun lookAlikeOfCoreToolIsNotAdded() {
        val core = setOf("get_battery_status", "send_whatsapp_message")
        assertFalse(names("mere phone ki battery kitni hai?", exclude = core).contains("get_battery"))
        // ...but a tool that adds a NEW ability next to a core tool stays.
        assertEquals("whatsapp_read_messages", names("whatsapp ke message padho", exclude = core).first())
    }

    @Test
    fun brokenToolJsonFromTheAuditIsRepaired() {
        val battery = ToolCallParser.parseToolCallLenient("<tool_call>\n{\"name\": \"get_battery_status\", \"arguments\": {\"}}")
        assertEquals("get_battery_status", battery?.functionName)
        assertEquals(0, battery?.arguments?.length())

        val reminder = ToolCallParser.parseToolCallLenient(
            "<tool_call>\n{\"name\": \"set_reminder\", \"arguments\": {\"message\": \"Alarm for 6 AM tomorrow\", \"minutes\": 60*24 - 6*60}}\n"
        )
        assertEquals("set_reminder", reminder?.functionName)
        assertEquals("Alarm for 6 AM tomorrow", reminder?.arguments?.optString("message"))
        assertEquals(1080L, reminder?.arguments?.optLong("minutes"))
    }

    @Test
    fun strictParserStillRejectsPartialCallsMidStream() {
        // Mid-stream the strict parser must wait; only the finished reply may be repaired.
        assertNull(ToolCallParser.parseToolCall("<tool_call>\n{\"name\": \"set_alarm\", \"argu"))
        assertNull(ToolCallParser.parseToolCallLenient("Delhi is the capital of India."))
    }

    @Test
    fun arithmeticEvaluator() {
        assertEquals(1080.0, ToolCallParser.evaluateArithmetic("60*24 - 6*60")!!, 0.0001)
        assertEquals(7.0, ToolCallParser.evaluateArithmetic("(2+5)")!!, 0.0001)
        assertNull(ToolCallParser.evaluateArithmetic("6 baje"))
    }

    @Test
    fun toolLineIsCompact() {
        val line = OfflineToolRouter.toLine(all[0])
        assertEquals("- set_alarm(time: string) - Sets an alarm clock for a time.", line)
        assertTrue(OfflineToolRouter.toLine(all[1]).contains("city?: string"))
    }
}
