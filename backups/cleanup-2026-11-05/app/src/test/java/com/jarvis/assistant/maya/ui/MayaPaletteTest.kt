package com.jarvis.assistant.maya.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MayaPaletteTest {

    @Test
    fun parseHex_acceptsShortLongAndAlphaForms() {
        assertEquals(0xFF1E90FF.toInt(), MayaPalette.parseHex("#1E90FF"))
        assertEquals(0xFF1E90FF.toInt(), MayaPalette.parseHex("1e90ff"))
        assertEquals(0xFFFFFFFF.toInt(), MayaPalette.parseHex("#FFF"))
        assertEquals(0xFF112233.toInt(), MayaPalette.parseHex("#123"))
        assertEquals(0x801E90FF.toInt(), MayaPalette.parseHex("#801E90FF"))
        assertEquals(0xFF14161C.toInt(), MayaPalette.parseHex("  #14161C  "))
    }

    @Test
    fun parseHex_fallsBackOnGarbage() {
        val fallback = 0xFF00FF00.toInt()
        assertEquals(fallback, MayaPalette.parseHex(null, fallback))
        assertEquals(fallback, MayaPalette.parseHex("", fallback))
        assertEquals(fallback, MayaPalette.parseHex("#12345", fallback))
        assertEquals(fallback, MayaPalette.parseHex("#GGHHII", fallback))
        assertEquals(MayaPalette.PRIMARY, MayaPalette.parseHex("not a colour"))
    }

    @Test
    fun toHex_roundTripsAndDropsAlpha() {
        assertEquals("#1E90FF", MayaPalette.toHex(MayaPalette.PRIMARY))
        assertEquals("#14161C", MayaPalette.toHex(MayaPalette.parseHex("#14161C")))
        assertEquals("#801E90FF", MayaPalette.toHexWithAlpha(0x801E90FF.toInt()))
        assertEquals("#1E90FF", MayaPalette.toHex(0x331E90FF))
    }

    @Test
    fun channelsAndAlphaHelpers() {
        val c = MayaPalette.argb(0x12, 0x34, 0x56, 0x78)
        assertEquals(0x12, MayaPalette.alpha(c))
        assertEquals(0x34, MayaPalette.red(c))
        assertEquals(0x56, MayaPalette.green(c))
        assertEquals(0x78, MayaPalette.blue(c))
        assertEquals(0x00, MayaPalette.alpha(MayaPalette.withAlpha(MayaPalette.PRIMARY, 0f)))
        assertEquals(0xFF, MayaPalette.alpha(MayaPalette.withAlpha(MayaPalette.PRIMARY, 1f)))
        assertEquals(0x80, MayaPalette.alpha(MayaPalette.withAlpha(MayaPalette.PRIMARY, 0.5f)))
        assertEquals(MayaPalette.PRIMARY and 0xFFFFFF, MayaPalette.withAlpha(MayaPalette.PRIMARY, 0.2f) and 0xFFFFFF)
    }

    @Test
    fun mix_hitsEndpointsAndMidpoint() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertEquals(black, MayaPalette.mix(black, white, 0f))
        assertEquals(white, MayaPalette.mix(black, white, 1f))
        assertEquals(0xFF808080.toInt(), MayaPalette.mix(black, white, 0.5f))
        assertEquals(white, MayaPalette.mix(black, white, 7f)) // clamped
    }

    @Test
    fun contrastRatio_matchesWcagReferenceValues() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertEquals(21.0, MayaPalette.contrastRatio(black, white), 0.01)
        assertEquals(21.0, MayaPalette.contrastRatio(white, black), 0.01) // symmetric
        assertEquals(1.0, MayaPalette.contrastRatio(MayaPalette.PRIMARY, MayaPalette.PRIMARY), 1e-9)
        assertEquals(0.0, MayaPalette.luminance(black), 1e-9)
        assertEquals(1.0, MayaPalette.luminance(white), 1e-9)
    }

    @Test
    fun mayaTextMeetsAaOnMayaSurfaces() {
        assertTrue(MayaPalette.meetsAa(MayaPalette.TEXT, MayaPalette.BG))
        assertTrue(MayaPalette.meetsAa(MayaPalette.TEXT, MayaPalette.SURFACE))
        assertTrue(MayaPalette.meetsAa(MayaPalette.TEXT, MayaPalette.SURFACE_RAISED))
        assertTrue(MayaPalette.meetsAa(MayaPalette.TEXT_SECONDARY, MayaPalette.BG))
        assertTrue(MayaPalette.meetsAa(MayaPalette.TEXT_SECONDARY, MayaPalette.SURFACE))
        // the hairline is decorative and must stay low-contrast against the surface
        assertFalse(MayaPalette.meetsAa(MayaPalette.HAIRLINE, MayaPalette.SURFACE))
    }

    @Test
    fun readableOn_picksInkThatContrasts() {
        assertEquals(MayaPalette.TEXT, MayaPalette.readableOn(MayaPalette.BG))
        assertFalse(MayaPalette.isLight(MayaPalette.BG))
        assertTrue(MayaPalette.isLight(MayaPalette.TINT_SOFTER))
        val onLight = MayaPalette.readableOn(MayaPalette.TINT_SOFTER)
        assertTrue(MayaPalette.contrastRatio(onLight, MayaPalette.TINT_SOFTER) >= 4.5)
        val onPrimary = MayaPalette.readableOn(MayaPalette.PRIMARY)
        assertTrue(MayaPalette.contrastRatio(onPrimary, MayaPalette.PRIMARY) >= 3.0)
    }

    @Test
    fun stateAndMonogramColoursAreDistinct() {
        val states = listOf("idle", "listening", "thinking", "speaking").map { MayaPalette.stateColor(it) }
        assertEquals(4, states.toSet().size)
        assertEquals(MayaPalette.PRIMARY, MayaPalette.stateColor("unknown"))
        val ids = listOf("maya", "maya_pro", "maya_intimate", "friday", "venom", "jarvis")
        assertEquals(ids.size, ids.map { MayaPalette.monogramColor(it) }.toSet().size)
    }
}
