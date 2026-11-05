package com.jarvis.assistant.maya.business

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentItemParserTest {

    private val sample = """
        Here you go:
        @@ITEM
        KIND: reel_script
        PLATFORM: Instagram
        PILLAR: Education
        TITLE: CCTV myths
        HOOK: Kya aapka CCTV raat me sach me dekhta hai?
        SCENE: 0-3s | dark shop | "Raat ka sach" | Kya aapka camera raat me dekhta hai?
        SCENE: 3-8s | night vision demo | IR 30m | Hamare cameras 30 meter tak
        CAPTION: Raat me bhi saaf video.
        Free site visit ke liye DM karo.
        HASHTAGS: #cctv #nashik
        CTA: DM 'VISIT'
        @@ITEM
        KIND: carousel
        PLATFORM: linkedin
        TITLE: 5 checks
        SLIDE: 1 | Camera lene se pehle 5 checks
        SLIDE: 2 | Resolution
        CAPTION: Save karo.
        CTA: Call now
        @@ITEM
        KIND: blog_brief
        TITLE: CCTV price guide
        HOOK: cctv installation cost nashik
        BODY: Primary keyword: cctv installation nashik
        Outline:
        ## Cost factors - what changes the price
        FAQ: kitna time lagta hai?
    """.trimIndent()

    @Test
    fun parsesThreeItems() {
        val items = ContentItemParser.parse(sample)
        assertEquals(3, items.size)
    }

    @Test
    fun reelScriptFields() {
        val reel = ContentItemParser.parse(sample)[0]
        assertEquals("reel_script", reel.kind)
        assertEquals("instagram", reel.platform)
        assertEquals("Education", reel.pillar)
        assertEquals("CCTV myths", reel.title)
        assertEquals(2, reel.scenes.size)
        assertTrue(reel.scenes[1].startsWith("3-8s"))
        assertEquals("Raat me bhi saaf video.\nFree site visit ke liye DM karo.", reel.caption)
        assertEquals("#cctv #nashik", reel.hashtags)
        assertEquals("DM 'VISIT'", reel.cta)
        assertEquals(ContentItem.STATUS_DRAFT, reel.status)
    }

    @Test
    fun carouselSlides() {
        val c = ContentItemParser.parse(sample)[1]
        assertEquals("carousel", c.kind)
        assertEquals("linkedin", c.platform)
        assertEquals(2, c.slides.size)
    }

    @Test
    fun blogBriefMultilineBody() {
        val b = ContentItemParser.parse(sample)[2]
        assertEquals("blog_brief", b.kind)
        assertTrue(b.body.startsWith("Primary keyword: cctv installation nashik"))
        assertTrue(b.body.contains("Outline:"))
        assertEquals("instagram", b.platform) // default when missing
    }

    @Test
    fun defaultsAndBoldStripping() {
        val raw = "@@ITEM\n**KIND:** post\n**PLATFORM:** twitter\nCAPTION: Hello\nHASHTAGS: #a\n#b"
        val item = ContentItemParser.parse(raw, defaultKind = "story").single()
        assertEquals("post", item.kind)
        assertEquals("x", item.platform)
        assertEquals("Hello", item.caption.trim().lineSequence().first())
        assertEquals("Hello", item.title)
    }

    @Test
    fun emptyInputGivesNothing() {
        assertTrue(ContentItemParser.parse("").isEmpty())
        assertTrue(ContentItemParser.parse("just chatter without labels").isEmpty())
    }

    @Test
    fun jsonRoundTrip() {
        val item = ContentItemParser.parse(sample)[0]
        val back = ContentItem.fromJson(item.toJson())
        assertEquals(item, back)
        assertTrue(back.shareText().contains("#cctv"))
    }
}
