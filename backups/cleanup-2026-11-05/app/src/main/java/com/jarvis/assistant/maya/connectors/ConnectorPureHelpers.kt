package com.jarvis.assistant.maya.connectors

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/*
 * Pure-Kotlin helpers (no Android imports) so they can be unit-tested on the JVM:
 * Notion block → text flattening, Todoist add-task body, GitLab path encoding,
 * Telegram getUpdates chat-id detection, Drive share-link formatting.
 */

/** Flattens Notion block JSON (as returned by GET /blocks/{id}/children) into plain text. */
object NotionBlockText {

    fun richText(arr: JSONArray?): String {
        if (arr == null) return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val rt = arr.optJSONObject(i) ?: continue
            val plain = rt.optString("plain_text").ifBlank { rt.optJSONObject("text")?.optString("content").orEmpty() }
            sb.append(plain)
        }
        return sb.toString()
    }

    /** Renders one block (without its children) as a line; null for blocks that carry no text. */
    fun blockLine(block: JSONObject, numberedIndex: Int = 0): String? {
        val type = block.optString("type")
        val data = block.optJSONObject(type) ?: JSONObject()
        val text = richText(data.optJSONArray("rich_text"))
        return when (type) {
            "paragraph" -> text
            "heading_1" -> "# $text"
            "heading_2" -> "## $text"
            "heading_3" -> "### $text"
            "bulleted_list_item" -> "• $text"
            "numbered_list_item" -> "${if (numberedIndex > 0) numberedIndex else 1}. $text"
            "to_do" -> (if (data.optBoolean("checked")) "[x] " else "[ ] ") + text
            "toggle" -> "▸ $text"
            "quote" -> "> $text"
            "callout" -> {
                val icon = data.optJSONObject("icon")?.optString("emoji").orEmpty()
                (if (icon.isNotBlank()) "$icon " else "") + text
            }
            "code" -> "```${data.optString("language")}\n$text\n```"
            "divider" -> "---"
            "child_page" -> "[Page: ${data.optString("title")}]"
            "child_database" -> "[Database: ${data.optString("title")}]"
            "bookmark" -> data.optString("url")
            "embed", "link_preview" -> data.optString("url")
            "image", "file", "pdf", "video" -> {
                val url = data.optJSONObject("external")?.optString("url")
                    ?: data.optJSONObject("file")?.optString("url") ?: ""
                "[$type${if (url.isNotBlank()) ": $url" else ""}]"
            }
            "table_row" -> {
                val cells = data.optJSONArray("cells")
                val parts = mutableListOf<String>()
                if (cells != null) for (i in 0 until cells.length()) parts.add(richText(cells.optJSONArray(i)))
                parts.joinToString(" | ")
            }
            "equation" -> data.optString("expression")
            else -> if (text.isNotBlank()) text else null
        }
    }

    /**
     * Flattens a list of blocks. [children] resolves nested children for a block id (already fetched
     * by the caller); the default never recurses.
     */
    fun flatten(blocks: JSONArray, indent: Int = 0, children: (String) -> JSONArray? = { null }): String {
        val sb = StringBuilder()
        var numbered = 0
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            val type = b.optString("type")
            numbered = if (type == "numbered_list_item") numbered + 1 else 0
            val line = blockLine(b, numbered) ?: continue
            sb.append("  ".repeat(indent)).append(line).append('\n')
            if (b.optBoolean("has_children")) {
                val kids = children(b.optString("id"))
                if (kids != null && kids.length() > 0) sb.append(flatten(kids, indent + 1, children))
            }
        }
        return sb.toString().trimEnd()
    }

    /** Extracts a page/database title from a Notion search result object. */
    fun titleOf(obj: JSONObject): String {
        if (obj.optString("object") == "database") {
            return richText(obj.optJSONArray("title")).ifBlank { "(untitled database)" }
        }
        val props = obj.optJSONObject("properties") ?: return "(untitled)"
        val keys = props.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val p = props.optJSONObject(k) ?: continue
            if (p.optString("type") == "title") {
                return richText(p.optJSONArray("title")).ifBlank { "(untitled)" }
            }
        }
        return "(untitled)"
    }

    /** Splits text into Notion paragraph blocks, respecting the 2000-char rich-text limit. */
    fun paragraphBlocks(text: String): JSONArray {
        val arr = JSONArray()
        val lines = text.split("\n")
        for (line in lines) {
            var rest = line
            do {
                val chunk = rest.take(2000)
                rest = rest.drop(2000)
                arr.put(JSONObject().apply {
                    put("object", "block")
                    put("type", "paragraph")
                    put("paragraph", JSONObject().apply {
                        put("rich_text", JSONArray().put(JSONObject().apply {
                            put("type", "text")
                            put("text", JSONObject().put("content", chunk))
                        }))
                    })
                })
            } while (rest.isNotEmpty())
        }
        return arr
    }
}

