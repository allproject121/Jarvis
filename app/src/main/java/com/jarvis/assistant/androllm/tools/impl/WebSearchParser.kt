package com.jarvis.assistant.androllm.tools.impl

import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** One structured search result fed back to the LLM. */
data class SearchResult(
    val title: String,
    val snippet: String,
    val url: String
)

/**
 * Pure parsers for the search providers — no I/O, fully unit-testable.
 *
 * - [parseInstantAnswer]: DuckDuckGo Instant Answer API (great for facts,
 *   often empty for open-ended queries).
 * - [parseDdgHtml]: DuckDuckGo HTML results page (real web results, no key).
 * - [parseBingHtml]: Bing results page (independent no-key fallback).
 */
object WebSearchParser {

    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    // ── Provider 1: api.duckduckgo.com (Instant Answer JSON) ───────────────

    fun parseInstantAnswer(body: String): List<SearchResult> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<SearchResult>()

        val heading = root.text("Heading")
        val abstractText = root.text("AbstractText")
        val abstractUrl = root.text("AbstractURL")
        if (abstractText != null) {
            out += SearchResult(
                title = heading ?: "Result",
                snippet = abstractText.take(500),
                url = abstractUrl ?: ""
            )
        }
        // Direct answers (calculations, conversions, quick facts).
        root.text("Answer")?.let { answer ->
            out += SearchResult(title = heading ?: "Answer", snippet = answer.take(500), url = "")
        }

        fun collect(topics: JsonArray?) {
            for (el in topics ?: return) {
                val obj = el as? JsonObject ?: continue
                val text = obj.text("Text")
                if (text != null) {
                    out += SearchResult(
                        title = text.substringBefore(" - ").take(120),
                        snippet = text.take(400),
                        url = obj.text("FirstURL") ?: ""
                    )
                } else {
                    (obj["Topics"] as? JsonArray)?.let { collect(it) }
                }
            }
        }
        collect(root["RelatedTopics"] as? JsonArray)
        return out
    }

    // ── Provider 2: html.duckduckgo.com (full web results) ─────────────────

    fun parseDdgHtml(html: String): List<SearchResult> {
        val out = mutableListOf<SearchResult>()
        val titleRe = Regex(
            """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val snippetRe = Regex(
            """<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        val titles = titleRe.findAll(html).toList()
        val snippets = snippetRe.findAll(html).map { unescape(it.groupValues[1]) }.toList()

        for ((i, m) in titles.withIndex()) {
            val title = unescape(m.groupValues[2]).trim().replace(Regex("""\s+"""), " ")
            val href = m.groupValues[1]
            val url = decodeDdgUrl(href)
            if (title.isBlank() || url.isBlank()) continue
            out += SearchResult(
                title = title.take(160),
                snippet = snippets.getOrNull(i)?.take(320) ?: "",
                url = url
            )
        }
        return out
    }

    // ── Provider 3: www.bing.com/search (real web results, direct URLs) ────

    fun parseBingHtml(html: String): List<SearchResult> {
        val out = mutableListOf<SearchResult>()
        val blockRe = Regex(
            """<li class="[^"]*b_algo[^"]*"[^>]*>(.*?)</li>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val linkRe = Regex(
            """<h2[^>]*><a href="([^"]+)"[^>]*>(.*?)</a></h2>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val snippetRe = Regex(
            """<p class="b_lineclamp[^"]*"[^>]*>(.*?)</p>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        for (block in blockRe.findAll(html)) {
            val content = block.groupValues[1]
            val link = linkRe.find(content) ?: continue
            val url = link.groupValues[1].trim()
            val title = unescape(link.groupValues[2]).trim().replace(Regex("""\s+"""), " ")
            if (title.isBlank() || url.isBlank() || url.startsWith("javascript:")) continue
            val snippet = snippetRe.find(content)
                ?.let { unescape(it.groupValues[1]) }
                ?.take(320)
                ?: ""
            out += SearchResult(title = title.take(160), snippet = snippet, url = url)
        }
        return out
    }

    /** DDG wraps result URLs as //duckduckgo.com/l/?uddg=<encoded> — unwrap. */
    private fun decodeDdgUrl(href: String): String {
        val u = if (href.startsWith("//")) "https:$href" else href
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(u)?.groupValues?.get(1) ?: return u
        return runCatching { URLDecoder.decode(uddg, "UTF-8") }.getOrDefault(u)
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("""<[^>]+>"""), " ")
        .replace(Regex("""\s{2,}"""), " ")
        // A stripped tag before punctuation leaves "word ." — fix it.
        .replace(Regex("""\s+([.,!?;:)])"""), "$1")
        .trim()
}
