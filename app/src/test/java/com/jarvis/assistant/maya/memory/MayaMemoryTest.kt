package com.jarvis.assistant.maya.memory

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

class RulesTest {

    @Test
    fun rulesJsonRoundTrip() {
        val file = RulesFile(3, listOf(Rule("r1", "Jawab chhote rakho", 100L, 200L, 2), Rule("r2", "Mujhe Boss bulao", 300L)))
        val parsed = RulesCodec.parse(RulesCodec.serialize(file))
        assertEquals(file, parsed)
    }

    @Test
    fun parseToleratesGarbage() {
        assertEquals(RulesFile(), RulesCodec.parse("not json"))
        assertEquals(RulesFile(), RulesCodec.parse(null))
        val p = RulesCodec.parse("""{"rules":[{"text":"  "},{"id":"x","text":"Keep it short"}]}""")
        assertEquals(1, p.rules.size)
        assertEquals("Keep it short", p.rules[0].text)
    }

    @Test
    fun promptRendering() {
        assertEquals("", RulesCodec.render(emptyList()))
        val out = RulesCodec.render(listOf(Rule("a", "Hindi me baat karo", 1L), Rule("b", "Emoji mat use karo", 2L)))
        assertEquals("[RULES — Boss ne diye]\n- Hindi me baat karo\n- Emoji mat use karo", out)
    }

    @Test
    fun renderingRespectsBudget() {
        val rules = (1..50).map { Rule("r$it", "Rule number $it with some padding text", it.toLong()) }
        val out = RulesCodec.render(rules, maxChars = 300)
        assertTrue(out.length <= 360)
        assertTrue(out.contains("more rules"))
    }

    @Test
    fun storeAddUpdateDeleteMerge() {
        val f = File.createTempFile("maya_rules", ".json").apply { delete(); deleteOnExit() }
        val store = RulesStore(f)
        val r = store.add("Jawab chhote rakho")
        assertEquals(r, store.add("jawab chhote rakho"))
        val v = store.version()
        val u = store.update(r.id, "Jawab bahut chhote rakho")!!
        assertEquals(2, u.version)
        assertTrue(store.version() > v)
        assertEquals(u.id, store.findByWords("bahut chhote")?.id)
        assertEquals(1, RulesStore(f).list().size) // persisted
        assertEquals(1, store.merge(listOf(Rule(r.id, "New rule", 5L), Rule("z", "jawab bahut chhote rakho", 6L))))
        assertTrue(store.delete(r.id))
        assertFalse(store.delete("missing"))
        assertEquals(listOf("New rule"), store.list().map { it.text })
    }

    @Test
    fun promptContributorStaysUnderBudget() {
        val rules = RulesCodec.render((1..30).map { Rule("r$it", "Rule $it ".repeat(5), 1L) }, 450)
        val out = MayaMemoryPromptContributor.compose(rules, listOf("x".repeat(300), "y".repeat(300), "z".repeat(300)), "[WIKI]\n- routine: wakes 6:45")
        assertTrue(out.length <= MayaMemoryPromptContributor.MAX_CHARS)
        assertTrue(out.contains("[RULES"))
    }
}

class EpisodeTest {

    private fun ts(y: Int, m: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    @Test
    fun summaryIsCapped() {
        val s = EpisodeCodec.summarize("a".repeat(300), "b")
        assertTrue(s.length <= EpisodeCodec.SUMMARY_MAX)
        assertEquals("User: hi | Jarvis: hello", EpisodeCodec.summarize("hi", "hello"))
    }

    @Test
    fun lineRoundTrip() {
        val e = Episode(ts(2026, 11, 3), "Rohit ko call karo", "Calling Rohit", listOf("call_contact"), app = "com.whatsapp")
        assertEquals(e, EpisodeCodec.fromLine(EpisodeCodec.toLine(e)))
        assertNull(EpisodeCodec.fromLine("garbage"))
    }

    @Test
    fun keywordScoringRanksBestFirst() {
        val eps = listOf(
            Episode(ts(2026, 11, 1), "weather kaisa hai", "Sunny"),
            Episode(ts(2026, 11, 2), "Rohit ko message bhejo", "Message sent to Rohit", listOf("send_whatsapp")),
            Episode(ts(2026, 11, 3), "Rohit ka birthday kab hai", "12 March")
        )
        val hits = EpisodeScoring.recall(eps, "Rohit message", null, 5)
        assertEquals(2, hits.size)
        assertEquals("Rohit ko message bhejo", hits[0].user)
        assertTrue(EpisodeScoring.recall(eps, "whatsapp", null, 5).isNotEmpty()) // tool hit
    }

    @Test
    fun dateFilter() {
        val eps = listOf(
            Episode(ts(2026, 11, 1), "a", "x"),
            Episode(ts(2026, 11, 2, 9), "b", "y"),
            Episode(ts(2026, 11, 2, 22), "c", "z")
        )
        assertEquals(listOf("c", "b"), EpisodeScoring.recall(eps, null, "2026-11-02", 10).map { it.user })
        assertEquals(listOf("a"), EpisodeScoring.recall(eps, null, "01/11/2026", 10).map { it.user })
        assertEquals(3, EpisodeScoring.recall(eps, null, "2026-11", 10).size)
        val now = ts(2026, 11, 3)
        assertEquals(listOf("c", "b"), EpisodeScoring.recall(eps, null, "yesterday", 10, now).map { it.user })
        assertNull(EpisodeScoring.dateRange("someday"))
        assertNotNull(EpisodeScoring.dateRange("aaj"))
    }
}

class WikiTest {

