package com.jarvis.assistant.androllm.tools.impl

import android.util.Log
import com.jarvis.assistant.androllm.agent.core.PrivacyLevel
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolCost
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Web search with a provider fallback chain:
 *
 * 1. DuckDuckGo Instant Answer API — structured, great for facts, but empty
 *    for most open-ended queries.
 * 2. DuckDuckGo HTML results — real web results, no API key.
 * 3. Bing HTML results — independent no-key source when DDG blocks the IP.
 *
 * Never returns a blank answer: with zero results it still returns a clear
 * message (plus structured `items: []`) so the LLM has something to say.
 * Jarvis' dispatcher search_web uses [search] first and keeps its old
 * open-in-Chrome behaviour as the fallback.
 */
class WebSearchTool : Tool {

    override val spec = ToolSpec(
        name = "search_web",
        description = "Search the web for a query and return the top results with titles, snippets and URLs. Use when asked to look something up, check news, prices or facts.",
        supportedTasks = listOf(
            "search", "web search", "look up", "news", "current events",
            "prices", "facts", "latest", "google"
        ),
        // Pure read — identical re-runs reuse the cached result.
        cacheable = true,
        // Multi-provider fallback chain can take longer than the default budget.
        executionTimeoutMs = 60_000L,
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "The search query")
                }
            }
            putJsonArray("required") { add("query") }
        },
        permission = ToolPermission.SEARCH,
        category = ToolCategory.INFORMATION,
        privacyLevel = PrivacyLevel.NETWORK,
        cost = ToolCost.NETWORK,
        failureModes = listOf("network", "timeout", "rate_limit")
    )

    /** Outcome of the provider chain (shared with Jarvis' dispatcher). */
    data class SearchOutcome(val query: String, val provider: String, val results: List<SearchResult>)

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = ToolArgs.str(arguments, "query", "q")
            ?: return ToolResult.Failure("Missing required argument: query")
        val outcome = search(query)
        val results = outcome.results

        if (results.isEmpty()) {
            // The search RAN, it just found nothing — a Success with empty data
            // lets the LLM honestly say "I couldn't find results for X".
            return ToolResult.Success(
                summary = "No web results found for \"$query\" — check the spelling or try a different query.",
                data = buildJsonObject {
                    put("query", query)
                    put("provider", outcome.provider)
                    put("results", 0)
                    putJsonArray("items") { }
                }
            )
        }

        val top = results.take(6)
        val data = buildJsonObject {
            put("query", query)
            put("provider", outcome.provider)
            put("results", top.size)
            putJsonArray("items") {
                top.forEach { r ->
                    add(
                        buildJsonObject {
                            put("title", r.title)
                            put("snippet", r.snippet)
                            put("url", r.url)
                        }
                    )
                }
            }
        }
        return ToolResult.Success(summary = summarize(query, top), data = data)
    }

    /** Runs the provider chain; never throws. */
    suspend fun search(query: String): SearchOutcome {
        val startedAt = System.currentTimeMillis()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")

        // Provider 1 — Instant Answer (facts/definitions).
        var provider = "instant-answer"
        var results = runCatching {
            WebSearchParser.parseInstantAnswer(
                fetch("https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&t=jarvis&q=$encoded")
            )
        }.onFailure { Log.w(TAG, "instant-answer provider failed — trying HTML provider", it) }
            .getOrDefault(emptyList())

        // Provider 2 — DDG HTML web results.
        if (results.isEmpty()) {
            provider = "duckduckgo-html"
            results = runCatching {
                WebSearchParser.parseDdgHtml(fetch("https://html.duckduckgo.com/html/?q=$encoded"))
            }.onFailure { Log.w(TAG, "duckduckgo-html provider failed — trying Bing", it) }
                .getOrDefault(emptyList())
        }

        // Provider 3 — Bing HTML (DDG often blocks mobile/datacenter IPs).
        if (results.isEmpty()) {
            provider = "bing"
            results = runCatching {
                WebSearchParser.parseBingHtml(fetch("https://www.bing.com/search?q=$encoded&count=10"))
            }.onFailure { Log.e(TAG, "bing provider failed", it) }
                .getOrDefault(emptyList())
        }

        Log.i(TAG, "provider=$provider query='$query' results=${results.size} in ${System.currentTimeMillis() - startedAt}ms")
        return SearchOutcome(query, provider, results)
    }

    private suspend fun fetch(url: String): String {
        val resp = ToolHttp.get(
            url,
            // A browser-ish UA keeps the HTML endpoints from blocking bots.
            mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36")
        )
        Log.i(TAG, "GET ${url.substringBefore('?')} → HTTP ${resp.code} (${resp.body.length} bytes)")
        return resp.body
    }

    companion object {
        private const val TAG = "WebSearchTool"

        /** Human/LLM-readable summary of [results]. */
        fun summarize(query: String, results: List<SearchResult>): String {
            val sb = StringBuilder("Search results for \"$query\":")
            results.forEachIndexed { i, r ->
                sb.append(' ').append(i + 1).append(". ")
                    .append(r.title)
                if (r.snippet.isNotBlank()) sb.append(" — ").append(r.snippet)
                if (r.url.isNotBlank()) sb.append(" (").append(r.url).append(')')
            }
            return sb.toString()
        }
    }
}
