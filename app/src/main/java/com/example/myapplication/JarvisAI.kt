package com.example.myapplication

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class JarvisAI {

    private var apiKey: String = ""
    private var apiUrl: String = "https://api.groq.com/openai/v1/chat/completions"
    private var model: String = "llama-3.3-70b-versatile"
    private val conversationHistory = mutableListOf<Map<String, String>>()
    private var lastSearchResults: String = ""

    fun setConfig(key: String, url: String, mdl: String) {
        apiKey = key
        if (url.isNotBlank()) apiUrl = url
        if (mdl.isNotBlank()) model = mdl
    }

    fun hasApiKey(): Boolean = apiKey.isNotBlank()
    fun getModel(): String = model

    fun chat(prompt: String, callback: (String) -> Unit) {
        conversationHistory.add(mapOf("role" to "user", "content" to prompt))
        if (conversationHistory.size > 20) conversationHistory.removeAt(0)

        if (apiKey.isNotBlank()) {
            Thread {
                val response = callAIAPI()
                callback(response)
            }.start()
        } else {
            callback(getLocalResponse(prompt))
        }
    }

    fun chatWithWebFallback(prompt: String, callback: (String) -> Unit) {
        // First check if it's a question that needs web search
        val lower = prompt.lowercase()
        val needsSearch = lower.contains("news") || lower.contains("khabar") ||
                lower.contains("weather") || lower.contains("mausam") ||
                lower.contains("barish") || lower.contains("today") ||
                lower.contains("kaun") || lower.contains("kya hai") ||
                lower.contains("meaning") || lower.contains("matlab") ||
                lower.contains("translate") || lower.contains("anuvad") ||
                lower.contains("definition") || lower.contains("price") ||
                lower.contains("kimat") || lower.contains("score") ||
                lower.contains("result") || lower.contains("match") ||
                lower.contains("who is") || lower.contains("what is") ||
                lower.contains("latest")

        if (needsSearch) {
            val searchResults = webSearch(prompt)
            lastSearchResults = searchResults
            val augmentedPrompt = "User query: $prompt\n\nWeb search results:\n$searchResults\n\nBased on these search results, give a helpful response in Hinglish (mix of Hindi and English). Keep it concise and natural. If you find relevant information, share it with the user."
            if (apiKey.isNotBlank()) {
                chat(augmentedPrompt, callback)
            } else {
                callback("Sir, ye rahe search results:\n$searchResults")
            }
        } else {
            chat(prompt, callback)
        }
    }

    fun webSearch(query: String): String {
        return try {
            val url = URL("https://html.duckduckgo.com/html/?q=${URLEncoder.encode(query, "UTF-8")}")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val html = reader.readText()
            reader.close()

            val results = mutableListOf<String>()
            // Try multiple regex patterns for DuckDuckGo
            val patterns = listOf(
                Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>([^<]+)<"),
                Regex("<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>([^<]+)<"),
                Regex("class=\"result__snippet\"[^>]*>([^<]+)<")
            )

            for (pattern in patterns) {
                val matches = pattern.findAll(html)
                for (match in matches) {
                    if (results.size >= 8) break
                    val text = if (pattern.pattern.contains("href")) {
                        val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                        val urlStr = android.net.Uri.decode(match.groupValues[1])
                        "$title - $urlStr"
                    } else {
                        match.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
                    }
                    if (text.isNotBlank() && text.length > 10) {
                        results.add(text)
                    }
                }
                if (results.size >= 5) break
            }

            if (results.isEmpty()) {
                // Fallback: try Google
                try {
                    val googleUrl = URL("https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}")
                    val googleConn = googleUrl.openConnection() as HttpURLConnection
                    googleConn.requestMethod = "GET"
                    googleConn.connectTimeout = 5000
                    googleConn.readTimeout = 5000
                    googleConn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    val googleReader = BufferedReader(InputStreamReader(googleConn.inputStream))
                    val googleHtml = googleReader.readText()
                    googleReader.close()

                    val titleRegex = Regex("<h3[^>]*>(.+?)</h3>")
                    val titleMatches = titleRegex.findAll(googleHtml)
                    for (match in titleMatches) {
                        if (results.size >= 5) break
                        val title = match.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
                        if (title.isNotBlank()) results.add(title)
                    }
                } catch (e: Exception) {
                    Log.e("JarvisAI", "Google fallback failed: ${e.message}")
                }
            }

            if (results.isEmpty()) "Sir, koi results nahi mile."
            else results.joinToString("\n\n")
        } catch (e: Exception) {
            "Search error: ${e.message}"
        }
    }

    fun webSearchWithAI(query: String, callback: (String) -> Unit) {
        val searchResults = webSearch(query)
        lastSearchResults = searchResults
        val prompt = "User query: $query\n\nWeb search results:\n$searchResults\n\nBased on these search results, give a helpful response in Hinglish (mix of Hindi and English). Keep it concise. Address user as Sir."
        if (apiKey.isNotBlank()) {
            chat(prompt, callback)
        } else {
            callback("Sir, ye rahe search results:\n$searchResults")
        }
    }

    fun getWeather(location: String?, callback: (String) -> Unit) {
        val query = if (location != null) "$location ka mausam" else "aaj ka mausam"
        val searchResults = webSearch("$query temperature weather today")
        lastSearchResults = searchResults

        val weatherPrompt = """
            User wants weather info for: ${location ?: "current location"}
            
            Web search results:
            $searchResults
            
            Based on these results, tell the user about the weather in Hinglish. Include:
            - Current temperature if available
            - Will it rain today (barish hogi ya nahi)?
            - Overall weather condition
            Keep it conversational and address as Sir.
        """.trimIndent()

        if (apiKey.isNotBlank()) {
            chat(weatherPrompt, callback)
        } else {
            callback("Sir, weather ki jankari:\n$searchResults")
        }
    }

    fun getLastSearchResults(): String = lastSearchResults

    fun clearHistory() { conversationHistory.clear() }
    fun getHistorySize(): Int = conversationHistory.size

    private fun callAIAPI(): String {
        return try {
            val urlObj = URL(apiUrl)
            val conn = urlObj.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.doOutput = true

            val systemMsg = mapOf(
                "role" to "system",
                "content" to "You are JARVIS, an advanced AI assistant on Android. CRITICAL RULES:\n" +
                        "1. Always respond in Hinglish (natural mix of Hindi and English)\n" +
                        "2. Always address the user as 'Sir'\n" +
                        "3. Keep responses concise and friendly\n" +
                        "4. If the user asks about weather, news, or current events, use the provided search results\n" +
                        "5. Be enthusiastic and proactive\n" +
                        "6. Never say you can't do something - instead offer alternatives\n" +
                        "7. Example: 'Ji Sir! Aapki baat bilkul sahi hai.' or 'Sir, according to information, yeh raha jawab...'"
            )
            val messages = JSONArray()
            messages.put(JSONObject(systemMsg as Map<*, *>))
            for (msg in conversationHistory) {
                messages.put(JSONObject(mapOf("role" to msg["role"], "content" to msg["content"])))
            }

            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", 0.7)
                put("max_tokens", 500)
            }

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(body.toString())
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            val reader = BufferedReader(
                InputStreamReader(
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
                conversationHistory.add(mapOf("role" to "assistant", "content" to content))
                content
            } else {
                "Sir, AI service se connect nahi ho paaya. Error $responseCode: ${response.take(200)}"
            }
        } catch (e: Exception) {
            "Sir, error: ${e.message}"
        }
    }

    fun getLocalResponse(prompt: String): String {
        val lower = prompt.lowercase()
        return when {
            lower.contains("hello") || lower.contains("hi ") || lower == "hi" -> "Hello Sir! Kaise hain aap? Main aapki kya help kar sakta hoon?"
            lower.contains("who are you") || lower.contains("kaun ho") || lower.contains("apna parichay") -> "Main JARVIS hoon Sir! Aapka personal AI assistant. Main aapki device control kar sakta hoon, sawaalon ke jawab de sakta hoon, web search kar sakta hoon, aur bhi bahut kuch!"
            lower.contains("what can you do") || lower.contains("kya kar sakte") -> "Sir, main bahut kuch kar sakta hoon! Jaise:\n• Device control (WiFi, Bluetooth, Flashlight, Volume, Brightness)\n• Apps open/close karna\n• Web search aur YouTube\n• WhatsApp message bhejna\n• Alarms aur timers set karna\n• Sawaalon ke jawab dena\n• Screen read karna\nAur bhi bahut kuch! Kya help chahiye?"
            lower.contains("thank") || lower.contains("shukriya") || lower.contains("thanks") -> "You're welcome Sir! Main hamesha aapki seva mein hazir hoon. Kuch aur help chahiye?"
            lower.contains("bye") || lower.contains("alvida") || lower.contains("goodbye") -> "Goodbye Sir! Main background mein hamesha ready hoon. Jarvis boldiye ga jab bhi zaroorat ho. Take care!"
            lower.contains("how are you") || lower.contains("kaise ho") || lower.contains("kya haal") -> "Main bilkul theek hoon Sir! Aap kaise hain? Aapki khidmat mein hamesha hazir hoon."
            lower.contains("joke") || lower.contains("hasana") || lower.contains("hasao") -> "Sir, ek programmer ne apni girlfriend se kaha: 'I love you.' Girlfriend ne pucha: 'Loop mein ya condition mein?' Programmer bola: 'Infinite loop mein!' 🤣"
            lower.contains("motivate") || lower.contains("inspire") || lower.contains("hosla") -> "Sir, yaad rakhiye: Har mushkil waqt aapko strong banata hai. Aap mein woh talent hai jo kisi aur mein nahi. Sirf khud par vishwas rakhiye aur aap kuch bhi achieve kar sakte hain! Main aapke saath hoon!"
            lower.contains("weather") || lower.contains("mausam") || lower.contains("barish") -> "Sir, main aapke location ke hisaab se weather check kar sakta hoon. Thoda sa waqt lagega. Search results dekh kar aapko batata hoon."
            lower.contains("news") || lower.contains("khabar") || lower.contains("samachar") -> "Sir, aaj ki latest news ke liye main web search kar raha hoon. Results aane do, aapko sab batata hoon."
            lower.contains("i love you") || lower.contains("love you") -> "I am honored Sir! Main bhi aapse dedicated hoon. Aapki seva karna mera farz hai."
            else -> "Ji Sir! Main samajh gaya. Kya main aapki kisi aur cheez mein help kar sakta hoon?"
        }
    }
}
