package com.jarvis.assistant.maya.connectors

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** GitLab REST v4 client (personal access token, gitlab.com or self-hosted). */
class GitLabClient(private val context: Context) {

    companion object {
        private const val SERVICE = "GitLab"
    }

    data class Project(val id: Long, val path: String, val url: String, val lastActivity: String, val defaultBranch: String)
    data class Issue(val iid: Long, val title: String, val state: String, val url: String, val assignee: String)

    private fun base(): String = ConnectorSecrets.gitlabHost(context) + "/api/v4"

    private fun headers(): Map<String, String> {
        val token = ConnectorSecrets.gitlabToken(context) ?: throw ConnectorHttp.notLinked(ConnectorService.GITLAB)
        return mapOf("PRIVATE-TOKEN" to token)
    }

    private fun check(resp: ConnectorHttp.Resp, notFound: String? = null): ConnectorHttp.Resp {
        if (resp.ok) return resp
        if (resp.code == 403 && resp.body.contains("insufficient_scope", true)) {
            throw ConnectorException("Commit nahi hua — token ko `api` scope chahiye, `read_api` se write nahi hota.", 403)
        }
        return ConnectorHttp.requireOk(SERVICE, resp, notFound)
    }

    suspend fun testConnection(): String {
        val j = check(ConnectorHttp.get(SERVICE, "${base()}/user", headers())).json()
        return "GitLab linked as ${j.optString("username")} (${ConnectorSecrets.gitlabHost(context)})"
    }

    suspend fun listProjects(limit: Int = 30): List<Project> {
        val url = "${base()}/projects?membership=true&order_by=last_activity_at&per_page=${limit.coerceIn(1, 100)}"
        val arr = check(ConnectorHttp.get(SERVICE, url, headers())).jsonArray()
        return parseProjects(arr)
    }

    private fun parseProjects(arr: JSONArray): List<Project> {
        val out = mutableListOf<Project>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            out.add(Project(p.optLong("id"), p.optString("path_with_namespace"), p.optString("web_url"),
                p.optString("last_activity_at").take(10), p.optString("default_branch", "main")))
        }
        return out
    }

    suspend fun getProject(project: String): Project {
        val url = "${base()}/projects/${GitLabPaths.encodeProject(project)}"
        val p = check(ConnectorHttp.get(SERVICE, url, headers()), "project").json()
        return Project(p.optLong("id"), p.optString("path_with_namespace"), p.optString("web_url"),
            p.optString("last_activity_at").take(10), p.optString("default_branch", "main"))
    }

    suspend fun listIssues(project: String, state: String = "opened", limit: Int = 30): List<Issue> {
        val url = "${base()}/projects/${GitLabPaths.encodeProject(project)}/issues?state=${URLEncoder.encode(state, "UTF-8")}&per_page=${limit.coerceIn(1, 100)}"
        val arr = check(ConnectorHttp.get(SERVICE, url, headers()), "project").jsonArray()
        val out = mutableListOf<Issue>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            out.add(Issue(it.optLong("iid"), it.optString("title"), it.optString("state"), it.optString("web_url"),
                it.optJSONObject("assignee")?.optString("username").orEmpty()))
        }
        return out
    }

    suspend fun createIssue(project: String, title: String, description: String?, labels: String? = null): Issue {
        val url = "${base()}/projects/${GitLabPaths.encodeProject(project)}/issues"
        val body = JSONObject().put("title", title.trim())
        if (!description.isNullOrBlank()) body.put("description", description)
        if (!labels.isNullOrBlank()) body.put("labels", labels)
        val it = check(ConnectorHttp.postJson(SERVICE, url, body, headers()), "project").json()
        return Issue(it.optLong("iid"), it.optString("title"), it.optString("state"), it.optString("web_url"), "")
    }

    suspend fun readFile(project: String, path: String, ref: String?): String {
        if (!GitLabPaths.isSafePath(path)) throw ConnectorException("File path me '..' ya '#' jaisa hissa hai — sahi path do.")
        val branch = ref?.takeIf { it.isNotBlank() } ?: getProject(project).defaultBranch
        val url = "${base()}/projects/${GitLabPaths.encodeProject(project)}/repository/files/${GitLabPaths.encodeFilePath(path)}/raw?ref=${URLEncoder.encode(branch, "UTF-8")}"
        return check(ConnectorHttp.get(SERVICE, url, headers()), "file").body
    }

    /** Creates or updates a file with a commit. Returns the commit branch and whether it was created. */
    suspend fun writeFile(project: String, path: String, content: String, message: String?, branch: String?): Pair<String, Boolean> {
        if (!GitLabPaths.isSafePath(path)) throw ConnectorException("File path me '..' ya '#' jaisa hissa hai — sahi path do.")
        val target = branch?.takeIf { it.isNotBlank() } ?: getProject(project).defaultBranch
        val fileUrl = "${base()}/projects/${GitLabPaths.encodeProject(project)}/repository/files/${GitLabPaths.encodeFilePath(path)}"
        val exists = ConnectorHttp.request(SERVICE, "HEAD", "$fileUrl?ref=${URLEncoder.encode(target, "UTF-8")}", headers()).ok
        val body = JSONObject().apply {
            put("branch", target)
            put("content", content)
            put("commit_message", message?.takeIf { it.isNotBlank() } ?: (if (exists) "Update $path" else "Add $path") + " via Jarvis")
        }
        val resp = if (exists) ConnectorHttp.putJson(SERVICE, fileUrl, body, headers())
                   else ConnectorHttp.postJson(SERVICE, fileUrl, body, headers())
        check(resp, "project/branch")
        return target to !exists
    }
}
