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
import java.util.Locale

/**
 * Lets JARVIS place phone calls by spoken contact name or raw phone number.
 */
object ContactCaller {

    data class Contact(val name: String, val number: String)

    fun hasContactsPermission(context: Context): Boolean = hasPermission(context, Manifest.permission.READ_CONTACTS)

    sealed class CallResult {
        data class Success(val contact: Contact) : CallResult()
        data class NoMatch(val query: String) : CallResult()
        data class MultipleMatches(val query: String, val matches: List<Contact>) : CallResult()
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

    /**
     * Looks up contacts stored on the device, matching case-insensitively and flexibly.
     */
    fun findMatches(context: Context, spokenName: String): List<Contact> {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return emptyList()

        val cleanQuery = cleanQueryString(spokenName)
        if (cleanQuery.isEmpty()) return emptyList()

        val rawCleanQuery = cleanQuery.replace(Regex("[^a-z0-9]"), "")

        val allContacts = mutableListOf<Contact>()
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
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
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numberIdx) ?: continue
                    if (name.isNotBlank() && number.isNotBlank()) {
                        allContacts.add(Contact(name.trim(), number.trim()))
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ContactCaller", "Contact query failed", e)
        }

        if (allContacts.isEmpty()) return emptyList()

        val distinctContacts = allContacts.distinctBy { it.number.filter(Char::isDigit) }

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
     * Places a call to whichever saved contact best matches [spokenName].
     */
    fun callContact(context: Context, spokenName: String): CallResult {
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

        // If there are multiple matches (either multiple contacts or multiple numbers for the same name),
        // autonomously inspect Call Log frequency & recency to pick the contact the user calls most!
        val resolvedTarget = if (matches.size > 1) {
            resolveOptimalContactByCallHistory(context, matches)
        } else {
            matches.first()
        }

        return placeCall(context, resolvedTarget)
    }

    /**
     * Inspects device Call Logs and user habits to autonomously select the most dialed contact.
     */
    private fun resolveOptimalContactByCallHistory(context: Context, candidates: List<Contact>): Contact {
        if (candidates.size <= 1) return candidates.first()

        val candidateScores = mutableMapOf<Contact, Int>()
        candidates.forEach { candidateScores[it] = 0 }

        if (hasPermission(context, Manifest.permission.READ_CALL_LOG)) {
            try {
                val resolver = context.contentResolver
                val projection = arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.TYPE
                )
                // Query the latest 200 calls
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

                        val date = it.getLong(dateIdx)
                        val type = it.getInt(typeIdx)

                        for (cand in candidates) {
                            val normCandNumber = cand.number.filter { c -> c.isDigit() }.takeLast(10)
                            if (normCandNumber == normCallNumber) {
                                var score = if (type == CallLog.Calls.OUTGOING_TYPE) 20 else 10
                                if (date > sevenDaysAgo) score += 30
                                else if (date > thirtyDaysAgo) score += 15
                                candidateScores[cand] = (candidateScores[cand] ?: 0) + score
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ContactCaller", "Could not analyze call logs: ${e.message}")
            }
        }

        // Return candidate with the highest frequency and recency score
        val best = candidateScores.maxByOrNull { it.value }
        if (best != null && best.value > 0) {
            android.util.Log.i("ContactCaller", "Autonomously resolved '${best.key.name}' (${best.key.number}) with score ${best.value}")
            return best.key
        }

        return candidates.first()
    }

    private fun placeCall(context: Context, target: Contact): CallResult {
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(target.number)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityLauncherHelper.startActivitySafely(context, intent)
            recordCallHabitAndMemory(context, target)
            CallResult.Success(target)
        } catch (e: Exception) {
            android.util.Log.e("ContactCaller", "Failed to place call to ${target.name}", e)
            CallResult.CallFailed
        }
    }

    private fun recordCallHabitAndMemory(context: Context, contact: Contact) {
        try {
            // 1. Record habit pattern
            com.jarvis.assistant.ai.learning.HabitPatternProfiler.getInstance(context)
                .recordAction("call_${contact.name.lowercase().replace(" ", "_")}")

            // 2. Index in Vector Semantic Memory for continuous self-learning
            CoroutineScope(Dispatchers.IO).launch {
                val memoryText = "User frequently calls ${contact.name} on phone number ${contact.number}"
                com.jarvis.assistant.ai.memory.VectorMemoryEngine.getInstance(context, this)
                    .indexMemory(System.currentTimeMillis(), memoryText, "contact_habit")
            }
        } catch (e: Exception) {
            android.util.Log.w("ContactCaller", "Failed recording call habit: ${e.message}")
        }
    }
}
