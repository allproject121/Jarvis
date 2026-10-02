package com.example.myapplication

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

class JarvisWhatsAppAgent(private val context: Context, private val bridge: AndroidBridge) {

    private val handler = Handler(Looper.getMainLooper())
    private val accessibilityService get() = JarvisAccessibilityService.instance

    interface AgentCallback {
        fun onProgress(message: String)
        fun onComplete(success: Boolean, message: String)
        fun onRequestInput(prompt: String, type: String)
    }

    data class ContactInfo(val name: String, val number: String, val isFromPhone: Boolean = false)

    fun executeAutonomousWhatsApp(query: String, callback: AgentCallback) {
        val contactName = extractContact(query)
        val message = extractMessage(query)

        if (contactName.isEmpty()) {
            callback.onRequestInput("Sir, kisse message bhejna hai?", "CONTACT")
            return
        }
        if (message.isEmpty()) {
            callback.onRequestInput("Sir, $contactName ko kya message bhejna hai?", "MESSAGE")
            return
        }

        // First check if contact exists in phone
        val contact = findContactInPhone(contactName)
        if (contact != null) {
            callback.onProgress("Sir, ${contact.name} mil gaye. Number: ${contact.number}")
            startStepByStepWorkflow(contact.name, message, callback)
        } else {
            // Check saved contacts in knowledge base
            val savedNum = bridge.getSavedContact(contactName)
            if (savedNum != null) {
                callback.onProgress("Sir, $contactName saved contacts mein mil gaye.")
                startStepByStepWorkflow(contactName, message, callback)
            } else {
                callback.onProgress("Sir, $contactName phone contacts mein nahi mile. WhatsApp mein search kar raha hoon.")
                startStepByStepWorkflow(contactName, message, callback)
            }
        }
    }

