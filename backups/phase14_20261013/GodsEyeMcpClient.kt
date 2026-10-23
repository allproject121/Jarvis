package com.jarvis.assistant.ui.godseye

import android.content.Context
import android.content.Intent
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unified Model Context Protocol (MCP) client bridging JARVIS and God's Eye View.
 * Connects directly to the local God's Eye View loopback service at http://localhost:4173/mcp,
 * executing live situational awareness queries and returning structured telemetry and globe camera URLs.
 */
object GodsEyeMcpClient {
    private const val TAG = "GodsEyeMcpClient"
    const val MCP_URL = "http://localhost:4173/mcp"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val rpcIdCounter = AtomicInteger(1)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun executeTool(toolName: String, args: JSONObject): JSONObject {
        val (mcpToolName, normalizedArgs) = normalizeToolAndArgs(toolName, args)

        val rpcId = rpcIdCounter.getAndIncrement()
        val requestJson = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", rpcId)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", mcpToolName)
                put("arguments", normalizedArgs)
            })
        }

        try {
            val requestBody = requestJson.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(MCP_URL)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e(TAG, "MCP HTTP error: code=${response.code}, body=$responseBody")
                return JSONObject().apply {
                    put("success", false)
                    put("message", "God's Eye service returned HTTP ${response.code}")
                }
            }

            val rpcResponse = JSONObject(responseBody)
            val resultObj = rpcResponse.optJSONObject("result") ?: JSONObject()
            val isError = resultObj.optBoolean("isError", false)

            if (isError) {
                val errorMsg = extractContentText(resultObj)
                return JSONObject().apply {
                    put("success", false)
                    put("message", if (errorMsg.isNotBlank()) errorMsg else "Tool execution reported an error.")
                }
            }

            val summaryText = extractContentText(resultObj)
            val structuredContent = resultObj.optJSONObject("structuredContent")
            var viewUrl: String? = structuredContent?.optString("url")?.takeIf { it.isNotBlank() }

            val viewObj = structuredContent?.optJSONObject("view")
            if (viewUrl == null && viewObj != null) {
                viewUrl = buildViewUrlFromViewObject(viewObj)
            }

            val finalResult = JSONObject().apply {
                put("success", true)
                put("summary", summaryText)
                if (structuredContent != null) {
                    put("data", structuredContent)
                }
                if (!viewUrl.isNullOrBlank()) {
                    put("view_url", viewUrl)
                }
            }

            return finalResult
        } catch (e: Exception) {
            Log.e(TAG, "Error executing MCP tool $mcpToolName: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("message", "Failed to connect to God's Eye console on localhost:4173 (${e.message}). Ensure the service is running.")
            }
        }
    }

    private fun normalizeToolAndArgs(toolName: String, args: JSONObject): Pair<String, JSONObject> {
        val normalized = JSONObject()
        val mcpName = when (toolName) {
            "open_gods_eye_view", "gods_eye_show_view" -> "show_in_gods_eye_view"
            "gods_eye_situation_brief" -> "situation_brief"
            "gods_eye_get_earthquakes" -> "get_earthquakes"
            "gods_eye_get_active_fires" -> "get_active_fires"
            "gods_eye_get_recent_launches" -> "get_recent_launches"
            "gods_eye_aircraft_in_area" -> "aircraft_in_area"
            "gods_eye_satellites_overhead" -> "satellites_overhead"
            "gods_eye_military_awareness" -> "military_awareness"
            "gods_eye_find_cctv_cameras" -> "find_cctv_cameras"
            else -> toolName.removePrefix("gods_eye_")
        }

        val location = args.optString("location", "").ifBlank { args.optString("area", "") }
        val rawArea = args.opt("area")
        if (rawArea is JSONObject) {
            normalized.put("area", rawArea)
        } else if (location.isNotBlank()) {
            normalized.put("area", JSONObject().apply { put("place", location) })
        }

        val keys = args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key != "area" && key != "location" && key != "show_on_globe") {
                normalized.put(key, args.get(key))
            }
        }

        return Pair(mcpName, normalized)
    }

    private fun extractContentText(resultObj: JSONObject): String {
        val contentArray = resultObj.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until contentArray.length()) {
            val item = contentArray.optJSONObject(i) ?: continue
            val text = item.optString("text", "")
            if (text.isNotBlank() && !text.startsWith("{")) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(text)
            }
        }
        return sb.toString().trim()
    }

    fun buildViewUrlFromViewObject(viewObj: JSONObject): String {
        val camera = viewObj.optJSONObject("camera") ?: return GodsEyeActivity.GODS_EYE_LOCAL_URL
        val lat = camera.optDouble("lat", 0.0)
        val lon = camera.optDouble("lon", 0.0)
        val alt = camera.optLong("altitude_m", 1500000L)
        val heading = camera.optDouble("heading_deg", 0.0)
        val pitch = camera.optDouble("pitch_deg", -90.0)
        val style = viewObj.optString("style", "normal")
        val styleParam = if (style == "thermal") "&style=flir" else if (style.isNotBlank() && style != "normal") "&style=$style" else ""
        return "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=$lat&lon=$lon&alt=$alt&heading=$heading&pitch=$pitch&roll=0$styleParam"
    }

    fun launchOrUpdateGodsEye(context: Context, viewUrl: String? = null) {
        try {
            val intent = Intent(context, GodsEyeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (!viewUrl.isNullOrBlank()) {
                    putExtra(GodsEyeActivity.EXTRA_VIEW_URL, viewUrl)
                }
            }
            context.startActivity(intent)
            Log.i(TAG, "Launched/Updated GodsEyeActivity with URL: $viewUrl")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch GodsEyeActivity: ${e.message}", e)
        }
    }
}