/** Builds Todoist REST v2 request bodies. `due_string` is passed through untouched — Todoist parses it. */
object TodoistRequests {
    fun addTaskBody(
        content: String,
        dueString: String? = null,
        priority: Int? = null,
        projectId: String? = null,
        description: String? = null
    ): JSONObject = JSONObject().apply {
        put("content", content.trim())
        val due = dueString?.trim()
        if (!due.isNullOrBlank()) put("due_string", due)
        if (priority != null && priority in 1..4) put("priority", priority)
        if (!projectId.isNullOrBlank()) put("project_id", projectId)
        if (!description.isNullOrBlank()) put("description", description)
    }

    fun defaultFilter(filter: String?): String = filter?.trim()?.ifBlank { null } ?: "today | overdue"

    /** One-line summary of a task object. */
    fun summarize(task: JSONObject): String {
        val due = task.optJSONObject("due")
        val dueText = due?.optString("string")?.ifBlank { null } ?: due?.optString("date").orEmpty()
        val pr = task.optInt("priority", 1)
        val prText = if (pr >= 3) " (p${5 - pr})" else ""
        return "${task.optString("content")}$prText${if (dueText.isNotBlank()) " — due $dueText" else ""} [id ${task.optString("id")}]"
    }
}

/** GitLab REST paths: project paths and file paths are URL-encoded with '/' → %2F and '.' → %2E. */
object GitLabPaths {
    fun encodeProject(project: String): String {
        val p = project.trim().trimStart('/').trimEnd('/')
        if (p.all { it.isDigit() }) return p
        return encodeSegment(p)
    }

    fun encodeFilePath(path: String): String = encodeSegment(path.trim().trimStart('/'))

    private fun encodeSegment(s: String): String =
        URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace(".", "%2E")
            .replace("*", "%2A")
            .replace("%7E", "~")

    fun isSafePath(path: String): Boolean =
        path.isNotBlank() && !path.contains("..") && !path.contains('#') && !path.contains('?') && !path.contains('\\')
}

/** Detects the chat to talk to from a Telegram `getUpdates` response. */
object TelegramUpdates {
    data class Chat(val id: String, val title: String)

    fun detectChat(updates: JSONObject): Chat? {
        if (!updates.optBoolean("ok", true)) return null
        val result = updates.optJSONArray("result") ?: return null
        for (i in result.length() - 1 downTo 0) {
            val u = result.optJSONObject(i) ?: continue
            val msg = u.optJSONObject("message")
                ?: u.optJSONObject("edited_message")
                ?: u.optJSONObject("channel_post")
                ?: u.optJSONObject("my_chat_member")
                ?: u.optJSONObject("callback_query")?.optJSONObject("message")
                ?: continue
            val chat = msg.optJSONObject("chat") ?: continue
            if (!chat.has("id")) continue
            val id = chat.optLong("id").toString()
            val title = chat.optString("title").ifBlank {
                listOf(chat.optString("first_name"), chat.optString("last_name"))
                    .filter { it.isNotBlank() }.joinToString(" ")
                    .ifBlank { chat.optString("username").ifBlank { "chat $id" } }
            }
            return Chat(id, title)
        }
        return null
    }

    /** Splits a long message into ≤4096-char chunks on line boundaries where possible. */
    fun chunks(text: String, max: Int = 4096): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            if (rest.length <= max) { out.add(rest); break }
            var cut = rest.lastIndexOf('\n', max)
            if (cut < max / 2) cut = max
            out.add(rest.substring(0, cut))
            rest = rest.substring(cut).trimStart('\n')
        }
        return out
    }
}

/** Google Drive share-link helpers. */
object DriveShare {
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"

    fun formatLink(fileId: String, webViewLink: String?, mimeType: String? = null): String {
        val v = webViewLink?.trim()
        if (!v.isNullOrBlank()) return v
        return when {
            mimeType == FOLDER_MIME -> "https://drive.google.com/drive/folders/$fileId?usp=sharing"
            mimeType == "application/vnd.google-apps.document" -> "https://docs.google.com/document/d/$fileId/edit?usp=sharing"
            mimeType == "application/vnd.google-apps.spreadsheet" -> "https://docs.google.com/spreadsheets/d/$fileId/edit?usp=sharing"
            else -> "https://drive.google.com/file/d/$fileId/view?usp=sharing"
        }
    }

    fun permissionBody(): JSONObject = JSONObject().put("role", "reader").put("type", "anyone")

    fun isFolder(mimeType: String?): Boolean = mimeType == FOLDER_MIME

    fun exportMime(mimeType: String?): String? = when (mimeType) {
        "application/vnd.google-apps.document" -> "text/plain"
        "application/vnd.google-apps.spreadsheet" -> "text/csv"
        "application/vnd.google-apps.presentation" -> "text/plain"
        else -> null
    }
}