    fun findContactInPhone(name: String): ContactInfo? {
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$name%")

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val foundName = cursor.getString(0)
                    val foundNumber = cursor.getString(1)
                    Log.d("WhatsAppAgent", "Found contact: $foundName, $foundNumber")
                    return ContactInfo(foundName, foundNumber, true)
                }
            }
        } catch (e: Exception) {
            Log.e("WhatsAppAgent", "Error finding contact: ${e.message}")
        }
        return null
    }

    fun findContactByNumber(number: String): ContactInfo? {
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
                    val foundName = cursor.getString(0)
                    val foundNumber = cursor.getString(1)
                    return ContactInfo(foundName, foundNumber, true)
                }
            }
        } catch (e: Exception) {
            Log.e("WhatsAppAgent", "Error finding contact by number: ${e.message}")
        }
        return null
    }

    fun searchContactInWhatsAppUI(contactName: String, callback: AgentCallback) {
        startStepByStepWorkflow(contactName, "", callback)
    }

    private fun startStepByStepWorkflow(contact: String, message: String, callback: AgentCallback) {
        callback.onProgress("Opening WhatsApp...")
        bridge.openApp("WhatsApp")

        handler.postDelayed({
            clickSearch(contact, message, callback)
        }, 3000)
    }

    private fun clickSearch(contact: String, message: String, callback: AgentCallback) {
        val service = accessibilityService ?: run {
            callback.onComplete(false, "Accessibility Service active nahi hai.")
            return
        }
        callback.onProgress("Search button dhoondh raha hoon...")

        val searchClicked = service.findAndClickId("com.whatsapp:id/menuitem_search") ||
                service.findAndClickDescription("Search") ||
                service.findAndClickText("Search")

        if (searchClicked) {
            handler.postDelayed({
                typeContact(contact, message, callback)
            }, 1500)
        } else {
            callback.onComplete(false, "WhatsApp mein Search button nahi mila. Kya aapne WhatsApp khol liya hai?")
        }
    }

    private fun typeContact(contact: String, message: String, callback: AgentCallback) {
        val service = accessibilityService ?: return
        callback.onProgress("$contact dhoondh raha hoon...")

        if (service.findAndFocusTypable()) {
            handler.postDelayed({
                val typed = service.typeTextInFocused(contact)
                if (typed) {
                    handler.postDelayed({
                        if (message.isNotEmpty()) {
                            openFirstChat(message, callback)
                        } else {
                            callback.onComplete(true, "$contact ka search complete. Aap manually select kar sakte hain.")
                        }
                    }, 2000)
                } else {
                    callback.onComplete(false, "Contact type nahi ho paaya.")
                }
            }, 500)
        } else {
            // fallback: try fuzzy type
            if (service.findAndTypeTextFuzzy(contact)) {
                handler.postDelayed({
                    if (message.isNotEmpty()) {
                        openFirstChat(message, callback)
                    } else {
                        callback.onComplete(true, "$contact search ho gaya.")
                    }
                }, 2000)
            } else {
                callback.onComplete(false, "Search field nahi mila.")
            }
        }
    }

    private fun openFirstChat(message: String, callback: AgentCallback) {
        val service = accessibilityService ?: return
        callback.onProgress("Chat open kar raha hoon...")

        val root = service.rootInActiveWindow
        if (root == null) {
            callback.onComplete(false, "Screen root node nahi mila.")
            return
        }

        // Try different ways to find contact in search results
        val contactRows = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/contact_row_container")
        if (contactRows.isNotEmpty()) {
            contactRows[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            proceedToTypeMessage(message, callback)
            return
        }

        val contactNames = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/contact_name")
        if (contactNames.isNotEmpty()) {
            contactNames[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            proceedToTypeMessage(message, callback)
            return
        }

        // Fallback: try clicking first clickable result
        if (service.findAndClickId("com.whatsapp:id/name") ||
            service.findAndClickDescription("Contact") ||
            service.findAndClickText("Send message")) {
            proceedToTypeMessage(message, callback)
            return
        }

        callback.onComplete(false, "Chat open nahi ho saki. Aap manually select karein.")
    }

    private fun proceedToTypeMessage(message: String, callback: AgentCallback) {
        val service = accessibilityService ?: return
        handler.postDelayed({
            callback.onProgress("Message type kar raha hoon...")
            typeAndSendMessage(message, callback)
        }, 2500)
    }

    private fun typeAndSendMessage(message: String, callback: AgentCallback) {
        val service = accessibilityService ?: return

        // Try to use the message box
        val typed = service.findAndTypeTextFuzzy(message)
        if (typed) {
            handler.postDelayed({
                callback.onProgress("Send kar raha hoon...")
                val sent = service.findAndClickId("com.whatsapp:id/send") ||
                        service.findAndClickDescription("Send") ||
                        service.findAndClickText("Send")
                if (sent) {
                    callback.onComplete(true, "Message successfully sent!")
                } else {
                    // Try pressing enter as fallback
                    callback.onComplete(true, "Message type ho gaya. Aap Enter press karke send kar sakte hain.")
                }
            }, 1500)
        } else {
            callback.onComplete(false, "Message box nahi mila. Aap manually type karein.")
        }
    }

    fun extractContact(query: String): String {
        val q = query.lowercase().trim()
        // "Muneeb Ahmad ko msg bhejdo" -> "Muneeb Ahmad"
        val koIdx = q.indexOf(" ko ")
        if (koIdx >= 0) {
            return query.substring(0, koIdx).trim()
        }
        // "send message to Ali" -> "Ali"
        val toIdx = q.indexOf(" to ")
        if (toIdx >= 0) {
            val afterTo = q.substring(toIdx + 4).trim()
            // take everything until next keyword
            for (sep in listOf("message", "msg", "send", "bhej", "that", "says")) {
                val sepIdx = afterTo.indexOf(sep)
                if (sepIdx >= 0) {
                    return afterTo.substring(0, sepIdx).trim()
                }
            }
            return afterTo
        }
        // "search Ali" or "find Ali"
        if (q.startsWith("search ")) return query.removePrefix("search ").trim()
        if (q.startsWith("find ")) return query.removePrefix("find ").trim()
        // Fallback: split by separators and take first meaningful word
        val msgSeparators = listOf("message", "msg", "send", "bhej", "likh", "bol")
        for (sep in msgSeparators) {
            val idx = q.indexOf(sep)
            if (idx >= 0) {
                val before = query.substring(0, idx).trim()
                if (before.isNotEmpty()) {
                    val words = before.split("\\s+".toRegex())
                    return if (words.size > 1) before else words.lastOrNull() ?: ""
                }
            }
        }
        return ""
    }

    fun extractMessage(query: String): String {
        val q = query.lowercase().trim()
        // Find the message after separators
        val separators = listOf("message ", "msg ", "send ", "bhej ", "likh ", "bol ",
            "that ", "says ", "ye message ", "yeh ", "text ")
        var result = query
        var found = false
        for (sep in separators) {
            if (q.contains(sep)) {
                val idx = q.indexOf(sep)
                result = query.substring(idx + sep.length).trim()
                found = true
                break
            }
        }
        if (!found) {
            // Check without trailing space
            for (sep in listOf("message", "msg", "send", "bhej", "likh", "bol")) {
                val idx = q.indexOf(sep)
                if (idx >= 0) {
                    result = query.substring(idx + sep.length).trim()
                    found = true
                    break
                }
            }
        }
        if (!found) return ""
        result = result.replace(Regex("\\b(karo|do|de|kar|kar do|bhej|send|bolo|likho)\\b", RegexOption.IGNORE_CASE), "").trim()
        // Remove contact name if still present at start
        val contact = extractContact(query)
        if (contact.isNotEmpty() && result.lowercase().startsWith(contact.lowercase())) {
            result = result.removePrefix(contact).trim()
        }
        // Remove leading "to " "ko " "send " etc
        result = result.replace(Regex("^(to |ko |send |message |whatsapp |bolo |likho )+", RegexOption.IGNORE_CASE), "").trim()
        return result
    }
}
