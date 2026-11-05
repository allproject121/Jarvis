package com.jarvis.assistant.androllm.diagnostics

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.AiTextSynthesizer
import com.jarvis.assistant.network.GeminiLiveWebSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Real end-to-end checks of the user's Gemini setup — not "key is set = connected":
 *
 * - [runText]: the exact models Jarvis' text chat tries (MultiModelAiRouter → saved model,
 *   then AiTextSynthesizer's fallbacks), each with HTTP status, Google's error text, latency
 *   and reply; plus the full chat call (AiTextSynthesizer.synthesizeText) as chat runs it.
 * - [runLive]: opens a real Gemini Live WebSocket session with the saved key / model / voice
 *   (the same public GeminiLiveWebSocket class the voice service uses, unmodified), sends one
 *   text turn and reports whether audio / text came back.
 *
 * Used by Developer & Diagnostics → "Gemini check" and the debug-only GEMINI_TEST broadcast.
 * The API key is never logged (only its last 4 characters).
 */
object GeminiSelfTest {

    private const val TAG = "GeminiSelfTest"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private const val SENTINEL = "\u0000gemini-self-test-no-reply\u0000"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Step(
        val label: String,
        val model: String,
        val httpCode: Int?,
        val ms: Long,
        val reply: String?,
        val error: String?
    ) {
        val ok: Boolean get() = !reply.isNullOrBlank()
        fun line(): String = "$label [$model] " +
            (httpCode?.let { "HTTP $it" } ?: "no HTTP") + " ${ms}ms " +
            if (ok) "reply='${reply!!.take(160)}'" else "error='${error ?: "empty reply"}'"
    }

    data class TextResult(
        val keyConfigured: Boolean,
        val keyHint: String,
        val provider: String,
        val savedModel: String,
        val modelsVisible: Int?,
        val steps: List<Step>,
        val chatReply: String?,
        val chatMs: Long
    ) {
        val workingModel: String? get() = steps.firstOrNull { it.ok }?.model
        val ok: Boolean get() = chatReply != null
        fun summary(): String = when {
            !keyConfigured -> "No Gemini API key saved."
            workingModel != null && steps.firstOrNull()?.ok == true -> "Gemini works with your model $workingModel."
            workingModel != null -> "Your saved model failed; chat works through fallback model $workingModel."
            chatReply != null -> "Gemini models failed; chat answered through another configured provider."
            else -> "Gemini text does NOT work: " + (steps.lastOrNull()?.error ?: "no reply")
        }
    }

    data class LiveResult(
        val model: String,
        val voice: String,
        val connected: Boolean,
        val setupMs: Long?,
        val firstResponseMs: Long?,
        val audioBytes: Int,
        val text: String,
        val toolCalls: List<String>,
        val turnCompleted: Boolean,
        val error: String?,
        val states: List<String>
    ) {
        val ok: Boolean get() = connected && (audioBytes > 0 || text.isNotBlank())
        fun summary(): String = when {
            ok -> "Gemini Live works: ${audioBytes / 1024} KB audio" +
                (if (text.isNotBlank()) ", said \"${text.take(80)}\"" else "") + " (first reply ${firstResponseMs ?: "-"} ms)."
            connected -> "Gemini Live connected but sent nothing back" + (error?.let { ": $it" } ?: ".")
            else -> "Gemini Live did NOT connect: ${error ?: states.lastOrNull() ?: "timeout"}"
        }
    }

    private fun prefs() = JarvisApp.instance.preferences

    private fun keyHint(key: String) = if (key.length >= 4) "…" + key.takeLast(4) else "(short)"

    // ── Text ────────────────────────────────────────────────────────────────

    suspend fun runText(context: Context, prompt: String): TextResult = withContext(Dispatchers.IO) {
        val p = prefs()
        val key = p.apiKey.trim()
        val provider = runCatching { p.selectedAiProvider }.getOrDefault("gemini")
        val saved = p.aiModel.replace("models/", "")
        if (key.isBlank()) {
            return@withContext TextResult(false, "", provider, saved, null, emptyList(), null, 0)
        }
        val visible = runCatching { listModels(key) }.getOrNull()
        // Same order chat uses: the saved model (only when it is a text model), then the
        // text-capable models this key can really call (GeminiTextModels / ListModels).
        val models = linkedSetOf(saved).apply { addAll(com.jarvis.assistant.ai.GeminiTextModels.candidates(key, saved)) }
        val steps = models.mapIndexed { i, m ->
            generate(key, m, prompt, if (i == 0) "chat model" else "fallback")
        }
        val started = System.currentTimeMillis()
        val chat = runCatching {
            AiTextSynthesizer.synthesizeText(
                context = context,
                systemInstruction = "You are JARVIS. Answer in one short sentence.",
                prompt = prompt
            ) { SENTINEL }
        }.getOrNull()
        val chatMs = System.currentTimeMillis() - started
        TextResult(
            keyConfigured = true,
            keyHint = keyHint(key),
            provider = provider,
            savedModel = saved,
            modelsVisible = visible,
            steps = steps,
            chatReply = chat?.takeIf { it != SENTINEL && it.isNotBlank() },
            chatMs = chatMs
        )
    }

