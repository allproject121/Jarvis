package com.jarvis.assistant.maya.memory

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A standing instruction the user gave ("ab se hamesha ...", "jawab chhote rakho"). */
data class Rule(
    val id: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    /** Per-rule revision, bumped on every edit. */
    val version: Int = 1
)

/** The on-disk shape of `maya_rules.json`. [version] is bumped on every mutation ("rules_have"). */
data class RulesFile(val version: Int = 0, val rules: List<Rule> = emptyList())

/**
 * Pure JSON codec + prompt rendering for rules (no Android dependencies so it is unit-testable).
 */
object RulesCodec {

    const val HEADER = "[RULES — Boss ne diye]"

    fun parse(json: String?): RulesFile {
        if (json.isNullOrBlank()) return RulesFile()
        return try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("rules") ?: JSONArray()
            val rules = ArrayList<Rule>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text").trim()
                if (text.isEmpty()) continue
                rules += Rule(
                    id = o.optString("id").ifBlank { newId() },
                    text = text,
                    createdAt = o.optLong("createdAt", 0L),
                    updatedAt = o.optLong("updatedAt", o.optLong("createdAt", 0L)),
                    version = o.optInt("version", 1)
                )
            }
            RulesFile(root.optInt("version", 0), rules)
        } catch (e: Exception) {
            RulesFile()
        }
    }

    fun toJsonArray(rules: List<Rule>): JSONArray {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("text", r.text)
                put("createdAt", r.createdAt)
                put("updatedAt", r.updatedAt)
                put("version", r.version)
            })
        }
        return arr
    }

    fun serialize(file: RulesFile): String {
        val root = JSONObject()
        root.put("version", file.version)
        root.put("rules", toJsonArray(file.rules))
        return root.toString(2)
    }

    /** Renders the block injected into every prompt; empty string when there are no rules. */
    fun render(rules: List<Rule>, maxChars: Int = 600): String {
        if (rules.isEmpty()) return ""
        val sb = StringBuilder(HEADER).append('\n')
        for ((index, r) in rules.withIndex()) {
            val line = "- ${r.text.trim()}\n"
            if (sb.length + line.length > maxChars) {
                sb.append("- (+${rules.size - index} more rules; call list_rules)\n")
                break
            }
            sb.append(line)
        }
        return sb.toString().trimEnd()
    }

    fun newId(): String = "r" + UUID.randomUUID().toString().replace("-", "").take(8)
}

/**
 * Rules persisted at `filesDir/maya_rules.json`. All mutations bump the file version so a
 * prompt cache keyed on it ("rules_have" in Maya) can notice the change.
 */
class RulesStore(private val file: File) {

    @Volatile
    private var cache: RulesFile? = null

    private fun load(): RulesFile = cache ?: synchronized(this) {
        cache ?: RulesCodec.parse(runCatching { if (file.exists()) file.readText() else null }.getOrNull()).also { cache = it }
    }

    private fun persist(next: RulesFile) {
        synchronized(this) {
            cache = next
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(RulesCodec.serialize(next))
            }.onFailure { Log.w(TAG, "Could not write rules: ${it.message}") }
        }
    }

    fun list(): List<Rule> = load().rules

    fun version(): Int = load().version

    fun get(id: String): Rule? = list().firstOrNull { it.id == id }

    /** Adds a rule; an identical (case-insensitive) rule is returned instead of duplicated. */
    fun add(text: String): Rule {
        val clean = text.trim()
        require(clean.isNotEmpty()) { "Rule text is empty" }
        val current = load()
        current.rules.firstOrNull { it.text.equals(clean, ignoreCase = true) }?.let { return it }
        val now = System.currentTimeMillis()
        val rule = Rule(RulesCodec.newId(), clean, now, now, 1)
        persist(RulesFile(current.version + 1, current.rules + rule))
        return rule
    }

    fun update(id: String, text: String): Rule? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val current = load()
        val idx = current.rules.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val old = current.rules[idx]
        val updated = old.copy(text = clean, updatedAt = System.currentTimeMillis(), version = old.version + 1)
        val next = current.rules.toMutableList().also { it[idx] = updated }
        persist(RulesFile(current.version + 1, next))
        return updated
    }

    /** Maya's `update_rule(find, rule)`: locate by id or by a few words of the old rule. */
    fun findByWords(find: String): Rule? {
        val q = find.trim().lowercase()
        if (q.isEmpty()) return null
        val rules = list()
        rules.firstOrNull { it.id == find.trim() }?.let { return it }
        rules.firstOrNull { it.text.lowercase().contains(q) }?.let { return it }
        val words = q.split(Regex("\\s+")).filter { it.length > 2 }
        if (words.isEmpty()) return null
        return rules.map { r -> r to words.count { r.text.lowercase().contains(it) } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first
    }

    fun delete(id: String): Boolean {
        val current = load()
        if (current.rules.none { it.id == id }) return false
        persist(RulesFile(current.version + 1, current.rules.filterNot { it.id == id }))
        return true
    }

    fun replaceAll(rules: List<Rule>) {
        persist(RulesFile(load().version + 1, rules))
    }

    /** Merge rules from a backup; duplicates (same text) are skipped. Returns the number added. */
    fun merge(incoming: List<Rule>): Int {
        val current = load()
        val existing = current.rules.map { it.text.lowercase() }.toMutableSet()
        val usedIds = current.rules.map { it.id }.toMutableSet()
        val added = ArrayList<Rule>()
        for (r in incoming) {
            val key = r.text.trim().lowercase()
            if (key.isEmpty() || key in existing) continue
            existing += key
            val id = if (r.id.isBlank() || r.id in usedIds) RulesCodec.newId() else r.id
            usedIds += id
            added += r.copy(id = id, text = r.text.trim())
        }
        if (added.isNotEmpty()) persist(RulesFile(current.version + 1, current.rules + added))
        return added.size
    }

    fun render(maxChars: Int = 600): String = RulesCodec.render(list(), maxChars)

    companion object {
        private const val TAG = "RulesStore"
        const val FILE_NAME = "maya_rules.json"

        @Volatile
        private var instance: RulesStore? = null

        fun get(context: Context): RulesStore = instance ?: synchronized(this) {
            instance ?: RulesStore(File(context.applicationContext.filesDir, FILE_NAME)).also { instance = it }
        }
    }
}

/** Prompt block for the standing rules, used by MemoryManager.getSystemPromptContext(). */
object RulesPromptContributor {
    fun render(context: Context, maxChars: Int = 600): String = RulesStore.get(context).render(maxChars)
}
