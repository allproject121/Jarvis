package com.jarvis.assistant.maya.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelGuidanceTest {

    @Test
    fun instagramGuidanceCarriesCaptionAndRules() {
        val g = ReelGuidance.instagram("  Goa trip #travel ", "clip.mp4")
        assertTrue(g.contains("\"Goa trip #travel\""))
        assertTrue(g.contains("clip.mp4"))
        assertTrue(g.contains("Share ek hi baar dabao"))
        assertTrue(g.contains("\"Post ho gayi\" tabhi bolo"))
        assertTrue(g.contains("'haan'"))
    }

    @Test
    fun facebookGuidanceForbidsBoost() {
        val g = ReelGuidance.facebook("hi", "v.mp4")
        assertTrue(g.contains("Boost"))
        assertTrue(g.contains("Discard"))
    }

    @Test
    fun agentGoalPressesOnce() {
        val g = ReelGuidance.agentGoal("instagram", "cap", "v.mp4")
        assertTrue(g.startsWith("Post the reel that is ALREADY loaded in Instagram's reel composer"))
        assertTrue(g.contains("at most ONCE"))
        assertTrue(g.contains("\"cap\""))
    }

    @Test
    fun captionCleaningAndPackages() {
        assertEquals("a b c", ReelGuidance.cleanCaption("  a \n b\t c "))
        assertEquals("", ReelGuidance.cleanCaption(null))
        assertEquals("com.facebook.katana", ReelGuidance.platformPackage("FB"))
        assertEquals("com.instagram.android", ReelGuidance.platformPackage("insta"))
        assertEquals("com.whatsapp", ReelGuidance.platformPackage("status"))
    }
}
