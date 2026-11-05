package com.jarvis.assistant.maya.connectors

import android.content.Context
import android.webkit.MimeTypeMap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** Google Drive v3 client on the narrow `drive.file` scope (files Jarvis created or the user picked). */
class GoogleDriveClient(private val context: Context) {

    companion object {
        private const val SERVICE = "Google Drive"
        private const val FILES = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,mimeType,webViewLink"
        private const val FIELDS = "id,name,mimeType,modifiedTime,size,webViewLink,parents"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val DOC_MIME = "application/vnd.google-apps.document"
        private const val MAX_READ_BYTES = 2L * 1024 * 1024
    }

    data class DriveFile(val id: String, val name: String, val mimeType: String, val modified: String, val link: String, val size: Long)

    private suspend fun headers(): Map<String, String> =
        mapOf("Authorization" to "Bearer ${GoogleOAuthHelper.accessToken(context)}")

    private fun check(resp: ConnectorHttp.Resp, notFound: String? = null): ConnectorHttp.Resp {
        if (resp.ok) return resp
        val msg = when (resp.code) {
            401 -> "Google ka access token expire/galat hai — Settings > Connectors me Google dobara link karo (ya naya token paste karo)"
            403 -> if (resp.body.contains("insufficientFilePermissions", true) || resp.body.contains("notFound", true))
                       "Ye file meri pahunch me nahi hai. Main sirf wahi files dekh sakti hoon jo maine banayi ya jo tumne mujhe di (drive.file scope)."
                   else "Google Drive ne mana kar diya: ${ConnectorHttp.errorSnippet(resp.body)} — Drive API enabled hai? scope drive.file hai?"
            404 -> "Ye ${notFound ?: "file"} Drive me nahi mili — ya id galat hai ya wo meri pahunch (drive.file) me nahi hai."
            else -> ConnectorHttp.mapError(SERVICE, resp.code, resp.body, notFound)
        }
        throw ConnectorException(msg, resp.code)
    }

    private fun parse(j: JSONObject): DriveFile = DriveFile(
        id = j.optString("id"), name = j.optString("name"), mimeType = j.optString("mimeType"),
        modified = j.optString("modifiedTime").take(16).replace('T', ' '),
        link = DriveShare.formatLink(j.optString("id"), j.optString("webViewLink").ifBlank { null }, j.optString("mimeType")),
        size = j.optString("size").toLongOrNull() ?: 0L
    )

    suspend fun aboutUser(): String {
        val j = check(ConnectorHttp.get(SERVICE, "https://www.googleapis.com/drive/v3/about?fields=user", headers())).json()
        return j.optJSONObject("user")?.optString("emailAddress").orEmpty()
    }

    suspend fun testConnection(): String {
        val email = aboutUser()
        return "Google Drive linked${if (email.isNotBlank()) " as $email" else ""} (${GoogleOAuthHelper.linkMode(context)})"
    }

