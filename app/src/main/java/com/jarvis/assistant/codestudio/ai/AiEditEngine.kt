package com.jarvis.assistant.codestudio.ai

/**
 * Surgical edit format used by Code Studio's AI (search/replace blocks, never whole-file rewrites):
 *
 * ```
 * FILE: path/relative/to/project.ext
 * <<<<<<< SEARCH
 * exact lines currently in the file
 * =======
 * the new lines
 * >>>>>>> REPLACE
 * ```
 *
 * An empty SEARCH section creates the file (or appends when it already has content).
 * [parse] is tolerant of markdown fences, bold/backticked file names, "### path" headers, marker
 * length variations and missing FILE lines (falls back to the default file). [apply] tries exact,
 * line-ending-normalised, trailing-whitespace, indentation-insensitive, blank-line-insensitive and
 * finally near-match (most lines equal, anchors equal) matching, re-indenting the replacement when
 * the match was indentation-insensitive.
 */
object AiEditEngine {

    data class EditBlock(val filePath: String?, val search: String, val replace: String)

    data class ParseResult(val summary: String?, val blocks: List<EditBlock>, val fencedCode: String?)

    enum class MatchKind { EXACT, LINE_ENDINGS, TRAILING_WS, INDENT_INSENSITIVE, BLANK_LINES_IGNORED, FUZZY, CREATE, APPEND }

    sealed class ApplyResult {
        data class Applied(val newText: String, val kind: MatchKind, val occurrences: Int) : ApplyResult()
        data class Failed(val reason: String) : ApplyResult()
    }

