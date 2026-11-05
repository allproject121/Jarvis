package com.jarvis.assistant.maya.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFinderTest {

    private val catalog = listOf(
        ToolCatalogEntry("set_flashlight", "Turns the phone flashlight / torch LED on or off.", "builtin", listOf("enabled:boolean")),
        ToolCatalogEntry("set_volume", "Sets the media volume level", "builtin", listOf("level:integer")),
        ToolCatalogEntry("send_whatsapp_message", "Send a WhatsApp text message to a contact", "builtin", listOf("contact:string", "message:string")),
        ToolCatalogEntry("watch_for", "Tell the user when something happens on screen", "screen", listOf("kind:string", "value?:string")),
        ToolCatalogEntry("get_weather", "Current weather", "builtin"),
        ToolCatalogEntry("set_reminder", "Set a reminder / alarm at a time", "builtin", listOf("time:string"))
    )

    @Test
    fun synonymFindsFlashlightForTorch() {
        val hits = ToolFinder.search("turn on torch", catalog)
        assertTrue(hits.isNotEmpty())
        assertEquals("set_flashlight", hits.first().entry.name)
    }

    @Test
    fun exactNameWins() {
        val hits = ToolFinder.search("get_weather", catalog)
        assertEquals("get_weather", hits.first().entry.name)
        assertTrue(hits.first().score >= 100)
    }

    @Test
    fun nameTokensOutscoreDescriptionHits() {
        val hits = ToolFinder.search("send message on whatsapp", catalog)
        assertEquals("send_whatsapp_message", hits.first().entry.name)
    }

    @Test
    fun alarmSynonymFindsReminder() {
        val hits = ToolFinder.search("wake me with an alarm", catalog)
        assertEquals("set_reminder", hits.first().entry.name)
    }

    @Test
    fun emptyAndStopwordQueriesReturnNothing() {
        assertTrue(ToolFinder.search("", catalog).isEmpty())
        assertTrue(ToolFinder.search("the and for", catalog).isEmpty())
    }

    @Test
    fun limitIsRespectedAndSortedByScore() {
        val hits = ToolFinder.search("set phone volume level torch reminder", catalog, limit = 2)
        assertEquals(2, hits.size)
        assertTrue(hits[0].score >= hits[1].score)
    }

    @Test
    fun usageLineShowsParamsAndShortDescription() {
        val line = ToolFinder.usageLine(catalog.first())
        assertEquals("set_flashlight(enabled:boolean) — Turns the phone flashlight / torch LED on or off.", line)
        val long = ToolFinder.usageLine(ToolCatalogEntry("x_tool", "y".repeat(400), "f"), maxDesc = 50)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun familiesSummaryGroupsByFamily() {
        val s = ToolFinder.familiesSummary(catalog)
        assertTrue(s.contains("builtin: get_weather"))
        assertTrue(s.contains("screen: watch_for"))
    }
}
