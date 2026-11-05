package com.jarvis.assistant.androllm.memory

import com.jarvis.assistant.androllm.memory.extraction.ExtractionJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionJsonParserTest {

    @Test
    fun parsesCleanJson() {
        val raw = """
            {"memories":[
              {"content":"User prefers dark mode","category":"PREFERENCES","importance":3,"tags":["ui"],"project":null},
              {"content":"Working on AndroLLM","category":"PROJECTS","importance":4,"tags":[],"project":"AndroLLM"}
            ]}
        """.trimIndent()
        val items = ExtractionJsonParser.parse(raw)
        assertEquals(2, items.size)
        assertEquals(MemoryCategory.PREFERENCES, items[0].category)
        assertEquals(listOf("ui"), items[0].tags)
        assertEquals("AndroLLM", items[1].projectName)
    }

    @Test
    fun parsesMarkdownFencedJsonWithProse() {
        val raw = """
            Here is the result:
            ```json
            {"memories":[{"content":"User knows Kotlin","category":"SKILLS","importance":2}]}
            ```
            Hope that helps!
        """.trimIndent()
        val items = ExtractionJsonParser.parse(raw)
        assertEquals(1, items.size)
        assertEquals(MemoryCategory.SKILLS, items[0].category)
    }

    @Test
    fun normalizesCategoryNameVariants() {
        val raw = """
            {"memories":[
              {"content":"A","category":"programming_languages"},
              {"content":"B","category":"Programming Languages"},
              {"content":"C","category":"BOGUS"},
              {"content":"D","category":"PREFERENCES"}
            ]}
        """.trimIndent()
        val items = ExtractionJsonParser.parse(raw)
        assertEquals(MemoryCategory.PROGRAMMING_LANGUAGES, items[0].category)
        assertEquals(MemoryCategory.PROGRAMMING_LANGUAGES, items[1].category)
        assertEquals(MemoryCategory.CUSTOM, items[2].category)
        assertEquals(MemoryCategory.PREFERENCES, items[3].category)
    }

    @Test
    fun recoversContentFromTruncatedOutput() {
        val raw = """{"memories":[{"content":"User is learning Jetpack Compose","category":"GOALS","imp"""
        val items = ExtractionJsonParser.parse(raw)
        assertTrue(items.isNotEmpty())
        assertTrue(items[0].content.contains("Jetpack Compose"))
    }

    @Test
    fun clampsImportance() {
        val items = ExtractionJsonParser.parse("""{"memories":[{"content":"A","importance":99},{"content":"B","importance":-5}]}""")
        assertEquals(5, items[0].importance)
        assertEquals(1, items[1].importance)
    }

    @Test
    fun deduplicatesIdenticalContent() {
        val items = ExtractionJsonParser.parse("""{"memories":[{"content":"Same fact"},{"content":"Same fact"}]}""")
        assertEquals(1, items.size)
    }

    @Test
    fun emptyOrNonJsonYieldsEmptyList() {
        assertTrue(ExtractionJsonParser.parse("").isEmpty())
        assertTrue(ExtractionJsonParser.parse(null).isEmpty())
        assertTrue(ExtractionJsonParser.parse("no json here").isEmpty())
        assertTrue(ExtractionJsonParser.parse("""{"memories":[]}""").isEmpty())
        // Jarvis offline fallback strings must never become memories.
        assertTrue(ExtractionJsonParser.parse("Sir, the offline neural model is not ready.").isEmpty())
    }

    @Test
    fun ignoresBlankAndOverlongContent() {
        val long = "x".repeat(900)
        assertTrue(ExtractionJsonParser.parse("""{"memories":[{"content":"  "},{"content":"$long"}]}""").isEmpty())
    }

    @Test
    fun mapsAndroCategoriesOntoJarvisCategories() {
        assertEquals("Preferences", MemoryCategory.PREFERENCES.jarvisCategory)
        assertEquals("Personal", MemoryCategory.IDENTITY.jarvisCategory)
        assertEquals("Notes", MemoryCategory.PROJECTS.jarvisCategory)
    }
}
