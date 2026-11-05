package com.jarvis.assistant.maya.connectors

import android.content.Context
import android.webkit.MimeTypeMap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

/** Telegram Bot API client: sendMessage, sendDocument, chat detection via getUpdates. */
class TelegramClient(private val context: Context) {

    companion object {
        private const val SERVICE = "Telegram"
        private const val MAX_FILE_BYTES = 50L * 1024 * 1024
    }

    private fun base(): String {
        val token = ConnectorSecrets.telegramBotToken(context) ?: throw ConnectorHttp.notLinked(ConnectorService.TELEGRAM)
        return "https://api.telegram.org/bot$token"
    }

    private fun chatId(): String =
        ConnectorSecrets.telegramChatId(context)
            ?: throw ConnectorException("Bot ko pata nahi kis chat me bhejna hai. Telegram me apne bot ko koi bhi message bhejo, phir Settings → Connectors → Telegram me 'Detect chat' dabao.")

    private fun check(resp: ConnectorHttp.Resp): JSONObject {
        val j = resp.json()
        if (resp.ok && j.optBoolean("ok")) return j
        val desc = j.optString("description")
        val msg = when {
            resp.code == 404 -> "Bot token galat lag raha hai (Telegram ne 404 diya) — @BotFather se dobara copy karke Settings > Connectors > Telegram me daalo."
            resp.code == 401 -> "Bot token galat ya revoke ho gaya — @BotFather se naya lo aur Settings > Connectors > Telegram me daalo."
            resp.code == 400 && desc.contains("chat not found", true) ->
                "Bot is chat me bhej nahi sakta — Telegram me apne bot ko ek message bhejo (group me add karo), phir Settings > Connectors > Telegram me 'Detect chat' dabao."
            resp.code == 403 -> "Telegram ne ye message mana kar diya (bot blocked ya chat se nikal diya gaya)."
            resp.code == 429 -> "Telegram ne bahut messages ki wajah se thodi der rok diya — ek minute baad bhejo."
            resp.code in 500..599 -> "Telegram ke server pe dikkat hai (error ${resp.code})"
            else -> "Telegram se error ${resp.code}${if (desc.isNotBlank()) ": $desc" else ""}"
        }
        throw ConnectorException(msg, resp.code)
    }

    suspend fun getMe(): String {
        val j = check(ConnectorHttp.get(SERVICE, "${base()}/getMe"))
        val r = j.optJSONObject("result") ?: JSONObject()
        return "@" + r.optString("username").ifBlank { r.optString("first_name") }
    }

    /** Lightweight test: bot identity plus whether a chat is saved. */
    suspend fun testConnection(): String {
        val me = getMe()
        val chat = ConnectorSecrets.telegramChatId(context)
        val name = ConnectorSecrets.get(context, ConnectorSecrets.TELEGRAM_CHAT_NAME)
        return "Bot $me linked" + if (chat != null) " → chat ${name ?: chat}" else " — chat abhi detect nahi hua"
    }

    /** "Detect chat": reads the last updates and stores the newest chat id. */
    suspend fun detectChatId(): TelegramUpdates.Chat {
        val j = check(ConnectorHttp.get(SERVICE, "${base()}/getUpdates?limit=20"))
        val chat = TelegramUpdates.detectChat(j)
            ?: throw ConnectorException("Koi message nahi mila — pehle apne bot ko Telegram pe message bhejo")
        ConnectorSecrets.set(context, ConnectorSecrets.TELEGRAM_CHAT_ID, chat.id)
        ConnectorSecrets.set(context, ConnectorSecrets.TELEGRAM_CHAT_NAME, chat.title)
        return chat
    }

    suspend fun sendMessage(text: String, chatIdOverride: String? = null): Int {
        val chat = chatIdOverride?.takeIf { it.isNotBlank() } ?: chatId()
        var sent = 0
        for (chunk in TelegramUpdates.chunks(text)) {
            val body = JSONObject().put("chat_id", chat).put("text", chunk).put("disable_web_page_preview", true)
            check(ConnectorHttp.postJson(SERVICE, "${base()}/sendMessage", body))
            sent++
        }
        return sent
    }

    suspend fun sendDocument(path: String, caption: String?, chatIdOverride: String? = null): String {
        val file = File(path)
        if (!file.exists() || !file.isFile) throw ConnectorException("File nahi mili: $path")
        if (file.length() > MAX_FILE_BYTES) throw ConnectorException("File 50 MB se badi hai — Telegram bot itni badi file nahi bhej sakta.")
        val chat = chatIdOverride?.takeIf { it.isNotBlank() } ?: chatId()
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chat)
            .addFormDataPart("document", file.name, file.asRequestBody(mime.toMediaType()))
        if (!caption.isNullOrBlank()) multipart.addFormDataPart("caption", caption.take(1024))
        val j = check(ConnectorHttp.request(SERVICE, "POST", "${base()}/sendDocument", body = multipart.build()))
        return j.optJSONObject("result")?.optJSONObject("document")?.optString("file_name") ?: file.name
    }
}
