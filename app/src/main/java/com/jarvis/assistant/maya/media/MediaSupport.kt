package com.jarvis.assistant.maya.media

import java.net.URLEncoder
import java.util.Locale

/*
 * Pure-Kotlin helpers for the media pack. No Android imports here so they are unit-testable
 * (see app/src/test/java/com/jarvis/assistant/maya/media/).
 */

/** Width/height presets understood by generate_image / generate_quote_image. */
data class ImageSize(val width: Int, val height: Int) {
    val label: String get() = "${width}x${height}"

    companion object {
        val SQUARE = ImageSize(1024, 1024)
        val PORTRAIT = ImageSize(768, 1152)
        val LANDSCAPE = ImageSize(1152, 768)
        val STORY = ImageSize(1080, 1920)
        val POST = ImageSize(1080, 1350)

        /**
         * Accepts "square", "portrait", "landscape", "instagram_story"/"story"/"status",
         * "instagram_post"/"post", "wide", "1024x1024", "1080*1920". Unknown -> [default].
         */
        fun parse(raw: String?, default: ImageSize = SQUARE): ImageSize {
            val s = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (s.isBlank()) return default
            val m = Regex("^(\\d{2,5})\\s*[x×*]\\s*(\\d{2,5})$").find(s)
            if (m != null) {
                val w = m.groupValues[1].toInt().coerceIn(64, 4096)
                val h = m.groupValues[2].toInt().coerceIn(64, 4096)
                return ImageSize(w, h)
            }
            return when {
                s.contains("story") || s.contains("status") || s == "9:16" || s == "vertical" -> STORY
                s.contains("post") || s == "4:5" -> POST
                s.contains("portrait") || s == "3:4" || s == "tall" -> PORTRAIT
                s.contains("landscape") || s.contains("wide") || s == "16:9" || s == "4:3" || s.contains("wallpaper") -> LANDSCAPE
                s.contains("square") || s == "1:1" -> SQUARE
                else -> default
            }
        }
    }
}

/** Builds Pollinations image URLs (no key needed; an optional token raises limits). */
object PollinationsUrl {
    const val BASE = "https://image.pollinations.ai/prompt/"

    fun encode(prompt: String): String =
        URLEncoder.encode(prompt.trim(), "UTF-8").replace("+", "%20")

    fun build(
        prompt: String,
        width: Int = 1024,
        height: Int = 1024,
        seed: Long? = null,
        model: String = "flux",
        nologo: Boolean = true,
        token: String? = null
    ): String {
        val sb = StringBuilder(BASE)
        sb.append(encode(prompt))
        sb.append("?width=").append(width.coerceIn(64, 4096))
        sb.append("&height=").append(height.coerceIn(64, 4096))
        if (seed != null) sb.append("&seed=").append(seed)
        if (nologo) sb.append("&nologo=true")
        sb.append("&model=").append(URLEncoder.encode(model.ifBlank { "flux" }, "UTF-8"))
        if (!token.isNullOrBlank()) sb.append("&token=").append(URLEncoder.encode(token.trim(), "UTF-8"))
        return sb.toString()
    }

    /** Folds an optional style hint into the prompt ("... , style: cinematic"). */
    fun promptWithStyle(prompt: String, style: String?): String {
        val p = prompt.trim()
        val s = style?.trim().orEmpty()
        if (s.isBlank() || p.lowercase(Locale.ROOT).contains(s.lowercase(Locale.ROOT))) return p
        return "$p, $s style"
    }
}


/** Reel/story guidance text ported from Maya (verb-loop steps + hard rules). */
object ReelGuidance {
    const val RULES =
        "NIYAM: Share dabane se PEHLE user ko caption aur kya post hoga wo batao aur saaf 'haan' ka intezaar karo. " +
            "Share ek hi baar dabao. \"Post ho gayi\" tabhi bolo jab screen pe upload ya posted dikhe."

    fun instagram(caption: String, videoName: String): String =
        "Instagram ka reel composer khul gaya hai aur video '$videoName' usme pehle se load hai. Abhi kuch post NAHI hua, " +
            "aur koi doosri video mat lo. Aage tum khud chalo: read_screen_elements se dekho, phir tap_element / type_text. " +
            "Editor me: neeche-daayein 'Next'; 'Edit video'; aur ek row me Add audio, Add text, Voiceover, Captions, Add sticker. " +
            "'Next' ke baad 'New reel' screen: caption field ('Write a caption and add hashtags…'), 'Tag people', 'Add location', " +
            "'Add AI label', 'Save draft' aur 'Share'. Caption yeh likhni hai: \"${caption.trim()}\". $RULES " +
            "Chhodna ho to: Back, Back, phir 'Start over', phir 'Back to Home' — adhoori reel screen pe mat chhodo."

    fun facebook(caption: String, videoName: String): String =
        "Facebook ka reel composer khul gaya hai aur video '$videoName' usme pehle se load hai. Abhi kuch post NAHI hua, " +
            "aur koi doosri video mat lo. Facebook ke ye screens naape nahi gaye hain, isliye har step pe read_screen_elements se " +
            "padh kar hi chalo (labels na dikhen to screen share se dekho): aam taur pe Next, phir description field, phir publish button. " +
            "'Share to …' wale switch, Boost aur Accounts Center ko mat chhuo. Caption yeh likhni hai: \"${caption.trim()}\". $RULES " +
            "Chhodna ho to Back dabao aur 'Discard' lo — adhoori reel screen pe mat chhodo."

