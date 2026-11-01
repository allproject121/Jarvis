package com.jarvis.assistant.automation.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Resolves human contact names ("Mom", "Alice Smith", "Doctor") to dialable phone numbers
 * via the Android Contacts Provider.
 */
object ContactResolver {

    fun isPhoneNumber(raw: String): Boolean {
        val normalized = normalize(raw)
        if (normalized.length < 3 || normalized.any { !it.isDigit() && it != '+' }) return false
        val alphanumeric = raw.count { it.isLetterOrDigit() }
        val digits = raw.count { it.isDigit() }
        return digits * 2 >= alphanumeric
    }

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val plus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        return (if (plus) "+" else "") + digits
    }

    fun resolveByName(context: Context, name: String): String? {
        // A blank name would turn the LIKE filter into "%%" and match the
        // first contact in the address book — never guess a recipient.
        if (name.isBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? COLLATE NOCASE",
                arrayOf("%$name%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(1) else null
            }
        }.getOrNull()
    }
}
