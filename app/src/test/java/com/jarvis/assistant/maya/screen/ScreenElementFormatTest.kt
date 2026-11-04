package com.jarvis.assistant.maya.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenElementFormatTest {

    @Test
    fun formatsBasicLineWithIdHint() {
        val line = ElementLine(id = 3, label = "Send", role = "button", idHint = "send_btn", x = 980, y = 2100)
        assertEquals("#3  Send  [button] [id:send_btn]  @ (980,2100)", ScreenElementFormat.formatLine(line))
    }

    @Test
    fun formatsSwitchState() {
        val line = ElementLine(id = 5, label = "Wi-Fi", role = "switch", state = "currently ON", x = 900, y = 400)
        assertEquals("#5  Wi-Fi  [switch] [currently ON]  @ (900,400)", ScreenElementFormat.formatLine(line))
    }

    @Test
    fun unlabeledSendIconGetsHint() {
        val note = ScreenElementFormat.sendHint("fab_send", ScreenElementFormat.NO_LABEL)
        assertEquals("probably the SEND button", note)
        val line = ElementLine(1, ScreenElementFormat.NO_LABEL, "icon", null, "fab_send", 1000, 2000, note)
        assertEquals("#1  (no label)  [icon] [id:fab_send]  @ (1000,2000)  — probably the SEND button", ScreenElementFormat.formatLine(line))
        assertNull(ScreenElementFormat.sendHint("send", "Send"))
        assertNull(ScreenElementFormat.sendHint(null, ScreenElementFormat.NO_LABEL))
    }

    @Test
    fun cleansLabels() {
        assertEquals("Hello world", ScreenElementFormat.cleanLabel("  Hello \n  world "))
        assertEquals(ScreenElementFormat.NO_LABEL, ScreenElementFormat.cleanLabel(null))
        assertEquals(ScreenElementFormat.NO_LABEL, ScreenElementFormat.cleanLabel("   "))
        val long = ScreenElementFormat.cleanLabel("x".repeat(200))
        assertEquals(ScreenElementFormat.MAX_LABEL_CHARS, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun resourceIdSuffix() {
        assertEquals("send", ScreenElementFormat.resourceIdSuffix("com.whatsapp:id/send"))
        assertEquals("plain", ScreenElementFormat.resourceIdSuffix("plain"))
        assertNull(ScreenElementFormat.resourceIdSuffix(null))
        assertNull(ScreenElementFormat.resourceIdSuffix(""))
    }

    @Test
    fun rolesFromClassAndFlags() {
        assertEquals("input", ScreenElementFormat.roleFor("android.widget.EditText", false, false, false, false, true))
        assertEquals("switch", ScreenElementFormat.roleFor("android.widget.Switch", true, true, false, false, true))
        assertEquals("button", ScreenElementFormat.roleFor("android.widget.Button", true, false, false, false, true))
        assertEquals("icon", ScreenElementFormat.roleFor("android.widget.ImageView", true, false, false, false, false))
        assertEquals("text", ScreenElementFormat.roleFor("android.widget.TextView", false, false, false, false, true))
        assertEquals("link", ScreenElementFormat.roleFor("android.widget.TextView", true, false, false, false, true))
        assertEquals("list", ScreenElementFormat.roleFor("androidx.recyclerview.widget.RecyclerView", false, false, false, true, false))
        assertEquals("item", ScreenElementFormat.roleFor("android.widget.LinearLayout", true, false, false, false, true))
    }

    @Test
    fun dedupeDropsSameLabelAtSamePoint() {
        val lines = listOf(
            ElementLine(1, "Chats", "tab", null, null, 100, 200),
            ElementLine(2, "Chats", "tab", null, null, 100, 200),
            ElementLine(3, "Chats", "tab", null, null, 600, 200)
        )
        val out = ScreenElementFormat.dedupe(lines)
        assertEquals(2, out.size)
        assertEquals(listOf(1, 2), ScreenElementFormat.renumber(out).map { it.id })
    }

    @Test
    fun renderTruncatesKeepingActionableFirst() {
        val lines = (1..130).map { i ->
            ElementLine(i, "L$i", if (i <= 100) "button" else "text", null, null, i, i)
        }
        val out = ScreenElementFormat.render("App on screen: Test (com.test)", lines, 120)
        val rows = out.lines()
        assertEquals("App on screen: Test (com.test)", rows.first())
        assertEquals(1 + 120 + 1, rows.size)
        assertTrue(rows.last().contains("10 more elements"))
        // every actionable element survived
        assertTrue(out.contains("#100  L100  [button]"))
    }

    @Test
    fun renderEmptyScreen() {
        val out = ScreenElementFormat.render("App on screen: X (x)", emptyList())
        assertTrue(out.contains("(no actionable elements found on this screen)"))
    }

    @Test
    fun unlabeledRatio() {
        val lines = listOf(
            ElementLine(1, ScreenElementFormat.NO_LABEL, "icon", x = 1, y = 1),
            ElementLine(2, ScreenElementFormat.NO_LABEL, "icon", x = 2, y = 2),
            ElementLine(3, "OK", "button", x = 3, y = 3),
            ElementLine(4, ScreenElementFormat.NO_LABEL, "icon", x = 4, y = 4)
        )
        assertEquals(0.75f, ScreenElementFormat.unlabeledRatio(lines), 0.0001f)
        assertEquals(0f, ScreenElementFormat.unlabeledRatio(emptyList()), 0.0001f)
    }
}