    private fun listModels(key: String): Int {
        val req = Request.Builder().url("$BASE/models?pageSize=200&key=$key").get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return -resp.code
            return JSONObject(resp.body?.string().orEmpty()).optJSONArray("models")?.length() ?: 0
        }
    }

    private fun generate(key: String, model: String, prompt: String, label: String): Step {
        val started = System.currentTimeMillis()
        return try {
            val body = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                ))
                put("generationConfig", JSONObject().put("maxOutputTokens", 200))
            }
            val req = Request.Builder()
                .url("$BASE/models/$model:generateContent?key=$key")
                .post(body.toString().toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val ms = System.currentTimeMillis() - started
                if (!resp.isSuccessful) {
                    val err = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                    Step(label, model, resp.code, ms, null, (err ?: text).take(300).ifBlank { "HTTP ${resp.code}" })
                } else {
                    val reply = runCatching {
                        JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                    }.getOrNull()
                    Step(label, model, resp.code, ms, reply?.trim(), if (reply.isNullOrBlank()) "200 but no text (blocked or empty)" else null)
                }
            }
        } catch (e: Exception) {
            Step(label, model, null, System.currentTimeMillis() - started, null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── Live ────────────────────────────────────────────────────────────────

    suspend fun runLive(context: Context, prompt: String, timeoutMs: Long = 30_000L): LiveResult {
        val p = prefs()
        val key = p.apiKey.trim()
        val model = p.aiModel
        val voice = p.voice
        if (key.isBlank()) {
            return LiveResult(model, voice, false, null, null, 0, "", emptyList(), false, "No Gemini API key saved.", emptyList())
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val states = mutableListOf<String>()
        val text = StringBuilder()
        val tools = mutableListOf<String>()
        var audio = 0
        var error: String? = null
        var firstAt: Long? = null
        val turnDone = CompletableDeferred<Unit>()
        val fatal = CompletableDeferred<String>()
        lateinit var socket: GeminiLiveWebSocket
        val listener = object : GeminiLiveWebSocket.Listener {
            override fun onConnectionStateChanged(status: String) {
                synchronized(states) { states += status }
            }
            override fun onAudioDataReceived(pcmData: ByteArray) {
                if (firstAt == null) firstAt = System.currentTimeMillis()
                audio += pcmData.size
            }
            override fun onAssistantTextReceived(textChunk: String) {
                if (firstAt == null) firstAt = System.currentTimeMillis()
                synchronized(text) { text.append(textChunk) }
            }
            override fun onUserTextReceived(textChunk: String) = Unit
            override fun onInterrupted() = Unit
            override fun onTurnCompleted() { turnDone.complete(Unit) }
            override fun onError(message: String, isFatal: Boolean, errorCode: String?) {
                error = (errorCode?.let { "$it: " } ?: "") + message
                if (isFatal) fatal.complete(error!!)
            }
            override fun onToolCall(callId: String, name: String, args: JSONObject) {
                synchronized(tools) { tools += name }
                // Test session: answer so the turn can finish, never run the tool.
                runCatching {
                    socket.sendToolResponse(callId, name, JSONObject().put("success", false)
                        .put("message", "Self-test session: tools are not executed. Just answer in words."))
                }
            }
        }
        socket = GeminiLiveWebSocket(
            apiKey = key,
            model = model,
            voiceName = voice,
            systemPrompt = "You are JARVIS. This is a connection self-test. Reply in one short spoken sentence and do not call tools.",
            listener = listener
        )
        val started = System.currentTimeMillis()
        var setupMs: Long? = null
        try {
            socket.connect(scope)
            val ready = withTimeoutOrNull(15_000L) {
                while (!socket.isSessionReady()) {
                    if (fatal.isCompleted) return@withTimeoutOrNull false
                    delay(100)
                }
                true
            } == true
            if (ready) {
                setupMs = System.currentTimeMillis() - started
                val sentAt = System.currentTimeMillis()
                socket.sendClientContent(prompt)
                withTimeoutOrNull(timeoutMs) {
                    while (!turnDone.isCompleted && !fatal.isCompleted) delay(100)
                }
                firstAt = firstAt?.let { it - sentAt }
            } else if (error == null) {
                error = "Session not ready after 15 s (states: ${synchronized(states) { states.joinToString(" > ") }})"
            }
            return LiveResult(
                model = model,
                voice = voice,
                connected = ready,
                setupMs = setupMs,
                firstResponseMs = if (ready) firstAt else null,
                audioBytes = audio,
                text = synchronized(text) { text.toString() },
                toolCalls = synchronized(tools) { tools.toList() },
                turnCompleted = turnDone.isCompleted,
                error = error,
                states = synchronized(states) { states.toList() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Live self-test failed", e)
            return LiveResult(model, voice, false, setupMs, null, audio, text.toString(), tools.toList(), false,
                "${e.javaClass.simpleName}: ${e.message}", synchronized(states) { states.toList() })
        } finally {
            runCatching { socket.disconnect() }
            scope.cancel()
        }
    }
}
