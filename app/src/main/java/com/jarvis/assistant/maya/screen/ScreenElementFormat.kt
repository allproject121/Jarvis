package com.jarvis.assistant.maya.screen

/**
 * One line of the Maya-style "SCREEN ELEMENTS" list. Pure Kotlin (no Android types) so the
 * formatting rules can be unit tested; [ScreenElementSerializer] builds these from the
 * accessibility tree.
 *
 * Rendered as: `#N  <label>  [role] [state] [id:<resource-id suffix>]  @ (x,y)`
 * where (x,y) are REAL device pixels (centre of the node bounds).
 */
data class ElementLine(
    val id: Int,
    val label: String,
    val role: String,
    val state: String? = null,
    val idHint: String? = null,
    val x: Int,
    val y: Int,
    val note: String? = null
)

object ScreenElementFormat {

    const val DEFAULT_LIMIT = 120
    const val NO_LABEL = "(no label)"
    const val MAX_LABEL_CHARS = 80

    private val SEND_HINTS = listOf("send", "post", "submit", "fab_send", "next", "publish", "done", "ok_button", "confirm")

    /** Collapses whitespace, trims and caps the label; blank → [NO_LABEL]. */
    fun cleanLabel(raw: String?): String {
        if (raw == null) return NO_LABEL
        val collapsed = raw.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty()) return NO_LABEL
        return if (collapsed.length > MAX_LABEL_CHARS) collapsed.substring(0, MAX_LABEL_CHARS - 1) + "…" else collapsed
    }

    /** `com.whatsapp:id/send` → `send`; null/blank → null. */
    fun resourceIdSuffix(fullId: String?): String? {
        if (fullId.isNullOrBlank()) return null
        val idx = fullId.lastIndexOf('/')
        val suffix = if (idx >= 0) fullId.substring(idx + 1) else fullId
        return suffix.trim().takeIf { it.isNotEmpty() }
    }

    /** Maps a view class + capability flags to the Maya role tag. */
    fun roleFor(
        className: String?,
        clickable: Boolean,
        checkable: Boolean,
        editable: Boolean,
        scrollable: Boolean,
        hasLabel: Boolean
    ): String {
        val cls = (className ?: "").substringAfterLast('.').lowercase()
        return when {
            editable || cls.contains("edittext") || cls.contains("autocomplete") -> "input"
            checkable || cls.contains("switch") || cls.contains("toggle") || cls.contains("checkbox") || cls.contains("radiobutton") -> "switch"
            cls.contains("tab") && clickable -> "tab"
            cls.contains("button") || cls.contains("imagebutton") -> "button"
            cls.contains("imageview") && clickable -> "icon"
            cls.contains("seekbar") || cls.contains("slider") -> "slider"
            scrollable && !clickable -> "list"
            clickable && cls.contains("textview") -> "link"
            clickable -> "item"
            hasLabel -> "text"
            else -> "view"
        }
    }

    /** Maya marks unlabeled icons whose resource id smells like a submit control. */
    fun sendHint(idHint: String?, label: String): String? {
        if (label != NO_LABEL) return null
        val hint = idHint?.lowercase() ?: return null
        return if (SEND_HINTS.any { hint.contains(it) }) "probably the SEND button" else null
    }

    fun formatLine(e: ElementLine): String {
        val sb = StringBuilder()
        sb.append('#').append(e.id).append("  ").append(e.label).append("  [").append(e.role).append(']')
        if (!e.state.isNullOrBlank()) sb.append(" [").append(e.state).append(']')
        if (!e.idHint.isNullOrBlank()) sb.append(" [id:").append(e.idHint).append(']')
        sb.append("  @ (").append(e.x).append(',').append(e.y).append(')')
        if (!e.note.isNullOrBlank()) sb.append("  — ").append(e.note)
        return sb.toString()
    }

    /**
     * Drops duplicates: same label+role within ~12 px of each other (wrapper containers that
     * repeat their only child's text). Ids are NOT renumbered here so #N stays stable with the
     * serializer's id→node map; callers should renumber before assigning ids if they want
     * contiguous numbers (see [renumber]).
     */
    fun dedupe(lines: List<ElementLine>): List<ElementLine> {
        val seen = HashSet<String>()
        val out = ArrayList<ElementLine>()
        for (l in lines) {
            val key = l.label.lowercase() + "|" + l.role + "|" + (l.x / 12) + "|" + (l.y / 12)
            if (seen.add(key)) out.add(l)
        }
        return out
    }

    fun renumber(lines: List<ElementLine>): List<ElementLine> =
        lines.mapIndexed { i, l -> l.copy(id = i + 1) }

    /**
     * Full text block: header, then at most [limit] lines, then a truncation note.
     * Static [text] rows are dropped first when the list is over the limit so actionable
     * elements survive.
     */
    fun render(header: String, lines: List<ElementLine>, limit: Int = DEFAULT_LIMIT): String {
        val kept: List<ElementLine> = if (lines.size <= limit) lines else {
            val actionable = lines.filter { it.role != "text" && it.role != "view" }
            if (actionable.size >= limit) actionable.take(limit)
            else actionable + lines.filter { it.role == "text" || it.role == "view" }.take(limit - actionable.size)
        }
        val ordered = kept.sortedBy { it.id }
        val sb = StringBuilder()
        if (header.isNotBlank()) sb.append(header).append('\n')
        for (l in ordered) sb.append(formatLine(l)).append('\n')
        if (lines.size > ordered.size) {
            sb.append("… ").append(lines.size - ordered.size)
                .append(" more elements not shown — scroll to reveal, or ask for a specific label.\n")
        }
        if (ordered.isEmpty()) sb.append("(no actionable elements found on this screen)\n")
        return sb.toString().trimEnd()
    }

    /**
     * Fraction of lines that are '(no label)' AND have no resource-id hint — Maya's
     * VISION-FIRST rule ("most elements are '(no label)' with no resource hint").
     */
    fun unlabeledRatio(lines: List<ElementLine>): Float {
        if (lines.isEmpty()) return 0f
        return lines.count { it.label == NO_LABEL && it.idHint.isNullOrBlank() }.toFloat() / lines.size.toFloat()
    }
}
