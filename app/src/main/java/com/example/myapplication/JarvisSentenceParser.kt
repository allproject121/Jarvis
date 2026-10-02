package com.example.myapplication

import android.util.Log

data class ParsedCommand(
    var primaryAction: String = "",
    var targetApp: String = "",
    var targetContact: String = "",
    var targetNumber: String = "",
    var messageText: String = "",
    var searchQuery: String = "",
    var actionVerb: String = "",
    val extraParams: MutableMap<String, String> = mutableMapOf(),
    var confidence: Float = 0.0f,
    var rawText: String = ""
)

class JarvisSentenceParser {

    companion object {
        private val TAG = "JarvisParser"

        // WhatsApp/Message keywords
        private val WA_KEYWORDS = listOf("whatsapp", "wa", "whats", "watsapp", "watsup")
        private val MSG_KEYWORDS = listOf("message", "msg", "send", "bhej", "bhejo", "likh", "likho", "bol", "bolo", "type", "type karo", "text", "kaho")
        private val SEND_KEYWORDS = listOf("send", "bhej", "bhejo", "forward", "forward karo")
        private val SEARCH_KEYWORDS = listOf("search", "dhoond", "dhoondh", "khoj", "find", "lookup", "talash", "search karo", "search kar")
        private val OPEN_KEYWORDS = listOf("open", "khol", "kholo", "chalao", "launch", "start", "start karo", "on karo")
        private val CALL_KEYWORDS = listOf("call", "phone", "phone karo", "call karo", "bulawa")
        private val VIDEO_KEYWORDS = listOf("video", "video call", "vcall", "vc")
        private val CONTACT_MARKERS = listOf(" ko ", " to ", " ke pass ", " ki chat ", " ki chat mein")
        private val CHAT_MARKERS = listOf("chat", "chat mein", "baat", "baat cheet", "conversation")
        private val TYPE_KEYWORDS = listOf("type", "type karo", "likh", "likho", "daal", "dalo", "paste", "paste karo")

        fun parse(cmd: String): ParsedCommand {
            val lower = cmd.lowercase().trim()
            val builder = ParsedCommand(rawText = cmd)

            // Step 1: Detect WhatsApp-related commands
            val hasWhatsApp = WA_KEYWORDS.any { lower.contains(it) }
            if (hasWhatsApp) {
                builder.targetApp = "whatsapp"
            }

            // Step 2: Detect open/launch intent
            if (hasWhatsApp || OPEN_KEYWORDS.any { lower.startsWith(it) || lower.contains(" $it ") }) {
                // Extract app name after open keywords
                if (builder.targetApp.isEmpty()) {
                    for (kw in OPEN_KEYWORDS) {
                        val idx = lower.indexOf(kw)
                        if (idx >= 0) {
                            val after = cmd.substring(idx + kw.length).trim()
                            val words = after.split("\\s+".toRegex())
                            if (words.isNotEmpty()) {
                                builder.targetApp = words.first().lowercase()
                                break
                            }
                        }
                    }
                }
            }

            // Step 3: Detect contact/name
            var contactName = ""
            for (marker in CONTACT_MARKERS) {
                val idx = lower.indexOf(marker)
                if (idx >= 0) {
                    val before = cmd.substring(0, idx).trim()
                    // Take the last 1-2 words before the marker
                    val words = before.split("\\s+".toRegex())
                    contactName = if (words.size >= 2) {
                        words.takeLast(2).joinToString(" ")
                    } else {
                        words.lastOrNull() ?: ""
                    }
                    // Check if it starts with a keyword and remove it
                    val allKeywords = WA_KEYWORDS + MSG_KEYWORDS + SEND_KEYWORDS + SEARCH_KEYWORDS + OPEN_KEYWORDS + listOf("karo", "kar do", "kar", "do", "de", "mein", "me", "par", "pe")
                    for (k in allKeywords) {
                        if (contactName.startsWith(k)) {
                            contactName = contactName.substring(k.length).trim()
                        }
                    }
                    if (contactName.isNotEmpty()) break
                }
            }

            // Also try extracting name after "search" + name pattern
            if (contactName.isEmpty()) {
                for (sk in SEARCH_KEYWORDS + listOf("search karo", "search kar")) {
                    val idx = lower.indexOf(sk)
                    if (idx >= 0) {
                        val after = cmd.substring(idx + sk.length).trim()
                        val words = after.split("\\s+".toRegex())
                        // Take words until we hit another keyword
                        val nameWords = mutableListOf<String>()
                        for (w in words) {
                            val wl = w.lowercase()
                            if (wl in listOf("karo", "kar", "do", "de", "mein", "ko", "to", "message", "msg", "send", "bhej", "likh", "bol", "call", "video", "chat")) break
                            if (wl in WA_KEYWORDS || wl in OPEN_KEYWORDS) break
                            nameWords.add(w)
                        }
                        if (nameWords.isNotEmpty()) {
                            contactName = nameWords.joinToString(" ")
                            break
                        }
                    }
                }
            }

            // Extract from "X ko" or "X to" patterns (X is the name, before ko/to)
            if (contactName.isEmpty()) {
                val koMatch = Regex("""(.+?)\s+ko\s+""").find(lower)
                if (koMatch != null) {
                    val candidate = koMatch.groupValues[1].trim()
                    val allKeywords = WA_KEYWORDS + MSG_KEYWORDS + SEND_KEYWORDS + SEARCH_KEYWORDS + OPEN_KEYWORDS + listOf("open", "kholo", "khol", "chalao", "launch", "start", "on karo", "karo", "kar do", "kar", "do", "de", "mein", "me", "par", "pe")
                    val clean = candidate.split("\\s+".toRegex()).filter { it.lowercase() !in allKeywords }.joinToString(" ")
                    if (clean.isNotEmpty() && clean.length > 1) contactName = clean
                }
            }

            builder.targetContact = contactName

            // Step 4: Detect message text
            var msgText = ""
            for (sep in listOf("message ", "msg ", "send ", "bhej ", "bhejo ", "likh ", "likho ", "bol ", "bolo ", "type ", "text ", "kaho ")) {
                val idx = lower.indexOf(sep)
                if (idx >= 0) {
                    val after = cmd.substring(idx + sep.length).trim()
                    // Remove trailing command words
                    msgText = after.replace(Regex("\\b(karo|kar do|kar|do|de|send|bhej|bhejo|likh|likho|bol|bolo)\\b", RegexOption.IGNORE_CASE), "").trim()
                    if (msgText.isNotEmpty()) break
                }
            }

            // Also check for message after contact marker
            if (msgText.isEmpty() && contactName.isNotEmpty()) {
                for (marker in CONTACT_MARKERS) {
                    val idx = lower.indexOf(marker)
                    if (idx >= 0) {
                        val after = cmd.substring(idx + marker.length).trim()
                        // Check if there's type keywords after
                        for (tk in TYPE_KEYWORDS) {
                            val tkIdx = after.indexOf(tk)
                            if (tkIdx >= 0) {
                                msgText = after.substring(tkIdx + tk.length).trim()
                                break
                            }
                        }
                        if (msgText.isEmpty()) {
                            // Check for message keywords in the remaining text
                            for (mk in MSG_KEYWORDS) {
                                val mkIdx = after.lowercase().indexOf(mk)
                                if (mkIdx >= 0) {
                                    msgText = after.substring(mkIdx + mk.length).trim()
                                    break
                                }
                            }
                        }
                    }
                }
            }

            builder.messageText = msgText.trimStart(' ', ',', '.', '!', ':')

            // Step 5: Detect search query
            if (builder.searchQuery.isEmpty()) {
                for (sk in SEARCH_KEYWORDS) {
                    val idx = lower.indexOf(sk)
                    if (idx >= 0) {
                        val after = cmd.substring(idx + sk.length).trim()
                        val words = after.split("\\s+".toRegex())
                        val queryWords = words.takeWhile { it.lowercase() !in listOf("karo", "kar", "do", "de", "mein", "ko", "to", "message", "msg") }
                        if (queryWords.isNotEmpty()) {
                            builder.searchQuery = queryWords.joinToString(" ")
                            break
                        }
                    }
                }
            }

            // Step 6: Detect primary action
            val actions = detectActions(lower)
            builder.primaryAction = actions.first
            builder.actionVerb = actions.second

            // Step 7: Detect number
            val numberMatch = Regex("""[\+\d][\d\s\-\(\)]{7,15}""").find(cmd)
            if (numberMatch != null) {
                builder.targetNumber = numberMatch.value.trim()
            }

            // Step 8: Calculate confidence
            builder.confidence = calculateConfidence(builder)

            Log.d(TAG, "Parsed: action=${builder.primaryAction}, app=${builder.targetApp}, contact=${builder.targetContact}, msg=${builder.messageText}, search=${builder.searchQuery}, num=${builder.targetNumber}, conf=${builder.confidence}")

            return builder
        }

        private fun detectActions(lower: String): Pair<String, String> {
            // Primary action detection based on keywords
            if (lower.contains("video call") || lower.contains("vcall")) return Pair("VIDEO_CALL", "video call")
            if (lower.contains("voice call") || lower.contains("phone call") || (lower.contains("call") && !lower.contains("video"))) return Pair("CALL", "call")
            if (lower.contains("send") || lower.contains("bhej") || lower.contains("forward")) return Pair("SEND", "send")
            if (lower.contains("search") || lower.contains("dhoond") || lower.contains("khoj") || lower.contains("find")) return Pair("SEARCH", "search")
            if (lower.contains("open") || lower.contains("khol") || lower.contains("chalao") || lower.contains("launch") || lower.contains("start")) return Pair("OPEN", "open")
            if (lower.contains("message") || lower.contains("msg") || lower.contains("likh") || lower.contains("bol") || lower.contains("type") || lower.contains("text")) return Pair("MESSAGE", "message")
            if (lower.contains("play") || lower.contains("chala")) return Pair("PLAY", "play")
            if (lower.contains("stop") || lower.contains("rok") || lower.contains("band")) return Pair("STOP", "stop")
            if (lower.contains("pause") || lower.contains("thahar") || lower.contains("ruk")) return Pair("PAUSE", "pause")
            if (lower.contains("next") || lower.contains("agle") || lower.contains("agla")) return Pair("NEXT", "next")
            if (lower.contains("previous") || lower.contains("pichle") || lower.contains("pichla")) return Pair("PREVIOUS", "previous")
            if (lower.contains("like") || lower.contains("pasand")) return Pair("LIKE", "like")
            if (lower.contains("comment") || lower.contains("reply")) return Pair("COMMENT", "comment")
            if (lower.contains("save") || lower.contains("download")) return Pair("SAVE", "save")
            if (lower.contains("share") || lower.contains("forward")) return Pair("SHARE", "share")
            if (lower.contains("follow") || lower.contains("subscribe")) return Pair("FOLLOW", "follow")
            if (lower.contains("copy")) return Pair("COPY", "copy")
            if (lower.contains("paste")) return Pair("PASTE", "paste")
            return Pair("UNKNOWN", "")
        }

        private fun calculateConfidence(p: ParsedCommand): Float {
            var score = 0.0f
            if (p.primaryAction.isNotEmpty() && p.primaryAction != "UNKNOWN") score += 0.3f
            if (p.targetApp.isNotEmpty()) score += 0.2f
            if (p.targetContact.isNotEmpty()) score += 0.25f
            if (p.messageText.isNotEmpty()) score += 0.15f
            if (p.searchQuery.isNotEmpty()) score += 0.1f
            if (p.targetNumber.isNotEmpty()) score += 0.2f
            return score.coerceAtMost(1.0f)
        }
    }
}
