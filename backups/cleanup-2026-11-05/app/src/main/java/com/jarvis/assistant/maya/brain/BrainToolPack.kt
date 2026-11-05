package com.jarvis.assistant.maya.brain

import android.content.Context
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry.ToolOutcome
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry.prop
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry.props
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

/**
 * Advanced-Agentic brain tools (family = "brain"): switch/test the brain, pick transcriber and
 * voice, route work to sub-agents (incl. vision "eyes"), translate, and transcribe audio files.
 */
object BrainToolPack {

    private const val FAMILY = "brain"

    fun install(context: Context) {
        MayaToolRegistry.register(
            name = "set_brain",
            description = "Switch the Advanced Agentic brain (the text LLM used in Advanced Agentic voice mode). " +
                "provider: ${BrainProviders.ids().joinToString("|")}. Optional model and base_url (base_url only for custom). " +
                "Optional api_key saves a key for that provider.",
            properties = props(
                "provider" to prop("string", "Provider id", BrainProviders.ids()),
                "model" to prop("string", "Model id, e.g. llama-3.3-70b-versatile. Omit for the provider default."),
                "base_url" to prop("string", "OpenAI-compatible base URL ending in /v1 (custom provider)"),
                "api_key" to prop("string", "Optional API key to store securely for this provider"),
                "autopick_free" to prop("boolean", "OpenRouter only: auto-pick free tool-capable models")
            ),
            required = listOf("provider"),
            family = FAMILY
        ) { ctx, args ->
            val s = BrainSettings.get(ctx)
            val p = BrainProviders.resolveAlias(args.optString("provider"))
                ?: return@register ToolOutcome.fail("Unknown provider '${args.optString("provider")}'. Use one of ${BrainProviders.ids()}")
            s.brainProviderId = p.id
            s.brainModel = args.optString("model", "").trim()
            s.brainBaseUrl = if (p.customBaseUrl) args.optString("base_url", "").trim() else ""
            args.optString("api_key", "").trim().takeIf { it.isNotBlank() }?.let { s.setProviderKey(p.id, it) }
            if (args.has("autopick_free")) s.autoPickFreeModels = args.optBoolean("autopick_free")
            val cfg = s.mainBrain()
            val msg = buildString {
                append("Brain set to ${p.label} / ${cfg.model.ifBlank { "(no model)" }}.")
                cfg.missingReason()?.let { append(" ").append(it) }
                if (cfg.autoPick) append(" AutoPick free models is on. ").append(OpenRouterAutoPick.FREE_MODELS_EXPLANATION)
            }
            ToolOutcome.ok(msg, JSONObject().put("provider", p.id).put("model", cfg.model).put("configured", cfg.isConfigured))
        }

        MayaToolRegistry.register(
            name = "test_brain",
            description = "Send one tiny request to the Advanced Agentic brain (or a sub-agent role) and report latency and the model that answered.",
            properties = props("role" to prop("string", "Optional sub-agent role to test instead of the main brain", SubAgentRole.entries.map { it.id })),
            family = FAMILY
        ) { ctx, args ->
            val role = SubAgentRole.byId(args.optString("role", ""))
            val provider = if (role == null) AgenticCloudProvider.main(ctx) else AgenticCloudProvider.forRole(ctx, role)
            val r = provider.test()
            val extra = JSONObject().put("latency_ms", r.latencyMs).put("model", r.model).put("provider", provider.config.provider.id)
            if (r.ok) ToolOutcome.ok("Brain OK: ${r.model} answered in ${r.latencyMs} ms.", extra)
            else ToolOutcome.fail(r.message, extra)
        }

        MayaToolRegistry.register(
            name = "set_transcriber",
            description = "Choose how Advanced Agentic mode hears the user: android (Google, free), deepgram (needs key), sherpa (on-device). " +
                "Optional backup transcriber and Deepgram key.",
            properties = props(
                "engine" to prop("string", "Primary transcriber", BrainSettings.TRANSCRIBERS),
                "backup" to prop("string", "Optional backup transcriber ('' for none)"),
                "deepgram_key" to prop("string", "Optional Deepgram API key to store securely")
            ),
            required = listOf("engine"),
            family = FAMILY
        ) { ctx, args ->
            val s = BrainSettings.get(ctx)
            val engine = args.optString("engine").trim().lowercase()
            if (engine !in BrainSettings.TRANSCRIBERS) return@register ToolOutcome.fail("Unknown transcriber '$engine'. Use ${BrainSettings.TRANSCRIBERS}")
            args.optString("deepgram_key", "").trim().takeIf { it.isNotBlank() }?.let { s.deepgramKey = it }
            if (engine == BrainSettings.TRANSCRIBER_DEEPGRAM && s.deepgramKey.isBlank()) {
                return@register ToolOutcome.fail(DeepgramStreamingRecognizer.ERR_NO_KEY)
            }
            s.transcriber = engine
            if (args.has("backup")) s.backupTranscriber = args.optString("backup", "").trim().lowercase()
            ToolOutcome.ok("Transcriber set to ${BrainSettings.transcriberLabel(engine)}" +
                (s.backupTranscriber.takeIf { it.isNotBlank() }?.let { ", backup ${BrainSettings.transcriberLabel(it)}" } ?: "") + ".")
        }

        MayaToolRegistry.register(
            name = "set_tts_engine",
            description = "Choose the Advanced Agentic voice: gemini_tts, google_cloud, edge, pocket or android. Optional voice name " +
                "(e.g. Kore for Gemini, en-IN-Neural2-A for Google Cloud, en-IN-NeerjaNeural for Edge). Set speak_test=true to hear it.",
            properties = props(
                "engine" to prop("string", "TTS engine", BrainSettings.TTS_ENGINES),
                "voice" to prop("string", "Optional voice name"),
                "speak_test" to prop("boolean", "Speak a short test line after switching")
            ),
            required = listOf("engine"),
            family = FAMILY
        ) { ctx, args ->
            val s = BrainSettings.get(ctx)
            val engine = args.optString("engine").trim().lowercase()
            if (engine !in BrainSettings.TTS_ENGINES) return@register ToolOutcome.fail("Unknown TTS engine '$engine'. Use ${BrainSettings.TTS_ENGINES}")
            s.ttsEngine = engine
            if (args.has("voice")) s.ttsVoice = args.optString("voice", "")
            var used: String? = engine
            if (args.optBoolean("speak_test", false)) {
                used = MayaTtsRouter.speak(ctx, "Hello, ye meri nayi awaaz hai.")
            }
            ToolOutcome.ok("Voice set to ${BrainSettings.ttsLabel(engine)}${s.ttsVoice.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}." +
                if (used != null && used != engine) " Test line actually played on $used (${MayaTtsRouter.lastError ?: "fallback"})." else "")
        }

        MayaToolRegistry.register(
            name = "generate_with_subagent",
            description = "Hand a task to a sub-agent brain: coding (write code), research (facts/summaries), mission (multi-step plan, may use tools), " +
                "eyes (describe an image; pass image_path). Each role uses its own provider from Settings → Sub-agents, else the main brain.",
            properties = props(
                "role" to prop("string", "Sub-agent role", SubAgentRole.entries.map { it.id }),
                "prompt" to prop("string", "What the sub-agent should do"),
                "context" to prop("string", "Optional extra context (code, notes, previous results)"),
                "image_path" to prop("string", "Eyes role only: absolute path of a jpg/png/webp image")
            ),
            required = listOf("role", "prompt"),
            family = FAMILY
        ) { ctx, args ->
            val role = SubAgentRole.byId(args.optString("role"))
                ?: return@register ToolOutcome.fail("Unknown role '${args.optString("role")}'. Use coding|research|mission|eyes")
            val prompt = args.optString("prompt").trim()
            if (prompt.isBlank()) return@register ToolOutcome.fail("prompt is empty")
            val extraCtx = args.optString("context", "").trim()
            val image = args.optString("image_path", "").trim().takeIf { it.isNotBlank() && role == SubAgentRole.EYES }
            val provider = AgenticCloudProvider.forRole(ctx, role)
            val cfg = provider.config
            if (!cfg.isConfigured) {
                return@register ToolOutcome.fail("Sub-agent brain set nahi hai - Settings > Sub-agents me ek provider daal do.")
            }
            val full = if (extraCtx.isBlank()) prompt else "$prompt\n\n[CONTEXT]\n$extraCtx"
            try {
                val answer = provider.chatOnce(
                    prompt = full,
                    systemPrompt = BrainPrompts.subAgentPrompt(role),
                    imagePath = image,
                    withTools = role == SubAgentRole.MISSION,
                    maxTokens = if (role == SubAgentRole.CODING) 4096 else 2048
                )
                if (answer.isBlank()) return@register ToolOutcome.fail(BrainHttpClient.MSG_NO_ANSWER)
                ToolOutcome.ok(answer, JSONObject().put("role", role.id).put("model", provider.lastModelUsed.ifBlank { cfg.model }).put("provider", cfg.provider.id))
            } catch (e: Exception) {
                val msg = e.message ?: BrainHttpClient.MSG_NO_ANSWER
                if (role == SubAgentRole.EYES && image != null && (msg.contains("image", true) || msg.contains("vision", true) || msg.contains("400"))) {
                    ToolOutcome.fail("Model (${cfg.model}) ne tasveer nahi li — ye model image nahi dekhta, ya iska naam galat hai. Settings → Voice engine me \"Eyes model\" badlo aur wahin Test dabao.")
                } else ToolOutcome.fail(msg)
            }
        }

        MayaToolRegistry.register(
            name = "translate_text",
            description = "Translate text into another language using the brain LLM. Returns only the translation.",
            properties = props(
                "text" to prop("string", "Text to translate"),
                "to_language" to prop("string", "Target language, e.g. Hindi, English, Tamil, Hinglish")
            ),
            required = listOf("text", "to_language"),
            family = FAMILY
        ) { ctx, args ->
            val text = args.optString("text").trim()
            val to = args.optString("to_language").trim()
            if (text.isBlank() || to.isBlank()) return@register ToolOutcome.fail("text and to_language are required")
            val research = AgenticCloudProvider.forRole(ctx, SubAgentRole.RESEARCH)
            val provider = if (research.config.isConfigured) research else AgenticCloudProvider.main(ctx)
            if (!provider.config.isConfigured) return@register ToolOutcome.fail(provider.config.missingReason() ?: "Brain not configured")
            try {
                val out = provider.chatOnce(
                    prompt = text,
                    systemPrompt = "You are a professional translator. Translate the user's text into $to. Keep names, numbers and meaning exact. Output ONLY the translation, nothing else.",
                    maxTokens = 2048
                )
                ToolOutcome.ok(out, JSONObject().put("translation", out).put("to_language", to))
            } catch (e: Exception) {
                ToolOutcome.fail(e.message ?: BrainHttpClient.MSG_NO_ANSWER)
            }
        }

        MayaToolRegistry.register(
            name = "transcribe_audio_file",
            description = "Transcribe a local audio file (mp3/m4a/wav/ogg/webm) to text using the transcription engine " +
                "(OpenAI-compatible /audio/transcriptions: OpenAI whisper-1, Groq whisper-large-v3, or a custom endpoint).",
            properties = props(
                "path" to prop("string", "Absolute path of the audio file"),
                "language" to prop("string", "Optional ISO language hint, e.g. hi or en")
            ),
            required = listOf("path"),
            family = FAMILY
        ) { ctx, args -> transcribeFile(ctx, args.optString("path").trim(), args.optString("language", "").trim()) }

        MayaToolRegistry.register(
            name = "voice_engine_status",
            description = "Describe the current Voice engine setup: mode (Gemini Live / Advanced Agentic), brain, transcriber, voice, and whether the agentic session is running.",
            family = FAMILY
        ) { ctx, _ ->
            ToolOutcome.ok(AdvancedAgenticMode.describe(ctx), JSONObject().put("advanced_agentic", AdvancedAgenticMode.isEnabled(ctx)))
        }
    }

