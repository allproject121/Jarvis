package com.jarvis.assistant.androllm.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The critical contract: a throwing runtime is reported as failed and never breaks the sweep. */
class RuntimeRegistryTest {

    private class FakeRuntime(
        override val id: String,
        override val category: RuntimeCategory = RuntimeCategory.LLM,
        private val block: () -> RuntimeStatus
    ) : Runtime {
        override val displayName: String = id
        override val description: String = "fake"
        override suspend fun status(): RuntimeStatus = block()
    }

    @Test
    fun throwing_runtime_is_isolated() = runBlocking {
        val registry = RuntimeRegistry(
            listOf(
                FakeRuntime("b_ok") { RuntimeStatus(true, "fine") },
                FakeRuntime("a_boom") { error("kaput") },
                FakeRuntime("c_ok") { RuntimeStatus(true, "fine") }
            )
        )
        val statuses = registry.statuses()
        assertEquals(listOf("a_boom", "b_ok", "c_ok"), statuses.map { it.first.id })
        val boom = statuses.first { it.first.id == "a_boom" }.second
        assertFalse(boom.available)
        assertEquals("kaput", boom.detail)
        assertTrue(statuses.filter { it.first.id != "a_boom" }.all { it.second.available })
    }

    @Test
    fun lookup_by_id_and_category() {
        val registry = RuntimeRegistry(
            listOf(
                FakeRuntime("mcp", RuntimeCategory.MCP) { RuntimeStatus(true, "x") },
                FakeRuntime("gguf", RuntimeCategory.LLM) { RuntimeStatus(true, "x") },
                FakeRuntime("gguf", RuntimeCategory.LLM) { RuntimeStatus(false, "duplicate") }
            )
        )
        assertEquals(2, registry.size)
        assertEquals("mcp", registry.byId("mcp")?.id)
        assertEquals(listOf("gguf"), registry.byCategory(RuntimeCategory.LLM).map { it.id })
    }
}
