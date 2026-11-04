package com.jarvis.assistant.maya.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollinationsUrlTest {

    @Test
    fun buildsEncodedUrlWithAllParams() {
        val url = PollinationsUrl.build("a cat & dog", 512, 768, seed = 42)
        assertEquals(
            "https://image.pollinations.ai/prompt/a%20cat%20%26%20dog?width=512&height=768&seed=42&nologo=true&model=flux",
            url
        )
    }

    @Test
    fun omitsSeedAndAddsToken() {
        val url = PollinationsUrl.build("sunset", token = "abc 123")
        assertFalse(url.contains("seed="))
        assertTrue(url.endsWith("&token=abc+123"))
        assertTrue(url.contains("width=1024&height=1024"))
    }

    @Test
    fun clampsDimensions() {
        val url = PollinationsUrl.build("x", width = 10, height = 99999)
        assertTrue(url.contains("width=64&height=4096"))
    }

    @Test
    fun foldsStyleIntoPrompt() {
        assertEquals("a fox, anime style", PollinationsUrl.promptWithStyle("a fox", "anime"))
        assertEquals("anime fox", PollinationsUrl.promptWithStyle("anime fox", "anime"))
        assertEquals("a fox", PollinationsUrl.promptWithStyle("a fox", null))
    }

    @Test
    fun parsesSizes() {
        assertEquals(ImageSize.STORY, ImageSize.parse("instagram_story"))
        assertEquals(ImageSize.SQUARE, ImageSize.parse(null))
        assertEquals(ImageSize(800, 600), ImageSize.parse("800x600"))
        assertEquals(ImageSize.LANDSCAPE, ImageSize.parse("wallpaper"))
        assertEquals(ImageSize.POST, ImageSize.parse("instagram_post"))
    }
}
