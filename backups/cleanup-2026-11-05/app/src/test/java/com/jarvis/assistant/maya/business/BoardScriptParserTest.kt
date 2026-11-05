package com.jarvis.assistant.maya.business

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardScriptParserTest {

    private val plan = """
        # Diwali leads plan
        ## Target audience
        - Nashik shop owners, 25-50
        - New home buyers
        ## Offer
        - Free site visit
        ## Week-by-week actions
        1. Week 1: 3 reels on Instagram, measure DMs
        * Week 2: **WhatsApp** broadcast, measure replies
        ---
        ## Note
        - Ye suggestions aur andaaze hain, measured data nahi.
    """.trimIndent()

    @Test
    fun parsesTitleAndSections() {
        val b = BoardScriptParser.parse(plan)
        assertEquals("Diwali leads plan", b.title)
        assertEquals(listOf("Target audience", "Offer", "Week-by-week actions", "Note"), b.sections.map { it.name })
        assertEquals(2, b.section("target audience")!!.points.size)
    }

    @Test
    fun bulletVariantsAndBoldStripped() {
        val pts = BoardScriptParser.parse(plan).section("Week-by-week actions")!!.points
        assertEquals("Week 1: 3 reels on Instagram, measure DMs", pts[0])
        assertEquals("Week 2: WhatsApp broadcast, measure replies", pts[1])
    }

    @Test
    fun noteSection() {
        val note = BoardScriptParser.parse(plan).note
        assertNotNull(note)
        assertTrue(note!!.points.single().contains("andaaze"))
    }

    @Test
    fun renderRoundTrip() {
        val b = BoardScriptParser.parse(plan)
        val again = BoardScriptParser.parse(b.render())
        assertEquals(b, again)
        assertTrue(b.render().contains("---\n## Note"))
    }

    @Test
    fun preambleBecomesSummary() {
        val b = BoardScriptParser.parse("Intro line one\nIntro line two\n## Points\n- a")
        assertEquals("Summary", b.sections.first().name)
        assertEquals(2, b.sections.first().points.size)
    }

    @Test
    fun jsonRoundTrip() {
        val b = BoardScriptParser.parse(plan)
        assertEquals(b, BoardScript.fromJson(b.toJson()))
    }
}
