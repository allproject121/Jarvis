package com.jarvis.assistant.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpRemoteToolDefTest {

    @Test
    fun description_gets_server_suffix() {
        assertEquals(
            "Reads a file. (via MCP server 'Files')",
            McpRemoteToolDef.describe("Reads a file.", "Files")
        )
        assertTrue(McpRemoteToolDef.describe("  ", "Files").startsWith("Remote tool from MCP server 'Files'."))
    }

    @Test
    fun schema_is_normalized_to_object_shape() {
        val raw = JSONObject(
            """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"object",
               "properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}"""
        )
        val normalized = McpRemoteToolDef.normalizeSchema(raw)
        assertEquals("object", normalized.getString("type"))
        assertTrue(normalized.getJSONObject("properties").has("path"))
        assertEquals("path", normalized.getJSONArray("required").getString(0))
        assertFalse(normalized.has("additionalProperties"))
        assertFalse(normalized.has("${'$'}schema"))
    }

    @Test
    fun missing_schema_becomes_empty_object_schema() {
        val normalized = McpRemoteToolDef.normalizeSchema(null)
        assertEquals("object", normalized.getString("type"))
        assertEquals(0, normalized.getJSONObject("properties").length())
        assertFalse(normalized.has("required"))
    }

    @Test
    fun non_text_content_becomes_placeholders() {
        val content = JSONArray(
            """[{"type":"text","text":"hello"},{"type":"image","data":"AAAA","mimeType":"image/png"},
               {"type":"resource","resource":{"uri":"file:///x"}}]"""
        )
        assertEquals("hello\n[ImageContent]\n[EmbeddedResource]", McpRemoteToolDef.flattenContent(content))
    }

    @Test
    fun empty_content_reports_ok() {
        assertEquals("OK (no content)", McpRemoteToolDef.flattenContent(JSONArray()))
        assertEquals("OK (no content)", McpRemoteToolDef.flattenContent(null))
    }
}
