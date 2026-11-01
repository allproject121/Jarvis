package com.jarvis.assistant.codestudio.ai

import android.content.Context
import com.jarvis.assistant.codestudio.workspace.CodeStudioWorkspace
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlinx.coroutines.currentCoroutineContext

/**
 * AUTO-FIX loop: analyse project context + error → ask the AI for a surgical patch → apply →
 * validate (static checks) → retry with the new findings, up to [maxCycles] times.
 */
class AutoFixEngine(
    private val context: Context,
    private val workspace: CodeStudioWorkspace
) {
    enum class Verdict { VERIFIED, VALIDATION_FAILED, NO_PATCH, AI_UNAVAILABLE }

    data class CycleReport(val cycle: Int, val summary: String?, val changed: List<String>, val failures: List<String>, val remainingIssues: List<String>)

    data class Result(
        val verdict: Verdict,
        val cycles: List<CycleReport>,
        val allChanges: List<CodeStudioAi.FileChange>,
        val remainingIssues: List<CodeValidator.Issue>,
        val message: String
    )

    suspend fun run(
        projectDir: File,
        activeFile: File?,
        errorText: String,
        maxCycles: Int = 3,
        onProgress: (String) -> Unit
    ): Result {
        val cycles = ArrayList<CycleReport>()
        val allChanges = ArrayList<CodeStudioAi.FileChange>()
        var previousFailure: String? = null

        onProgress("Analyzing project context…")
        var issues = validate(projectDir)
        if (errorText.isBlank() && issues.isEmpty()) {
            return Result(Verdict.VERIFIED, cycles, allChanges, emptyList(), "No errors reported and static validation is clean — nothing to fix.")
        }
        if (issues.isNotEmpty()) onProgress("Validation found ${issues.size} issue(s) before fixing")

        for (cycle in 1..maxCycles.coerceIn(1, 6)) {
            currentCoroutineContext().ensureActive()
            onProgress("Cycle $cycle/$maxCycles: asking AI for a patch…")
            val activePath = activeFile?.let { workspace.relativeTo(projectDir, it) }
            val hint = errorText + "\n" + issues.joinToString("\n") { it.file }
            val ctxFiles = CodeStudioAi.collectContext(workspace, projectDir, activeFile, hint)
            val ctx = CodeStudioAi.renderContext(workspace.describeTree(projectDir), ctxFiles)
            val response = try {
                CodeStudioAi.complete(context, CodeStudioAi.EDIT_SYSTEM, CodeStudioAi.fixPrompt(errorText, issues, cycle, previousFailure, ctx), maxTokens = 4096)
            } catch (e: CodeStudioAi.AiUnavailableException) {
                return Result(Verdict.AI_UNAVAILABLE, cycles, allChanges, issues, e.message ?: "AI unavailable")
            }
            currentCoroutineContext().ensureActive()
            onProgress("Cycle $cycle: applying patch…")
            val outcome = CodeStudioAi.applyResponse(workspace, projectDir, response, activePath)
            allChanges += outcome.changes
            outcome.changes.forEach { onProgress("  ✓ patched ${it.path} (${it.kind.name.lowercase().replace('_', ' ')})") }
            outcome.failures.forEach { onProgress("  ✗ ${it.path ?: "?"}: ${it.reason}") }

            onProgress("Cycle $cycle: validating…")
            issues = validate(projectDir)
            cycles += CycleReport(
                cycle, outcome.summary, outcome.changedPaths,
                outcome.failures.map { "${it.path ?: "?"}: ${it.reason}" },
                issues.map { it.toString() }
            )

            if (outcome.anyApplied && issues.isEmpty() && outcome.failures.isEmpty()) {
                return Result(Verdict.VERIFIED, cycles, allChanges, issues,
                    "AUTO-FIX VERIFIED after $cycle cycle(s): ${outcome.summary ?: "patch applied"}; static validation passed.")
            }
            if (outcome.anyApplied && issues.isEmpty()) {
                // Some blocks failed but everything that matters is clean.
                return Result(Verdict.VERIFIED, cycles, allChanges, issues,
                    "AUTO-FIX VERIFIED after $cycle cycle(s) (${outcome.failures.size} block(s) skipped); static validation passed.")
            }
            previousFailure = buildString {
                if (!outcome.anyApplied) append("none of your SEARCH blocks matched the file contents (copy SEARCH text exactly). ")
                outcome.failures.take(5).forEach { append("[${it.path}: ${it.reason} near \"${it.searchPreview}\"] ") }
                if (issues.isNotEmpty()) append("Validation still reports: ").append(issues.take(8).joinToString("; "))
            }
            onProgress("Cycle $cycle: not verified — ${if (issues.isNotEmpty()) "${issues.size} issue(s) remain" else "patch did not apply"}")
        }
        val verdict = if (allChanges.isEmpty()) Verdict.NO_PATCH else Verdict.VALIDATION_FAILED
        val msg = if (verdict == Verdict.NO_PATCH) "Could not apply a fix automatically after $maxCycles cycle(s)."
        else "VALIDATION FAILED after $maxCycles cycle(s): ${issues.size} issue(s) remain."
        return Result(verdict, cycles, allChanges, issues, msg)
    }

    fun validate(projectDir: File): List<CodeValidator.Issue> =
        CodeValidator.validateProject(projectDir, workspace.listProjectFiles(projectDir).filter { CodeStudioWorkspace.isTextFile(it) })
}
