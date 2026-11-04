package com.jarvis.assistant.maya.hub

/**
 * Pure helpers for Termux job output: tail-first paging with `termux_read_more` tokens,
 * `termux_find_in_output` search, and the command heuristics Maya used to decide when a
 * command is really a server that should run in the background.
 *
 * No Android imports - covered by unit tests.
 */
object TermuxOutputPager {

    const val DEFAULT_PAGE_CHARS = 6000
    private const val TOKEN_SEP = '#'

    /**
     * One page of output. [text] is the slice, [from]/[to] are char offsets into the full
     * output, [nextToken] pages further *back* (towards the beginning) or is null at the start.
     */
    data class Page(
        val jobId: String,
        val text: String,
        val from: Int,
        val to: Int,
        val totalChars: Int,
        val nextToken: String?
    ) {
        val hasMore: Boolean get() = nextToken != null
        val truncated: Boolean get() = from > 0 || to < totalChars
    }

    /** Encodes a paging token: `<jobId>#<endOffset>`. */
    fun token(jobId: String, endOffset: Int): String = "$jobId$TOKEN_SEP$endOffset"

    /** Decodes a token; a bare job id means "the last page". */
    fun parseToken(token: String): Pair<String, Int?>? {
        val t = token.trim()
        if (t.isEmpty()) return null
        val idx = t.lastIndexOf(TOKEN_SEP)
        if (idx < 0) return t to null
        val id = t.substring(0, idx)
        val off = t.substring(idx + 1).toIntOrNull() ?: return null
        if (id.isEmpty() || off < 0) return null
        return id to off
    }

    /** The newest [pageChars] characters (tail-first, like Maya). */
    fun lastPage(jobId: String, full: String, pageChars: Int = DEFAULT_PAGE_CHARS): Page =
        pageEndingAt(jobId, full, full.length, pageChars)

    /** Page whose *end* is at [endOffset] (exclusive), cut on a line boundary where possible. */
    fun pageEndingAt(jobId: String, full: String, endOffset: Int, pageChars: Int = DEFAULT_PAGE_CHARS): Page {
        val total = full.length
        val end = endOffset.coerceIn(0, total)
        val size = pageChars.coerceAtLeast(200)
        var start = (end - size).coerceAtLeast(0)
        if (start > 0) {
            // Snap forward to the next newline so pages begin on whole lines.
            val nl = full.indexOf('\n', start)
            if (nl in start until end - 1) start = nl + 1
        }
        val text = full.substring(start, end)
        val next = if (start > 0) token(jobId, start) else null
        return Page(jobId, text, start, end, total, next)
    }

    /** Resolves a token against the job's full output. */
    fun pageForToken(token: String, full: String, pageChars: Int = DEFAULT_PAGE_CHARS): Page? {
        val (id, off) = parseToken(token) ?: return null
        return if (off == null) lastPage(id, full, pageChars) else pageEndingAt(id, full, off, pageChars)
    }

    data class Match(val lineNumber: Int, val line: String, val context: List<String>)

    /** Case-insensitive line search with [contextLines] of surrounding lines. */
    fun find(full: String, needle: String, contextLines: Int = 1, limit: Int = 25): List<Match> {
        val n = needle.trim()
        if (n.isEmpty()) return emptyList()
        val lines = full.split('\n')
        val regex = runCatching { Regex(n, RegexOption.IGNORE_CASE) }.getOrNull()
        val out = ArrayList<Match>()
        for ((i, line) in lines.withIndex()) {
            val hit = if (regex != null) regex.containsMatchIn(line) else line.contains(n, ignoreCase = true)
            if (!hit) continue
            val lo = (i - contextLines).coerceAtLeast(0)
            val hi = (i + contextLines).coerceAtMost(lines.lastIndex)
            out.add(Match(i + 1, line, lines.subList(lo, hi + 1)))
            if (out.size >= limit) break
        }
        return out
    }

