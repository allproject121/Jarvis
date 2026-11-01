package com.jarvis.assistant.codestudio.generator

import java.io.File

/**
 * Strict file-block format the AI must emit, plus a tolerant parser.
 *
 * ```
 * <<<MANIFEST>>>
 * index.html
 * css/style.css
 * js/app.js
 * <<<END MANIFEST>>>
 * <<<FILE: index.html>>>
 * ...raw file content, no markdown fences...
 * <<<END FILE>>>
 * <<<CONTINUE: js/app.js>>>      (only in continuation rounds: text appended to a cut-off file)
 * ...
 * <<<END FILE>>>
 * <<<PROJECT COMPLETE>>>
 * ```
 * The parser also accepts common model deviations (missing END, `<<FILE path>>`, code fences inside
 * blocks, markdown "### path" + fenced block output) and reports a cut-off last block as incomplete.
 */
object ProjectFileFormat {

    val FORMAT_SPEC = """
OUTPUT FORMAT (MANDATORY — your reply is parsed by a program):
1. First a manifest listing every file path you will create, one per line:
<<<MANIFEST>>>
index.html
css/style.css
js/main.js
<<<END MANIFEST>>>
2. Then every file, each in its own block, with the RAW file content (NO markdown code fences, NO commentary):
<<<FILE: relative/path.ext>>>
...complete file content...
<<<END FILE>>>
3. After the last file, output exactly: <<<PROJECT COMPLETE>>>
Rules: relative paths only (no leading '/', no '..'), forward slashes, lowercase file names.
Write nothing outside these blocks. Never abbreviate code with comments like "// rest of code here".
""".trim()

    data class Block(val path: String, val content: String, val complete: Boolean, val isContinuation: Boolean)

    data class Parsed(
        val manifest: List<String>,
        val blocks: List<Block>,
        val projectComplete: Boolean
    )

