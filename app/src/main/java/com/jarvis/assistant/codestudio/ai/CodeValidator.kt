package com.jarvis.assistant.codestudio.ai

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * Lightweight static checks run after every AI patch (Auto-Fix "validate" step). No compiler is
 * available on-device, so this catches what can be detected reliably: unbalanced brackets /
 * unterminated strings & comments (JS, TS, CSS, Kotlin, Java, C-like), invalid JSON, mismatched
 * HTML container tags, broken inline <script>/<style> blocks, local src/href references to
 * missing files, Python indentation/bracket problems, and leftover merge/edit markers.
 */
object CodeValidator {

    data class Issue(val file: String, val line: Int, val message: String) {
        override fun toString() = "$file:$line: $message"
    }

    fun validateProject(projectDir: File, files: List<File>): List<Issue> {
        val issues = ArrayList<Issue>()
        for (f in files) {
            if (f.length() > 800_000) continue
            val rel = runCatching { f.relativeTo(projectDir).path.replace('\\', '/') }.getOrDefault(f.name)
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            issues += validateText(rel, text, f.parentFile, projectDir)
        }
        return issues
    }

    fun validateText(rel: String, text: String, dir: File? = null, projectDir: File? = null): List<Issue> {
        val issues = ArrayList<Issue>()
        // Leftover edit markers are always a bug.
        text.lineSequence().forEachIndexed { idx, l ->
            if (Regex("""^\s*(<{7}\s*SEARCH|>{7}\s*REPLACE|<{7} HEAD|>{7} \S+)""").containsMatchIn(l)) {
                issues += Issue(rel, idx + 1, "Leftover edit/merge marker")
            }
        }
        when (rel.substringAfterLast('.', "").lowercase()) {
            "js", "mjs", "cjs", "ts", "jsx", "tsx" -> issues += checkBrackets(rel, text, Lang.JS)
            "kt", "kts", "java", "c", "cpp", "h", "cs", "go", "rs", "swift", "dart" -> issues += checkBrackets(rel, text, Lang.C)
            "css", "scss", "less" -> issues += checkBrackets(rel, text, Lang.CSS)
            "json" -> checkJson(rel, text)?.let { issues += it }
            "html", "htm" -> issues += checkHtml(rel, text, dir, projectDir)
            "py" -> issues += checkPython(rel, text)
        }
        return issues
    }

    private enum class Lang { JS, C, CSS }

    private fun lineOf(text: String, index: Int): Int {
        var n = 1
        for (i in 0 until minOf(index, text.length)) if (text[i] == '\n') n++
        return n
    }

