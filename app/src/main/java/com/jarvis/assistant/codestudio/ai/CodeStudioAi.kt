package com.jarvis.assistant.codestudio.ai

import android.content.Context
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.AiTextSynthesizer
import com.jarvis.assistant.codestudio.workspace.CodeStudioWorkspace
import java.io.File

/**
 * Code Studio's AI layer. All model calls go through Jarvis' TEXT routing
 * ([AiTextSynthesizer.synthesizeText]: Gemini REST → OpenAI → local Ollama/LM Studio endpoint).
 * Gemini Live is never used here.
 */
object CodeStudioAi {

    private const val NO_AI = "__CODE_STUDIO_NO_AI_RESPONSE__"

    enum class Provider { CLOUD, LOCAL_ONLY, NONE }

    data class ProviderStatus(val provider: Provider, val message: String?)

    fun providerStatus(context: Context): ProviderStatus {
        val prefs = ((context.applicationContext as? JarvisApp) ?: JarvisApp.instance).preferences
        val cloud = prefs.apiKey.isNotBlank() || prefs.openAiApiKey.isNotBlank()
        val local = prefs.customEndpoint.isNotBlank()
        return when {
            cloud -> ProviderStatus(Provider.CLOUD, null)
            local -> ProviderStatus(
                Provider.LOCAL_ONLY,
                "Using your local model — code edits may be weak. Add a Gemini API key in Jarvis Settings for reliable AI edits."
            )
            else -> ProviderStatus(
                Provider.NONE,
                "No AI provider configured. Add a Gemini API key in Jarvis Settings (API keys) to use AI edit and Auto-Fix."
            )
        }
    }

    class AiUnavailableException(message: String) : Exception(message)

    /** Raw completion through Jarvis text routing. Throws [AiUnavailableException] when nothing answered. */
    suspend fun complete(context: Context, system: String, prompt: String, maxTokens: Int = 4096, temperature: Double = 0.2): String {
        val status = providerStatus(context)
        if (status.provider == Provider.NONE) throw AiUnavailableException(status.message!!)
        val out = AiTextSynthesizer.synthesizeText(
            context = context,
            systemInstruction = system,
            prompt = prompt,
            temperature = temperature,
            maxTokens = maxTokens,
            fallback = { NO_AI }
        )
        if (out == NO_AI || out.isBlank()) {
            throw AiUnavailableException(
                "The AI did not respond (network error, quota or invalid key). " +
                    if (status.provider == Provider.LOCAL_ONLY) "Your local endpoint may be offline — add a Gemini key in Jarvis Settings."
                    else "Check your Gemini/OpenAI key in Jarvis Settings and try again."
            )
        }
        return out
    }

    // ── Prompts ─────────────────────────────────────────────────────────────

    val EDIT_SYSTEM = """
You are the code-editing engine of Jarvis Code Studio, a mobile IDE.
You make SURGICAL edits using SEARCH/REPLACE blocks. Never output a whole file unless you are creating a new one.

Output format (repeat for every change):
FILE: relative/path/from/project/root.ext
<<<<<<< SEARCH
exact lines copied verbatim from the current file (include 2-3 lines of unchanged context so the match is unique)
=======
the new lines that replace them
>>>>>>> REPLACE

Rules:
- Start your answer with one line: SUMMARY: <what you changed, one sentence>.
- SEARCH text must match the existing file character-for-character, including indentation. Keep it short but unique.
- Use several small blocks rather than one big block. Do not repeat unchanged code outside SEARCH/REPLACE.
- To create a new file use an empty SEARCH section and put the full file in REPLACE.
- To delete code, leave the REPLACE section empty.
- Only edit files that exist in the provided project tree unless a new file is genuinely required.
- Do not wrap blocks in explanations; no prose after the blocks.
""".trim()

    data class FileContext(val path: String, val content: String)

