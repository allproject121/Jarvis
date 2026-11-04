package com.jarvis.assistant.maya.connectors

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Todoist REST v2 client. */
class TodoistClient(private val context: Context) {

    companion object {
        private const val BASE = "https://api.todoist.com/rest/v2"
        private const val SERVICE = "Todoist"
    }

    private fun headers(): Map<String, String> {
        val token = ConnectorSecrets.todoistToken(context) ?: throw ConnectorHttp.notLinked(ConnectorService.TODOIST)
        return mapOf("Authorization" to "Bearer $token")
    }

    private fun check(resp: ConnectorHttp.Resp, notFoundId: String? = null): ConnectorHttp.Resp {
        if (resp.ok) return resp
        val msg = when (resp.code) {
            401 -> "Todoist ka token kaam nahi kar raha — lagta hai revoke ho gaya. Settings > Connectors > Todoist me naya API token daal do."
            403 -> "Todoist ne is token ko mana kar diya — Todoist Settings > Integrations se naya API token banao aur Settings > Connectors me daalo."
            404 -> if (notFoundId != null) "'$notFoundId' Todoist me nahi mila — id purani ho sakti hai, todoist_list_tasks se dobara le lo."
                   else "Todoist ki API badal gayi lagti hai (server ne 404 diya) — Jarvis ka update chahiye. Tumhara token theek hai."
            429 -> "Todoist ne thodi der ke liye rok diya (bahut requests) — 1-2 minute baad try karo."
            in 500..599 -> "Todoist ke server pe dikkat hai (error ${resp.code})"
            else -> "Todoist se error ${resp.code}: ${ConnectorHttp.errorSnippet(resp.body)}"
        }
        throw ConnectorException(msg, resp.code)
    }

    suspend fun testConnection(): String {
        val resp = check(ConnectorHttp.get(SERVICE, "$BASE/projects", headers()))
        val n = resp.jsonArray().length()
        return "Todoist linked ($n projects)"
    }

    /** Lists tasks by Todoist filter string; default "today | overdue". */
    suspend fun listTasks(filter: String?, limit: Int = 50): List<JSONObject> {
        val f = TodoistRequests.defaultFilter(filter)
        val url = "$BASE/tasks?filter=" + URLEncoder.encode(f, "UTF-8")
        val resp = check(ConnectorHttp.get(SERVICE, url, headers()))
        val arr = try { JSONArray(resp.body) } catch (_: Exception) { throw ConnectorException("Todoist ka jawab samajh nahi aaya.") }
        val out = mutableListOf<JSONObject>()
        for (i in 0 until minOf(arr.length(), limit)) arr.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    suspend fun addTask(content: String, dueString: String?, priority: Int?, projectId: String?, description: String?): JSONObject {
        val body = TodoistRequests.addTaskBody(content, dueString, priority, projectId, description)
        val resp = check(ConnectorHttp.postJson(SERVICE, "$BASE/tasks", body, headers()))
        return resp.json()
    }

    suspend fun completeTask(taskId: String): Boolean {
        val id = taskId.trim()
        check(ConnectorHttp.request(SERVICE, "POST", "$BASE/tasks/$id/close", headers()), id)
        return true
    }
}
