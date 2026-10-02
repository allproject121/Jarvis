package com.example.myapplication

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class JarvisAIProcessor(private val bridge: AndroidBridge) {

    private var apiKey: String = ""
    private var aiModel: String = "llama-3.3-70b-versatile"
    private var apiUrl: String = "https://api.groq.com/openai/v1/chat/completions"

    fun setConfig(key: String, url: String, model: String) {
        apiKey = key
        if (url.isNotBlank()) apiUrl = url
        if (model.isNotBlank()) aiModel = model
    }

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    fun processCommand(command: String, screenContext: String, callback: (String) -> Unit) {
        if (!hasApiKey()) {
            // Fallback: simple command parsing
            callback(parseCommandSimple(command))
            return
        }

        Thread {
            try {
                val prompt = buildPrompt(command, screenContext)
                val response = callAI(prompt)
                callback(response)
            } catch (e: Exception) {
                Log.e("AIProcessor", "AI error: ${e.message}")
                callback(parseCommandSimple(command))
            }
        }.start()
    }

    private fun buildPrompt(command: String, screenContext: String): String {
        return """
You are JARVIS AI Processor. Your job is to understand the user's voice command and convert it into a structured action.

**USER COMMAND:** "$command"

**SCREEN CONTEXT:**
$screenContext

Respond with ONLY a JSON object (no other text):
{
  "action": "ACTION_NAME",
  "params": { "key1": "value1", ... },
  "explanation": "brief Hinglish explanation for user"
}

**AVAILABLE ACTIONS:**
- OPEN_APP: Open any app. Params: {app: "app name"}
- WHATSAPP_MSG: Send WhatsApp message. Params: {contact: "name", message: "text"}
- WHATSAPP_CALL: WhatsApp voice call. Params: {contact: "name"}
- WHATSAPP_VIDEO_CALL: WhatsApp video call. Params: {contact: "name"}
- WHATSAPP_SEARCH: Search in WhatsApp. Params: {contact: "name"}
- CALL_CONTACT: Phone call. Params: {contact: "name"}
- SMS_CONTACT: Send SMS. Params: {contact: "name", message: "text"}
- SAVE_CONTACT: Save contact. Params: {name: "name", number: "number"}
- FIND_CONTACT: Find number. Params: {name: "name"}
- CLICK_TEXT: Click on text on screen. Params: {text: "button text"}
- TYPE_TEXT: Type text in input field. Params: {text: "what to type"}
- SCROLL_DOWN: Scroll down
- SCROLL_UP: Scroll up
- SWIPE_LEFT: Swipe left
- SWIPE_RIGHT: Swipe right
- GO_BACK: Go back
- GO_HOME: Go home
- RECENTS: Open recent apps
- LIKE: Like current content (TikTok/IG/YT). Params: {platform: "tiktok/ig/youtube"}
- COMMENT: Add comment. Params: {text: "comment text", platform: "app"}
- SAVE_CONTENT: Save current content
- SHARE: Share current content
- EMOJI: Send emoji. Params: {emoji: "emoji name like smile/heart/thumbsup"}
- SEARCH: Search on current platform. Params: {query: "search text"}
- CAPTURE_SCREEN: Take screenshot
- READ_SCREEN: Read screen content aloud
- SET_VOLUME: Set volume. Params: {level: 0-100}
- TOGGLE_WIFI: Toggle WiFi. Params: {state: on/off}
- TOGGLE_BLUETOOTH: Toggle Bluetooth. Params: {state: on/off}
- TOGGLE_FLASHLIGHT: Toggle flashlight. Params: {state: on/off}
- SET_BRIGHTNESS: Set brightness. Params: {level: 0-255}
- TOGGLE_AIRPLANE: Airplane mode. Params: {state: on/off}
- SET_ALARM: Set alarm. Params: {hour: 7, minute: 0}
- SET_TIMER: Set timer. Params: {seconds: 60}
- PLAY_MUSIC: Play/Pause/Next/Prev. Params: {action: play/pause/next/previous}
- OPEN_YOUTUBE_SEARCH: Search YouTube. Params: {query: "search text"}
- WEB_SEARCH: Google search. Params: {query: "search text"}
- CHECK_WEATHER: Check weather
- CHECK_NEWS: Check news
- CHECK_TIME: Tell time
- CHECK_DATE: Tell date
- CHECK_BATTERY: Battery status
- TELL_JOKE: Tell a joke
- MOTIVATE: Give motivation
- TELL_QUOTE: Give a quote
- SOS_EMERGENCY: Emergency SOS
- AI_CHAT: General AI chat. Params: {query: "user message"}

Your job is to figure out what action matches the command best.
For social media (TikTok, Instagram, YouTube): "like karo" -> LIKE, "comment karo" -> COMMENT, "save karo" -> SAVE_CONTENT
For screen interaction: "yahan click karo" or specific text -> CLICK_TEXT
For typing: "type karo" or "likho" -> TYPE_TEXT
For emoji: "emoji bhejo" -> EMOJI

Be smart and map the command to the right action. Return ONLY the JSON.
"""
    }

    private fun callAI(prompt: String): String {
        return try {
            val url = java.net.URL(apiUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true

            val messages = JSONArray()
            messages.put(JSONObject(mapOf("role" to "user", "content" to prompt)))

            val body = JSONObject().apply {
                put("model", aiModel)
                put("messages", messages)
                put("temperature", 0.3)
                put("max_tokens", 500)
            }

            val writer = java.io.OutputStreamWriter(conn.outputStream)
            writer.write(body.toString())
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            val reader = java.io.BufferedReader(
                java.io.InputStreamReader(
                    if (responseCode in 200..299) conn.inputStream
                    else conn.errorStream
                )
            )
            val response = reader.readText()
            reader.close()

            if (responseCode in 200..299) {
                val json = JSONObject(response)
                val content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                content.trim()
            } else {
                parseCommandSimple("")
            }
        } catch (e: Exception) {
            Log.e("AIProcessor", "API call failed: ${e.message}")
            parseCommandSimple("")
        }
    }

    private fun parseCommandSimple(cmd: String): String {
        return """{"action":"AI_CHAT","params":{"query":"$cmd"},"explanation":"Processing with AI"}"""
    }

    fun parseAIResponse(response: String): Triple<String, Map<String, String>, String> {
        return try {
            // Try to extract JSON from response (it might be wrapped in markdown code blocks)
            var jsonStr = response.trim()
            if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.substringAfter("```json").substringAfter("```").trim()
                jsonStr = jsonStr.substringBefore("```").trim()
            }

            val json = JSONObject(jsonStr)
            val action = json.optString("action", "AI_CHAT")
            val params = mutableMapOf<String, String>()
            val paramsJson = json.optJSONObject("params")
            if (paramsJson != null) {
                for (key in paramsJson.keys()) {
                    params[key] = paramsJson.optString(key, "")
                }
            }
            val explanation = json.optString("explanation", "Ji Sir!")
            Triple(action, params, explanation)
        } catch (e: Exception) {
            Log.e("AIProcessor", "Failed to parse AI response: ${e.message}")
            Triple("AI_CHAT", mapOf("query" to response), "Processing...")
        }
    }
}