    private val HEADER = Regex("""^\s*(?:[#*>/\- ]*)<{2,3}\s*(FILE|CONTINUE)\s*[:=]?\s*(.+?)\s*>{2,3}\s*\**\s*$""", RegexOption.IGNORE_CASE)
    private val END = Regex("""^\s*<{2,3}\s*/?\s*END\s*(?:OF\s+)?(?:FILE)?\s*(?:[:=]\s*\S+)?\s*>{2,3}\s*$""", RegexOption.IGNORE_CASE)
    private val COMPLETE = Regex("""<{2,3}\s*PROJECT[\s_]+COMPLETE\s*>{2,3}""", RegexOption.IGNORE_CASE)
    private val MANIFEST_START = Regex("""^\s*<{2,3}\s*MANIFEST\s*>{2,3}\s*$""", RegexOption.IGNORE_CASE)
    private val MANIFEST_END = Regex("""^\s*<{2,3}\s*END\s+MANIFEST\s*>{2,3}\s*$""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Parsed {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        val manifest = mutableListOf<String>()
        val blocks = mutableListOf<Block>()
        val projectComplete = COMPLETE.containsMatchIn(text)

        var inManifest = false
        var curPath: String? = null
        var curCont = false
        val buf = StringBuilder()

        fun flush(complete: Boolean) {
            val p = curPath ?: return
            blocks += Block(p, stripFences(buf.toString(), complete), complete, curCont)
            curPath = null
            buf.setLength(0)
        }

        for (line in lines) {
            if (curPath == null) {
                if (MANIFEST_START.matches(line)) { inManifest = true; continue }
                if (inManifest) {
                    if (MANIFEST_END.matches(line) || HEADER.matches(line)) {
                        inManifest = false
                        if (!HEADER.matches(line)) continue
                    } else {
                        val token = line.trim().trimStart('-', '*', ' ', '•').trim()
                            .replace(Regex("^\\d+[.)]\\s+"), "").split(Regex("\\s+")).firstOrNull().orEmpty()
                        SafePaths.clean(token)?.let { if (it !in manifest) manifest += it }
                        continue
                    }
                }
            }
            val h = HEADER.matchEntire(line)
            if (h != null) {
                // A new header while inside a block: model forgot END — treat previous as complete.
                flush(complete = true)
                val path = SafePaths.clean(h.groupValues[2])
                if (path != null) {
                    curPath = path
                    curCont = h.groupValues[1].equals("CONTINUE", ignoreCase = true)
                }
                continue
            }
            if (curPath != null) {
                if (END.matches(line)) { flush(complete = true); continue }
                if (COMPLETE.containsMatchIn(line) && line.trim().length < 40) { flush(complete = true); continue }
                buf.append(line).append('\n')
            }
        }
        // Last block without END: cut off (unless the model still declared completion).
        flush(complete = projectComplete)

        if (blocks.isEmpty()) blocks += parseMarkdownFallback(text)
        return Parsed(manifest, blocks, projectComplete)
    }

    /** Removes a wrapping ```lang ... ``` fence that models like to add inside blocks. */
    private fun stripFences(content: String, complete: Boolean): String {
        val ls = content.split('\n').toMutableList()
        while (ls.isNotEmpty() && ls.first().isBlank()) ls.removeAt(0)
        while (ls.isNotEmpty() && ls.last().isBlank()) ls.removeAt(ls.lastIndex)
        if (ls.isNotEmpty() && ls.first().trim().startsWith("```")) {
            ls.removeAt(0)
            if (ls.isNotEmpty() && ls.last().trim() == "```") ls.removeAt(ls.lastIndex)
        } else if (complete && ls.isNotEmpty() && ls.last().trim() == "```" && ls.none { it.trim().startsWith("```") && it.trim().length > 3 }) {
            // stray closing fence only
            if (ls.count { it.trim() == "```" } == 1) ls.removeAt(ls.lastIndex)
        }
        return if (ls.isEmpty()) "" else ls.joinToString("\n") + "\n"
    }

    private val FENCE_FILE_NAME = Regex("""([A-Za-z0-9_\-./]+\.(?:html?|css|m?js|json|md|svg|txt|xml|webmanifest|ts|py|kt|java|sh|yml|yaml|toml|glsl))""", RegexOption.IGNORE_CASE)

    /** Fallback for replies that ignored the format: "### css/style.css" followed by a fenced block. */
    private fun parseMarkdownFallback(text: String): List<Block> {
        val result = mutableListOf<Block>()
        val lines = text.split('\n')
        var i = 0
        var lastName: String? = null
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            if (t.startsWith("```")) {
                val lang = t.removePrefix("```").trim().lowercase()
                val inlineName = FENCE_FILE_NAME.find(lang)?.value
                val body = StringBuilder()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    if (lines[j].trim() == "```") { closed = true; break }
                    body.append(lines[j]).append('\n'); j++
                }
                val name = SafePaths.clean(inlineName ?: lastName ?: when (lang) {
                    "html" -> if (result.none { it.path == "index.html" }) "index.html" else null
                    "css" -> if (result.none { it.path == "style.css" }) "style.css" else null
                    "js", "javascript" -> if (result.none { it.path == "script.js" }) "script.js" else null
                    else -> null
                } ?: "")
                if (name != null && body.isNotBlank()) result += Block(name, body.toString(), closed, false)
                lastName = null
                i = j + 1
                continue
            }
            if (t.isNotEmpty()) {
                // A short line naming a file right before a fence: "### js/app.js", "**style.css**", "File: x".
                lastName = if (t.length <= 120) FENCE_FILE_NAME.find(t.replace("`", "").replace("*", ""))?.value else null
            }
            i++
        }
        return result
    }

    /** Joins a continuation chunk onto a cut-off file, removing repeated overlap. */
    fun mergeContinuation(existing: String, continuation: String): String {
        if (existing.isEmpty()) return continuation
        val cont = continuation
        // Model restarted the whole file → take the new text.
        val head = existing.take(200)
        if (head.length >= 40 && cont.startsWith(head)) return cont
        val maxK = minOf(existing.length, cont.length, 1500)
        for (k in maxK downTo 12) {
            if (existing.regionMatches(existing.length - k, cont, 0, k)) {
                return existing + cont.substring(k)
            }
        }
        // Trim a dangling partial line on the old text if the continuation restarts that line.
        val lastNl = existing.lastIndexOf('\n')
        if (lastNl >= 0) {
            val partial = existing.substring(lastNl + 1)
            if (partial.isNotBlank() && cont.trimStart().startsWith(partial.trim())) {
                return existing.substring(0, lastNl + 1) + cont.trimStart()
            }
        }
        return existing + cont
    }
}

/** Relative-path sanitizing for generated files (no traversal, no absolute paths, sane chars). */
object SafePaths {
    private val ALLOWED = Regex("""^[A-Za-z0-9._\- ]+$""")
    private const val MAX_LEN = 160
    private const val MAX_DEPTH = 8

    fun clean(raw: String): String? {
        var p = raw.trim().trim('`', '"', '\'', '*', ' ').replace('\\', '/')
        p = p.substringBefore(" (").trim() // "index.html (main page)"
        while (p.startsWith("./")) p = p.removePrefix("./")
        p = p.trimStart('/')
        if (p.isEmpty() || p.length > MAX_LEN || p.contains("://") || p.contains(':')) return null
        val segs = p.split('/')
        if (segs.size > MAX_DEPTH) return null
        if (segs.any { it.isEmpty() || it == "." || it == ".." || !ALLOWED.matches(it) || it.startsWith(".") }) return null
        if (!segs.last().contains('.')) return null // files need an extension
        return segs.joinToString("/")
    }

    /** Resolves [relative] inside [root], or null if it would escape. */
    fun resolveInside(root: File, relative: String): File? {
        val clean = clean(relative) ?: return null
        val base = root.canonicalFile
        val f = File(base, clean).canonicalFile
        return if (f.path.startsWith(base.path + File.separator)) f else null
    }
}