    /** Goal text handed to the screen pack's run_agent_task when it is installed. */
    fun agentGoal(platform: String, caption: String, videoName: String): String =
        "Post the reel that is ALREADY loaded in ${platform.replaceFirstChar { it.uppercase() }}'s reel composer, with the given caption. " +
            "The video ($videoName) is ALREADY loaded in the app's own reel screen: never open the app's create or camera screen and never " +
            "choose any other video. Type the caption exactly: \"${caption.trim()}\". Before pressing Share/Publish, tell the user the caption " +
            "and what will be posted and wait for a clear 'haan'. Press the publish button at most ONCE — if it is unclear whether it went, " +
            "stop and say so rather than pressing again. Never switch on cross-posting, Boost, or anything that changes the audience. " +
            "The task is complete only when the app shows the reel uploading or posted."

    fun story(platform: String): String {
        val name = when (platform.lowercase(Locale.ROOT)) {
            "facebook", "fb" -> "Facebook"
            "whatsapp", "wa", "status" -> "WhatsApp"
            else -> "Instagram"
        }
        return "$name ki story screen khul gayi hai aur photo load hai. Abhi kuch post NAHI hua. read_screen_elements se dekho, " +
            "phir 'Your story' / 'Share' / 'Send' button dhoondo. $RULES"
    }

    fun platformPackage(platform: String): String = when (platform.lowercase(Locale.ROOT)) {
        "facebook", "fb" -> "com.facebook.katana"
        "whatsapp", "wa", "status" -> "com.whatsapp"
        else -> "com.instagram.android"
    }

    fun cleanCaption(caption: String?): String = caption?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
}

/** Parses "90", "1:30", "2 min", "1h 20m", "45 seconds", "do minute" into seconds. */
object TimerParsing {
    private val hindiNumbers = mapOf(
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5,
        "chhe" to 6, "che" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10, "pandrah" to 15,
        "bees" to 20, "tees" to 30, "aadha" to 0, "adha" to 0
    )

    fun parseSeconds(raw: String?): Int {
        if (raw == null) return 0
        var s = raw.trim().lowercase(Locale.ROOT)
        if (s.isBlank()) return 0
        s.toDoubleOrNull()?.let { return it.toInt().coerceAtLeast(0) }
        // mm:ss or hh:mm:ss
        val colon = Regex("^(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?$").find(s)
        if (colon != null) {
            val a = colon.groupValues[1].toInt()
            val b = colon.groupValues[2].toInt()
            val c = colon.groupValues[3].toIntOrNull()
            return if (c != null) a * 3600 + b * 60 + c else a * 60 + b
        }
        for ((word, n) in hindiNumbers) s = s.replace(Regex("\\b$word\\b"), n.toString())
        if (Regex("\\b(aadha|adha|half)\\b").containsMatchIn(raw.lowercase(Locale.ROOT))) {
            if (raw.lowercase(Locale.ROOT).contains("ghant") || raw.lowercase(Locale.ROOT).contains("hour")) return 1800
            return 30
        }
        var total = 0
        var matched = false
        val unitRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|h|ghante|ghanta|minutes?|mins?|m|minute|minat|seconds?|secs?|s|second)\\b")
        for (m in unitRegex.findAll(s)) {
            matched = true
            val v = m.groupValues[1].toDouble()
            val unit = m.groupValues[2]
            total += when {
                unit.startsWith("h") || unit.startsWith("gh") -> (v * 3600).toInt()
                unit.startsWith("m") -> (v * 60).toInt()
                else -> v.toInt()
            }
        }
        if (matched) return total
        val lone = Regex("(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (s.contains("min")) lone * 60 else lone
    }

    fun describe(seconds: Int): String {
        if (seconds <= 0) return "0 seconds"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val sec = seconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts.add("$h hour${if (h > 1) "s" else ""}")
        if (m > 0) parts.add("$m minute${if (m > 1) "s" else ""}")
        if (sec > 0) parts.add("$sec second${if (sec > 1) "s" else ""}")
        return parts.joinToString(" ")
    }
}

/** Phone-number normalisation shared with the WhatsApp tools (Indian default country code). */
object PhoneNumbers {
    fun normalize(raw: String?): String {
        val digits = raw.orEmpty().filter { it.isDigit() }
        return when {
            digits.isEmpty() -> ""
            digits.length == 10 -> "91$digits"
            digits.length == 11 && digits.startsWith("0") -> "91" + digits.substring(1)
            else -> digits
        }
    }

    fun looksLikeNumber(raw: String?): Boolean =
        raw.orEmpty().replace(Regex("[^0-9+]"), "").length >= 7 &&
            raw.orEmpty().count { it.isLetter() } == 0

    fun jid(raw: String?): String = normalize(raw) + "@s.whatsapp.net"
}