    private val SEARCH_RE = Regex("""^\s*<{5,9}\s*SEARCH\b.*$""", RegexOption.IGNORE_CASE)
    private val DIVIDER_RE = Regex("""^\s*={5,9}\s*$""")
    private val REPLACE_RE = Regex("""^\s*>{5,9}\s*REPLACE\b.*$""", RegexOption.IGNORE_CASE)
    private val FILE_RE = Regex("""^\s*(?:#+\s*)?(?:\*\*)?\s*(?:FILE|File|file|PATH|Path|path|Filename|filename)\s*:\s*(.+?)\s*$""")
    private val HEADER_PATH_RE = Regex("""^\s*#{1,6}\s+`?([\w./\- ]+\.[A-Za-z0-9]{1,8})`?\s*$""")
    private val BARE_PATH_RE = Regex("""^\s*[`*]*([\w./\-]+\.[A-Za-z0-9]{1,8})[`*:]*\s*$""")
    private val FENCE_RE = Regex("""^\s*```.*$""")
    private val SUMMARY_RE = Regex("""^\s*(?:\*\*)?SUMMARY(?:\*\*)?\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

    fun cleanPath(raw: String): String =
        raw.trim().trim('`', '*', '"', '\'', ':').trim().replace('\\', '/').removePrefix("./").trimStart('/')

    fun parse(response: String, defaultFile: String? = null): ParseResult {
        val lines = response.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<EditBlock>()
        var summary: String? = null
        var currentFile: String? = defaultFile
        var lastPathCandidate: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (summary == null) SUMMARY_RE.find(line)?.let { summary = it.groupValues[1].trim() }
            val fileMatch = FILE_RE.find(line)
            when {
                fileMatch != null -> {
                    currentFile = cleanPath(fileMatch.groupValues[1]); lastPathCandidate = null
                }
                HEADER_PATH_RE.matches(line) -> {
                    currentFile = cleanPath(HEADER_PATH_RE.find(line)!!.groupValues[1]); lastPathCandidate = null
                }
                SEARCH_RE.matches(line) -> {
                    val search = ArrayList<String>()
                    val replace = ArrayList<String>()
                    var j = i + 1
                    var inReplace = false
                    var closed = false
                    while (j < lines.size) {
                        val l = lines[j]
                        if (!inReplace && DIVIDER_RE.matches(l)) { inReplace = true; j++; continue }
                        if (REPLACE_RE.matches(l)) { closed = true; break }
                        // A new SEARCH before REPLACE means a malformed block – stop here.
                        if (SEARCH_RE.matches(l)) break
                        if (inReplace) replace += l else search += l
                        j++
                    }
                    if (inReplace) {
                        val file = lastPathCandidate ?: currentFile
                        blocks += EditBlock(file, search.joinToString("\n"), replace.joinToString("\n"))
                        if (file != null) currentFile = file
                    }
                    lastPathCandidate = null
                    i = if (closed) j + 1 else j
                    continue
                }
                FENCE_RE.matches(line) -> { /* ignore fences around blocks */ }
                line.isBlank() -> { /* keep last candidate across blank lines */ }
                else -> {
                    val bare = BARE_PATH_RE.find(line)
                    lastPathCandidate = if (bare != null && '.' in bare.groupValues[1]) cleanPath(bare.groupValues[1]) else null
                }
            }
            i++
        }
        return ParseResult(summary, blocks, if (blocks.isEmpty()) extractFirstFence(response) else null)
    }

    private fun extractFirstFence(text: String): String? {
        val m = Regex("```[A-Za-z0-9_+-]*\\s*\\n([\\s\\S]*?)\\n```").find(text) ?: return null
        return m.groupValues[1]
    }

    // ── Apply ───────────────────────────────────────────────────────────────

    fun apply(original: String?, block: EditBlock): ApplyResult {
        val content = (original ?: "")
        val usesCrlf = content.contains("\r\n")
        val text = content.replace("\r\n", "\n")
        val search = block.search.replace("\r\n", "\n")
        val replace = block.replace.replace("\r\n", "\n")

        fun out(s: String) = if (usesCrlf) s.replace("\n", "\r\n") else s

        if (search.isBlank()) {
            return if (text.isBlank()) ApplyResult.Applied(out(ensureTrailingNewline(replace)), MatchKind.CREATE, 1)
            else ApplyResult.Applied(out(ensureTrailingNewline(text.trimEnd('\n') + "\n" + replace)), MatchKind.APPEND, 1)
        }

        // 1. Exact
        val exactIdx = text.indexOf(search)
        if (exactIdx >= 0) {
            val count = countOccurrences(text, search)
            val kind = if (content.contains("\r\n")) MatchKind.LINE_ENDINGS else MatchKind.EXACT
            return ApplyResult.Applied(out(text.replaceRange(exactIdx, exactIdx + search.length, replace)), kind, count)
        }
        // 1b. Exact after trimming surrounding blank lines of the search
        val trimmedSearch = search.trim('\n')
        if (trimmedSearch.isNotEmpty()) {
            val idx = text.indexOf(trimmedSearch)
            if (idx >= 0) {
                return ApplyResult.Applied(
                    out(text.replaceRange(idx, idx + trimmedSearch.length, replace.trim('\n'))),
                    MatchKind.EXACT, countOccurrences(text, trimmedSearch)
                )
            }
        }

        val contentLines = text.split('\n')
        val searchLines = trimBlankEdges(search.split('\n'))
        val replaceLines = trimBlankEdges(replace.split('\n'))
        if (searchLines.isEmpty()) return ApplyResult.Failed("Empty search block")

        // 2. Trailing whitespace tolerant
        findWindow(contentLines, searchLines) { a, b -> a.trimEnd() == b.trimEnd() }?.let { start ->
            return ApplyResult.Applied(out(spliceLines(contentLines, start, searchLines.size, replaceLines)), MatchKind.TRAILING_WS, 1)
        }
        // 3. Indentation / inner whitespace insensitive (re-indent replacement)
        findWindow(contentLines, searchLines) { a, b -> normWs(a) == normWs(b) }?.let { start ->
            val reindented = reindent(replaceLines, searchLines, contentLines.subList(start, start + searchLines.size))
            return ApplyResult.Applied(out(spliceLines(contentLines, start, searchLines.size, reindented)), MatchKind.INDENT_INSENSITIVE, 1)
        }
        // 4. Blank lines ignored
        matchIgnoringBlankLines(contentLines, searchLines)?.let { (start, endExclusive) ->
            val reindented = reindent(replaceLines, searchLines, contentLines.subList(start, endExclusive))
            return ApplyResult.Applied(out(spliceLines(contentLines, start, endExclusive - start, reindented)), MatchKind.BLANK_LINES_IGNORED, 1)
        }
        // 5. Near match: first & last non-blank lines equal (normalised) and >= 80% of lines equal.
        fuzzyWindow(contentLines, searchLines)?.let { start ->
            val reindented = reindent(replaceLines, searchLines, contentLines.subList(start, start + searchLines.size))
            return ApplyResult.Applied(out(spliceLines(contentLines, start, searchLines.size, reindented)), MatchKind.FUZZY, 1)
        }
        val preview = searchLines.firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: ""
        return ApplyResult.Failed("Search text not found (starts with \"$preview\")")
    }

    private fun ensureTrailingNewline(s: String) = if (s.endsWith("\n")) s else "$s\n"

    private fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0; var idx = text.indexOf(needle)
        while (idx >= 0) { count++; idx = text.indexOf(needle, idx + needle.length) }
        return count
    }

    private fun normWs(s: String) = s.trim().replace(Regex("\\s+"), " ")

    private fun trimBlankEdges(lines: List<String>): List<String> {
        var s = 0; var e = lines.size
        while (s < e && lines[s].isBlank()) s++
        while (e > s && lines[e - 1].isBlank()) e--
        return lines.subList(s, e)
    }

    private inline fun findWindow(content: List<String>, search: List<String>, eq: (String, String) -> Boolean): Int? {
        if (search.size > content.size) return null
        outer@ for (start in 0..content.size - search.size) {
            for (k in search.indices) if (!eq(content[start + k], search[k])) continue@outer
            return start
        }
        return null
    }

    private fun matchIgnoringBlankLines(content: List<String>, search: List<String>): Pair<Int, Int>? {
        val s = search.filter { it.isNotBlank() }.map(::normWs)
        if (s.isEmpty()) return null
        val idx = content.indices.filter { content[it].isNotBlank() }
        val norm = idx.map { normWs(content[it]) }
        if (s.size > norm.size) return null
        outer@ for (start in 0..norm.size - s.size) {
            for (k in s.indices) if (norm[start + k] != s[k]) continue@outer
            return idx[start] to (idx[start + s.size - 1] + 1)
        }
        return null
    }

    private fun fuzzyWindow(content: List<String>, search: List<String>): Int? {
        if (search.size < 3 || search.size > content.size) return null
        val s = search.map(::normWs)
        var best: Int? = null; var bestScore = 0.0
        for (start in 0..content.size - search.size) {
            if (normWs(content[start]) != s.first() || normWs(content[start + s.size - 1]) != s.last()) continue
            var same = 0
            for (k in s.indices) if (normWs(content[start + k]) == s[k]) same++
            val score = same.toDouble() / s.size
            if (score > bestScore) { bestScore = score; best = start }
        }
        return if (bestScore >= 0.8) best else null
    }

    private fun spliceLines(content: List<String>, start: Int, count: Int, replacement: List<String>): String {
        val out = ArrayList<String>(content.size - count + replacement.size)
        out.addAll(content.subList(0, start))
        out.addAll(replacement)
        out.addAll(content.subList(start + count, content.size))
        return out.joinToString("\n")
    }

    private fun leadingWs(s: String) = s.takeWhile { it == ' ' || it == '\t' }

    /** Shifts the replacement by the indentation difference between the search text and the matched text. */
    private fun reindent(replacement: List<String>, search: List<String>, matched: List<String>): List<String> {
        val sFirst = search.firstOrNull { it.isNotBlank() } ?: return replacement
        val mFirst = matched.firstOrNull { it.isNotBlank() } ?: return replacement
        val sInd = leadingWs(sFirst); val mInd = leadingWs(mFirst)
        if (sInd == mInd) return replacement
        return replacement.map { line ->
            when {
                line.isBlank() -> line
                sInd.isNotEmpty() && line.startsWith(sInd) -> mInd + line.removePrefix(sInd)
                sInd.isEmpty() -> mInd + line
                else -> line
            }
        }
    }
}