    /** Resolves the transcription endpoint: explicit settings → OpenAI key → Groq key → none. */
    internal data class TranscriptionTarget(val url: String, val key: String, val model: String)

    internal fun transcriptionTarget(s: BrainSettings): TranscriptionTarget? {
        if (s.transcriptionBaseUrl.isNotBlank()) {
            return TranscriptionTarget(
                "${s.transcriptionBaseUrl.trimEnd('/')}/audio/transcriptions",
                s.transcriptionKey,
                s.transcriptionModel.ifBlank { "whisper-1" }
            )
        }
        val openAiKey = s.transcriptionKey.ifBlank { s.providerKey(BrainProviders.OPENAI) }
        if (openAiKey.isNotBlank()) return TranscriptionTarget("https://api.openai.com/v1/audio/transcriptions", openAiKey, s.transcriptionModel.ifBlank { "whisper-1" })
        val groqKey = s.providerKey(BrainProviders.GROQ)
        if (groqKey.isNotBlank()) return TranscriptionTarget("https://api.groq.com/openai/v1/audio/transcriptions", groqKey, s.transcriptionModel.ifBlank { "whisper-large-v3" })
        return null
    }

    private suspend fun transcribeFile(ctx: Context, path: String, language: String): ToolOutcome = withContext(Dispatchers.IO) {
        val f = File(path)
        if (path.isBlank() || !f.exists() || f.length() == 0L) return@withContext ToolOutcome.fail("Audio file not found: $path")
        if (f.length() > 25L * 1024 * 1024) return@withContext ToolOutcome.fail("Audio file is larger than 25 MB; trim it first.")
        val target = transcriptionTarget(BrainSettings.get(ctx))
            ?: return@withContext ToolOutcome.fail(
                "Transcription engine set nahi hai — Settings → Voice engine me OpenAI ya Groq key daalo (ya custom transcription URL), phir dobara bolo."
            )
        val mime = when (f.extension.lowercase()) {
            "mp3", "mpeg", "mpga" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg", "oga", "opus" -> "audio/ogg"
            "webm" -> "audio/webm"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", f.name, f.asRequestBody(mime.toMediaType()))
            .addFormDataPart("model", target.model)
            .addFormDataPart("response_format", "json")
            .apply { if (language.isNotBlank()) addFormDataPart("language", language) }
            .build()
        val req = Request.Builder().url(target.url).post(body)
        if (target.key.isNotBlank()) req.addHeader("Authorization", "Bearer ${target.key}")
        try {
            BrainHttpClient.http.newCall(req.build()).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) return@withContext ToolOutcome.fail("Transcription failed (HTTP ${r.code}): ${BrainJson.extractErrorMessage(raw).take(200)}")
                val text = runCatching { JSONObject(raw).optString("text", "") }.getOrDefault(raw).trim()
                ToolOutcome.ok(text.ifBlank { "(no speech found)" }, JSONObject().put("text", text).put("model", target.model))
            }
        } catch (e: Exception) {
            ToolOutcome.fail("Transcription error: ${e.message}")
        }
    }

    /** Names registered by this pack (for docs/tests). */
    val TOOL_NAMES: List<String> = listOf(
        "set_brain", "test_brain", "set_transcriber", "set_tts_engine", "generate_with_subagent",
        "translate_text", "transcribe_audio_file", "voice_engine_status"
    )
}
