package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import com.jarvis.assistant.androllm.permissions.Feature
import com.jarvis.assistant.androllm.permissions.PermissionManager
import com.jarvis.assistant.util.PermissionHelper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Translation via the system Google Translate handler (intent). In-chat
 * translation is already handled natively by the model — this tool is for the
 * case where the user wants the text open in the Translate app/web UI.
 */
class TranslationTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "open_translation",
        description = "Open Google Translate for a piece of text (optionally to a target language, e.g. 'es'). Note: the assistant can also translate directly in chat.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") { put("type", "string") }
                putJsonObject("target") { put("type", "string"); put("description", "Target language code, e.g. es, fr, de") }
            }
            putJsonArray("required") { add("text") }
        },
        permission = ToolPermission.TRANSLATION,
        category = ToolCategory.INFORMATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = ToolArgs.str(arguments, "text")
            ?: return ToolResult.Failure("Missing required argument: text")
        val target = ToolArgs.str(arguments, "target", "language", "lang")
        val url = if (target != null) {
            "https://translate.google.com/?sl=auto&tl=${Uri.encode(target)}&text=${Uri.encode(text)}"
        } else {
            "https://translate.google.com/?sl=auto&text=${Uri.encode(text)}"
        }
        val opened = ToolIntents.launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return if (opened) {
            ToolResult.Success("Opened Google Translate${target?.let { " ($it)" } ?: ""}.")
        } else {
            ToolResult.Failure("Could not open Google Translate.")
        }
    }
}

/**
 * Opens the device gallery (or the Photos app). One-tap convenience.
 */
class GalleryTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "open_gallery",
        description = "Open the device's photo gallery.",
        permission = ToolPermission.MEDIA,
        category = ToolCategory.MEDIA
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "image/*"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val opened = runCatching {
            context.startActivity(
                Intent.createChooser(intent, "Open gallery").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
        return if (opened) ToolResult.Success("Opened the gallery.") else ToolResult.Failure("No gallery app is available.")
    }
}

/**
 * Records a voice note with the microphone (one-shot, bounded duration) and
 * returns the audio file path. Needs RECORD_AUDIO — requested lazily through
 * [PermissionManager] when missing.
 */
class VoiceRecorderTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "record_voice",
        description = "Record a voice note for a few seconds (default 10, max 60) and return the audio file path.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("duration_s") { put("type", "integer"); put("description", "Seconds to record (1–60, default 10)") }
                putJsonObject("label") { put("type", "string"); put("description", "Optional file label") }
            }
        },
        permission = ToolPermission.VOICE_RECORDER,
        category = ToolCategory.MEDIA,
        // The microphone is sensitive: always confirmed before recording.
        requiresConfirmation = true,
        executionTimeoutMs = 90_000L
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!PermissionHelper.hasRecordAudioPermission(context)) {
            return ToolResult.Failure(
                PermissionManager.getInstance(context).missingMessage(
                    Feature.VOICE_ASSISTANT, "Recording a voice note needs the microphone."
                ),
                retryable = false
            )
        }
        val duration = ToolArgs.int(
            arguments, "duration_s",
            ToolArgs.int(arguments, "seconds", ToolArgs.int(arguments, "duration", 10))
        ).coerceIn(1, 60)
        val label = ToolArgs.str(arguments, "label", "name")?.replace(Regex("[^A-Za-z0-9_-]"), "_") ?: "recording"
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "${label}_${System.currentTimeMillis()}.m4a")

        return withContext(Dispatchers.IO) {
            val recorder = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            runCatching {
                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(64_000)
                    setAudioSamplingRate(44_100)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                // One-shot, bounded wait; the executor timeout is the safety net.
                repeat(duration * 10) { delay(100) }
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                if (!file.exists() || file.length() == 0L) {
                    ToolResult.Failure(
                        "Recording failed — the microphone may be busy (e.g. a live voice session) or unavailable."
                    )
                } else {
                    ToolResult.Success(
                        "Recorded ${duration}s voice note (${file.length() / 1024} KB): ${file.absolutePath}",
                        buildJsonObject {
                            put("path", file.absolutePath)
                            put("duration_s", duration)
                            put("bytes", file.length())
                        }
                    )
                }
            }.getOrElse { t ->
                runCatching { recorder.release() }
                file.delete()
                ToolResult.Failure("Recording failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }
}
