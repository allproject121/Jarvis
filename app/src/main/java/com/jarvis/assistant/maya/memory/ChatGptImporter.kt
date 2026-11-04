package com.jarvis.assistant.maya.memory

import android.content.Context
import android.net.Uri
import android.util.Log
import com.jarvis.assistant.ai.AiTextSynthesizer
import com.jarvis.assistant.ai.learning.HabitPatternProfiler
import com.jarvis.assistant.data.memory.MemoryManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Imports a user's own ChatGPT data export and turns it into durable Jarvis memory, so the
 * assistant starts out already knowing the person's habits, preferences and standing rules.
 *
 * This reads the user's OWN exported file only (ChatGPT → Settings → Data controls → Export data,
 * which emails a zip containing `conversations.json`). It never touches OpenAI's servers.
 *
 * Pipeline: parse conversations.json → collect the user's OWN turns (not the assistant's) →
 * chunk them → ask the configured Jarvis LLM to distill a compact JSON of facts / preferences /
 * routines / rules / app-habits → write facts via [MemoryManager.rememberFact], standing rules via
 * [RulesStore], and seed [HabitPatternProfiler]. Nothing is invented: the model is told to extract
 * only what the user actually stated.
 */
object ChatGptImporter {

    private const val TAG = "ChatGptImporter"
    private const val MAX_USER_TURNS = 4000
    private const val MAX_CHARS_PER_CHUNK = 12000
    private const val MAX_CHUNKS = 12

    data class Result(
        val ok: Boolean,
        val message: String,
        val conversations: Int = 0,
        val userTurns: Int = 0,
        val facts: Int = 0,
        val rules: Int = 0,
        val habits: Int = 0,
        val error: String? = null
    )

    /** Reads the export from a file path or a content:// uri. */
    fun readText(context: Context, pathOrUri: String): String? = try {
        if (pathOrUri.startsWith("content:")) {
            context.contentResolver.openInputStream(Uri.parse(pathOrUri))?.bufferedReader()?.use { it.readText() }
        } else {
            val f = java.io.File(pathOrUri)
            if (f.isDirectory) java.io.File(f, "conversations.json").takeIf { it.exists() }?.readText()
            else if (f.exists()) f.readText() else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "read failed: ${e.message}"); null
    }

