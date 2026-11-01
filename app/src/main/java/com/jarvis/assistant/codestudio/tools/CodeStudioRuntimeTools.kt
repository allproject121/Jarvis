package com.jarvis.assistant.codestudio.tools

import android.content.Context
import com.jarvis.assistant.automation.agent.PendingAction
import com.jarvis.assistant.automation.agent.ToolConfirmationManager
import com.jarvis.assistant.codestudio.CodeStudioPaths
import com.jarvis.assistant.codestudio.generator.GeneratorKind
import com.jarvis.assistant.codestudio.generator.ProjectGenerator
import com.jarvis.assistant.codestudio.preview.PreviewServer
import com.jarvis.assistant.codestudio.terminal.RootShell
import com.jarvis.assistant.codestudio.terminal.ShellRunner
import com.jarvis.assistant.codestudio.terminal.ShellSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Agent tools for Code Studio runtime features (shell, generator, preview), registered in
 * JarvisToolDispatcher: `run_shell_command`, `generate_project`, `start_preview`.
 */
object CodeStudioRuntimeTools {

    val NAMES = setOf("run_shell_command", "generate_project", "start_preview")

    private const val MAX_OUTPUT_CHARS = 6_000

    fun schemas(): List<JSONObject> {
        fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
        fun tool(name: String, desc: String, props: JSONObject, required: List<String>) = JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", desc)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", props)
                    put("required", JSONArray(required))
                })
            })
        }
        return listOf(
            tool(
                "run_shell_command",
                "Runs a shell command (Android /system/bin/sh, toybox utilities) in the Jarvis Code Studio projects folder " +
                    "(or inside a project). Returns exit code, stdout and stderr. Destructive or root commands require user confirmation.",
                JSONObject()
                    .put("command", prop("string", "The shell command line to run"))
                    .put("project", prop("string", "Optional project name: run inside that project's folder"))
                    .put("root", prop("boolean", "Run via su (only works on rooted devices after the user grants root). Default false"))
                    .put("timeout_seconds", prop("integer", "Timeout in seconds (default 60, max 300)")),
                listOf("command")
            ),
            tool(
                "generate_project",
                "Generates a complete multi-file web project in Jarvis Code Studio with AI (landing page, web app, HTML5 canvas game, or generic). " +
                    "Returns the project name, folder and files. Use start_preview afterwards to run it.",
                JSONObject()
                    .put("prompt", prop("string", "What to build, in detail"))
                    .put("kind", JSONObject().put("type", "string")
                        .put("enum", JSONArray(listOf("landing_page", "web_app", "html5_game", "generic")))
                        .put("description", "Project type (default generic)"))
                    .put("name", prop("string", "Project name (folder name)"))
                    .put("offline", prop("boolean", "true = use the built-in starter template without AI")),
                listOf("prompt")
            ),
            tool(
                "start_preview",
                "Starts the local live-preview web server (127.0.0.1) for a Code Studio project and returns its URL.",
                JSONObject().put("project", prop("string", "Project name")),
                listOf("project")
            )
        )
    }

    /** Executes [name]; writes success/message (+ data) into [result]. Returns success. */
    suspend fun execute(context: Context, name: String, args: JSONObject, result: JSONObject): Boolean = try {
        when (name) {
            "run_shell_command" -> runShell(context, args, result)
            "generate_project" -> generate(context, args, result)
            "start_preview" -> startPreview(context, args, result)
            else -> fail(result, "Unknown Code Studio tool '$name'")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        fail(result, "Code Studio error: ${e.message}")
    }

    private suspend fun runShell(context: Context, args: JSONObject, result: JSONObject): Boolean {
        val command = args.optString("command").trim()
        if (command.isEmpty()) return fail(result, "command is required.")
        val root = args.optBoolean("root", false)
        val timeoutMs = (args.optInt("timeout_seconds", 60).coerceIn(1, 300)) * 1000L
        val projectsRoot = CodeStudioPaths.projectsRoot(context)
        val project = args.optString("project").trim()
        val workDir = if (project.isNotEmpty()) {
            existingProject(context, project) ?: return fail(result, "Project '$project' not found.")
        } else projectsRoot

        if (root && !RootShell.isSuAvailable()) {
            return fail(result, "Root is not available: no 'su' binary on this device. Run without root.")
        }

        val danger = ShellSafety.destructiveReason(command)
        if (root || danger != null || ShellSafety.isRisky(command)) {
            val why = danger ?: if (root) "runs as ROOT" else "may modify files or the system"
            val approved = runCatching {
                ToolConfirmationManager.awaitApproval(
                    PendingAction(
                        id = "shell_${System.currentTimeMillis()}_${command.hashCode().toUInt().toString(16)}",
                        toolName = "run_shell_command",
                        description = "Run shell command ($why): $command",
                        speakableQuestion = "Jarvis wants to run a shell command that $why. Do you want to allow it?"
                    )
                )
            }.getOrDefault(false)
            if (!approved) {
                result.put("cancelled", true)
                return fail(result, "The user did not confirm this command, so it was not run.")
            }
        }

        val res = ShellRunner.run(command, workDir, root = root, timeoutMs = timeoutMs)
        result.put("exit_code", res.exitCode)
        result.put("timed_out", res.timedOut)
        result.put("stdout", res.stdout.takeLast(MAX_OUTPUT_CHARS))
        result.put("stderr", res.stderr.takeLast(MAX_OUTPUT_CHARS))
        result.put("work_dir", workDir.absolutePath)
        if (res.stdout.length > MAX_OUTPUT_CHARS || res.stderr.length > MAX_OUTPUT_CHARS) result.put("truncated", true)
        val summary = when {
            res.timedOut -> "Command timed out after ${timeoutMs / 1000}s."
            res.exitCode == 0 -> "Command succeeded."
            else -> "Command exited with code ${res.exitCode}."
        }
        val preview = (res.stdout.ifBlank { res.stderr }).trim().take(600)
        return if (res.isSuccess) ok(result, if (preview.isEmpty()) summary else "$summary\n$preview")
        else fail(result, if (preview.isEmpty()) summary else "$summary\n$preview")
    }

    private suspend fun generate(context: Context, args: JSONObject, result: JSONObject): Boolean {
        val prompt = args.optString("prompt").trim()
        if (prompt.isEmpty()) return fail(result, "prompt is required.")
        val kind = when (args.optString("kind").trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "landing_page", "landing", "website" -> GeneratorKind.LANDING_PAGE
            "web_app", "webapp", "app" -> GeneratorKind.WEB_APP
            "html5_game", "game" -> GeneratorKind.HTML5_GAME
            else -> GeneratorKind.GENERIC
        }
        val name = args.optString("name").trim().ifEmpty { prompt.split(Regex("\\s+")).take(4).joinToString(" ") }
        val log = mutableListOf<String>()
        val onProgress: (String) -> Unit = { synchronized(log) { log += it } }
        val res = if (args.optBoolean("offline", false)) {
            ProjectGenerator.generateOffline(context, kind, prompt, name, onProgress)
        } else {
            ProjectGenerator.generate(context, kind, prompt, name, onProgress)
        }
        val dir = res.getOrElse { return fail(result, "Generation failed: ${it.message}") }
        val files = withContext(Dispatchers.IO) {
            dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()
        }
        result.put("project", dir.name)
        result.put("path", dir.absolutePath)
        result.put("files", JSONArray(files.take(200)))
        result.put("log", JSONArray(synchronized(log) { log.takeLast(40) }))
        val warnings = ProjectGenerator.validate(dir)
        if (warnings.isNotEmpty()) result.put("warnings", JSONArray(warnings))
        return ok(result, "Generated project '${dir.name}' with ${files.size} file(s): ${files.take(20).joinToString()}.")
    }

    private suspend fun startPreview(context: Context, args: JSONObject, result: JSONObject): Boolean {
        val project = args.optString("project").trim()
        val dir = existingProject(context, project) ?: return fail(result, "Project '$project' not found.")
        val url = withContext(Dispatchers.IO) { PreviewServer.acquireDetached(dir) }
        result.put("url", url)
        result.put("project", dir.name)
        result.put("index_exists", File(dir, "index.html").isFile)
        return ok(result, "Preview of '${dir.name}' is running at $url (local to this phone).")
    }

    private fun existingProject(context: Context, name: String): File? {
        if (name.isBlank()) return null
        val root = CodeStudioPaths.projectsRoot(context).canonicalFile
        val dir = File(root, CodeStudioPaths.sanitize(name)).canonicalFile
        return if (dir.isDirectory && dir.path.startsWith(root.path + File.separator)) dir else null
    }

    private fun ok(r: JSONObject, msg: String): Boolean { r.put("success", true); r.put("message", msg); return true }
    private fun fail(r: JSONObject, msg: String): Boolean { r.put("success", false); r.put("message", msg); return false }
}
