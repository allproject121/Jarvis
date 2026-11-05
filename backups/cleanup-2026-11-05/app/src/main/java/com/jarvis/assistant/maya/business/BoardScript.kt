package com.jarvis.assistant.maya.business

import org.json.JSONArray
import org.json.JSONObject

/** One `## section` of a board script with its `- ` points. */
data class BoardSection(val name: String, val points: List<String>)

/**
 * Maya's "board script" output: `# title`, then `## section` headings with `- point` lines,
 * `---` and a final `## Note` section. Plans, research and reviews all use this shape so the
 * Marketing board can render them uniformly.
 */
data class BoardScript(val title: String, val sections: List<BoardSection>) {

    val note: BoardSection? get() = sections.firstOrNull { it.name.equals("Note", ignoreCase = true) }

    fun section(name: String): BoardSection? =
        sections.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun isEmpty(): Boolean = title.isBlank() && sections.isEmpty()

    /** Re-renders as canonical board-script text. */
    fun render(): String {
        val sb = StringBuilder()
        if (title.isNotBlank()) sb.append("# ").append(title).append('\n')
        for (s in sections) {
            if (s.name.equals("Note", ignoreCase = true)) sb.append("---\n")
            sb.append("## ").append(s.name).append('\n')
            for (p in s.points) sb.append("- ").append(p).append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("sections", JSONArray().apply {
            sections.forEach { s ->
                put(JSONObject().put("name", s.name).put("points", JSONArray(s.points)))
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): BoardScript {
            val arr = o.optJSONArray("sections") ?: JSONArray()
            val sections = ArrayList<BoardSection>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val pts = s.optJSONArray("points") ?: JSONArray()
                val list = ArrayList<String>()
                for (j in 0 until pts.length()) list.add(pts.optString(j))
                sections.add(BoardSection(s.optString("name"), list))
            }
            return BoardScript(o.optString("title"), sections)
        }
    }
}

object BoardScriptParser {

    private val BULLET = Regex("^\\s*(?:[-*•]|\\d+[.)])\\s+(.*)$")

    fun isBoardScript(text: String): Boolean =
        text.lineSequence().any { it.trimStart().startsWith("# ") } ||
            text.lineSequence().any { it.trimStart().startsWith("## ") }

    /**
     * Tolerant parser: markdown bold is stripped, `*`/`•`/numbered bullets count as points,
     * free text under a section becomes a point, `---` is a separator only.
     */
    fun parse(text: String): BoardScript {
        var title = ""
        val sections = ArrayList<BoardSection>()
        var currentName: String? = null
        var currentPoints = ArrayList<String>()
        val preamble = ArrayList<String>()

        fun closeSection() {
            val name = currentName
            if (name != null) sections.add(BoardSection(name, currentPoints.toList()))
            currentName = null
            currentPoints = ArrayList()
        }

        for (rawLine in text.lines()) {
            val line = rawLine.replace("**", "").replace("__", "").trimEnd()
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t == "---" || t == "***" || t == "___") continue
            when {
                t.startsWith("## ") || t.startsWith("##") && !t.startsWith("###") && t.length > 2 && t[2] != '#' -> {
                    closeSection()
                    currentName = t.removePrefix("##").trim().trimEnd(':')
                }
                t.startsWith("### ") -> {
                    closeSection()
                    currentName = t.removePrefix("###").trim().trimEnd(':')
                }
                t.startsWith("# ") && title.isBlank() && currentName == null -> {
                    title = t.removePrefix("#").trim()
                }
                t.startsWith("# ") -> {
                    // A second H1 is treated as a section heading.
                    closeSection()
                    currentName = t.removePrefix("#").trim().trimEnd(':')
                }
                else -> {
                    val m = BULLET.matchEntire(t)
                    val point = (m?.groupValues?.get(1) ?: t).trim()
                    if (currentName == null) preamble.add(point) else currentPoints.add(point)
                }
            }
        }
        closeSection()
        if (preamble.isNotEmpty()) {
            if (title.isBlank() && preamble.size == 1 && sections.isNotEmpty()) {
                title = preamble[0]
            } else {
                sections.add(0, BoardSection("Summary", preamble))
            }
        }
        return BoardScript(title, sections)
    }
}
