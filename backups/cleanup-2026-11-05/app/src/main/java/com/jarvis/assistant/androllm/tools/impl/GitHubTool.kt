package com.jarvis.assistant.androllm.tools.impl

import com.jarvis.assistant.androllm.agent.core.PrivacyLevel
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolCost
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * GitHub REST API access (no auth needed for public data — the anonymous
 * rate limit is 60 req/hr, plenty for a search + a release check). Uses the
 * shared [ToolHttp] client.
 */
class GitHubTool : Tool {

    private val json = Json { ignoreUnknownKeys = true }

    override val spec = ToolSpec(
        name = "github",
        description = "Query GitHub: action 'search_repos' (with query) finds repositories; 'latest_release' (with repo owner/name) returns the newest release; 'repo_info' (with repo) returns stars, forks and description.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("search_repos", "latest_release", "repo_info").forEach { add(it) } }
                }
                putJsonObject("query") { put("type", "string"); put("description", "Search query for search_repos") }
                putJsonObject("repo") { put("type", "string"); put("description", "owner/repo for latest_release and repo_info") }
            }
            putJsonArray("required") { add("action") }
        },
        permission = ToolPermission.GITHUB,
        category = ToolCategory.INFORMATION,
        cacheable = true,
        privacyLevel = PrivacyLevel.NETWORK,
        cost = ToolCost.NETWORK,
        failureModes = listOf("network", "rate_limit", "timeout")
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val action = ToolArgs.str(arguments, "action", "type")
            ?: return ToolResult.Failure("Missing required argument: action")
        return when (action.lowercase()) {
            "search_repos", "search" -> searchRepos(ToolArgs.str(arguments, "query", "q"))
            "latest_release", "release" -> latestRelease(ToolArgs.str(arguments, "repo"))
            "repo_info", "repo" -> repoInfo(ToolArgs.str(arguments, "repo"))
            else -> ToolResult.Failure("Unknown github action '$action'. Use search_repos, latest_release or repo_info.")
        }
    }

    private suspend fun searchRepos(query: String?): ToolResult {
        if (query.isNullOrBlank()) return ToolResult.Failure("Missing required argument: query")
        val body = when (val r = get("https://api.github.com/search/repositories?q=${urlEncode(query)}&sort=stars&per_page=5")) {
            is Fetch.Ok -> r.body
            is Fetch.Err -> return ToolResult.Failure("GitHub search failed — ${r.reason}")
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ToolResult.Failure("GitHub returned an unexpected response.")
        val items = root["items"] as? JsonArray ?: return ToolResult.Failure("No repositories found for '$query'.")
        val repos = items.mapNotNull { it as? JsonObject }.map { item ->
            Repo(
                fullName = item.str("full_name"),
                description = item.str("description"),
                stars = item.str("stargazers_count").toLongOrNull() ?: 0,
                url = item.str("html_url"),
                language = item.str("language")
            )
        }
        val data = buildJsonObject {
            put("query", query)
            putJsonArray("repos") {
                repos.forEach { r ->
                    add(buildJsonObject {
                        put("full_name", r.fullName)
                        put("stars", r.stars)
                        put("language", r.language)
                        put("description", r.description.take(160))
                        put("url", r.url)
                    })
                }
            }
        }
        return ToolResult.Success(
            repos.joinToString("; ") { "${it.fullName} (⭐${it.stars})" }.ifBlank { "No repositories found." },
            data
        )
    }

    private suspend fun latestRelease(repo: String?): ToolResult {
        if (repo.isNullOrBlank() || !repo.contains('/')) {
            return ToolResult.Failure("Missing required argument: repo (owner/name, e.g. BerriAI/litellm)")
        }
        val body = when (val r = get("https://api.github.com/repos/${repo.trim()}/releases/latest")) {
            is Fetch.Ok -> r.body
            is Fetch.Err -> return ToolResult.Failure("GitHub request failed — ${r.reason}")
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ToolResult.Failure("Repo '$repo' has no releases or was not found.")
        val tag = root.str("tag_name").ifBlank { "unknown" }
        val name = root.str("name")
        val published = root.str("published_at")
        val notes = root.str("body").take(600)
        val data = buildJsonObject {
            put("repo", repo)
            put("tag", tag)
            put("name", name)
            put("published_at", published)
            put("notes", notes)
        }
        return ToolResult.Success("$repo latest release: $tag${if (name.isNotBlank()) " — $name" else ""} (published $published).\n$notes", data)
    }

    private suspend fun repoInfo(repo: String?): ToolResult {
        if (repo.isNullOrBlank() || !repo.contains('/')) {
            return ToolResult.Failure("Missing required argument: repo (owner/name)")
        }
        val body = when (val r = get("https://api.github.com/repos/${repo.trim()}")) {
            is Fetch.Ok -> r.body
            is Fetch.Err -> return ToolResult.Failure("GitHub request failed — ${r.reason}")
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ToolResult.Failure("Repo '$repo' was not found.")
        val fullName = root.str("full_name").ifBlank { repo }
        val stars = root.str("stargazers_count").toLongOrNull() ?: 0
        val forks = root.str("forks_count").toLongOrNull() ?: 0
        val desc = root.str("description")
        val lang = root.str("language")
        return ToolResult.Success(
            "$fullName — ⭐$stars · forks $forks · $lang\n$desc",
            buildJsonObject {
                put("full_name", fullName)
                put("stars", stars)
                put("forks", forks)
                put("language", lang)
                put("description", desc.take(200))
            }
        )
    }

    private sealed interface Fetch {
        data class Ok(val body: String) : Fetch
        data class Err(val reason: String) : Fetch
    }

    private suspend fun get(url: String): Fetch = runCatching {
        val resp = ToolHttp.get(
            url,
            mapOf("User-Agent" to "Jarvis-Assistant", "Accept" to "application/vnd.github+json")
        )
        when {
            resp.code == 403 || resp.code == 429 -> Fetch.Err("GitHub rate limit reached (60 requests/hour without sign-in). Try again later.")
            resp.code == 404 -> Fetch.Err("not found (check the owner/repo name).")
            resp.code !in 200..299 -> Fetch.Err("HTTP ${resp.code}.")
            else -> Fetch.Ok(resp.body)
        }
    }.getOrElse { Fetch.Err("check your connection (${it.message ?: it.javaClass.simpleName}).") }

    private fun JsonObject.str(key: String): String =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private data class Repo(
        val fullName: String,
        val description: String,
        val stars: Long,
        val url: String,
        val language: String
    )
}
