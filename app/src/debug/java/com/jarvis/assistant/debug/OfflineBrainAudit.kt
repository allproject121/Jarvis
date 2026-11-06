package com.jarvis.assistant.debug

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ai.compat.ChatTemplateRenderer
import com.jarvis.assistant.ai.compat.ModelFamily
import com.jarvis.assistant.ai.local.OfflineToolRouter
import com.jarvis.assistant.ai.local.ToolCallParser
import com.jarvis.assistant.ai.provider.LocalPhoneProvider
import com.jarvis.assistant.automation.dispatcher.JarvisToolDispatcher
import com.jarvis.assistant.data.model.ChatTurn
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DEBUG BUILD ONLY. Offline-brain quality audit: the same questions through four prompt setups,
 * measured with llama.cpp's own timings. Tools are only PARSED, never executed (no side effects).
 *
 *   adb shell am broadcast -a com.jarvis.assistant.debug.OFFLINE_BRAIN -p com.jarvis.assistant --ez audit true
 *
 * One logcat line per run, tag OfflineBrainAudit, each a JSON object.
 */
object OfflineBrainAudit {
    private const val TAG = "OfflineBrainAudit"

    private data class Q(val id: String, val text: String, val expectTool: String?, val expectWords: List<String>)

    private val QUESTIONS = listOf(
        Q("Q1-identity", "tum kaun ho?", null, listOf("jarvis")),
        Q("Q2-knowledge", "India ki capital kya hai aur wahan ki ek famous jagah batao", null, listOf("delhi")),
        Q("Q3-math", "12 aur 15 ka guna kitna hota hai?", null, listOf("180")),
        Q("Q4-hindi", "मुझे बारिश पर दो लाइन की छोटी कविता सुनाओ", null, emptyList()),
        Q("Q5-battery", "mere phone ki battery kitni hai?", "get_battery_status", emptyList()),
        Q("Q6-alarm", "kal subah 6 baje ka alarm laga do", "set_alarm", emptyList()),
        Q("Q7-torch", "torch on karo", "set_flashlight", emptyList()),
        Q("Q8-memory", "mera naam kya hai, main kahan rehta hoon aur kal kahan ja raha hoon?", null, listOf("roshan", "pune", "goa"))
    )

    private const val CHAT_SYSTEM = "You are JARVIS, an ultra-capable AI assistant. Answer clearly and helpfully."
    private const val MEMORY = "User ka naam Roshan hai. User Pune me rehta hai."
    private val HISTORY = listOf(ChatTurn(userTranscript = "main kal Goa ja raha hoon", jarvisResponse = "Wah, Goa trip ke liye shubhkamnayein!"))

    private fun minimalChatMl(q: String) =
        "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n<|im_start|>user\n$q<|im_end|>\n<|im_start|>assistant\n"

    private fun memInfo(): String {
        val rss = runCatching {
            File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.filter { it.isDigit() }.toLong() / 1024
        }.getOrDefault(-1)
        val avail = runCatching {
            File("/proc/meminfo").readLines().first { it.startsWith("MemAvailable") }.filter { it.isDigit() }.toLong() / 1024
        }.getOrDefault(-1)
        return "$rss/$avail"
    }

    private fun thermal(): String = runCatching {
        File("/sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq").readText().trim()
    }.getOrDefault("?")

    suspend fun run(context: Context, scope: CoroutineScope) {
        val provider = LocalPhoneProvider.getInstance(context, scope)
        val dispatcher = JarvisToolDispatcher.getInstance(context)
        val family = ModelFamily.QWEN2P5
        val core = dispatcher.getOfflineToolSchemas()
        val all = dispatcher.getAvailableToolSchemas(includeDisabled = false)
        val coreNames = core.map { it.optJSONObject("function")?.optString("name").orEmpty() }.toSet()
        Log.i(TAG, "AUDIT start coreTools=${core.size} allTools=${all.size} core=$coreNames")

        // ---- template check: GGUF's own template vs Jarvis' ----
        val msgs = JSONObject().put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", "SYS"))
            .put(JSONObject().put("role", "user").put("content", "USER")))
            .put("add_generation_prompt", true)
        val (embedded, rendered) = provider.diagnosticTemplate(msgs.toString())
        Log.i(TAG, "AUDIT template embedded(first 400)=" + JSONObject.quote(embedded.take(400)))
        Log.i(TAG, "AUDIT template llama.cpp-rendered=" + JSONObject.quote(rendered.take(400)))
        val ours = ChatTemplateRenderer.render(family, "USER", "SYS", "", emptyList(), emptyList())
        Log.i(TAG, "AUDIT template jarvis-rendered(first 400)=" + JSONObject.quote(ours.take(400)))

        val configs = listOf("A", "B", "C", "D")
        for (cfg in configs) {
            for (q in QUESTIONS) {
                val extra = if (cfg == "C" || cfg == "D") OfflineToolRouter.select(q.text, all, max = 6, exclude = coreNames) else emptyList()
                val prompt = when (cfg) {
                    "A" -> minimalChatMl(q.text)
                    "B" -> ChatTemplateRenderer.render(family, q.text, CHAT_SYSTEM, "", emptyList(), core)
                    "C" -> ChatTemplateRenderer.render(family, q.text, CHAT_SYSTEM, "", emptyList(), core, extra)
                    else -> ChatTemplateRenderer.render(family, q.text, CHAT_SYSTEM, MEMORY, HISTORY, core, extra)
                }
                val memBefore = memInfo()
                val r = runCatching { provider.diagnosticComplete(prompt, nPredict = 200) }.getOrNull()
                if (r == null) { Log.w(TAG, "AUDIT $cfg ${q.id} failed"); continue }
                val raw = r.text
                val visible = ToolCallParser.streamVisibleText(raw, final = true)
                val tool = ToolCallParser.parseToolCall(raw)
                val lower = visible.lowercase()
                val wordsOk = q.expectWords.all { lower.contains(it) }
                val toolOk = q.expectTool?.let { tool?.functionName == it }
                val ttftMs = if (r.predictedN > 0) r.promptMs + r.predictedMs / r.predictedN else r.promptMs
                val line = JSONObject()
                    .put("cfg", cfg).put("q", q.id)
                    .put("promptTok", r.promptTokens).put("cacheN", r.cacheN).put("promptN", r.promptN)
                    .put("promptMs", r.promptMs.toLong()).put("promptTps", "%.1f".format(r.promptPerSecond))
                    .put("genN", r.predictedN).put("genTps", "%.1f".format(r.predictedPerSecond))
                    .put("ttftMs", ttftMs.toLong()).put("wallMs", r.wallMs)
                    .put("mem_rss_avail_MB", "$memBefore -> ${memInfo()}").put("cpu7max", thermal())
                    .put("stop", r.stopReason)
                    .put("think", raw.contains("<think>")).put("leak", ToolCallParser.findTurnLeak(raw) >= 0)
                    .put("tool", tool?.let { "${it.functionName} ${it.arguments}" } ?: JSONObject.NULL)
                    .put("toolOk", toolOk ?: JSONObject.NULL).put("wordsOk", wordsOk)
                    .put("answerDiscarded", raw.isNotBlank() && visible.isBlank() && tool == null)
                    .put("extra", extra.joinToString(",") { it.optJSONObject("function")?.optString("name").orEmpty() })
                    .put("visible", visible.take(240))
                    .put("raw", raw.take(300))
                Log.i(TAG, "AUDIT " + line.toString())
            }
        }
        Log.i(TAG, "AUDIT done")
    }
}
