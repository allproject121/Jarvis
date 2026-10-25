package com.jarvis.assistant.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/**
 * Lets JARVIS place phone calls by spoken contact name or raw phone number.
 *
 * Resolution strategy (in priority order) for an ambiguous name:
 *  1. JARVIS Memory Core — the contact affinity store JARVIS writes on every
 *     call it places ("who does the user actually call?"). This is the signal
 *     the user expects to be honoured.
 *  2. Vector Memory Core — facts JARVIS previously wrote such as
 *     "User frequently calls Rahul Sharma on phone number 98765 43210".
 *  3. Learned habits — the habit profiler's call_* entries.
 *  4. OS Call Log — frequency/recency of dialled numbers.
 *
 * If one candidate wins by a clear margin the call is placed autonomously
 * (and the reason is reported). If the signal is genuinely ambiguous the
 * candidates are returned so the caller can ask one short, specific question
 * instead of dumping the whole address book.
 */
object ContactCaller {

    data class Contact(val name: String, val number: String, val label: String = "") {
        /** Last 4 digits — enough for a human to tell two same-name contacts apart. */
        val last4: String get() = number.filter { it.isDigit() }.takeLast(4)

        /** Normalised key used by the affinity store (last 10 digits). */
        val affinityKey: String get() = number.filter { it.isDigit() }.takeLast(10)
    }

    /**
     * A disambiguation candidate with the evidence that was used to rank it.
     */
    data class Candidate(
        val contact: Contact,
        val affinityScore: Int = 0,
        val memoryScore: Int = 0,
        val habitScore: Int = 0,
        val callLogScore: Int = 0,
        val affinityCount: Int = 0,
        val lastCalledEpochMs: Long = 0L,
        val reason: String = ""
    ) {
        val totalScore: Int get() = affinityScore + memoryScore + habitScore + callLogScore
    }

    private data class AffinityRecord(val count: Int, val lastEpochMs: Long, val name: String)

    fun hasContactsPermission(context: Context): Boolean = hasPermission(context, Manifest.permission.READ_CONTACTS)

    sealed class CallResult {
        data class Success(val contact: Contact, val reason: String = "") : CallResult()
        data class NoMatch(val query: String) : CallResult()
        data class MultipleMatches(val query: String, val candidates: List<Candidate>) : CallResult()
        object MissingContactsPermission : CallResult()
        object MissingCallPermission : CallResult()
        object CallFailed : CallResult()
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun cleanQueryString(raw: String): String {
        var q = raw.trim().lowercase()
        val prefixes = listOf(
            "message ", "send message to ", "send whatsapp to ", "whatsapp ", "open contact ", "open ",
            "call ", "phone ", "dial ", "contact ", "to ", "my ", "please ", "karo ", "lagao ", "bhejo "
        )
        val suffixes = listOf(
            " ko message bhejo", " ko whatsapp karo", " ko call karo", " ko call lagao", " ko phone karo",
            " ko message karo", " ko message", " ko whatsapp", " ko call", " ko phone", " ke paas",
            " ko", " ji", " bhai", " bhaiya", " sir", " beta", " madam", " didi", " uncle", " aunty"
        )
        var changed = true
        while (changed) {
            changed = false
            for (prefix in prefixes) {
                if (q.startsWith(prefix)) {
                    q = q.removePrefix(prefix).trim()
                    changed = true
                }
            }
            for (suffix in suffixes) {
                if (q.endsWith(suffix)) {
                    q = q.removeSuffix(suffix).trim()
                    changed = true
                }
            }
        }
        return q
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Contact loading
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Loads every saved contact with its phone label ("Mobile", "Home", "Work"…)
     * so two contacts that share a name can still be told apart by a human.
     */
    private fun loadAllContacts(context: Context): List<Contact> {
        val allContacts = mutableListOf<Contact>()
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY
        )

        try {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                val primaryIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY)

                val res = context.resources
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numberIdx) ?: continue
                    if (name.isBlank() || number.isBlank()) continue

                    val type = cursor.getInt(typeIdx)
                    val isPrimary = cursor.getInt(primaryIdx) == 1
                    val rawLabel = runCatching {
                        ContactsContract.CommonDataKinds.Phone.getTypeLabel(res, type, null).toString()
                    }.getOrDefault("")

                    val label = buildString {
                        if (isPrimary) append("Primary")
                        if (rawLabel.isNotBlank() && !rawLabel.equals("Main", ignoreCase = true)) {
                            if (isNotEmpty()) append(" • ")
                            append(rawLabel)
                        }
                    }

