package com.jarvis.assistant.maya.connectors

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Notion REST client (integration secret). Only pages shared with the integration are visible. */
class NotionClient(private val context: Context) {

    companion object {
        private const val BASE = "https://api.notion.com/v1"
        private const val VERSION = "2022-06-28"
        private const val SERVICE = "Notion"
    }

    data class Page(val id: String, val title: String, val kind: String, val url: String, val lastEdited: String)

    private fun headers(): Map<String, String> {
        val token = ConnectorSecrets.notionToken(context) ?: throw ConnectorHttp.notLinked(ConnectorService.NOTION)
        return mapOf("Authorization" to "Bearer $token", "Notion-Version" to VERSION)
    }

    private fun check(resp: ConnectorHttp.Resp, notFound: String? = null): ConnectorHttp.Resp {
        if (resp.ok) return resp
        val msg = when (resp.code) {
            401 -> "Notion ka integration secret kaam nahi kar raha — Settings > Connectors me Notion dobara link kar do."
            403 -> "Notion ne mana kar diya — us page me ... (top-right) > Connections > apna integration add karo, tabhi main use chhu sakti hoon."
            404 -> "'${notFound ?: "ye"}' Notion me nahi mila — ya id galat hai, ya page integration ke saath share nahi hai (page > ... > Connections > integration)."
            429 -> "Notion ne rate limit laga di — thodi der me phir try karo."
            in 500..599 -> "Notion ke server pe dikkat hai (error ${resp.code})."
            else -> "Notion se error ${resp.code}: ${ConnectorHttp.errorSnippet(resp.body)}"
        }
        throw ConnectorException(msg, resp.code)
    }

    /** Lightweight connectivity test: GET /users/me. */
    suspend fun testConnection(): String {
        val resp = check(ConnectorHttp.get(SERVICE, "$BASE/users/me", headers()))
        val j = resp.json()
        val name = j.optString("name").ifBlank { j.optJSONObject("bot")?.optJSONObject("owner")?.optJSONObject("user")?.optString("name").orEmpty() }
        return "Notion workspace linked${if (name.isNotBlank()) " as $name" else ""}"
    }

    suspend fun search(query: String, limit: Int = 10): List<Page> {
        val body = JSONObject().apply {
            if (query.isNotBlank()) put("query", query)
            put("page_size", limit.coerceIn(1, 50))
            put("sort", JSONObject().put("direction", "descending").put("timestamp", "last_edited_time"))
        }
        val resp = check(ConnectorHttp.postJson(SERVICE, "$BASE/search", body, headers()))
        val results = resp.json().optJSONArray("results") ?: JSONArray()
        val out = mutableListOf<Page>()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            out.add(
                Page(
                    id = r.optString("id"),
                    title = NotionBlockText.titleOf(r),
                    kind = r.optString("object"),
                    url = r.optString("url"),
                    lastEdited = r.optString("last_edited_time").take(10)
                )
            )
        }
        return out
    }

    private suspend fun fetchChildren(blockId: String): JSONArray {
        val all = JSONArray()
        var cursor: String? = null
        var guard = 0
        do {
            val url = "$BASE/blocks/$blockId/children?page_size=100" + (cursor?.let { "&start_cursor=$it" } ?: "")
            val j = check(ConnectorHttp.get(SERVICE, url, headers()), "page").json()
            val results = j.optJSONArray("results") ?: JSONArray()
            for (i in 0 until results.length()) all.put(results.get(i))
            cursor = if (j.optBoolean("has_more")) j.optString("next_cursor").ifBlank { null } else null
            guard++
        } while (cursor != null && guard < 10)
        return all
    }

    /** Reads a page's title + body text (blocks flattened, nested children up to 2 levels). */
    suspend fun readPage(pageId: String, maxChars: Int = 12000): String {
        val id = normalizeId(pageId)
        val page = check(ConnectorHttp.get(SERVICE, "$BASE/pages/$id", headers()), "page").json()
        val title = NotionBlockText.titleOf(page)
        val blocks = fetchChildren(id)
        // Pre-fetch nested children (2 levels) so flatten() can stay synchronous.
        val nested = HashMap<String, JSONArray>()
        suspend fun collect(arr: JSONArray, depth: Int) {
            if (depth > 2) return
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                if (b.optBoolean("has_children") && b.optString("type") != "child_page") {
                    val kids = try { fetchChildren(b.optString("id")) } catch (_: ConnectorException) { JSONArray() }
                    nested[b.optString("id")] = kids
                    collect(kids, depth + 1)
                }
            }
        }
        collect(blocks, 1)
        val text = NotionBlockText.flatten(blocks) { nested[it] }
        val full = "# $title\n\n$text"
        return if (full.length > maxChars) full.take(maxChars) + "\n…(truncated)" else full
    }

    /**
     * Creates a page under [parentId]. The parent may be a page (default) or a database
     * ([parentIsDatabase]); for a database the title property is discovered automatically.
     */
    suspend fun createPage(parentId: String, title: String, content: String?, parentIsDatabase: Boolean = false): Page {
        val parent = normalizeId(parentId)
        val titleProp = if (parentIsDatabase) {
            val db = check(ConnectorHttp.get(SERVICE, "$BASE/databases/$parent", headers()), "database").json()
            val props = db.optJSONObject("properties") ?: JSONObject()
            var name = "Name"
            val keys = props.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (props.optJSONObject(k)?.optString("type") == "title") { name = k; break }
            }
            name
        } else "title"
        val body = JSONObject().apply {
            put("parent", JSONObject().put(if (parentIsDatabase) "database_id" else "page_id", parent))
            put("properties", JSONObject().put(titleProp, JSONObject().put("title", JSONArray().put(
                JSONObject().put("type", "text").put("text", JSONObject().put("content", title.take(2000)))
            ))))
            if (!content.isNullOrBlank()) put("children", limit100(NotionBlockText.paragraphBlocks(content)))
        }
        val resp = check(ConnectorHttp.postJson(SERVICE, "$BASE/pages", body, headers()), "parent page")
        val j = resp.json()
        return Page(j.optString("id"), title, "page", j.optString("url"), j.optString("last_edited_time").take(10))
    }

    /** Appends paragraphs to the end of a page. */
    suspend fun appendText(pageId: String, text: String): Int {
        val id = normalizeId(pageId)
        val blocks = NotionBlockText.paragraphBlocks(text)
        var appended = 0
        var i = 0
        while (i < blocks.length()) {
            val batch = JSONArray()
            var n = 0
            while (i < blocks.length() && n < 100) { batch.put(blocks.get(i)); i++; n++ }
            check(ConnectorHttp.patchJson(SERVICE, "$BASE/blocks/$id/children", JSONObject().put("children", batch), headers()), "page")
            appended += n
        }
        return appended
    }

    private fun limit100(arr: JSONArray): JSONArray {
        if (arr.length() <= 100) return arr
        val out = JSONArray()
        for (i in 0 until 100) out.put(arr.get(i))
        return out
    }

    private fun normalizeId(raw: String): String {
        var s = raw.trim()
        // Accept full Notion URLs: take the trailing 32-hex id.
        val m = Regex("([0-9a-fA-F]{32})").findAll(s).lastOrNull()
        if (m != null && (s.contains("notion.so") || s.length == 32)) s = m.value
        return s.removePrefix("/")
    }
}
