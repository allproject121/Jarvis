package com.example.myapplication

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class JarvisContactManager(private val context: Context, private val memory: JarvisKnowledgeBase) {

    data class Contact(
        val name: String,
        val number: String,
        val isPhoneContact: Boolean = false,
        val isSavedContact: Boolean = false
    )

    fun findContact(query: String): Contact? {
        val lower = query.lowercase().trim()
        // 1. Check saved contacts (in-app)
        val savedContacts = getAllSavedContacts()
        savedContacts.forEach { (name, number) ->
            if (name.lowercase() == lower || name.lowercase().contains(lower)) {
                return Contact(name, number, isSavedContact = true)
            }
        }

        // 2. Check phone contacts
        val phoneContact = findInPhoneContacts(lower)
        if (phoneContact != null) return phoneContact

        // 3. Fuzzy match in saved contacts
        for ((name, number) in savedContacts) {
            val nameWords = name.lowercase().split(" ")
            val queryWords = lower.split(" ")
            if (nameWords.any { queryWords.contains(it) } || queryWords.any { nameWords.contains(it) }) {
                return Contact(name, number, isSavedContact = true)
            }
        }

        // 4. Fuzzy match in phone contacts
        return findInPhoneContactsFuzzy(lower)
    }

    fun findContactByNumber(number: String): Contact? {
        // Remove non-digit characters
        val cleanNum = number.replace(Regex("[^\\d]"), "")

        // Check saved contacts
        val savedContacts = getAllSavedContacts()
        for ((name, savedNum) in savedContacts) {
            if (savedNum.replace(Regex("[^\\d]"), "").contains(cleanNum) ||
                cleanNum.contains(savedNum.replace(Regex("[^\\d]"), ""))) {
                return Contact(name, savedNum, isSavedContact = true)
            }
        }

        // Check phone contacts
        return findInPhoneContactsByNumber(cleanNum)
    }

    private fun findInPhoneContacts(query: String): Contact? {
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$query%")
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return Contact(cursor.getString(0), cursor.getString(1), isPhoneContact = true)
                }
            }
        } catch (e: Exception) {
            Log.e("ContactManager", "Phone contact search error: ${e.message}")
        }
        return null
    }

    private fun findInPhoneContactsFuzzy(query: String): Contact? {
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0).lowercase()
                    if (name.contains(query) || query.contains(name)) {
                        return Contact(cursor.getString(0), cursor.getString(1), isPhoneContact = true)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ContactManager", "Fuzzy search error: ${e.message}")
        }
        return null
    }

    private fun findInPhoneContactsByNumber(number: String): Contact? {
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            val selectionArgs = arrayOf("%$number%")
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return Contact(cursor.getString(0), cursor.getString(1), isPhoneContact = true)
                }
            }
        } catch (e: Exception) {
            Log.e("ContactManager", "Number search error: ${e.message}")
        }
        return null
    }

    fun getAllPhoneContacts(): List<Contact> {
        val contacts = mutableListOf<Contact>()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    val number = cursor.getString(1)
                    if (name.isNotBlank() && number.isNotBlank()) {
                        contacts.add(Contact(name, number, isPhoneContact = true))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ContactManager", "Load contacts error: ${e.message}")
        }
        return contacts
    }

    fun getAllPhoneContactsJSON(): String {
        val result = JSONArray()
        getAllPhoneContacts().forEach { contact ->
            val obj = JSONObject()
            obj.put("name", contact.name)
            obj.put("number", contact.number)
            result.put(obj)
        }
        return result.toString()
    }

    fun saveContact(name: String, number: String) {
        memory.rememberContact(name, number)
        Log.d("ContactManager", "Contact saved: $name -> $number")
    }

    fun deleteSavedContact(name: String): Boolean {
        val contacts = getSavedContactsMap().toMutableMap()
        val key = contacts.keys.find { it.lowercase() == name.lowercase() }
        return if (key != null) {
            contacts.remove(key)
            memory.remember("saved_contacts", JSONObject(contacts as Map<String, String>).toString())
            Log.d("ContactManager", "Contact deleted: $key")
            true
        } else {
            false
        }
    }

    fun getAllSavedContacts(): List<Pair<String, String>> {
        return memory.getAllContacts().toList()
    }

    private fun getSavedContactsMap(): Map<String, String> {
        return memory.getAllContacts()
    }

    fun getAllSavedContactsJSON(): String {
        val result = JSONArray()
        getAllSavedContacts().forEach { (name, number) ->
            val obj = JSONObject()
            obj.put("name", name)
            obj.put("number", number)
            result.put(obj)
        }
        return result.toString()
    }

    fun getAllContactsJSON(): String {
        val result = JSONArray()
        val seen = mutableSetOf<String>()

        // Add saved contacts first
        getAllSavedContacts().forEach { (name, number) ->
            val obj = JSONObject()
            obj.put("name", name)
            obj.put("number", number)
            obj.put("type", "saved")
            result.put(obj)
            seen.add(number)
        }

        // Add phone contacts (avoid duplicates)
        getAllPhoneContacts().forEach { contact ->
            if (contact.number !in seen) {
                val obj = JSONObject()
                obj.put("name", contact.name)
                obj.put("number", contact.number)
                obj.put("type", "phone")
                result.put(obj)
                seen.add(contact.number)
            }
        }

        return result.toString()
    }

    fun getContactSummary(): String {
        val savedCount = getAllSavedContacts().size
        val phoneCount = getAllPhoneContacts().size
        return "Sir, aapke $savedCount saved contacts hain aur $phoneCount phone contacts hain."
    }

    fun addContactViaVoice(input: String): String {
        // Parse: "Sara ka number 1234567890 save karo"
        val lower = input.lowercase()
        val patterns = listOf(
            Regex("(.+?)\\s*ka\\s*number\\s*([\\d]+)\\s*save\\s*karo"),
            Regex("(.+?)\\s*ko\\s*save\\s*karo\\s*([\\d]+)"),
            Regex("save\\s*contact\\s*(.+?)\\s*([\\d]+)")
        )

        for (pattern in patterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val name = match.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                val number = match.groupValues[2].trim()
                saveContact(name, number)
                return "Sir, $name ka number $number save kar diya."
            }
        }
        return "Sir, main samajh nahi paya. Aise bolein: Sara ka number 1234567890 save karo"
    }

    fun deleteContactViaVoice(input: String): String {
        val lower = input.lowercase()
        val patterns = listOf(
            Regex("(.+?)\\s*ko\\s*delete\\s*karo"),
            Regex("(.+?)\\s*ka\\s*contact\\s*delete\\s*karo"),
            Regex("(.+?)\\s*ko\\s*hatao"),
            Regex("delete\\s*contact\\s*(.+)")
        )

        for (pattern in patterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val name = match.groupValues[1].trim()
                if (deleteSavedContact(name)) {
                    return "Sir, $name ka contact delete kar diya."
                }
                return "Sir, $name saved contacts mein nahi mila."
            }
        }
        return "Sir, main samajh nahi paya. Aise bolein: Sara ko delete karo"
    }
}