    /**
     * Collects the files given to the AI: [primary] first, then files mentioned in [hintText],
     * then the remaining small text files, until [budgetChars] is used.
     */
    fun collectContext(
        workspace: CodeStudioWorkspace,
        projectDir: File,
        primary: File?,
        hintText: String,
        budgetChars: Int = 40_000
    ): List<FileContext> {
        val files = workspace.listProjectFiles(projectDir).filter { CodeStudioWorkspace.isTextFile(it) && it.length() < 200_000 }
        val ordered = LinkedHashSet<File>()
        primary?.let { p -> files.firstOrNull { it.canonicalPath == p.canonicalPath }?.let { ordered += it } }
        files.filter { f -> hintText.contains(f.name) }.forEach { ordered += it }
        files.sortedBy { it.length() }.forEach { ordered += it }
        val out = ArrayList<FileContext>()
        var used = 0
        for (f in ordered) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if (used + text.length > budgetChars && out.isNotEmpty()) continue
            out += FileContext(workspace.relativeTo(projectDir, f), text)
            used += text.length
        }
        return out
    }

    fun renderContext(tree: String, files: List<FileContext>): String = buildString {
        append("PROJECT TREE:\n").append(tree).append('\n')
        for (f in files) {
            append("----- FILE: ").append(f.path).append(" -----\n")
            append(f.content)
            if (!f.content.endsWith("\n")) append('\n')
            append("----- END FILE: ").append(f.path).append(" -----\n\n")
        }
    }

    fun editPrompt(instruction: String, activePath: String?, selection: String?, context: String): String = buildString {
        append(context)
        if (activePath != null) append("CURRENTLY OPEN FILE: ").append(activePath).append('\n')
        if (!selection.isNullOrBlank()) append("SELECTED CODE (focus on this):\n").append(selection).append("\n\n")
        append("REQUEST: ").append(instruction.trim()).append('\n')
        append("Respond with SUMMARY line + SEARCH/REPLACE blocks only.")
    }

    fun fixPrompt(error: String, issues: List<CodeValidator.Issue>, attempt: Int, previousFailure: String?, context: String): String = buildString {
        append(context)
        append("TASK: Fix the following problem(s) in this project with minimal surgical edits.\n")
        if (error.isNotBlank()) append("REPORTED ERROR / CONSOLE OUTPUT:\n").append(error.trim()).append("\n\n")
        if (issues.isNotEmpty()) {
            append("STATIC VALIDATION ISSUES:\n")
            issues.take(25).forEach { append("- ").append(it.toString()).append('\n') }
            append('\n')
        }
        if (attempt > 1 && previousFailure != null) {
            append("PREVIOUS ATTEMPT ").append(attempt - 1).append(" DID NOT SUCCEED: ").append(previousFailure).append('\n')
            append("The files above already include the previous changes. Try a different, correct fix.\n\n")
        }
        append("First analyse the root cause in at most 3 short lines starting with 'ANALYSIS:', then output SUMMARY line + SEARCH/REPLACE blocks.")
    }

    // ── Apply ───────────────────────────────────────────────────────────────

    data class FileChange(val path: String, val file: File, val before: String?, val after: String, val kind: AiEditEngine.MatchKind)
    data class FailedBlock(val path: String?, val reason: String, val searchPreview: String)

    data class ApplyOutcome(
        val summary: String?,
        val changes: List<FileChange>,
        val failures: List<FailedBlock>,
        val rawResponse: String
    ) {
        val anyApplied get() = changes.isNotEmpty()
        val changedPaths get() = changes.map { it.path }.distinct()
    }

    /**
     * Parses [response] and applies every block to files inside [projectDir]. Blocks targeting the
     * same file are applied sequentially; the file is written once at the end. Paths outside the
     * project are rejected.
     */
    fun applyResponse(
        workspace: CodeStudioWorkspace,
        projectDir: File,
        response: String,
        defaultPath: String?
    ): ApplyOutcome {
        val parsed = AiEditEngine.parse(response, defaultPath)
        val failures = ArrayList<FailedBlock>()
        val working = LinkedHashMap<String, String?>()      // path -> current text
        val originals = LinkedHashMap<String, String?>()
        val kinds = HashMap<String, AiEditEngine.MatchKind>()
        val fileOf = HashMap<String, File>()

        var blocks = parsed.blocks
        if (blocks.isEmpty() && parsed.fencedCode != null && defaultPath != null) {
            // Fallback: no blocks, but a code fence and the target file is empty → treat as new content.
            val f = runCatching { workspace.resolveInProject(projectDir, defaultPath) }.getOrNull()
            if (f != null && (!f.exists() || f.readText().isBlank())) {
                blocks = listOf(AiEditEngine.EditBlock(defaultPath, "", parsed.fencedCode))
            }
        }
        if (blocks.isEmpty()) {
            return ApplyOutcome(parsed.summary, emptyList(),
                listOf(FailedBlock(defaultPath, "The AI reply contained no SEARCH/REPLACE edit blocks", "")), response)
        }

        for (b in blocks) {
            val path = b.filePath?.let(AiEditEngine::cleanPath) ?: defaultPath
            if (path.isNullOrBlank()) { failures += FailedBlock(null, "No target file given", preview(b.search)); continue }
            val file = try { workspace.resolveInProject(projectDir, path) } catch (e: SecurityException) {
                failures += FailedBlock(path, "Rejected: path is outside the project", preview(b.search)); continue
            }
            if (file.isDirectory) { failures += FailedBlock(path, "Target is a folder", preview(b.search)); continue }
            val key = workspace.relativeTo(projectDir, file)
            if (key !in working) {
                val text = if (file.exists()) runCatching { file.readText() }.getOrNull() else null
                working[key] = text; originals[key] = text; fileOf[key] = file
            }
            when (val r = AiEditEngine.apply(working[key], b)) {
                is AiEditEngine.ApplyResult.Applied -> {
                    working[key] = r.newText
                    kinds[key] = maxOf(kinds[key] ?: r.kind, r.kind)
                }
                is AiEditEngine.ApplyResult.Failed -> failures += FailedBlock(key, r.reason, preview(b.search))
            }
        }

        val changes = ArrayList<FileChange>()
        for ((key, text) in working) {
            val before = originals[key]
            if (text == null || text == before) continue
            val file = fileOf.getValue(key)
            workspace.writeText(file, text)
            changes += FileChange(key, file, before, text, kinds[key] ?: AiEditEngine.MatchKind.EXACT)
        }
        return ApplyOutcome(parsed.summary, changes, failures, response)
    }

    private fun preview(s: String) = s.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80) ?: "(empty)"
}
