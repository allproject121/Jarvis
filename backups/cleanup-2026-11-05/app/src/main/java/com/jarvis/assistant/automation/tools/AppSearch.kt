package com.jarvis.assistant.automation.tools

import android.content.Context
import android.content.Intent

data class AppEntry(
    val label: String,
    val packageName: String,
    val hasLauncher: Boolean,
    val launchIntent: Intent?
)

data class AppSearchResult(
    val matches: List<AppEntry>,
    val ambiguous: Boolean
)

object AppSearch {

    private val ALIASES = mapOf(
        "yt" to "youtube",
        "insta" to "instagram",
        "ig" to "instagram",
        "fb" to "facebook",
        "wa" to "whatsapp",
        "playstore" to "play store"
    )

    /** Launchable apps, de-duplicated by package and cached (see [AppIndex]). */
    fun getInstalledApps(context: Context): List<AppEntry> = AppIndex.all(context)

    fun search(entries: List<AppEntry>, rawQuery: String): AppSearchResult {
        val query = rawQuery.trim()
        if (query.isEmpty()) return AppSearchResult(emptyList(), false)

        val q = query.lowercase()
        val qNorm = normalize(q)

        val aliasTarget = ALIASES[q]
        if (aliasTarget != null) {
            val aliased = entries.distinctBy { it.packageName }
                .filter { it.label.lowercase() == aliasTarget || normalize(it.label.lowercase()) == normalize(aliasTarget) }
                .sortedBy { it.label }
            if (aliased.isNotEmpty()) {
                return AppSearchResult(aliased, ambiguous = aliased.size > 1)
            }
        }

        val scored = entries.distinctBy { it.packageName }.mapNotNull { entry ->
            val score = score(entry, q, qNorm) ?: return@mapNotNull null
            entry to score
        }.sortedByDescending { it.second }

        val top = scored.firstOrNull()?.second ?: return AppSearchResult(emptyList(), false)
        val matches = scored.takeWhile { top - it.second <= 10 }.map { it.first }
        val ambiguous = matches.size > 1 && (scored.getOrNull(1)?.second ?: 0) >= top - 15
        return AppSearchResult(matches.take(5), ambiguous)
    }

    private fun score(entry: AppEntry, q: String, qNorm: String): Int? {
        val l = entry.label.lowercase()
        val lNorm = normalize(l)
        val pkg = entry.packageName.lowercase()

        if (pkg == q) return 100
        if (l == q) return 95
        if (lNorm == qNorm) return 90
        if (l.startsWith(q)) return 80
        if (l.contains(q)) return 70
        if (lNorm.contains(qNorm)) return 65
        return null
    }

    private fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }
}