    @Test
    fun slugging() {
        assertEquals("about-you", WikiIndex.slugify("About you"))
        assertEquals("people-friends", WikiIndex.slugify("  People & Friends!! "))
        assertEquals("page", WikiIndex.slugify("!!!"))
        assertTrue(WikiIndex.slugify("x".repeat(100)).length <= 48)
    }

    @Test
    fun indexMergeReplacesAndOrders() {
        val existing = listOf(
            WikiPageMeta("Routine", "routine", "old", 1L, 2),
            WikiPageMeta("Zeta extra", "zeta-extra", "", 1L, 1)
        )
        val merged = WikiIndex.merge(existing, WikiPageMeta("About you", "about-you", "name", 5L, 4))
        assertEquals(listOf("about-you", "routine", "zeta-extra"), merged.map { it.slug })
        val again = WikiIndex.merge(merged, WikiPageMeta("Routine", "routine", "new", 9L, 7))
        assertEquals(3, again.size)
        assertEquals("new", again.first { it.slug == "routine" }.summary)
        assertEquals(again, WikiIndex.parseIndex(WikiIndex.serializeIndex(again)))
    }

    @Test
    fun writerOutputParsing() {
        val (summary, body) = WikiIndex.parseWriterOutput("SUMMARY: Morning routine and gym\n---\n## Subah\n- 6:45 uthta hai")!!
        assertEquals("Morning routine and gym", summary)
        assertTrue(body.startsWith("## Subah"))
        assertNull(WikiIndex.parseWriterOutput("   "))
    }

    @Test
    fun classification() {
        assertEquals("people", WikiIndex.classify("Mera bhai Rohit Pune me rehta hai", "Contacts").slug)
        assertEquals("routine", WikiIndex.classify("I go to the gym every morning at 6 am", "Routines").slug)
    }
}

class NamesTest {

    @Test
    fun reservedNamesRejected() {
        assertTrue(UserNames.isReserved("Jarvis"))
        assertTrue(UserNames.isReserved(" maya "))
        assertTrue(UserNames.isReserved("BOSS"))
        assertTrue(UserNames.isReserved("Jarvis bhai"))
        assertFalse(UserNames.isReserved("Rahul"))
        assertTrue(UserNames.isValid("Priya Sharma"))
        assertFalse(UserNames.isValid("R2D2"))
        assertFalse(UserNames.isValid(""))
    }
}

class BackupFormatTest {

    @Test
    fun backupJsonStructure() {
        val root = MayaBackupFormat.build(
            userName = "Rahul",
            memories = JSONObject().put("version", 2).put("memories", org.json.JSONArray()),
            chats = listOf(MayaBackupFormat.ChatExport("c1", "Chat", 1L, 2L, listOf(MayaBackupFormat.ChatMessageExport("m1", "user", "hi", 1L)))),
            rules = listOf(Rule("r1", "Keep it short", 1L)),
            episodes = listOf(Episode(10L, "hi", "hello")),
            wikiIndex = listOf(WikiPageMeta("Routine", "routine", "s", 3L, 1)),
            wikiPages = mapOf("routine" to "# Routine"),
            notices = listOf(Notice("n1", "Backup ready", "ok", "info", 4L, false)),
            contacts = MayaBackupFormat.Contacts(sos = listOf("112", "9876543210"))
        )
        val parsed = JSONObject(root.toString())
        assertTrue(MayaBackupFormat.isMayaBackup(parsed))
        for (key in listOf("format", "version", "exportedAt", "user", "memories", "chats", "rules", "episodes", "wiki", "notices", "contacts", "excluded")) {
            assertTrue("missing $key", parsed.has(key))
        }
        val text = parsed.toString().lowercase()
        assertFalse(text.contains("apikey"))
        assertEquals("Keep it short", MayaBackupFormat.readRules(parsed).single().text)
        assertEquals("hello", MayaBackupFormat.readEpisodes(parsed).single().assistant)
        assertEquals("# Routine", MayaBackupFormat.readWiki(parsed).second["routine"])
        assertEquals("Backup ready", MayaBackupFormat.readNotices(parsed).single().title)
        assertEquals(1, MayaBackupFormat.readChats(parsed).single().messages.size)
        assertEquals(listOf("112", "9876543210"), MayaBackupFormat.readContacts(parsed).sos)
        assertEquals(listOf("112", "100"), MayaBackupFormat.splitContacts("112, 100"))
    }
}
