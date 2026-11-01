package com.jarvis.assistant.codestudio.generator

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ai.AiTextSynthesizer
import com.jarvis.assistant.codestudio.CodeStudioPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class GeneratorKind { LANDING_PAGE, WEB_APP, HTML5_GAME, GENERIC }

/**
 * AI project generator for Code Studio.
 *
 * Uses Jarvis TEXT routing ([AiTextSynthesizer.synthesizeText]: Gemini REST → OpenAI → local host),
 * never Gemini Live. The model answers in [ProjectFileFormat]; output that is cut off (token limit)
 * is resumed with continuation rounds. Files are written into a fresh [CodeStudioPaths.projectDir]
 * with traversal checks and size caps, then validated (index.html + local references).
 * When no AI provider is reachable, an offline starter template is written instead.
 */
object ProjectGenerator {
    private const val TAG = "CodeStudioGenerator"
    private const val NO_AI = "__JCS_NO_AI_PROVIDER__"

    const val MAX_FILES = 60
    const val MAX_FILE_CHARS = 400_000
    const val MAX_TOTAL_CHARS = 3_000_000
    private const val MAX_ROUNDS = 7
    /** Per-call output budget; kept moderate so each REST call finishes within the synthesizer's timeouts. */
    private const val ROUND_TOKENS = 4096
    private const val CONTEXT_BUDGET_CHARS = 14_000