    /**
     * Pulls every user-authored turn out of a ChatGPT `conversations.json`. Supports the standard
     * export shape (array of conversations, each with a `mapping` of message nodes) and the simpler
     * `{title, messages:[{role, content}]}` shape some tools produce.
     */
    fun extractUserTurns(json: String): Pair<Int, List<String>> {
        val turns = ArrayList<String>()
        var conversations = 0
        val root: Any = try { JSONArray(json) } catch (_: Exception) {
            try { JSONObject(json) } catch (_: Exception) { return 0 to emptyList() }
        }
        val convos: List<JSONObject> = when (root) {
            is JSONArray -> (0 until root.length()).mapNotNull { root.optJSONObject(it) }
            is JSONObject -> root.optJSONArray("conversations")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            } ?: listOf(root)
            else -> emptyList()
        }
        for (c in convos) {
            conversations++
            val mapping = c.optJSONObject("mapping")
            if (mapping != null) {
                val keys = mapping.keys()
                while (keys.hasNext()) {
                    val node = mapping.optJSONObject(keys.next()) ?: continue
                    val msg = node.optJSONObject("message") ?: continue
                    if (msg.optJSONObject("author")?.optString("role") != "user") continue
                    collectText(msg.optJSONObject("content"))?.let { if (it.isNotBlank()) turns.add(it.trim()) }
                }
            } else {
                val msgs = c.optJSONArray("messages") ?: continue
                for (i in 0 until msgs.length()) {
                    val m = msgs.optJSONObject(i) ?: continue
                    if (m.optString("role") != "user") continue
                    val t = m.opt("content")
                    val text = if (t is String) t else collectText(m.optJSONObject("content"))
                    if (!text.isNullOrBlank()) turns.add(text.trim())
                }
            }
            if (turns.size >= MAX_USER_TURNS) break
        }
        return conversations to turns
    }

    private fun collectText(content: JSONObject?): String? {
        content ?: return null
        content.optString("text", "").takeIf { it.isNotBlank() }?.let { return it }
        val parts = content.optJSONArray("parts") ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            when (val p = parts.opt(i)) {
                is String -> if (p.isNotBlank()) sb.append(p).append('\n')
                is JSONObject -> p.optString("text").takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
            }
        }
        return sb.toString().ifBlank { null }
    }

    private fun chunk(turns: List<String>): List<String> {
        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        for (t in turns) {
            val line = t.replace("\n", " ").take(600)
            if (sb.length + line.length > MAX_CHARS_PER_CHUNK) {
                if (sb.isNotEmpty()) chunks.add(sb.toString())
                sb.setLength(0)
                if (chunks.size >= MAX_CHUNKS) break
            }
            sb.append("- ").append(line).append('\n')
        }
        if (sb.isNotEmpty() && chunks.size < MAX_CHUNKS) chunks.add(sb.toString())
        return chunks
    }

    private const val SYSTEM = """You distill a person's durable profile from messages THEY wrote to an AI assistant.
Extract ONLY what the person actually stated about themselves — never invent, never include the assistant's words, never include one-off task text.
Return STRICT JSON only, no prose, shaped exactly:
{"facts":["short durable fact about the user (name, people, work, location, health, beliefs)"],
 "preferences":["a like/dislike or working style"],
 "routines":["a recurring routine or schedule"],
 "rules":["a standing instruction the user clearly wants the assistant to always follow"],
 "apps":["app or service the user uses often, lowercase short name"]}
Keep each item one short line. Omit anything uncertain. Empty arrays are fine."""

    suspend fun distill(context: Context, chunkText: String): JSONObject? {
        val out = AiTextSynthesizer.synthesizeText(
            context = context,
            systemInstruction = SYSTEM,
            prompt = "Messages the user wrote:\n$chunkText\n\nReturn the JSON profile.",
            temperature = 0.2,
            maxTokens = 900,
            fallback = { "" }
        )
        val s = out.substringAfter('{', "").let { if (it.isEmpty()) "" else "{$it" }.substringBeforeLast('}', "").let { if (it.isEmpty()) "" else "$it}" }
        return try { if (s.isBlank()) null else JSONObject(s) } catch (_: Exception) { null }
    }

    /** Full import. Safe to call off the main thread; it does network + disk work. */
    suspend fun import(context: Context, pathOrUri: String): Result {
        if (!MemoryPrefs.canStore(context)) {
            return Result(false, "Incognito is on, so nothing was imported. Turn it off in Memory settings first.")
        }
        val raw = readText(context, pathOrUri)
            ?: return Result(false, "Couldn't read the export. Point me at your ChatGPT conversations.json file.", error = "read_failed")
        val (convos, turns) = extractUserTurns(raw)
        if (turns.isEmpty()) {
            return Result(false, "No messages found. Export your data from ChatGPT (Settings - Data controls - Export) and give me the conversations.json.", conversations = convos, error = "empty")
        }
        val mm = MemoryManager.getInstance(context)
        val rules = RulesStore.get(context)
        val habits = HabitPatternProfiler.getInstance(context)
        val seenFacts = HashSet<String>()
        val seenRules = HashSet<String>()
        var factCount = 0; var ruleCount = 0; var habitCount = 0

        for (chunk in chunk(turns)) {
            val profile = distill(context, chunk) ?: continue
            for (key in listOf("facts", "preferences", "routines")) {
                val arr = profile.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val fact = arr.optString(i).trim()
                    if (fact.length < 4) continue
                    if (!seenFacts.add(fact.lowercase())) continue
                    try { mm.rememberFact(fact); factCount++ } catch (e: Exception) { Log.w(TAG, "fact: ${e.message}") }
                }
            }
            profile.optJSONArray("rules")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val r = arr.optString(i).trim()
                    if (r.length < 4) continue
                    if (!seenRules.add(r.lowercase())) continue
                    try { rules.add(r); ruleCount++ } catch (e: Exception) { Log.w(TAG, "rule: ${e.message}") }
                }
            }
            profile.optJSONArray("apps")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val app = arr.optString(i).trim().lowercase()
                    if (app.length in 2..24) { try { habits.recordAction("chatgpt_app_$app"); habitCount++ } catch (_: Exception) {} }
                }
            }
        }
        val msg = buildString {
            append("ChatGPT se seekh liya: ")
            append("$factCount baatein yaad kiin")
            if (ruleCount > 0) append(", $ruleCount rule")
            if (habitCount > 0) append(", $habitCount app-habit")
            append(". (")
            append("$convos chats, ${turns.size} aapke messages padhe)")
        }
        if (factCount > 0 || ruleCount > 0) {
            runCatching { NoticesStore.post(context, "ChatGPT import", msg, "info") }
        }
        return Result(
            ok = factCount > 0 || ruleCount > 0,
            message = if (factCount == 0 && ruleCount == 0) "Padha toh sahi, par koi pakki baat nahi mili yaad rakhne layak." else msg,
            conversations = convos, userTurns = turns.size, facts = factCount, rules = ruleCount, habits = habitCount
        )
    }
}
