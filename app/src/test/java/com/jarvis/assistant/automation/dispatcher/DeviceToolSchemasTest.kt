package com.jarvis.assistant.automation.dispatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * getAvailableToolSchemas() needs a live Context (skills, MCP, settings), so the Batch-4 tool
 * schemas live in the pure [DeviceToolSchemas] object that the dispatcher merges in.
 */
class DeviceToolSchemasTest {

    private val schemas = DeviceToolSchemas.schemas()
    private fun fn(name: String) = schemas.map { it.getJSONObject("function") }.first { it.getString("name") == name }

    @Test
    fun containsEveryNewTool() {
        val names = schemas.map { it.getJSONObject("function").getString("name") }
        for (expected in listOf(
            "get_battery_status", "get_storage_stats", "whatsapp_read_messages", "find_contacts",
            "search_contacts", "press_key", "get_scheduled_tasks", "set_dnd", "start_focus_timer"
        )) {
            assertTrue("missing $expected", expected in names)
        }
        assertEquals(DeviceToolSchemas.NAMES.toSet(), names.toSet())
        assertEquals("schema names must be unique", names.size, names.toSet().size)
    }

    @Test
    fun schemasAreWellFormed() {
        for (schema in schemas) {
            assertEquals("function", schema.getString("type"))
            val params = schema.getJSONObject("function").getJSONObject("parameters")
            assertEquals("object", params.getString("type"))
            assertNotNull(params.getJSONObject("properties"))
            val required = params.getJSONArray("required")
            for (i in 0 until required.length()) {
                assertTrue(params.getJSONObject("properties").has(required.getString(i)))
            }
        }
    }

    @Test
    fun enumsMatchHandlers() {
        val keyEnum = fn("press_key").getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject("key").getJSONArray("enum")
        val keys = (0 until keyEnum.length()).map { keyEnum.getString(it) }
        assertEquals(DeviceToolSchemas.PRESS_KEYS, keys)
        assertTrue(keys.containsAll(listOf("back", "home", "recents")))

        val modeEnum = fn("set_dnd").getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject("mode").getJSONArray("enum")
        val modes = (0 until modeEnum.length()).map { modeEnum.getString(it) }
        for (m in modes) assertEquals(m, DeviceStatusFormat.normalizeDndMode(m))

        val required = fn("start_focus_timer").getJSONObject("parameters").getJSONArray("required")
        assertEquals("minutes", required.getString(0))
    }
}