    fun formatMatches(matches: List<Match>, needle: String): String {
        if (matches.isEmpty()) return "No line matches '$needle'."
        val sb = StringBuilder("${matches.size} match(es) for '$needle':\n")
        for (m in matches) {
            sb.append("--- line ${m.lineNumber}\n")
            m.context.forEach { sb.append(it).append('\n') }
        }
        return sb.toString().trimEnd()
    }

    // ── Command heuristics (ported from Maya's termux_run description) ──────

    private val serverPatterns = listOf(
        Regex("""python3?\s+-m\s+http\.server"""),
        Regex("""\bnpm\s+(run\s+)?(dev|start|serve)\b"""),
        Regex("""\b(yarn|pnpm)\s+(dev|start|serve)\b"""),
        Regex("""\btail\s+-[a-zA-Z]*f\b"""),
        Regex("""\bnode\s+\S+\.js\b.*\b(server|listen)\b"""),
        Regex("""\b(flask|uvicorn|gunicorn|django-admin)\b.*\b(run|runserver)?"""),
        Regex("""\bpython3?\s+manage\.py\s+runserver"""),
        Regex("""\bopenclaw\s+gateway\b"""),
        Regex("""\bsshd\b"""),
        Regex("""\bnpx\s+(vite|next|serve|http-server|expo)\b"""),
        Regex("""\bhermes\s+(serve|gateway)\b"""),
        Regex("""\bollama\s+serve\b""")
    )

    /** True when the command looks like a server/watcher that never exits. */
    fun looksLikeServer(command: String): Boolean {
        val c = command.trim()
        if (c.isEmpty()) return false
        if (c.endsWith("&") && !c.endsWith("&&")) return true
        return serverPatterns.any { it.containsMatchIn(c) }
    }

    /** Strips a trailing `&` so the hub can own the backgrounding itself. */
    fun stripTrailingAmpersand(command: String): String {
        val c = command.trim()
        return if (c.endsWith("&") && !c.endsWith("&&")) c.dropLast(1).trimEnd() else c
    }

    /** Ports mentioned in a command (`--port 3000`, `:8080`, `-p 5000`, `PORT=...`). */
    fun guessPorts(command: String): List<Int> {
        val found = LinkedHashSet<Int>()
        val patterns = listOf(
            Regex("""(?:--port|-p|PORT=|port[=:])\s*(\d{2,5})""", RegexOption.IGNORE_CASE),
            Regex(""":(\d{4,5})\b"""),
            Regex("""http\.server\s+(\d{2,5})""")
        )
        for (p in patterns) for (m in p.findAll(command)) {
            m.groupValues[1].toIntOrNull()?.let { if (it in 1..65535) found.add(it) }
        }
        if (found.isEmpty() && Regex("""python3?\s+-m\s+http\.server""").containsMatchIn(command)) found.add(8000)
        return found.toList()
    }

    /** Shell-quotes a string with single quotes. */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Resolves a user path for Termux: `~`/`$HOME` -> home, bare names -> Jarvis scratch dir,
     * `/sdcard/...` and absolute paths unchanged.
     */
    fun resolveTermuxPath(path: String, home: String = "/data/data/com.termux/files/home"): String {
        val p = path.trim()
        return when {
            p.isEmpty() -> "$home/.jarvis"
            p == "~" -> home
            p.startsWith("~/") -> home + p.substring(1)
            p.startsWith("\$HOME/") -> home + p.substring(5)
            p.startsWith("/") -> p
            p.startsWith("./") -> "$home/${p.substring(2)}"
            !p.contains('/') -> "$home/.jarvis/$p"
            else -> "$home/$p"
        }
    }

    /** True when the path lives inside Termux's private data dir (not reachable from Jarvis). */
    fun isTermuxPrivatePath(path: String): Boolean = path.trim().startsWith("/data/data/com.termux/")

    fun newJobId(now: Long = System.currentTimeMillis()): String =
        "job_" + now.toString(36) + "_" + ((now xor 0x5DEECE66DL) % 9973).toString(36)
}
