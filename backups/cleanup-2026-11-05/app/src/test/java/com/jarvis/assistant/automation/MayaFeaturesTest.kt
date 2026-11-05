package com.jarvis.assistant.automation

import com.jarvis.assistant.automation.command.CommandParser
import com.jarvis.assistant.automation.command.JarvisCommand
import com.jarvis.assistant.marketing.YouTubeCreatorToolkit
import org.junit.Assert.*
import org.junit.Test

class MayaFeaturesTest {

    @Test
    fun testCommandParserGenerativeMacros() {
        val parsedGpt = CommandParser.parse("create image in chatgpt: cybernetic robot", false, false)
        assertTrue(parsedGpt.command is JarvisCommand.CreateImageInChatGPT)
        assertEquals("cybernetic robot", (parsedGpt.command as JarvisCommand.CreateImageInChatGPT).prompt)

        val parsedGemini = CommandParser.parse("create image in gemini: iron man mark 85", false, false)
        assertTrue(parsedGemini.command is JarvisCommand.CreateImageInGemini)
        assertEquals("iron man mark 85", (parsedGemini.command as JarvisCommand.CreateImageInGemini).prompt)
    }

    @Test
    fun testCommandParserStockQueries() {
        val parsedStock = CommandParser.parse("reliance stock price", false, false)
        assertTrue(parsedStock.command is JarvisCommand.StockQuoteCommand)
        assertEquals("reliance", (parsedStock.command as JarvisCommand.StockQuoteCommand).symbol.lowercase())
    }

    @Test
    fun testYouTubeCreatorToolkit() {
        val script = YouTubeCreatorToolkit.generateScriptOutline("How AI works", "Short", true)
        assertNotNull(script)
        assertTrue(script.contains("HOOK"))
        assertTrue(script.contains("How AI works"))
    }

    @Test
    fun testReviewReplyGeneratorPositive() {
        val reply = com.jarvis.assistant.marketing.ReviewReplyGenerator.draftReply(
            reviewText = "Amazing food and super fast delivery!",
            rating = 5,
            businessName = "Stark Cafe",
            customerName = "Rahul",
            language = "hinglish"
        )
        assertNotNull(reply)
        assertTrue(reply.contains("Rahul"))
        assertTrue(reply.contains("5-star"))
        assertTrue(reply.contains("Stark Cafe"))
    }

    @Test
    fun testReviewReplyGeneratorNegativeDeescalation() {
        val reply = com.jarvis.assistant.marketing.ReviewReplyGenerator.draftReply(
            reviewText = "Order was delayed by an hour and cold.",
            rating = 1,
            businessName = "Stark Cafe",
            customerName = "Amit",
            language = "english",
            contactChannel = "manager@starkcafe.com"
        )
        assertNotNull(reply)
        assertTrue(reply.contains("apologize"))
        assertTrue(reply.contains("manager@starkcafe.com"))
    }
}

