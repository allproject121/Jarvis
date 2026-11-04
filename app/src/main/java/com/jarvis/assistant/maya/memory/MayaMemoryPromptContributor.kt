package com.jarvis.assistant.maya.memory

import android.content.Context

/**
 * The block MemoryManager.getSystemPromptContext() appends: standing rules, the last three
 * episode summaries and the wiki index. Kept under [MAX_CHARS] (~800).
 */
object MayaMemoryPromptContributor {

    const val MAX_CHARS = 800

    /** Pure renderer (unit-testable). */
    fun compose(rulesBlock: String, episodeSummaries: List<String>, wikiIndex: String, maxChars: Int = MAX_CHARS): String {
        val sb = StringBuilder()
        if (rulesBlock.isNotBlank()) sb.append('\n').append(rulesBlock.take(maxChars - 50)).append('\n')
        if (episodeSummaries.isNotEmpty()) {
            val header = "[RECENT EPISODES — newest first; recall_episodes(query|date) for more]\n"
            if (sb.length + header.length < maxChars) {
                sb.append(header)
                for (s in episodeSummaries.take(3)) {
                    val line = "- ${s.take(120)}\n"
                    if (sb.length + line.length > maxChars) break
                    sb.append(line)
                }
            }
        }
        if (wikiIndex.isNotBlank() && sb.length + wikiIndex.length + 1 <= maxChars) sb.append(wikiIndex).append('\n')
        return sb.toString().trimEnd().take(maxChars)
    }

    fun render(context: Context): String = try {
        compose(
            rulesBlock = RulesStore.get(context).render(maxChars = 450),
            episodeSummaries = if (MemoryPrefs.isIncognito(context)) emptyList() else EpisodeLog.get(context).recentSummaries(3),
            wikiIndex = WikiCompiler.promptIndex(context, maxChars = 250)
        )
    } catch (e: Throwable) {
        ""
    }
}