    /** Bracket balance aware of strings, template literals, comments and (heuristically) JS regex literals. */
    private fun checkBrackets(rel: String, text: String, lang: Lang, lineOffset: Int = 0): List<Issue> {
        val issues = ArrayList<Issue>()
        val stack = ArrayDeque<Pair<Char, Int>>()
        var i = 0
        val n = text.length
        fun issue(at: Int, msg: String) { issues += Issue(rel, lineOf(text, at) + lineOffset, msg) }
        var lastSignificant = ' '
        while (i < n) {
            val c = text[i]
            val next = if (i + 1 < n) text[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' && lang != Lang.CSS -> { while (i < n && text[i] != '\n') i++; continue }
                c == '/' && next == '*' -> {
                    val end = text.indexOf("*/", i + 2)
                    if (end < 0) { issue(i, "Unterminated block comment"); return issues }
                    i = end + 2; continue
                }
                c == '"' || c == '\'' || (c == '`' && lang == Lang.JS) -> {
                    // Kotlin/Java char literals and strings; triple-quoted Kotlin strings
                    if (c == '"' && text.startsWith("\"\"\"", i) && lang == Lang.C) {
                        val end = text.indexOf("\"\"\"", i + 3)
                        if (end < 0) { issue(i, "Unterminated raw string"); return issues }
                        i = end + 3; lastSignificant = '"'; continue
                    }
                    val start = i
                    i++
                    var closed = false
                    while (i < n) {
                        val ch = text[i]
                        if (ch == '\\') { i += 2; continue }
                        if (ch == '$' && i + 1 < n && text[i + 1] == '{' && (c == '`' || (c == '"' && lang == Lang.C))) {
                            // Template expression: skip to its matching brace.
                            var depth = 0
                            var k = i + 1
                            while (k < n) {
                                if (text[k] == '{') depth++
                                else if (text[k] == '}') { depth--; if (depth == 0) break }
                                k++
                            }
                            i = k + 1; continue
                        }
                        if (ch == c) { closed = true; break }
                        if (ch == '\n' && c != '`') break
                        i++
                    }
                    if (!closed) {
                        // CSS / JS apostrophes inside comments are already skipped; report real breaks only.
                        issue(start, "Unterminated string literal")
                        if (i >= n) return issues
                    }
                    i++; lastSignificant = c; continue
                }
                c == '/' && lang == Lang.JS && lastSignificant in "(,=:[!&|?{};" -> {
                    // Regex literal – skip to the closing slash on the same line.
                    var j = i + 1
                    var inClass = false
                    while (j < n && text[j] != '\n') {
                        val ch = text[j]
                        if (ch == '\\') { j += 2; continue }
                        if (ch == '[') inClass = true else if (ch == ']') inClass = false
                        else if (ch == '/' && !inClass) break
                        j++
                    }
                    if (j < n && text[j] == '/') { i = j + 1; lastSignificant = '/'; continue }
                }
                c == '(' || c == '[' || c == '{' -> stack.addLast(c to i)
                c == ')' || c == ']' || c == '}' -> {
                    val open = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
                    if (stack.isEmpty()) issue(i, "Unexpected '$c'")
                    else {
                        val top = stack.removeLast()
                        if (top.first != open) {
                            issue(i, "Mismatched '$c' (opened '${top.first}' on line ${lineOf(text, top.second) + lineOffset})")
                        }
                    }
                }
            }
            if (!c.isWhitespace()) lastSignificant = c
            i++
            if (issues.size > 8) return issues
        }
        stack.take(5).forEach { (ch, at) -> issue(at, "Unclosed '$ch'") }
        return issues
    }

    private fun checkJson(rel: String, text: String): Issue? {
        if (text.isBlank()) return null
        return try {
            when (val v = JSONTokener(text).nextValue()) {
                is JSONObject, is JSONArray -> null
                else -> if (v == null) Issue(rel, 1, "Invalid JSON") else null
            }
        } catch (e: Exception) {
            val m = Regex("at character (\\d+)").find(e.message ?: "")
            val line = m?.groupValues?.get(1)?.toIntOrNull()?.let { lineOf(text, it) } ?: 1
            Issue(rel, line, "Invalid JSON: ${e.message?.substringBefore(" at character")}")
        }
    }

    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr", "param", "keygen", "command")
    private val OPTIONAL_CLOSE = setOf("p", "li", "dt", "dd", "tr", "td", "th", "option", "optgroup", "thead", "tbody", "tfoot", "colgroup", "rt", "rp", "html", "head", "body")

