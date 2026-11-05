package com.jarvis.assistant.maya.connectors

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Vercel REST client: deploy a static folder with inline files, list deployments. */
class VercelClient(private val context: Context) {

    companion object {
        private const val SERVICE = "Vercel"
        private const val DEPLOY_URL = "https://api.vercel.com/v13/deployments"
        private const val MAX_FILE_BYTES = 5L * 1024 * 1024
        private const val MAX_FILES = 600
        private const val MAX_TOTAL_BYTES = 40L * 1024 * 1024
        private val SKIP_DIRS = setOf("node_modules", ".git", ".gradle", "build", ".idea", ".next", "dist_cache", "__pycache__")
        private val TEXT_EXT = setOf("html", "htm", "css", "js", "mjs", "json", "txt", "md", "svg", "xml", "map", "ts", "tsx", "jsx", "webmanifest", "csv")
    }

    data class Deployment(val id: String, val url: String, val state: String, val name: String, val created: String)

    private fun headers(): Map<String, String> {
        val token = ConnectorSecrets.vercelToken(context) ?: throw ConnectorHttp.notLinked(ConnectorService.VERCEL)
        return mapOf("Authorization" to "Bearer $token")
    }

    private fun check(resp: ConnectorHttp.Resp): JSONObject {
        if (resp.ok) return resp.json()
        val j = resp.json()
        val err = j.optJSONObject("error")
        val code = err?.optString("code").orEmpty()
        val msg = when {
            resp.code == 401 || resp.code == 403 -> "Vercel token kaam nahi kar raha - Settings > Connectors me naya paste karo"
            code.contains("limit", true) || resp.code == 402 -> "Vercel ne plan limit bata di: ${err?.optString("message")}"
            resp.code == 429 -> "Vercel ne rate limit laga di — thodi der me phir try karo"
            resp.code in 500..599 -> "Vercel ke server pe dikkat hai (error ${resp.code})"
            else -> "Vercel ne mana kiya (HTTP ${resp.code}): ${err?.optString("message") ?: ConnectorHttp.errorSnippet(resp.body)}"
        }
        throw ConnectorException(msg, resp.code)
    }

    suspend fun testConnection(): String {
        val j = check(ConnectorHttp.get(SERVICE, "https://api.vercel.com/v2/user", headers()))
        val u = j.optJSONObject("user") ?: JSONObject()
        return "Vercel linked as ${u.optString("username").ifBlank { u.optString("email") }}"
    }

    /** Vercel project names: lowercase, letters/digits/dash, max 100. */
    fun sanitizeProjectName(raw: String): String {
        val s = raw.trim().lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(100)
        return s.ifBlank { "jarvis-site" }
    }

    /**
     * Deploys every file under [folder] as a static production deployment and waits up to
     * [waitMs] for the build. Returns the deployment with its final state.
     */
    suspend fun deployFolder(folder: File, projectName: String, waitMs: Long = 30_000L): Deployment {
        if (!folder.exists() || !folder.isDirectory) throw ConnectorException("Folder nahi mila: ${folder.path}")
        val files = JSONArray()
        var count = 0
        var total = 0L
        val root = folder.canonicalFile
        root.walkTopDown()
            .onEnter { d -> d == root || d.name !in SKIP_DIRS }
            .filter { it.isFile }
            .forEach { f ->
                if (count >= MAX_FILES) return@forEach
                if (f.length() > MAX_FILE_BYTES) return@forEach
                if (total + f.length() > MAX_TOTAL_BYTES) return@forEach
                val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                val entry = JSONObject().put("file", rel)
                if (f.extension.lowercase() in TEXT_EXT) {
                    entry.put("data", f.readText())
                } else {
                    entry.put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                    entry.put("encoding", "base64")
                }
                files.put(entry)
                count++
                total += f.length()
            }
        if (count == 0) throw ConnectorException("Folder khaali hai — deploy karne ke liye kam se kam ek index.html chahiye.")
        val name = sanitizeProjectName(projectName)
        val body = JSONObject().apply {
            put("name", name)
            put("files", files)
            put("target", "production")
            put("projectSettings", JSONObject().put("framework", JSONObject.NULL))
        }
        val j = check(ConnectorHttp.postJson(SERVICE, DEPLOY_URL, body, headers()))
        val id = j.optString("id")
        if (id.isBlank()) throw ConnectorException("Vercel ka jawab samajh nahi aaya")
        var dep = parseDeployment(j)
        if (dep.url.isBlank()) throw ConnectorException("Vercel ne URL nahi diya")
        val start = System.currentTimeMillis()
        while (dep.state !in setOf("READY", "ERROR", "CANCELED") && System.currentTimeMillis() - start < waitMs) {
            delay(2500)
            dep = getDeployment(id)
        }
        return dep
    }

    suspend fun getDeployment(id: String): Deployment {
        val j = check(ConnectorHttp.get(SERVICE, "$DEPLOY_URL/$id", headers()))
        return parseDeployment(j)
    }

    private fun parseDeployment(j: JSONObject): Deployment {
        val url = j.optString("url").let { if (it.isNotBlank() && !it.startsWith("http")) "https://$it" else it }
        return Deployment(
            id = j.optString("id").ifBlank { j.optString("uid") },
            url = url,
            state = j.optString("readyState").ifBlank { j.optString("state") },
            name = j.optString("name"),
            created = j.optLong("createdAt", j.optLong("created")).toString()
        )
    }

    suspend fun listDeployments(limit: Int = 10, projectName: String? = null): List<Deployment> {
        var url = "https://api.vercel.com/v6/deployments?limit=${limit.coerceIn(1, 50)}"
        if (!projectName.isNullOrBlank()) url += "&app=${sanitizeProjectName(projectName)}"
        val j = check(ConnectorHttp.get(SERVICE, url, headers()))
        val arr = j.optJSONArray("deployments") ?: JSONArray()
        val out = mutableListOf<Deployment>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(parseDeployment(it)) }
        return out
    }

    /** Tail of the build log for a failed deployment (best effort). */
    suspend fun buildLogTail(id: String, maxChars: Int = 1500): String {
        val resp = ConnectorHttp.get(SERVICE, "https://api.vercel.com/v2/deployments/$id/events?limit=100", headers())
        if (!resp.ok) return ""
        val arr = resp.jsonArray()
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val text = e.optString("text").ifBlank { e.optJSONObject("payload")?.optString("text").orEmpty() }
            if (text.isNotBlank()) sb.append(text).append('\n')
        }
        val s = sb.toString()
        return if (s.length > maxChars) s.takeLast(maxChars) else s
    }

    fun describeState(dep: Deployment, logTail: String = ""): String = when (dep.state) {
        "READY" -> "Site live hai: ${dep.url}"
        "ERROR" -> "Vercel pe upload ho gaya par build fail ho gaya. Log ka aakhri hissa:\n$logTail"
        "CANCELED" -> "Vercel ne ye deploy cancel kar diya, site live nahi hui. Aisa aksar tab hota hai jab isi project ka naya deploy shuru ho gaya ho. Dobara deploy bolo."
        else -> "Vercel ne files le li hain par build 30 second me poori nahi hui. URL: ${dep.url} (status ${dep.state}) — vercel_deployment_status se check karo."
    }
}
