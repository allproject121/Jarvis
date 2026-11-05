package com.jarvis.assistant.maya.connectors

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Linear GraphQL client (personal API key). */
class LinearClient(private val context: Context) {

    companion object {
        private const val URL = "https://api.linear.app/graphql"
        private const val SERVICE = "Linear"
    }

    data class Team(val id: String, val name: String, val key: String)
    data class Issue(val identifier: String, val title: String, val state: String, val priority: Int, val dueDate: String, val url: String)

    private fun headers(): Map<String, String> {
        val key = ConnectorSecrets.linearToken(context) ?: throw ConnectorHttp.notLinked(ConnectorService.LINEAR)
        // Personal API keys are sent raw; OAuth tokens as "Bearer ...".
        val auth = if (key.startsWith("lin_oauth_")) "Bearer $key" else key
        return mapOf("Authorization" to auth)
    }

    private suspend fun gql(query: String, variables: JSONObject = JSONObject()): JSONObject {
        val body = JSONObject().put("query", query).put("variables", variables)
        val resp = ConnectorHttp.postJson(SERVICE, URL, body, headers())
        if (resp.code == 401 || resp.code == 400 && resp.body.contains("authentication", ignoreCase = true)) {
            throw ConnectorException("Linear API key galat ya expire — Settings > Connectors me nayi key paste karo", resp.code)
        }
        ConnectorHttp.requireOk(SERVICE, resp)
        val j = resp.json()
        val errors = j.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val msg = errors.optJSONObject(0)?.optString("message").orEmpty()
            throw ConnectorException("Linear se error: $msg")
        }
        return j.optJSONObject("data") ?: JSONObject()
    }

    suspend fun testConnection(): String {
        val d = gql("{ viewer { name email } }")
        val v = d.optJSONObject("viewer") ?: JSONObject()
        return "Linear linked as ${v.optString("name").ifBlank { v.optString("email") }}"
    }

    suspend fun listTeams(): List<Team> {
        val d = gql("{ teams(first: 50) { nodes { id name key } } }")
        val nodes = d.optJSONObject("teams")?.optJSONArray("nodes") ?: JSONArray()
        val out = mutableListOf<Team>()
        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            out.add(Team(n.optString("id"), n.optString("name"), n.optString("key")))
        }
        return out
    }

    /** Issues assigned to the viewer that are not completed/canceled. */
    suspend fun myIssues(limit: Int = 30): List<Issue> {
        val q = """
            query(${'$'}first: Int!) {
              viewer {
                assignedIssues(first: ${'$'}first, orderBy: updatedAt,
                  filter: { state: { type: { nin: ["completed", "canceled"] } } }) {
                  nodes { identifier title priority dueDate url state { name } }
                }
              }
            }
        """.trimIndent()
        val d = gql(q, JSONObject().put("first", limit.coerceIn(1, 100)))
        val nodes = d.optJSONObject("viewer")?.optJSONObject("assignedIssues")?.optJSONArray("nodes") ?: JSONArray()
        val out = mutableListOf<Issue>()
        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            out.add(
                Issue(
                    n.optString("identifier"), n.optString("title"),
                    n.optJSONObject("state")?.optString("name").orEmpty(),
                    n.optInt("priority", 0), n.optString("dueDate").takeIf { it != "null" }.orEmpty(), n.optString("url")
                )
            )
        }
        return out
    }

    suspend fun createIssue(teamId: String, title: String, description: String?, priority: Int? = null): Issue {
        val input = JSONObject().put("teamId", teamId.trim()).put("title", title.trim())
        if (!description.isNullOrBlank()) input.put("description", description)
        if (priority != null && priority in 0..4) input.put("priority", priority)
        val q = """
            mutation(${'$'}input: IssueCreateInput!) {
              issueCreate(input: ${'$'}input) { success issue { identifier title url priority dueDate state { name } } }
            }
        """.trimIndent()
        val d = gql(q, JSONObject().put("input", input))
        val res = d.optJSONObject("issueCreate") ?: throw ConnectorException("Linear ka jawab samajh nahi aaya.")
        if (!res.optBoolean("success")) throw ConnectorException("Linear ne issue banane se mana kar diya.")
        val n = res.optJSONObject("issue") ?: JSONObject()
        return Issue(n.optString("identifier"), n.optString("title"), n.optJSONObject("state")?.optString("name").orEmpty(),
            n.optInt("priority", 0), n.optString("dueDate").takeIf { it != "null" }.orEmpty(), n.optString("url"))
    }

    /** Resolves a team key like "ENG" (or a name) to its id when the caller didn't pass a UUID. */
    suspend fun resolveTeamId(keyOrId: String): String {
        val s = keyOrId.trim()
        if (s.length >= 32 && s.contains('-')) return s
        val teams = listTeams()
        return teams.firstOrNull { it.key.equals(s, true) || it.name.equals(s, true) }?.id
            ?: throw ConnectorException("Linear team '$s' nahi mili — linear_list_teams se id le lo.")
    }
}
