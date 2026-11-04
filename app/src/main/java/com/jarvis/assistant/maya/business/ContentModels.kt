package com.jarvis.assistant.maya.business

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One piece of social content written by the content engine (ported from Maya's content
 * calendar). It stays a *suggestion* until the owner approves it: Draft -> Approved ->
 * Scheduled -> Published. Nothing here is ever posted by the app on its own.
 */
data class ContentItem(
    val id: String = UUID.randomUUID().toString().take(8),
    val kind: String,
    val platform: String,
    val pillar: String = "",
    val title: String,
    val hook: String = "",
    val scenes: List<String> = emptyList(),
    val slides: List<String> = emptyList(),
    val caption: String = "",
    val hashtags: String = "",
    val cta: String = "",
    val body: String = "",
    val status: String = STATUS_DRAFT,
    val scheduledAt: Long = 0L,
    val publishedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val topic: String = ""
) {
    companion object {
        const val STATUS_DRAFT = "Draft"
        const val STATUS_APPROVED = "Approved"
        const val STATUS_SCHEDULED = "Scheduled"
        const val STATUS_PUBLISHED = "Published"

        val KINDS = listOf("post", "reel_script", "carousel", "story", "blog_brief")
        val PLATFORMS = listOf("instagram", "facebook", "linkedin", "x", "whatsapp", "youtube")

        fun fromJson(o: JSONObject): ContentItem = ContentItem(
            id = o.optString("id", UUID.randomUUID().toString().take(8)),
            kind = o.optString("kind", "post"),
            platform = o.optString("platform", "instagram"),
            pillar = o.optString("pillar", ""),
            title = o.optString("title", ""),
            hook = o.optString("hook", ""),
            scenes = jsonToList(o.optJSONArray("scenes")),
            slides = jsonToList(o.optJSONArray("slides")),
            caption = o.optString("caption", ""),
            hashtags = o.optString("hashtags", ""),
            cta = o.optString("cta", ""),
            body = o.optString("body", ""),
            status = o.optString("status", STATUS_DRAFT),
            scheduledAt = o.optLong("scheduledAt", 0L),
            publishedAt = o.optLong("publishedAt", 0L),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            topic = o.optString("topic", "")
        )

        private fun jsonToList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) out.add(arr.optString(i, ""))
            return out
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        put("platform", platform)
        put("pillar", pillar)
        put("title", title)
        put("hook", hook)
        put("scenes", JSONArray(scenes))
        put("slides", JSONArray(slides))
        put("caption", caption)
        put("hashtags", hashtags)
        put("cta", cta)
        put("body", body)
        put("status", status)
        put("scheduledAt", scheduledAt)
        put("publishedAt", publishedAt)
        put("createdAt", createdAt)
        put("topic", topic)
    }

    /** The text that goes into the share sheet / clipboard when the owner publishes. */
    fun shareText(): String {
        val sb = StringBuilder()
        if (caption.isNotBlank()) sb.append(caption.trim())
        else if (hook.isNotBlank()) sb.append(hook.trim())
        if (cta.isNotBlank() && !sb.contains(cta.trim())) sb.append("\n\n").append(cta.trim())
        if (hashtags.isNotBlank()) sb.append("\n\n").append(hashtags.trim())
        return sb.toString().trim()
    }

    /** Full readable rendering (what the assistant reads back to the owner). */
    fun render(): String {
        val sb = StringBuilder()
        sb.append("[").append(id).append("] ").append(kind).append(" / ").append(platform)
        if (pillar.isNotBlank()) sb.append(" / ").append(pillar)
        sb.append(" - ").append(status).append('\n')
        sb.append("TITLE: ").append(title).append('\n')
        if (hook.isNotBlank()) sb.append("HOOK: ").append(hook).append('\n')
        scenes.forEach { sb.append("SCENE: ").append(it).append('\n') }
        slides.forEach { sb.append("SLIDE: ").append(it).append('\n') }
        if (caption.isNotBlank()) sb.append("CAPTION: ").append(caption).append('\n')
        if (body.isNotBlank()) sb.append("BODY:\n").append(body).append('\n')
        if (hashtags.isNotBlank()) sb.append("HASHTAGS: ").append(hashtags).append('\n')
        if (cta.isNotBlank()) sb.append("CTA: ").append(cta).append('\n')
        return sb.toString().trimEnd()
    }
}

/**
 * Parser for Maya's `@@ITEM` content format (see BusinessPrompts.CONTENT_FORMAT):
 *
 * ```
 * @@ITEM
 * KIND: post | reel_script | carousel | story | blog_brief
 * PLATFORM: instagram | facebook | linkedin | x | whatsapp | youtube
 * PILLAR: ...
 * TITLE: ...
 * HOOK: ...
 * SCENE: time | what we see | on-screen text | voiceover   (one line per scene)
 * SLIDE: heading | supporting line                         (one line per slide)
 * CAPTION: ... (may continue on following lines)
 * BODY: ... (multi-line, blog_brief)
 * HASHTAGS: #a #b
 * CTA: ...
 * ```
 * Pure Kotlin + org.json so it is unit-testable.
 */