    suspend fun generate(
        context: Context,
        kind: GeneratorKind,
        prompt: String,
        projectName: String,
        onProgress: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val request = prompt.trim().ifBlank { defaultPrompt(kind) }
            val dir = freshProjectDir(context, projectName)
            onProgress("Project folder: ${dir.name}")

            val files = LinkedHashMap<String, String>()
            val completed = LinkedHashSet<String>()
            var manifest = emptyList<String>()
            var cutOff: String? = null
            var totalChars = 0
            var gotAnyAi = false

            var round = 0
            var userMsg = initialPrompt(kind, request)
            var emptyReplies = 0
            while (round < MAX_ROUNDS) {
                round++
                onProgress(if (round == 1) "Asking AI to design the project…" else "Continuing generation (round $round)…")
                val reply = ask(context, kind, userMsg)
                if (reply == NO_AI) {
                    if (!gotAnyAi) {
                        onProgress("No AI provider reachable (check API key / network) — using offline starter template.")
                        return@runCatching writeOffline(dir, kind, projectName, request, onProgress)
                    }
                    onProgress("AI stopped responding — keeping the files generated so far.")
                    break
                }
                gotAnyAi = true
                val parsed = ProjectFileFormat.parse(reply)
                if (parsed.manifest.isNotEmpty() && manifest.isEmpty()) {
                    manifest = parsed.manifest
                    onProgress("Plan: ${manifest.joinToString(", ")}")
                }
                if (parsed.blocks.isEmpty()) {
                    emptyReplies++
                    if (emptyReplies >= 2) break
                    userMsg = initialPrompt(kind, request) +
                        "\n\nIMPORTANT: your previous reply did not follow the output format. Reply ONLY with the manifest and <<<FILE: path>>> blocks."
                    continue
                }

                for (b in parsed.blocks) {
                    if (files.size >= MAX_FILES && b.path !in files) { onProgress("File limit reached; skipping ${b.path}"); continue }
                    val before = files[b.path]?.length ?: 0
                    val merged = if (b.isContinuation && files.containsKey(b.path)) {
                        ProjectFileFormat.mergeContinuation(files.getValue(b.path), b.content)
                    } else b.content
                    if (merged.length > MAX_FILE_CHARS) { onProgress("${b.path} exceeds size cap; truncated."); }
                    val capped = merged.take(MAX_FILE_CHARS)
                    if (totalChars - before + capped.length > MAX_TOTAL_CHARS) { onProgress("Project size cap reached; skipping ${b.path}"); continue }
                    totalChars += capped.length - before
                    files[b.path] = capped
                    if (b.complete) {
                        completed += b.path
                        if (cutOff == b.path) cutOff = null
                        onProgress("✓ ${b.path} (${capped.length} chars)")
                    } else {
                        completed -= b.path
                        cutOff = b.path
                        onProgress("… ${b.path} was cut off, will continue")
                    }
                }
                // Write incrementally so partial work is never lost.
                writeFiles(dir, files)

                val remaining = manifest.filter { it !in completed && it != cutOff }
                val done = cutOff == null && remaining.isEmpty() &&
                    (parsed.projectComplete || manifest.isNotEmpty() || completed.isNotEmpty())
                if (done) break
                if (cutOff == null && remaining.isEmpty()) break
                userMsg = continuationPrompt(kind, request, manifest, completed, files, cutOff, remaining)
            }

            if (files.isEmpty()) {
                onProgress("AI reply could not be parsed into files — using offline starter template.")
                return@runCatching writeOffline(dir, kind, projectName, request, onProgress)
            }
            cutOff?.let { onProgress("Warning: $it may be incomplete (output was cut off).") }

            // Validation + one repair round for missing referenced files.
            val missing = missingReferences(dir)
            if (missing.isNotEmpty() && gotAnyAi) {
                onProgress("Generating missing referenced files: ${missing.joinToString(", ")}")
                val reply = ask(context, kind, repairPrompt(kind, request, files, missing))
                if (reply != NO_AI) {
                    ProjectFileFormat.parse(reply).blocks
                        .filter { it.path in missing || it.path == "index.html" }
                        .forEach { b -> files[b.path] = b.content.take(MAX_FILE_CHARS) }
                    writeFiles(dir, files)
                }
            }
            ensureIndexHtml(dir, projectName, onProgress)
            validate(dir).forEach { onProgress("Warning: $it") }
            onProgress("Done — ${dir.walkTopDown().count { it.isFile }} files in ${dir.name}")
            dir
        }.onFailure {
            if (it is CancellationException) throw it
            Log.e(TAG, "generate failed", it)
        }
    }

    /** Writes the offline starter template for [kind] (no AI, no network). */
    suspend fun generateOffline(
        context: Context,
        kind: GeneratorKind,
        prompt: String,
        projectName: String,
        onProgress: (String) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching { writeOffline(freshProjectDir(context, projectName), kind, projectName, prompt, onProgress) }
    }

    /** Problems found in a project: missing index.html and local references that do not exist. */
    fun validate(dir: File): List<String> {
        val problems = mutableListOf<String>()
        if (!File(dir, "index.html").isFile) problems += "index.html is missing"
        missingReferences(dir).forEach { problems += "referenced file not found: $it" }
        return problems
    }

    // ── AI plumbing ─────────────────────────────────────────────────────

    private suspend fun ask(context: Context, kind: GeneratorKind, userMsg: String): String {
        val text = AiTextSynthesizer.synthesizeText(
            context = context,
            systemInstruction = systemPrompt(kind),
            prompt = userMsg,
            temperature = 0.4,
            maxTokens = ROUND_TOKENS,
            fallback = { NO_AI }
        )
        return if (text.isBlank()) NO_AI else text
    }

    private fun systemPrompt(kind: GeneratorKind): String = buildString {
        append("You are Jarvis Code Studio, a senior front-end engineer who writes complete, working, production-quality projects. ")
        append("Projects run as static files served from a local web server and are previewed in an Android WebView, so use only HTML, CSS and vanilla JavaScript ")
        append("(no build step, no npm, no frameworks unless loaded from a CDN <script> tag, and prefer none). Everything must work offline when possible.\n\n")
        append(kindRequirements(kind)).append("\n\n")
        append("Keep every file under ~250 lines: split code into several focused files instead of one huge file. ")
        append("Every file referenced by <script src>, <link href> or <img src> must be included. Use relative paths.\n\n")
        append(ProjectFileFormat.FORMAT_SPEC)
    }

    private fun kindRequirements(kind: GeneratorKind): String = when (kind) {
        GeneratorKind.LANDING_PAGE -> """
TASK TYPE: modern responsive landing page.
- Files: index.html, css/style.css, js/main.js (+ optional extra css/js or inline SVG assets; no binary images — use CSS gradients, emoji or inline SVG).
- Sections: sticky nav with mobile hamburger menu, hero with strong headline + call-to-action, features, social proof/testimonials, pricing or details, FAQ, final CTA with form (handled in JS, no backend), footer.
- Mobile-first responsive layout (CSS grid/flex, clamp() typography), smooth scroll, subtle scroll-reveal animations (IntersectionObserver), accessible semantic HTML, good contrast, prefers-reduced-motion support.
""".trim()
        GeneratorKind.WEB_APP -> """
TASK TYPE: multi-file single-page web application.
- Files: index.html, css/ (styles), js/ split by responsibility (e.g. js/state.js, js/storage.js, js/ui.js, js/app.js). Load scripts in dependency order with plain <script src> tags (or type="module" with relative imports).
- Real, working functionality for the request; persist data in localStorage; input validation; empty states; responsive mobile-first UI; light/dark friendly colors; no placeholder "TODO" logic.
""".trim()
        GeneratorKind.HTML5_GAME -> """
TASK TYPE: playable HTML5 2D canvas game.
- Files: index.html, css/style.css, js/input.js (keyboard + touch/pointer controls — it must be playable on a phone touchscreen), js/entities.js, js/game.js (+ more js files if needed, e.g. js/levels.js, js/audio.js using WebAudio oscillators, no audio files).
- Game loop: requestAnimationFrame with a FIXED 60 Hz update step (accumulator) and separate render, clamp large frame gaps, pause on visibilitychange.
- Canvas scales to the screen keeping aspect ratio (handle devicePixelRatio), start screen, score + high score (localStorage), increasing difficulty, game over + restart, collision detection, particles/juice. Draw everything with canvas shapes — no image files.
""".trim()
        GeneratorKind.GENERIC -> """
TASK TYPE: whatever the user asks for, as a static web project.
- Always include an index.html entry point (if the request is not a web page, index.html should present/demonstrate the project, e.g. a runnable demo or documentation page).
- Split into sensible files (css/, js/, data/ etc.).
""".trim()
    }

    private fun initialPrompt(kind: GeneratorKind, request: String): String =
        "Create this ${kindLabel(kind)}:\n\n$request\n\nStart with the <<<MANIFEST>>> block, then write every file in full."

    private fun continuationPrompt(
        kind: GeneratorKind,
        request: String,
        manifest: List<String>,
        completed: Set<String>,
        files: Map<String, String>,
        cutOff: String?,
        remaining: List<String>
    ): String = buildString {
        append("You are continuing generation of a ${kindLabel(kind)} for this request:\n").append(request).append("\n\n")
        if (manifest.isNotEmpty()) append("Planned files: ").append(manifest.joinToString(", ")).append('\n')
        append("Already finished (do NOT rewrite): ").append(completed.joinToString(", ").ifEmpty { "none" }).append("\n\n")
        // Give the model the finished files (budgeted) so ids, classes and function names stay consistent.
        var budget = CONTEXT_BUDGET_CHARS
        val ordered = completed.sortedBy { if (it.endsWith(".html")) 0 else 1 }
        for (path in ordered) {
            val c = files[path] ?: continue
            if (c.length > budget) continue
            budget -= c.length
            append("<<<EXISTING: ").append(path).append(">>>\n").append(c).append("<<<END EXISTING>>>\n")
        }
        append('\n')
        if (cutOff != null) {
            val tail = files[cutOff].orEmpty().lines().takeLast(30).joinToString("\n")
            append("Your previous output was CUT OFF in the middle of ").append(cutOff).append(". It currently ends with these lines:\n")
            append("<<<TAIL>>>\n").append(tail).append("\n<<<END TAIL>>>\n")
            append("First continue that file EXACTLY from where it stopped (do not repeat earlier lines) inside:\n")
            append("<<<CONTINUE: ").append(cutOff).append(">>>\n...rest of the file...\n<<<END FILE>>>\n")
        }
        if (remaining.isNotEmpty()) {
            append("Then write these remaining files in full, each as <<<FILE: path>>> ... <<<END FILE>>>: ")
            append(remaining.joinToString(", ")).append('\n')
        }
        append("Finish with <<<PROJECT COMPLETE>>>. No manifest, no commentary.")
    }

    private fun repairPrompt(kind: GeneratorKind, request: String, files: Map<String, String>, missing: List<String>): String = buildString {
        append("A ${kindLabel(kind)} was generated for: ").append(request).append("\n")
        append("These files are referenced but missing: ").append(missing.joinToString(", ")).append("\n")
        files["index.html"]?.takeIf { it.length < CONTEXT_BUDGET_CHARS }?.let {
            append("<<<EXISTING: index.html>>>\n").append(it).append("<<<END EXISTING>>>\n")
        }
        append("Write ONLY the missing files, complete and consistent with index.html, each as <<<FILE: path>>> ... <<<END FILE>>>, then <<<PROJECT COMPLETE>>>.")
    }

    private fun kindLabel(kind: GeneratorKind) = when (kind) {
        GeneratorKind.LANDING_PAGE -> "landing page"
        GeneratorKind.WEB_APP -> "web app"
        GeneratorKind.HTML5_GAME -> "HTML5 canvas game"
        GeneratorKind.GENERIC -> "project"
    }

    private fun defaultPrompt(kind: GeneratorKind) = when (kind) {
        GeneratorKind.LANDING_PAGE -> "A sleek landing page for a new productivity app."
        GeneratorKind.WEB_APP -> "A task manager with priorities, filters and search."
        GeneratorKind.HTML5_GAME -> "An arcade space dodger game with power-ups."
        GeneratorKind.GENERIC -> "A small interactive demo page."
    }

    // ── files ───────────────────────────────────────────────────────────

    /** A new, empty project folder: "name", or "name 2", "name 3"… if the name is taken. */
    private fun freshProjectDir(context: Context, projectName: String): File {
        val root = CodeStudioPaths.projectsRoot(context)
        val base = CodeStudioPaths.sanitize(projectName)
        var name = base
        var n = 2
        while (true) {
            val candidate = File(root, name)
            if (!candidate.exists() || candidate.listFiles().isNullOrEmpty()) break
            name = CodeStudioPaths.sanitize("${base.take(55)} $n")
            n++
            if (n > 999) { name = CodeStudioPaths.sanitize("${base.take(45)} ${System.currentTimeMillis()}"); break }
        }
        return CodeStudioPaths.projectDir(context, name)
    }

    private fun writeFiles(dir: File, files: Map<String, String>) {
        for ((path, content) in files) {
            val target = SafePaths.resolveInside(dir, path)
            if (target == null) { Log.w(TAG, "Rejected unsafe path: $path"); continue }
            target.parentFile?.mkdirs()
            target.writeText(content)
        }
    }

    private fun writeOffline(dir: File, kind: GeneratorKind, projectName: String, prompt: String, onProgress: (String) -> Unit): File {
        val files = OfflineTemplates.files(kind, projectName, prompt)
        writeFiles(dir, files)
        files.keys.forEach { onProgress("✓ $it (template)") }
        onProgress("Offline starter ready in ${dir.name}")
        return dir
    }

    private fun ensureIndexHtml(dir: File, projectName: String, onProgress: (String) -> Unit) {
        val index = File(dir, "index.html")
        if (index.isFile) return
        val htmls = dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("html", "htm") }.toList()
        val top = htmls.firstOrNull { it.parentFile == dir }
        when {
            top != null -> { top.copyTo(index, overwrite = true); onProgress("Copied ${top.name} to index.html") }
            htmls.isNotEmpty() -> {
                val rel = htmls.first().relativeTo(dir).invariantSeparatorsPath
                index.writeText("<!doctype html><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"0; url=$rel\"><a href=\"$rel\">Open $rel</a>\n")
                onProgress("Created index.html redirecting to $rel")
            }
            else -> {
                val list = dir.walkTopDown().filter { it.isFile }.joinToString("") {
                    val r = it.relativeTo(dir).invariantSeparatorsPath
                    "<li><a href=\"$r\">$r</a></li>"
                }
                val title = projectName.replace("<", "&lt;")
                index.writeText("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>$title</title></head>" +
                    "<body style=\"font-family:system-ui,sans-serif;padding:24px\"><h1>$title</h1><ul>$list</ul></body></html>\n")
                onProgress("Created index.html listing the project files")
            }
        }
    }

    private val REF_ATTR = Regex("""(?:src|href)\s*=\s*["']([^"'#?]+)""", RegexOption.IGNORE_CASE)
    private val CSS_URL = Regex("""url\(\s*["']?([^"')#?]+)""", RegexOption.IGNORE_CASE)
    private val JS_IMPORT = Regex("""(?:import\s[^'"]*?from\s*|import\s*\(\s*|import\s+)["'](\.{1,2}/[^"']+)["']""")

    /** Local relative references (src/href/url()/ES imports) that point to files not present in the project. */
    fun missingReferences(dir: File): List<String> {
        val root = dir.canonicalFile
        val missing = LinkedHashSet<String>()
        dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("html", "htm", "css", "js", "mjs") }.take(200).forEach { f ->
            val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
            val refs = when (f.extension.lowercase()) {
                "css" -> CSS_URL.findAll(text).map { it.groupValues[1] }
                "js", "mjs" -> JS_IMPORT.findAll(text).map { it.groupValues[1] }
                else -> REF_ATTR.findAll(text).map { it.groupValues[1] } + CSS_URL.findAll(text).map { it.groupValues[1] }
            }
            for (raw in refs) {
                val ref = raw.trim()
                if (ref.isEmpty() || isExternal(ref)) continue
                val base = if (ref.startsWith("/")) root else f.parentFile ?: root
                val target = File(base, ref.trimStart('/')).canonicalFile
                if (!target.path.startsWith(root.path)) continue
                if (target.isDirectory && File(target, "index.html").isFile) continue
                if (!target.exists()) missing += target.relativeTo(root).invariantSeparatorsPath
            }
        }
        return missing.toList()
    }

    private fun isExternal(ref: String): Boolean {
        val r = ref.lowercase()
        return r.startsWith("http:") || r.startsWith("https:") || r.startsWith("//") || r.startsWith("data:") ||
            r.startsWith("mailto:") || r.startsWith("tel:") || r.startsWith("javascript:") || r.startsWith("blob:") ||
            r.startsWith("about:") || r.contains("{") || r.contains("+") || r.contains("'") // template/concat leftovers
    }
}
