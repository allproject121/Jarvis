package com.jarvis.assistant.maya.connectors

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorPureHelpersTest {

    private fun rt(text: String) = JSONArray().put(JSONObject().put("type", "text").put("plain_text", text))
    private fun block(type: String, text: String, extra: (JSONObject) -> Unit = {}, id: String = "b", hasChildren: Boolean = false) =
        JSONObject().put("id", id).put("type", type).put("has_children", hasChildren)
            .put(type, JSONObject().put("rich_text", rt(text)).also(extra))

    // ---------------- Notion block → text
    @Test
    fun notionFlattensCommonBlocks() {
        val blocks = JSONArray()
            .put(block("heading_1", "Plan"))
            .put(block("paragraph", "Hello world"))
            .put(block("bulleted_list_item", "milk"))
            .put(block("numbered_list_item", "first"))
            .put(block("numbered_list_item", "second"))
            .put(block("to_do", "done thing", { it.put("checked", true) }))
            .put(block("to_do", "open thing"))
            .put(JSONObject().put("type", "divider").put("divider", JSONObject()))
        val text = NotionBlockText.flatten(blocks)
        assertEquals(
            "# Plan\nHello world\n• milk\n1. first\n2. second\n[x] done thing\n[ ] open thing\n---",
            text
        )
    }

    @Test
    fun notionFlattensNestedChildrenWithIndent() {
        val parent = block("toggle", "More", id = "p1", hasChildren = true)
        val kids = JSONArray().put(block("paragraph", "inside"))
        val text = NotionBlockText.flatten(JSONArray().put(parent)) { if (it == "p1") kids else null }
        assertEquals("▸ More\n  inside", text)
    }

    @Test
    fun notionRichTextConcatenatesAndTitleFound() {
        val arr = JSONArray()
            .put(JSONObject().put("plain_text", "Hello "))
            .put(JSONObject().put("text", JSONObject().put("content", "there")))
        assertEquals("Hello there", NotionBlockText.richText(arr))
        val page = JSONObject().put("object", "page").put("properties",
            JSONObject().put("Name", JSONObject().put("type", "title").put("title", rt("My Page"))))
        assertEquals("My Page", NotionBlockText.titleOf(page))
    }

    @Test
    fun notionParagraphBlocksSplitLongLines() {
        val blocks = NotionBlockText.paragraphBlocks("a\n" + "x".repeat(4500))
        assertEquals(4, blocks.length()) // "a" + 2000 + 2000 + 500
    }

    // ---------------- Todoist due passthrough
    @Test
    fun todoistDueStringPassedThroughUntouched() {
        val body = TodoistRequests.addTaskBody("Buy milk", "next monday 9am", 4, null, null)
        assertEquals("Buy milk", body.getString("content"))
        assertEquals("next monday 9am", body.getString("due_string"))
        assertEquals(4, body.getInt("priority"))
        assertFalse(body.has("project_id"))
    }

    @Test
    fun todoistBlankDueOmittedAndDefaultFilter() {
        val body = TodoistRequests.addTaskBody("x", "   ", 9, "", "")
        assertFalse(body.has("due_string"))
        assertFalse(body.has("priority"))
        assertEquals("today | overdue", TodoistRequests.defaultFilter(null))
        assertEquals("today | overdue", TodoistRequests.defaultFilter("  "))
        assertEquals("#Work", TodoistRequests.defaultFilter("#Work"))
    }

    // ---------------- GitLab path encoding
    @Test
    fun gitlabEncodesProjectAndFilePaths() {
        assertEquals("group%2Fsub%2Fproject", GitLabPaths.encodeProject("group/sub/project"))
        assertEquals("12345", GitLabPaths.encodeProject("12345"))
        assertEquals("src%2Fmain%2Fapp%2Ejs", GitLabPaths.encodeFilePath("src/main/app.js"))
        assertEquals("docs%2Fmy%20file%2Emd", GitLabPaths.encodeFilePath("/docs/my file.md"))
    }

    @Test
    fun gitlabRejectsTraversal() {
        assertFalse(GitLabPaths.isSafePath("../secrets"))
        assertFalse(GitLabPaths.isSafePath("a#b"))
        assertTrue(GitLabPaths.isSafePath("src/index.html"))
    }

    // ---------------- Telegram chat detection
    @Test
    fun telegramDetectsNewestChat() {
        val updates = JSONObject().put("ok", true).put("result", JSONArray()
            .put(JSONObject().put("update_id", 1).put("message", JSONObject().put("chat",
                JSONObject().put("id", 111L).put("type", "private").put("first_name", "Old"))))
            .put(JSONObject().put("update_id", 2).put("message", JSONObject().put("chat",
                JSONObject().put("id", -100987654321L).put("type", "supergroup").put("title", "Family")))))
        val chat = TelegramUpdates.detectChat(updates)!!
        assertEquals("-100987654321", chat.id)
        assertEquals("Family", chat.title)
    }

    @Test
    fun telegramPrivateChatNameAndEmpty() {
        val updates = JSONObject().put("ok", true).put("result", JSONArray()
            .put(JSONObject().put("message", JSONObject().put("chat",
                JSONObject().put("id", 42L).put("first_name", "Rosh").put("last_name", "T")))))
        assertEquals(TelegramUpdates.Chat("42", "Rosh T"), TelegramUpdates.detectChat(updates))
        assertNull(TelegramUpdates.detectChat(JSONObject().put("ok", true).put("result", JSONArray())))
        assertNull(TelegramUpdates.detectChat(JSONObject().put("ok", false)))
    }

    @Test
    fun telegramChunksLongText() {
        val chunks = TelegramUpdates.chunks("a".repeat(5000))
        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.length <= 4096 })
    }

    // ---------------- Drive share link
    @Test
    fun driveShareLinkPrefersWebViewLink() {
        assertEquals("https://docs.google.com/document/d/abc/edit", DriveShare.formatLink("abc", "https://docs.google.com/document/d/abc/edit"))
    }

    @Test
    fun driveShareLinkFallbacksByMime() {
        assertEquals("https://drive.google.com/file/d/f1/view?usp=sharing", DriveShare.formatLink("f1", null))
        assertEquals("https://drive.google.com/drive/folders/d1?usp=sharing", DriveShare.formatLink("d1", "", "application/vnd.google-apps.folder"))
        assertEquals("https://docs.google.com/document/d/g1/edit?usp=sharing", DriveShare.formatLink("g1", null, "application/vnd.google-apps.document"))
        val perm = DriveShare.permissionBody()
        assertEquals("reader", perm.getString("role"))
        assertEquals("anyone", perm.getString("type"))
        assertEquals("text/csv", DriveShare.exportMime("application/vnd.google-apps.spreadsheet"))
        assertNull(DriveShare.exportMime("text/plain"))
    }
}