                    allContacts.add(Contact(name.trim(), number.trim(), label))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ContactCaller", "Contact query failed", e)
        }

        return allContacts.distinctBy { it.affinityKey }
    }

    /**
     * Looks up contacts stored on the device, matching case-insensitively and flexibly.
     */
    fun findMatches(context: Context, spokenName: String): List<Contact> {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return emptyList()

        val cleanQuery = cleanQueryString(spokenName)
        if (cleanQuery.isEmpty()) return emptyList()

        val rawCleanQuery = cleanQuery.replace(Regex("[^a-z0-9]"), "")

        val distinctContacts = loadAllContacts(context)
        if (distinctContacts.isEmpty()) return emptyList()

        val aliases = when (cleanQuery) {
            "mom", "mum", "mummy", "mother", "maa", "ammi" -> setOf("mom", "mum", "mummy", "mother", "maa", "ammi", "mommy")
            "dad", "daddy", "father", "papa", "abbu" -> setOf("dad", "daddy", "father", "papa", "abbu", "baba")
            else -> emptySet()
        }
        if (aliases.isNotEmpty()) {
            val aliasMatches = distinctContacts.filter { contact ->
                contact.name.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
                    .any { token -> token in aliases }
            }
            if (aliasMatches.isNotEmpty()) return aliasMatches
        }

        // 1. Exact match (case-insensitive)
        val exactMatches = distinctContacts.filter { it.name.lowercase() == cleanQuery }
        if (exactMatches.isNotEmpty()) return exactMatches

        // 2. Exact alphanumeric match (ignoring spaces & punctuation)
        val alphaMatches = distinctContacts.filter {
            it.name.lowercase().replace(Regex("[^a-z0-9]"), "") == rawCleanQuery
        }
        if (alphaMatches.isNotEmpty()) return alphaMatches

        // 3. Contact full name starts with cleanQuery
        if (cleanQuery.length >= 2) {
            val startsWithMatches = distinctContacts.filter {
                it.name.lowercase().startsWith(cleanQuery)
            }
            if (startsWithMatches.isNotEmpty()) return startsWithMatches
        }

        // 4. Any word in contact name exactly equals cleanQuery
        val exactWordMatches = distinctContacts.filter { c ->
            val words = c.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
            words.any { word -> word == cleanQuery }
        }
        if (exactWordMatches.isNotEmpty()) return exactWordMatches

        // 5. Any word in contact name starts with cleanQuery
        if (cleanQuery.length >= 3) {
            val wordStartsWithMatches = distinctContacts.filter { c ->
                val words = c.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
                words.any { word -> word.startsWith(cleanQuery) }
            }
            if (wordStartsWithMatches.isNotEmpty()) return wordStartsWithMatches
        }

        // 6. Query contains exact word matching a contact name word
        val queryWords = cleanQuery.split(Regex("\\s+")).filter { it.length >= 3 }
        if (queryWords.isNotEmpty()) {
            val queryWordMatches = distinctContacts.filter { c ->
                val contactWords = c.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
                contactWords.any { cWord -> queryWords.contains(cWord) }
            }
            if (queryWordMatches.isNotEmpty()) return queryWordMatches
        }

        return emptyList()
    }

    /**
     * Ranked contact search used by the `find_contacts` tool.
     *
     * Unlike [findMatches] this never fails — it returns the closest contacts
     * ranked by name similarity plus how often the user actually calls them.
     */
    fun searchContacts(context: Context, query: String, limit: Int = 8): List<Candidate> {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return emptyList()
        val all = loadAllContacts(context)
        if (all.isEmpty()) return emptyList()

        val cleanQuery = cleanQueryString(query).lowercase(Locale.ROOT)
        val affinity = readAffinity(context)
        val callLogScores = readCallLogScores(context, all)

        val scored = all.map { contact ->
            val nameScore = nameSimilarity(cleanQuery, contact.name.lowercase(Locale.ROOT))
            val aff = affinity[contact.affinityKey]
            val affScore = aff?.let { affinityScore(it) } ?: 0
            val logScore = callLogScores[contact.affinityKey] ?: 0
            val total = nameScore * 60 + affScore + logScore
            Candidate(
                contact = contact,
                affinityScore = affScore,
                callLogScore = logScore,
                affinityCount = aff?.count ?: 0,
                lastCalledEpochMs = aff?.lastEpochMs ?: 0L,
                reason = buildReason(aff, affScore, logScore)
            ) to total
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }

        return scored.take(limit).map { it.first }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Calling
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Places a call to whichever saved contact best matches [spokenName].
     *
     * @param explicitNumber optional number supplied by the model after a
     *        disambiguation round (e.g. the user answered "the office one").
     */
    fun callContact(context: Context, spokenName: String, explicitNumber: String? = null): CallResult {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return CallResult.MissingContactsPermission
        if (!hasPermission(context, Manifest.permission.CALL_PHONE)) return CallResult.MissingCallPermission

        val rawInput = spokenName.trim()
        if (rawInput.isEmpty()) return CallResult.NoMatch(spokenName)

        val digitsOnly = rawInput.filter { it.isDigit() || it == '+' }
        if (digitsOnly.length >= 3 && rawInput.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }) {
            return placeCall(context, Contact(rawInput, digitsOnly))
        }

        val matches = findMatches(context, spokenName)
        if (matches.isEmpty()) {
            return CallResult.NoMatch(spokenName)
        }

        // The model already knows which contact it wants (disambiguation round).
        val wantedDigits: String = explicitNumber.orEmpty().filter { it.isDigit() }.takeLast(10)
        if (wantedDigits.isNotEmpty()) {
            val explicit = matches.firstOrNull { it.affinityKey == wantedDigits }
            if (explicit != null) {
                return placeCall(context, explicit, "Matched the contact you selected (${explicit.label.ifBlank { "saved contact" }}).")
            }
            // The number was not among the name matches — honour the number anyway.
            val fallbackName = matches.firstOrNull { it.name.equals(spokenName.trim(), ignoreCase = true) }?.name
                ?: spokenName.trim()
            return placeCall(context, Contact(fallbackName, explicitNumber!!), "Called the number you specified.")
        }

        if (matches.size == 1) {
            return placeCall(context, matches.first())
        }

        // Multiple same-name candidates → rank them with everything JARVIS knows.
        val candidates = rankCandidates(context, matches)
        val sorted = candidates.sortedByDescending { it.totalScore }
        val best = sorted.first()
        val second = sorted.getOrNull(1)

        val clearWinner = best.totalScore >= MIN_AUTONOMOUS_SCORE &&
            (second == null || best.totalScore >= second.totalScore * AUTONOMY_RATIO)

        if (clearWinner) {
            val reason = best.reason.ifBlank { "Best match from your contacts and calling history." }
            android.util.Log.i(
                "ContactCaller",
                "Autonomously resolved '${best.contact.name}' (${best.contact.number}) " +
                    "score=${best.totalScore} runnerUp=${second?.totalScore ?: 0} via $reason"
            )
            return placeCall(context, best.contact, reason)
        }

        android.util.Log.i(
            "ContactCaller",
            "Ambiguous contact '${spokenName}': ${sorted.size} candidates, best=${best.totalScore} second=${second?.totalScore ?: 0} — returning candidates for clarification."
        )
        return CallResult.MultipleMatches(spokenName, sorted.take(MAX_DISAMBIGUATION_CANDIDATES))
    }

    /**
     * Scores every candidate using JARVIS's own memory first, then the OS call log.
     */
    private fun rankCandidates(context: Context, candidates: List<Contact>): List<Candidate> {
        val affinity = readAffinity(context)
        val memoryNumbers = readMemoryPhoneNumbers(context)
        val callLogScores = readCallLogScores(context, candidates)
        val habitCounts = readHabitCounts(context)

        return candidates.map { contact ->
            val aff = affinity[contact.affinityKey]
            val affScore = aff?.let { affinityScore(it) } ?: 0
            val memHits = memoryNumbers[contact.affinityKey] ?: 0
            val memScore = (memHits * 8).coerceAtMost(32)
            val habitKey = "call_${contact.name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_")}_${contact.last4}"
            val habitHits = habitCounts[habitKey] ?: 0
            val habitScore = (habitHits * 4).coerceAtMost(24)
            val logScore = (callLogScores[contact.affinityKey] ?: 0).coerceAtMost(60)

            Candidate(
                contact = contact,
                affinityScore = affScore,
                memoryScore = memScore,
                habitScore = habitScore,
                callLogScore = logScore,
                affinityCount = aff?.count ?: 0,
                lastCalledEpochMs = aff?.lastEpochMs ?: 0L,
                reason = buildReason(aff, affScore, memHits, logScore)
            )
        }
    }

    private fun buildReason(
        aff: AffinityRecord?,
        affScore: Int,
        memoryHits: Int = 0,
        logScore: Int = 0
    ): String {
        val parts = mutableListOf<String>()
        if (aff != null && aff.count > 0) {
            val ago = relativeTime(aff.lastEpochMs)
            parts.add("you called this number ${aff.count} time${if (aff.count == 1) "" else "s"}$ago")
        }
        if (memoryHits > 0) {
            parts.add("recorded in your memory $memoryHits time${if (memoryHits == 1) "" else "s"}")
        }
        if (logScore > 0) {
            parts.add("frequent in your call history")
        }
        return if (parts.isEmpty()) "matching saved contact" else parts.joinToString(", ")
    }

    private fun relativeTime(epochMs: Long): String {
        if (epochMs <= 0L) return ""
        val diff = System.currentTimeMillis() - epochMs
        val days = diff / (24L * 60 * 60 * 1000)
        return when {
            days <= 0 -> " today"
            days == 1L -> " yesterday"
            days < 7 -> " $days days ago"
            days < 30 -> " ${days / 7} week${if (days / 7 == 1L) "" else "s"} ago"
            else -> " ${days / 30} month${if (days / 30 == 1L) "" else "s"} ago"
        }
    }

    /**
     * Reads the OS call log and returns a raw score per normalised number key.
     */
    private fun readCallLogScores(context: Context, candidates: List<Contact>): Map<String, Int> {
        val scores = mutableMapOf<String, Int>()
        if (candidates.isEmpty()) return scores
        if (!hasPermission(context, Manifest.permission.READ_CALL_LOG)) return scores

        val candidateKeys = candidates.map { it.affinityKey }.toSet()
        try {
            val resolver = context.contentResolver
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.DATE,
                CallLog.Calls.TYPE
            )
            val cursor = resolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 200"
            )

            cursor?.use {
                val numberIdx = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val dateIdx = it.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val typeIdx = it.getColumnIndexOrThrow(CallLog.Calls.TYPE)

                val now = System.currentTimeMillis()
                val sevenDaysAgo = now - (7L * 24 * 60 * 60 * 1000)
                val thirtyDaysAgo = now - (30L * 24 * 60 * 60 * 1000)

                while (it.moveToNext()) {
                    val rawNumber = it.getString(numberIdx) ?: continue
                    val normCallNumber = rawNumber.filter { c -> c.isDigit() }.takeLast(10)
                    if (normCallNumber.length < 6) continue
                    if (!candidateKeys.contains(normCallNumber)) continue

                    val date = it.getLong(dateIdx)
                    val type = it.getInt(typeIdx)

                    var score = if (type == CallLog.Calls.OUTGOING_TYPE) 20 else 10
                    if (date > sevenDaysAgo) score += 30
                    else if (date > thirtyDaysAgo) score += 15
                    scores[normCallNumber] = (scores[normCallNumber] ?: 0) + score
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ContactCaller", "Could not analyze call logs: ${e.message}")
        }
        return scores
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JARVIS Memory Core — contact affinity (write once, read back forever)
    // ─────────────────────────────────────────────────────────────────────────

    private fun affinityPrefs(context: Context) =
        context.getSharedPreferences(AFFINITY_PREFS, Context.MODE_PRIVATE)

    /**
     * Reads the contact affinity store: how many times JARVIS has called each
     * number, and when it last did. This is the "who does the user actually
     * call" memory that used to be written but never read back.
     */
    private fun readAffinity(context: Context): Map<String, AffinityRecord> {
        val out = mutableMapOf<String, AffinityRecord>()
        val raw = affinityPrefs(context).getString(AFFINITY_KEY, null) ?: return out
        try {
            val root = JSONObject(raw)
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = root.optJSONObject(key) ?: continue
                out[key] = AffinityRecord(
                    count = entry.optInt("count", 0),
                    lastEpochMs = entry.optLong("last", 0L),
                    name = entry.optString("name", "")
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("ContactCaller", "Failed reading contact affinity: ${e.message}")
        }
        return out
    }

    private fun recordAffinity(context: Context, contact: Contact) {
        try {
            val key = contact.affinityKey
            if (key.length < 6) return
            val prefs = affinityPrefs(context)
            val root = try {
                JSONObject(prefs.getString(AFFINITY_KEY, null) ?: "{}")
            } catch (_: Exception) {
                JSONObject()
            }
            val entry = root.optJSONObject(key) ?: JSONObject()
            val count = entry.optInt("count", 0) + 1
            entry.put("count", count)
            entry.put("last", System.currentTimeMillis())
            entry.put("name", contact.name)
            root.put(key, entry)
            prefs.edit().putString(AFFINITY_KEY, root.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.w("ContactCaller", "Failed recording contact affinity: ${e.message}")
        }
    }

    private fun affinityScore(record: AffinityRecord): Int {
        val countScore = (record.count.coerceAtMost(12)) * 6
        val age = System.currentTimeMillis() - record.lastEpochMs
        val recency = when {
            age <= 7L * 24 * 60 * 60 * 1000 -> 30
            age <= 30L * 24 * 60 * 60 * 1000 -> 18
            age <= 90L * 24 * 60 * 60 * 1000 -> 8
            else -> 0
        }
        return countScore + recency
    }

    /**
     * Reads phone numbers mentioned in the vector memory core, so facts like
     * "User frequently calls Rahul Sharma on phone number 98765 43210" become
     * usable evidence instead of write-only text.
     */
    private fun readMemoryPhoneNumbers(context: Context): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        try {
            val engine = com.jarvis.assistant.ai.memory.VectorMemoryEngine.getInstance(context, CoroutineScope(Dispatchers.IO))
            val raw = engine.snapshotRawMemories()
            val phoneRegex = Regex("""(?:phone number|number|ph no)\s*[:=-]?\s*([+0-9][0-9\s-]{5,20})""", RegexOption.IGNORE_CASE)
            for (text in raw) {
                if (!text.contains("call", ignoreCase = true) && !text.contains("phone", ignoreCase = true)) continue
                for (m in phoneRegex.findAll(text)) {
                    val digits = m.groupValues[1].filter { it.isDigit() }.takeLast(10)
                    if (digits.length < 6) continue
                    counts[digits] = (counts[digits] ?: 0) + 1
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ContactCaller", "Failed reading memory phone numbers: ${e.message}")
        }
        return counts
    }

    /**
     * Reads the learned call-habit counts. Habit keys include the last four
     * digits so that two contacts sharing a name remain distinguishable.
     */
    private fun readHabitCounts(context: Context): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        try {
            val profiler = com.jarvis.assistant.ai.learning.HabitPatternProfiler.getInstance(context)
            counts.putAll(profiler.getActionCountsSnapshot())
        } catch (e: Exception) {
            android.util.Log.w("ContactCaller", "Failed reading habit counts: ${e.message}")
        }
        return counts
    }

    private fun nameSimilarity(query: String, name: String): Float {
        if (query.isBlank()) return 0f
        if (name == query) return 1f
        if (name.startsWith(query)) return 0.85f
        if (name.contains(query)) return 0.7f
        val queryWords = query.split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }
        if (queryWords.isEmpty()) return 0f
        val nameWords = name.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        val hits = queryWords.count { qw -> nameWords.any { nw -> nw.startsWith(qw) } }
        return if (hits == 0) 0f else (hits.toFloat() / queryWords.size) * 0.6f
    }

    private fun placeCall(context: Context, target: Contact, reason: String = ""): CallResult {
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(target.number)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = ActivityLauncherHelper.startActivitySafely(context, intent)
            if (!launched) {
                context.startActivity(intent)
            }
            recordCallHabitAndMemory(context, target)
            CallResult.Success(target, reason)
        } catch (e: Exception) {
            android.util.Log.e("ContactCaller", "Failed to place call to ${target.name}", e)
            CallResult.CallFailed
        }
    }

    private fun recordCallHabitAndMemory(context: Context, contact: Contact) {
        try {
            // 1. Record habit pattern — keyed by name AND last 4 digits so that
            //    two contacts sharing a name stay distinguishable.
            com.jarvis.assistant.ai.learning.HabitPatternProfiler.getInstance(context)
                .recordAction("call_${contact.name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_")}_${contact.last4}")

            // 2. Record in the contact affinity store (read back during resolution)
            recordAffinity(context, contact)

            // 3. Index in Vector Semantic Memory for continuous self-learning
            CoroutineScope(Dispatchers.IO).launch {
                val memoryText = "User frequently calls ${contact.name} on phone number ${contact.number}"
                com.jarvis.assistant.ai.memory.VectorMemoryEngine.getInstance(context, this)
                    .indexMemory(System.currentTimeMillis(), memoryText, "contact_habit")
            }
        } catch (e: Exception) {
            android.util.Log.w("ContactCaller", "Failed recording call habit: ${e.message}")
        }
    }

    const val AFFINITY_PREFS = "jarvis_contact_affinity"
    const val AFFINITY_KEY = "contact_affinity"
    const val MIN_AUTONOMOUS_SCORE = 25
    const val AUTONOMY_RATIO = 1.6f
    const val MAX_DISAMBIGUATION_CANDIDATES = 5
}