object ContentItemParser {

    private val LABELS = setOf(
        "KIND", "PLATFORM", "PILLAR", "TITLE", "HOOK", "SCENE", "SLIDE", "CAPTION", "HASHTAGS", "CTA", "BODY"
    )
    private val MULTILINE = setOf("CAPTION", "BODY", "HOOK", "TITLE")
    private val LABEL_LINE = Regex("^\\s*[*_#\\-\\s]*([A-Z]{3,8})\\s*:\\s*(.*)$")

    private class Builder {
        val fields = LinkedHashMap<String, StringBuilder>()
        val scenes = ArrayList<String>()
        val slides = ArrayList<String>()
        var lastLabel: String? = null

        fun isEmpty(): Boolean =
            fields.values.all { it.isBlank() } && scenes.isEmpty() && slides.isEmpty()

        fun field(name: String): String = fields[name]?.toString()?.trim().orEmpty()
    }

    fun parse(
        raw: String,
        defaultKind: String = "post",
        defaultPlatform: String = "instagram",
        topic: String = ""
    ): List<ContentItem> {
        if (raw.isBlank()) return emptyList()
        val items = ArrayList<ContentItem>()
        var current: Builder? = null

        fun flush() {
            val b = current ?: return
            if (!b.isEmpty()) items.add(toItem(b, defaultKind, defaultPlatform, topic))
            current = null
        }

        for (rawLine in raw.lines()) {
            val line = rawLine.replace("**", "").trimEnd()
            val trimmed = line.trim()
            if (trimmed.equals("@@ITEM", ignoreCase = true) || trimmed.startsWith("@@ITEM", ignoreCase = true)) {
                flush()
                current = Builder()
                continue
            }
            val m = LABEL_LINE.matchEntire(line)
            val label = m?.groupValues?.get(1)?.uppercase()
            if (m != null && label != null && label in LABELS) {
                if (current == null) current = Builder()
                val b = current!!
                val value = m.groupValues[2].trim()
                when (label) {
                    "SCENE" -> if (value.isNotBlank()) b.scenes.add(value)
                    "SLIDE" -> if (value.isNotBlank()) b.slides.add(value)
                    else -> {
                        val sb = b.fields.getOrPut(label) { StringBuilder() }
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(value)
                    }
                }
                b.lastLabel = label
                continue
            }
            // Continuation line for a multi-line field.
            val b = current ?: continue
            val last = b.lastLabel ?: continue
            if (trimmed.isEmpty()) {
                if (last in MULTILINE) b.fields[last]?.append('\n')
                continue
            }
            if (last in MULTILINE) {
                val sb = b.fields.getOrPut(last) { StringBuilder() }
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(trimmed)
            } else if (last == "SCENE") {
                b.scenes.add(trimmed)
            } else if (last == "SLIDE") {
                b.slides.add(trimmed)
            }
        }
        flush()
        return items
    }

    private fun toItem(b: Builder, defaultKind: String, defaultPlatform: String, topic: String): ContentItem {
        val kindRaw = b.field("KIND").lowercase().replace(' ', '_').replace('-', '_')
        val kind = when {
            kindRaw in ContentItem.KINDS -> kindRaw
            kindRaw.startsWith("reel") -> "reel_script"
            kindRaw.startsWith("blog") -> "blog_brief"
            kindRaw.startsWith("carousel") -> "carousel"
            kindRaw.startsWith("story") -> "story"
            kindRaw.isBlank() -> defaultKind
            else -> defaultKind
        }
        val platRaw = b.field("PLATFORM").lowercase().trim()
        val platform = when {
            platRaw in ContentItem.PLATFORMS -> platRaw
            platRaw.startsWith("insta") -> "instagram"
            platRaw.startsWith("face") || platRaw == "fb" -> "facebook"
            platRaw.startsWith("linked") -> "linkedin"
            platRaw == "twitter" || platRaw.startsWith("x ") || platRaw == "x" -> "x"
            platRaw.startsWith("whats") -> "whatsapp"
            platRaw.startsWith("you") || platRaw == "yt" -> "youtube"
            else -> defaultPlatform
        }
        val caption = b.field("CAPTION").trim()
        val hook = b.field("HOOK").trim()
        val title = b.field("TITLE").ifBlank {
            hook.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: caption.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: "Untitled"
        }
        return ContentItem(
            kind = kind,
            platform = platform,
            pillar = b.field("PILLAR"),
            title = title,
            hook = hook,
            scenes = b.scenes.toList(),
            slides = b.slides.toList(),
            caption = caption,
            hashtags = b.field("HASHTAGS").replace('\n', ' ').trim(),
            cta = b.field("CTA").trim(),
            body = b.field("BODY").trim(),
            topic = topic
        )
    }
}
