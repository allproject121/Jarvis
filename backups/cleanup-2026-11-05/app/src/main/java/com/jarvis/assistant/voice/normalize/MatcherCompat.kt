package com.jarvis.assistant.voice.normalize

import java.util.regex.MatchResult
import java.util.regex.Matcher

/**
 * API-28-safe replacement for Matcher.replaceAll(Function), which only exists on API 34+.
 */
inline fun Matcher.replaceAllCompat(replacer: (MatchResult) -> String): String {
    reset()
    val sb = StringBuffer()
    while (find()) {
        appendReplacement(sb, Matcher.quoteReplacement(replacer(toMatchResult())))
    }
    appendTail(sb)
    return sb.toString()
}