    private fun checkHtml(rel: String, text: String, dir: File?, projectDir: File?): List<Issue> {
        val issues = ArrayList<Issue>()
        // Inline scripts / styles
        Regex("<script\\b([^>]*)>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            val type = Regex("type\\s*=\\s*[\"']([^\"']+)").find(attrs)?.groupValues?.get(1)?.lowercase()
            if (body.isNotBlank() && (type == null || "javascript" in type || type == "module")) {
                issues += checkBrackets(rel, body, Lang.JS, lineOf(text, m.groups[2]!!.range.first) - 1)
            }
        }
        Regex("<style\\b[^>]*>([\\s\\S]*?)</style>", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            issues += checkBrackets(rel, m.groupValues[1], Lang.CSS, lineOf(text, m.groups[1]!!.range.first) - 1)
        }
        // Tag balance (outside script/style/comments)
        val stripped = text
            .replace(Regex("<!--[\\s\\S]*?-->")) { " ".repeat(it.value.length).let { s -> keepNewlines(it.value, s) } }
            .replace(Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)) { keepNewlines(it.value, "") }
        val stack = ArrayDeque<Pair<String, Int>>()
        Regex("<(/?)([A-Za-z][A-Za-z0-9-]*)\\b[^>]*?(/?)>").findAll(stripped).forEach { m ->
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            val selfClosing = m.groupValues[3] == "/"
            if (tag in VOID || selfClosing) return@forEach
            if (!closing) { stack.addLast(tag to m.range.first); return@forEach }
            if (stack.none { it.first == tag }) {
                if (tag !in OPTIONAL_CLOSE) issues += Issue(rel, lineOf(stripped, m.range.first), "Stray closing tag </$tag>")
                return@forEach
            }
            while (stack.isNotEmpty()) {
                val top = stack.removeLast()
                if (top.first == tag) break
                if (top.first !in OPTIONAL_CLOSE) issues += Issue(rel, lineOf(stripped, top.second), "<${top.first}> is not closed before </$tag>")
            }
        }
        stack.filter { it.first !in OPTIONAL_CLOSE }.take(5).forEach { (tag, at) ->
            issues += Issue(rel, lineOf(stripped, at), "Unclosed <$tag>")
        }
        // Local references
        if (dir != null) {
            Regex("""(?:src|href)\s*=\s*["']([^"'#?]+)""", RegexOption.IGNORE_CASE).findAll(stripped + scriptsAndStylesTags(text)).forEach { m ->
                val ref = m.groupValues[1].trim()
                if (ref.isEmpty() || ref.contains(":") || ref.startsWith("//") || ref.startsWith("{") || ref.startsWith("$")) return@forEach
                val target = if (ref.startsWith("/") && projectDir != null) File(projectDir, ref.trimStart('/')) else File(dir, ref)
                if (!target.exists()) issues += Issue(rel, lineOf(text, text.indexOf(ref).coerceAtLeast(0)), "Referenced file not found: $ref")
            }
        }
        return issues.distinctBy { it.toString() }
    }

    /** Opening tags of script/link elements (their attributes were blanked out together with bodies). */
    private fun scriptsAndStylesTags(text: String): String =
        Regex("<script\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(text).joinToString("\n") { it.value }

    private fun keepNewlines(original: String, replacement: String): String {
        val nl = original.count { it == '\n' }
        return if (replacement.isEmpty()) "\n".repeat(nl) else replacement.mapIndexed { i, c -> if (original[i] == '\n') '\n' else c }.joinToString("")
    }

    private fun checkPython(rel: String, text: String): List<Issue> {
        val issues = ArrayList<Issue>()
        var usesTabs = false; var usesSpaces = false
        text.lines().forEachIndexed { i, l ->
            val ind = l.takeWhile { it == ' ' || it == '\t' }
            if (l.isNotBlank()) {
                if ('\t' in ind) usesTabs = true
                if (' ' in ind) usesSpaces = true
                if ('\t' in ind && ' ' in ind) issues += Issue(rel, i + 1, "Mixed tabs and spaces in indentation")
                val code = l.substringBefore('#').trimEnd()
                if (Regex("^\\s*(def|class|if|elif|else|for|while|try|except|finally|with)\\b").containsMatchIn(code) &&
                    !code.endsWith(":") && !code.endsWith("(") && !code.endsWith(",") && !code.endsWith("\\") && code.count { it == '(' } == code.count { it == ')' }) {
                    issues += Issue(rel, i + 1, "Missing ':' at end of statement")
                }
            }
        }
        if (usesTabs && usesSpaces && issues.none { "Mixed" in it.message }) issues += Issue(rel, 1, "File mixes tab and space indentation")
        // Brackets (ignore strings/comments roughly by reusing the C scanner on a comment-stripped copy)
        val noComments = text.lines().joinToString("\n") { l -> if (l.trimStart().startsWith("#")) "" else l }
            .replace(Regex("(\"\"\"|''')[\\s\\S]*?\\1")) { keepNewlines(it.value, "") }
        issues += checkBrackets(rel, noComments, Lang.CSS).filter { "string" !in it.message }
        return issues.take(10)
    }
}
