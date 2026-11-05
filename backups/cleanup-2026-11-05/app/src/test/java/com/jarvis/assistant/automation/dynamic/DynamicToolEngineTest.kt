package com.jarvis.assistant.automation.dynamic

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DynamicToolEngineTest {

    private lateinit var tempDir: File
    private lateinit var registry: DynamicToolRegistry

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("jarvis_dyn_tools_test").toFile()
        registry = DynamicToolRegistry.createForTesting(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testDynamicTool_SerializationRoundTrip() {
        val original = DynamicTool(
            name = "calculate_crypto_profit",
            description = "Calculates profit on crypto trades",
            parametersSchemaJson = "{\"type\":\"object\",\"properties\":{\"buy\":{\"type\":\"number\"}}}",
            scriptType = DynamicTool.ScriptType.JAVASCRIPT,
            scriptCode = "function runTool(args) { return args.buy * 1.1; }",
            version = 2,
            author = "JARVIS_AI",
            lastExecutionSuccess = false,
            failureCount = 1,
            lastError = "Invalid number"
        )

        val json = original.toJson()
        val restored = DynamicTool.fromJson(json)

        assertEquals(original.name, restored.name)
        assertEquals(original.description, restored.description)
        assertEquals(original.scriptType, restored.scriptType)
        assertEquals(original.version, restored.version)
        assertEquals(original.author, restored.author)
        assertEquals(original.lastExecutionSuccess, restored.lastExecutionSuccess)
        assertEquals(original.failureCount, restored.failureCount)
        assertEquals(original.lastError, restored.lastError)
    }

    @Test
    fun testToGeminiSchema_GeneratesValidFunctionDeclaration() {
        val tool = DynamicTool(
            name = "fetch_stock_quote",
            description = "Fetches live stock market quotes",
            parametersSchemaJson = "{\"type\":\"object\",\"properties\":{\"symbol\":{\"type\":\"string\"}},\"required\":[\"symbol\"]}",
            scriptType = DynamicTool.ScriptType.JAVASCRIPT,
            scriptCode = "return { price: 150 };"
        )

        val schema = tool.toGeminiSchema()
        assertEquals("function", schema.getString("type"))

        val func = schema.getJSONObject("function")
        assertEquals("fetch_stock_quote", func.getString("name"))
        assertEquals("Fetches live stock market quotes", func.getString("description"))

        val params = func.getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
        assertTrue(params.getJSONObject("properties").has("symbol"))
    }

    @Test
    fun testCreateTool_Success() = runBlocking {
        val result = registry.createTool(
            name = "custom_unit_converter",
            description = "Converts currencies",
            parametersSchemaJson = "{\"type\":\"object\",\"properties\":{}}",
            scriptType = DynamicTool.ScriptType.JAVASCRIPT,
            scriptCode = "console.log('Converter ready');"
        )

        assertTrue(result.isSuccess)
        val tool = result.getOrNull()
        assertNotNull(tool)
        assertEquals("custom_unit_converter", tool?.name)
        assertEquals(1, tool?.version)

        val retrieved = registry.getTool("custom_unit_converter")
        assertNotNull(retrieved)
        assertEquals("custom_unit_converter", retrieved?.name)
        assertTrue(registry.isDynamicTool("custom_unit_converter"))
    }

    @Test
    fun testCreateTool_BlocksProtectedSystemNames() = runBlocking {
        val result = registry.createTool(
            name = "call_contact",
            description = "Attempting to hijack protected tool",
            parametersSchemaJson = "{}",
            scriptType = DynamicTool.ScriptType.SHELL,
            scriptCode = "exit 1"
        )

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("protected") == true)
    }

    @Test
    fun testCreateTool_RejectsInvalidNames() = runBlocking {
        val invalidNames = listOf("ab", "invalid-name-with-dash", "has spaces", "TOOL!@#", "a".repeat(70))
        for (name in invalidNames) {
            val result = registry.createTool(
                name = name,
                description = "Invalid test",
                parametersSchemaJson = "{}",
                scriptType = DynamicTool.ScriptType.JAVASCRIPT,
                scriptCode = ""
            )
            assertFalse("Expected '$name' to be rejected", result.isSuccess)
        }
    }

    @Test
    fun testEditTool_IncrementsVersionAndUpdatesCode() = runBlocking {
        registry.createTool(
            name = "weather_alert_tool",
            description = "Alerts on severe weather",
            parametersSchemaJson = "{\"type\":\"object\",\"properties\":{}}",
            scriptType = DynamicTool.ScriptType.JAVASCRIPT,
            scriptCode = "function runTool() { return 'Sunny'; }"
        )

        val editResult = registry.editTool(
            name = "weather_alert_tool",
            updatedScriptCode = "function runTool() { return 'Rain expected'; }",
            updatedDescription = "Alerts on rain and storms"
        )

        assertTrue(editResult.isSuccess)
        val updated = editResult.getOrNull()
        assertNotNull(updated)
        assertEquals(2, updated?.version)
        assertEquals("Alerts on rain and storms", updated?.description)
        assertEquals("function runTool() { return 'Rain expected'; }", updated?.scriptCode)
    }

    @Test
    fun testDeleteTool_RemovesFromRegistryAndDisk() = runBlocking {
        registry.createTool(
            name = "temp_cleanup_tool",
            description = "Temporary tool",
            parametersSchemaJson = "{}",
            scriptType = DynamicTool.ScriptType.SHELL,
            scriptCode = "echo 'Done'"
        )

        assertTrue(registry.isDynamicTool("temp_cleanup_tool"))
        val deleted = registry.deleteTool("temp_cleanup_tool")
        assertTrue(deleted)
        assertFalse(registry.isDynamicTool("temp_cleanup_tool"))
        assertNull(registry.getTool("temp_cleanup_tool"))
    }

    @Test
    fun testRecordExecution_UpdatesFailureTelemetry() = runBlocking {
        registry.createTool(
            name = "telemetry_test_tool",
            description = "Testing telemetry",
            parametersSchemaJson = "{}",
            scriptType = DynamicTool.ScriptType.JAVASCRIPT,
            scriptCode = "throw new Error('Test failure');"
        )

        // Record 1st failure
        registry.recordExecution("telemetry_test_tool", success = false, error = "Null pointer in script")
        var tool = registry.getTool("telemetry_test_tool")
        assertFalse(tool!!.lastExecutionSuccess)
        assertEquals(1, tool.failureCount)
        assertEquals("Null pointer in script", tool.lastError)

        // Record 2nd failure
        registry.recordExecution("telemetry_test_tool", success = false, error = "Timeout")
        tool = registry.getTool("telemetry_test_tool")
        assertEquals(2, tool!!.failureCount)
        assertEquals("Timeout", tool.lastError)

        // Record success (resets failure count)
        registry.recordExecution("telemetry_test_tool", success = true, error = null)
        tool = registry.getTool("telemetry_test_tool")
        assertTrue(tool!!.lastExecutionSuccess)
        assertEquals(0, tool.failureCount)
        assertNull(tool.lastError)
    }
}
