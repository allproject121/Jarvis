package com.jarvis.assistant.maya.hub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubPureLogicTest {

    // ── Maya Home multi-target parsing ───────────────────────────────────────

    private fun dev(name: String, room: String, kind: SmartHomeDevices.Kind, ip: String) =
        SmartHomeDevices.newDevice(name, room, kind, ip, SmartHomeDevices.Firmware.TASMOTA)

    private val devices = listOf(
        dev("room light", "bedroom", SmartHomeDevices.Kind.LIGHT, "192.168.1.10"),
        dev("blue light", "hall", SmartHomeDevices.Kind.LIGHT, "192.168.1.11"),
        dev("fan", "bedroom", SmartHomeDevices.Kind.FAN, "192.168.1.12"),
        dev("charger", "hall", SmartHomeDevices.Kind.PLUG, "192.168.1.13")
    )

    private fun names(r: SmartHomeDevices.Resolution) = r.devices.map { it.name }.toSet()

    @Test
    fun splitsCommaAndAndSeparatedTargets() {
        assertEquals(listOf("room light", "blue light"), SmartHomeDevices.splitTargets("room light, blue light"))
        assertEquals(listOf("fan", "charger"), SmartHomeDevices.splitTargets("fan aur charger"))
        assertEquals(listOf("fan", "blue light"), SmartHomeDevices.splitTargets("fan and blue light"))
    }

    @Test
    fun resolvesMultipleNamedTargets() {
        val r = SmartHomeDevices.resolve("room light, blue light", devices)
        assertEquals(setOf("room light", "blue light"), names(r))
        assertTrue(r.unknown.isEmpty())
        assertFalse(r.all)
    }

    @Test
    fun saariLightsMeansEveryLight() {
        val r = SmartHomeDevices.resolve("saari lights", devices)
        assertEquals(setOf("room light", "blue light"), names(r))
    }

    @Test
    fun sabMeansEverything() {
        val r = SmartHomeDevices.resolve("sab", devices)
        assertEquals(4, r.devices.size)
        assertTrue(r.all)
    }

    @Test
    fun fuzzyAndRoomMatching() {
        assertEquals(setOf("blue light"), names(SmartHomeDevices.resolve("blu light", devices)))
        assertEquals(setOf("fan"), names(SmartHomeDevices.resolve("bedroom fan", devices)))
        assertEquals(setOf("fan"), names(SmartHomeDevices.resolve("pankha", devices)))
    }

    @Test
    fun unknownTargetsAreReportedForFallback() {
        val r = SmartHomeDevices.resolve("fan, geyser", devices)
        assertEquals(setOf("fan"), names(r))
        assertEquals(listOf("geyser"), r.unknown)
    }

    @Test
    fun firmwareDefaultUrls() {
        val t = SmartHomeDevices.defaultUrls(SmartHomeDevices.Firmware.TASMOTA, "192.168.1.5", SmartHomeDevices.Kind.LIGHT)
        assertEquals("http://192.168.1.5/cm?cmnd=Power%20On", t["on"])
        val e = SmartHomeDevices.defaultUrls(SmartHomeDevices.Firmware.ESPHOME, "http://esp.local/", SmartHomeDevices.Kind.RELAY, "relay1")
        assertEquals("http://esp.local/switch/relay1/turn_on", e["on"])
        assertTrue(SmartHomeDevices.defaultUrls(SmartHomeDevices.Firmware.CUSTOM, "1.2.3.4", SmartHomeDevices.Kind.PLUG).isEmpty())
    }

    @Test
    fun actionNormalisation() {
        assertEquals("on", SmartHomeDevices.normalizeAction("chalu karo"))
        assertEquals("off", SmartHomeDevices.normalizeAction("off"))
        assertEquals("blink", SmartHomeDevices.normalizeAction("Blink"))
        assertEquals("chase", SmartHomeDevices.normalizeAction("party"))
        assertEquals("cancel", SmartHomeDevices.normalizeAction("cancel"))
    }

    // ── Pattern scheduler timing maths ───────────────────────────────────────

    @Test
    fun blinkTiming() {
        val steps = PatternMath.blink(deviceCount = 2, intervalSec = 0.5, count = 3)
        assertEquals(6, steps.size)
        assertEquals(listOf(0L, 500L, 1000L, 1500L, 2000L, 2500L), steps.map { it.atMs })
        assertTrue(steps.all { it.deviceIndex == -1 })
        assertEquals(listOf(true, false, true, false, true, false), steps.map { it.on })
        assertEquals(2500L, PatternMath.totalDurationMs(steps))
    }

    @Test
    fun chaseTiming() {
        val steps = PatternMath.chase(deviceCount = 3, intervalSec = 1.0, rounds = 1)
        val ons = steps.filter { it.on }
        assertEquals(listOf(0, 1, 2), ons.map { it.deviceIndex })
        assertEquals(listOf(0L, 1000L, 2000L), ons.map { it.atMs })
        assertEquals(3000L, PatternMath.totalDurationMs(steps))
        // Times never go backwards.
        assertEquals(steps.map { it.atMs }.sorted(), steps.map { it.atMs })
    }

    @Test
    fun staggeredAndDelayed() {
        val s = PatternMath.staggered(3, 2.0, true)
        assertEquals(listOf(0L, 2000L, 4000L), s.map { it.atMs })
        val d = PatternMath.delayed(s, PatternMath.clampDelayMs(600.0))
        assertEquals(listOf(600_000L, 602_000L, 604_000L), d.map { it.atMs })
        assertEquals(listOf(0L, 0L), PatternMath.staggered(2, null, false).map { it.atMs })
    }

    @Test
    fun intervalClamping() {
        assertEquals(1000L, PatternMath.clampInterval(null, 1.0))
        assertEquals(200L, PatternMath.clampInterval(0.05, 1.0))
        assertEquals(0L, PatternMath.clampDelayMs(-5.0))
        assertEquals(PatternMath.MAX_DELAY_SEC * 1000L, PatternMath.clampDelayMs(1e9))
        assertEquals(2, PatternMath.grouped(PatternMath.blink(4, 1.0, 1)).size)
    }

    // ── Termux output paging ─────────────────────────────────────────────────

    private val longOutput = (1..1000).joinToString("\n") { "line %04d some build output".format(it) }

    @Test
    fun lastPageIsTailFirstAndLineAligned() {
        val page = TermuxOutputPager.lastPage("job_x", longOutput, 2000)
        assertEquals(longOutput.length, page.to)
        assertTrue(page.text.endsWith("line 1000 some build output"))
        assertTrue(page.text.startsWith("line "))
        assertTrue(page.hasMore)
        assertEquals("job_x#${page.from}", page.nextToken)
    }

    @Test
    fun pagingWalksBackToTheStart() {
        var page = TermuxOutputPager.lastPage("job_y", longOutput, 3000)
        val seen = StringBuilder(page.text)
        var guard = 0
        while (page.nextToken != null && guard++ < 100) {
            page = TermuxOutputPager.pageForToken(page.nextToken!!, longOutput, 3000)!!
            seen.insert(0, page.text)
        }
        assertEquals(0, page.from)
        assertNull(page.nextToken)
        assertEquals(longOutput, seen.toString())
    }

    @Test
    fun tokenParsing() {
        assertEquals("job_a" to 42, TermuxOutputPager.parseToken("job_a#42"))
        assertEquals("job_a" to null, TermuxOutputPager.parseToken("job_a"))
        assertNull(TermuxOutputPager.parseToken("job_a#x"))
        assertNull(TermuxOutputPager.parseToken(""))
    }

    @Test
    fun findInOutput() {
        val text = "ok\nERROR: missing module foo\nok\nerror again"
        val m = TermuxOutputPager.find(text, "error")
        assertEquals(2, m.size)
        assertEquals(2, m[0].lineNumber)
        assertTrue(TermuxOutputPager.formatMatches(m, "error").contains("line 4"))
    }

    @Test
    fun serverHeuristicsAndPaths() {
        assertTrue(TermuxOutputPager.looksLikeServer("python -m http.server 8080"))
        assertTrue(TermuxOutputPager.looksLikeServer("npm run dev"))
        assertTrue(TermuxOutputPager.looksLikeServer("./server &"))
        assertFalse(TermuxOutputPager.looksLikeServer("make && make install"))
        assertEquals(listOf(8080), TermuxOutputPager.guessPorts("python -m http.server 8080"))
        assertEquals("/data/data/com.termux/files/home/x.sh", TermuxOutputPager.resolveTermuxPath("~/x.sh"))
        assertEquals("/data/data/com.termux/files/home/.jarvis/notes.txt", TermuxOutputPager.resolveTermuxPath("notes.txt"))
        assertEquals("'it'\\''s'", TermuxOutputPager.shellQuote("it's"))
    }

    @Test
    fun recipesExistWithScripts() {
        listOf("jarvis-setup", "hermes", "openclaw", "ubuntu").forEach { n ->
            val r = TermuxRecipes.get(n)
            assertNotNull(n, r)
            assertTrue(r!!.script.contains("set -e"))
        }
        assertEquals("jarvis-setup", TermuxRecipes.get("hunter-setup")!!.name)
        assertTrue(TermuxRecipes.get("openclaw")!!.script.contains("https://openclaw.ai/install.sh"))
        assertTrue(TermuxRecipes.get("hermes")!!.script.contains("https://github.com/NousResearch/hermes-agent"))
    }

    // ── PC hub envelope ─────────────────────────────────────────────────────

    @Test
    fun envelopeRoundTrip() {
        val msg = PcHubProtocol.text(PcHubProtocol.T_ASK, "kitna RAM free hai?")
        val decoded = PcHubProtocol.decode(msg.encode())!!
        assertEquals(PcHubProtocol.T_ASK, decoded.type)
        assertEquals(msg.id, decoded.id)
        assertEquals("kitna RAM free hai?", decoded.text())
        assertNull(decoded.replyTo)
        assertEquals(1, decoded.version)
    }

    @Test
    fun replyCarriesReplyTo() {
        val raw = JSONObject().put("v", 1).put("type", "reply").put("id", "pc_1").put("reply_to", "m_42")
            .put("payload", JSONObject().put("text", "4 GB")).toString()
        val m = PcHubProtocol.decode(raw)!!
        assertTrue(m.isReply())
        assertEquals("m_42", m.replyTo)
        assertEquals("4 GB", m.text())
    }

    @Test
    fun malformedFramesAreRejected() {
        assertNull(PcHubProtocol.decode("not json"))
        assertNull(PcHubProtocol.decode("{}"))
        assertNull(PcHubProtocol.decode("{\"type\":\"\"}"))
        val noPayload = PcHubProtocol.decode("{\"type\":\"pong\"}")!!
        assertEquals(0, noPayload.payload.length())
    }

    @Test
    fun wsUrlAndPairing() {
        assertEquals("ws://192.168.1.50:8766/jarvis", PcHubProtocol.wsUrl("http://192.168.1.50/", 8766))
        assertEquals("ws://pc:8765/jarvis?token=abc", PcHubProtocol.wsUrl("pc", 8765, "abc"))
        assertEquals("123456", PcPairing.normalize("123 456"))
        assertNull(PcPairing.normalize("12345"))
        assertTrue(PcPairing.matches("123-456", "123456"))
        assertFalse(PcPairing.matches("123457", "123456"))
        assertEquals(6, PcPairing.generateCode().length)
        assertEquals("192.168.1.5" to 9000, PcPairing.parseHostPort("ws://192.168.1.5:9000/jarvis"))
        assertEquals("pc.local" to 8766, PcPairing.parseHostPort("pc.local"))
    }
}