    suspend fun listFiles(query: String?, folderId: String? = null, limit: Int = 25): List<DriveFile> {
        val parts = mutableListOf("trashed = false")
        if (!query.isNullOrBlank()) parts.add("name contains '${query.replace("'", "\\'")}'")
        if (!folderId.isNullOrBlank()) parts.add("'${folderId}' in parents")
        val url = "$FILES?pageSize=${limit.coerceIn(1, 100)}&orderBy=modifiedTime desc&fields=files($FIELDS)&q=" +
            URLEncoder.encode(parts.joinToString(" and "), "UTF-8")
        val arr = check(ConnectorHttp.get(SERVICE, url, headers())).json().optJSONArray("files") ?: JSONArray()
        val out = mutableListOf<DriveFile>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(parse(it)) }
        return out
    }

    suspend fun getFile(fileId: String): DriveFile {
        val j = check(ConnectorHttp.get(SERVICE, "$FILES/${fileId.trim()}?fields=$FIELDS", headers()), "file").json()
        return parse(j)
    }

    private suspend fun multipartUpload(metadata: JSONObject, mediaType: String, media: okhttp3.RequestBody, convertTo: String? = null): DriveFile {
        if (convertTo != null) metadata.put("mimeType", convertTo)
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(MultipartBody.Part.create(null, metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())))
            .addPart(MultipartBody.Part.create(null, media))
            .build()
        val j = check(ConnectorHttp.request(SERVICE, "POST", UPLOAD, headers(), body)).json()
        return parse(j)
    }

    suspend fun uploadFile(path: String, folderId: String?, name: String?): DriveFile {
        val file = File(path)
        if (!file.exists() || !file.isFile) throw ConnectorException("File nahi mili: $path")
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val meta = JSONObject().put("name", name?.takeIf { it.isNotBlank() } ?: file.name)
        if (!folderId.isNullOrBlank()) meta.put("parents", JSONArray().put(folderId))
        return multipartUpload(meta, mime, file.asRequestBody(mime.toMediaType()))
    }

    /** Creates a Google Doc from plain text (Drive converts text/plain → Docs). */
    suspend fun createDoc(title: String, text: String, folderId: String?): DriveFile {
        val meta = JSONObject().put("name", title.trim().ifBlank { "Untitled" })
        if (!folderId.isNullOrBlank()) meta.put("parents", JSONArray().put(folderId))
        return multipartUpload(meta, "text/plain", text.toRequestBody("text/plain; charset=UTF-8".toMediaType()), convertTo = DOC_MIME)
    }

    suspend fun createFolder(name: String, parentId: String?): DriveFile {
        val body = JSONObject().put("name", name.trim()).put("mimeType", FOLDER_MIME)
        if (!parentId.isNullOrBlank()) body.put("parents", JSONArray().put(parentId))
        val j = check(ConnectorHttp.postJson(SERVICE, "$FILES?fields=$FIELDS", body, headers())).json()
        return parse(j)
    }

    /** Reads a file as text; Docs/Sheets/Slides are exported automatically. */
    suspend fun readFileText(fileId: String, maxChars: Int = 12000): Pair<DriveFile, String> {
        val f = getFile(fileId)
        if (DriveShare.isFolder(f.mimeType)) throw ConnectorException("'${f.name}' ek folder hai — drive_list_files me folder_id de ke andar dekho.")
        val export = DriveShare.exportMime(f.mimeType)
        val text = if (export != null) {
            check(ConnectorHttp.get(SERVICE, "$FILES/${f.id}/export?mimeType=${URLEncoder.encode(export, "UTF-8")}", headers()), "file").body
        } else {
            if (f.size > MAX_READ_BYTES) throw ConnectorException("'${f.name}' 2 MB se badi hai — text ke taur pe nahi padh sakti.")
            val resp = check(ConnectorHttp.get(SERVICE, "$FILES/${f.id}?alt=media", headers()), "file")
            val looksText = f.mimeType.startsWith("text/") || f.mimeType.contains("json") || f.mimeType.contains("xml") ||
                f.mimeType.contains("javascript") || f.mimeType.contains("csv") || resp.body.none { it == '\u0000' }
            if (!looksText) throw ConnectorException("'${f.name}' (${f.mimeType}) text file nahi hai — isko padh nahi sakti.")
            resp.body
        }
        return f to (if (text.length > maxChars) text.take(maxChars) + "\n…(truncated)" else text)
    }

    /** Makes the file viewable by anyone with the link; returns the share link. */
    suspend fun shareAnyoneWithLink(fileId: String): Pair<DriveFile, String> {
        val f = getFile(fileId)
        check(ConnectorHttp.postJson(SERVICE, "$FILES/${f.id}/permissions", DriveShare.permissionBody(), headers()), "file")
        return f to DriveShare.formatLink(f.id, f.link.ifBlank { null }, f.mimeType)
    }

    suspend fun trash(fileId: String): DriveFile {
        val j = check(ConnectorHttp.patchJson(SERVICE, "$FILES/${fileId.trim()}?fields=$FIELDS", JSONObject().put("trashed", true), headers()), "file").json()
        return parse(j)
    }
}
