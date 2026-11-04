package com.jarvis.assistant.maya.connectors

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Thrown by every connector client; [userMessage] is ready to speak/show (Hinglish like Maya). */
class ConnectorException(val userMessage: String, val httpCode: Int = 0, cause: Throwable? = null) :
    Exception(userMessage, cause)

/**
 * Shared OkHttp JSON helper for the Maya connectors: timeouts, JSON bodies, and HTTP → user-message
 * mapping ("Notion token galat ya expire — Settings > Connectors me naya paste karo").
 */
object ConnectorHttp {

    private const val TAG = "ConnectorHttp"
    val JSON = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    data class Resp(val code: Int, val body: String, val headers: Map<String, String>) {
        val ok: Boolean get() = code in 200..299
        fun json(): JSONObject = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        fun jsonArray(): JSONArray = try { JSONArray(body) } catch (_: Exception) { JSONArray() }
    }

    /** Raw request on Dispatchers.IO. Network failures become [ConnectorException] with a Hinglish message. */
    suspend fun request(
        service: String,
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null
    ): Resp = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).header("User-Agent", "Jarvis-Assistant-Android")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val m = method.uppercase()
        val rb = body ?: if (m == "GET" || m == "HEAD") null else "".toRequestBody(null)
        builder.method(m, rb)
        try {
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val hdrs = HashMap<String, String>()
                for (name in resp.headers.names()) hdrs[name.lowercase()] = resp.header(name).orEmpty()
                Resp(resp.code, text, hdrs)
            }
        } catch (e: UnknownHostException) {
            throw ConnectorException("$service tak pahunch nahi paayi — internet check karo", cause = e)
        } catch (e: SocketTimeoutException) {
            throw ConnectorException("$service ne time pe jawab nahi diya — thodi der me phir try karo", cause = e)
        } catch (e: IOException) {
            throw ConnectorException("$service tak pahunch nahi paayi — internet check karo (${e.message ?: "network error"})", cause = e)
        }
    }

    suspend fun get(service: String, url: String, headers: Map<String, String> = emptyMap()): Resp =
        request(service, "GET", url, headers)

    suspend fun postJson(service: String, url: String, json: JSONObject, headers: Map<String, String> = emptyMap()): Resp =
        request(service, "POST", url, headers + ("Content-Type" to "application/json"), json.toString().toRequestBody(JSON))

    suspend fun patchJson(service: String, url: String, json: JSONObject, headers: Map<String, String> = emptyMap()): Resp =
        request(service, "PATCH", url, headers + ("Content-Type" to "application/json"), json.toString().toRequestBody(JSON))

    suspend fun putJson(service: String, url: String, json: JSONObject, headers: Map<String, String> = emptyMap()): Resp =
        request(service, "PUT", url, headers + ("Content-Type" to "application/json"), json.toString().toRequestBody(JSON))

    suspend fun deleteJson(service: String, url: String, json: JSONObject?, headers: Map<String, String> = emptyMap()): Resp =
        request(service, "DELETE", url, headers + ("Content-Type" to "application/json"), json?.toString()?.toRequestBody(JSON))

    /**
     * Throws a [ConnectorException] with a user-facing message when [resp] is not 2xx.
     * [notFound] lets callers describe what was missing ("page", "task").
     */
    fun requireOk(service: String, resp: Resp, notFound: String? = null): Resp {
        if (resp.ok) return resp
        throw ConnectorException(mapError(service, resp.code, resp.body, notFound), resp.code)
    }

    fun mapError(service: String, code: Int, body: String, notFound: String? = null): String {
        val snippet = errorSnippet(body)
        return when (code) {
            401 -> "$service token galat ya expire — Settings > Connectors me naya paste karo"
            403 -> "$service ne ye request mana kar di (permission/scope kam hai) — Settings > Connectors me naya token banao${if (snippet.isNotBlank()) ": $snippet" else ""}"
            404 -> if (notFound != null) "$service pe ye $notFound nahi mila — id/naam check karo" else "$service pe ye cheez nahi mili (404)"
            409 -> "$service: conflict — cheez pehle se badal gayi hai, dobara padh ke try karo${if (snippet.isNotBlank()) " ($snippet)" else ""}"
            422, 400 -> "$service ne request reject kar di${if (snippet.isNotBlank()) ": $snippet" else ""}"
            429 -> "$service ne rate limit laga di — thodi der me phir try karo"
            in 500..599 -> "$service ke server pe dikkat hai (error $code) — thodi der baad try karo"
            else -> "$service se error $code${if (snippet.isNotBlank()) ": $snippet" else ""}"
        }
    }

    /** Pulls a short human message out of a JSON error body. */
    fun errorSnippet(body: String): String {
        if (body.isBlank()) return ""
        val text = try {
            val j = JSONObject(body)
            j.optString("message").ifBlank { null }
                ?: j.optJSONObject("error")?.let { e -> e.optString("message").ifBlank { null } ?: e.optString("code") }
                ?: j.optString("error").ifBlank { null }
                ?: j.optString("description").ifBlank { null }
                ?: j.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
                ?: ""
        } catch (_: Exception) {
            body
        }
        return text.replace('\n', ' ').trim().take(160)
    }

    fun notLinked(service: ConnectorService): ConnectorException {
        val msg = when (service) {
            ConnectorService.NOTION -> "Notion connect nahi hai. Settings → Connectors se Notion link kar do."
            ConnectorService.LINEAR -> "Linear connect nahi hai. Settings → Connectors se API key daal do."
            ConnectorService.TODOIST -> "Todoist connect nahi hai. Settings → Connectors se token daal do."
            ConnectorService.GITLAB -> "GitLab connect nahi hai. Settings → Connectors se token daal do."
            ConnectorService.VERCEL -> "Vercel token nahi juda hua hai. Settings > Connectors > Vercel, phir vercel.com/account/tokens se token bana ke paste kar do. Free Hobby plan kaafi hai."
            ConnectorService.TELEGRAM -> "Telegram connect nahi hai. Settings → Connectors se bot token daal do."
            ConnectorService.GOOGLE -> "Google Drive connect nahi hai. Settings → Connectors se Drive link kar do."
            ConnectorService.GITHUB -> "GitHub connect nahi hai. Settings → Connectors me GitHub token daal do."
        }
        return ConnectorException(msg)
    }

    fun log(msg: String) = Log.d(TAG, msg)
}
