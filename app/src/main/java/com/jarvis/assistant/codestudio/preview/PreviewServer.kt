package com.jarvis.assistant.codestudio.preview

import android.util.Log
import com.jarvis.assistant.codestudio.CODE_STUDIO_PREVIEW_PORT
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tiny HTTP/1.1 static file server for Code Studio live preview.
 *
 * - Bound to 127.0.0.1 only, on [CODE_STUDIO_PREVIEW_PORT] or the next free port.
 * - Ref-counted: [acquire] / [release]. The server stops when the last user releases it.
 *   Agent tools hold at most one extra "detached" reference via [acquireDetached].
 * - Serves one root directory at a time (the most recently acquired project); `/` → index.html.
 * - Canonical-path check prevents traversal; responses carry no-cache headers.
 */
object PreviewServer {
    private const val TAG = "CodeStudioPreview"
    private const val MAX_PORT_TRIES = 20

    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var pool: ExecutorService? = null
    private val refCount = AtomicInteger(0)
    private var detachedHeld = false

    @Volatile private var rootDir: File? = null
    @Volatile var port: Int = -1
        private set

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** Base URL ("http://127.0.0.1:PORT/") or null when stopped. */
    fun baseUrl(): String? = if (isRunning) "http://127.0.0.1:$port/" else null

    /**
     * Starts (if needed) and points the server at [dir]. Returns the port.
     * Call off the main thread (binds a socket). Must be paired with [release].
     */
    fun acquire(dir: File): Int = synchronized(lock) {
        rootDir = dir
        if (!isRunning) start()
        refCount.incrementAndGet()
        port
    }

    fun release() = synchronized(lock) {
        val left = refCount.decrementAndGet()
        if (left <= 0) {
            refCount.set(0)
            stop()
        }
    }

    /** For agent tools: keeps one long-lived reference (replaced on every call). Returns the URL. */
    fun acquireDetached(dir: File): String = synchronized(lock) {
        if (!detachedHeld) {
            acquire(dir)
            detachedHeld = true
        } else {
            rootDir = dir
            if (!isRunning) start()
        }
        "http://127.0.0.1:$port/"
    }

    fun releaseDetached() = synchronized(lock) {
        if (detachedHeld) {
            detachedHeld = false
            release()
        }
    }

    /** Re-points a running server to another project (e.g. when the IDE switches projects). */
    fun setRoot(dir: File) { rootDir = dir }

    private fun start() {
        var bound: ServerSocket? = null
        var p = CODE_STUDIO_PREVIEW_PORT
        val loopback = InetAddress.getByName("127.0.0.1")
        for (i in 0 until MAX_PORT_TRIES) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(loopback, p), 50)
                bound = s
                break
            } catch (_: Exception) {
                p++
            }
        }
        val socket = bound ?: ServerSocket(0, 50, loopback) // last resort: ephemeral port
        serverSocket = socket
        port = socket.localPort
        val executor = Executors.newFixedThreadPool(6) { r -> Thread(r, "cs-preview-worker").apply { isDaemon = true } }
        pool = executor
        acceptThread = Thread({
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    executor.execute { handle(client) }
                } catch (_: SocketException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed: ${e.message}")
                }
            }
        }, "cs-preview-accept").apply { isDaemon = true; start() }
        Log.i(TAG, "Preview server listening on 127.0.0.1:$port")
    }

    private fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool?.shutdownNow()
        pool = null
        acceptThread = null
        port = -1
        Log.i(TAG, "Preview server stopped")
    }

    // ── request handling ─────────────────────────────────────────────────

    private fun handle(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 15_000
                val input = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream()
                // Keep-alive loop: serve requests until the client closes or asks to close.
                var served = 0
                while (served < 100) {
                    val requestLine = readLine(input) ?: return
                    if (requestLine.isBlank()) continue
                    val headers = HashMap<String, String>()
                    while (true) {
                        val h = readLine(input) ?: return
                        if (h.isEmpty()) break
                        val idx = h.indexOf(':')
                        if (idx > 0) headers[h.substring(0, idx).trim().lowercase(Locale.ROOT)] = h.substring(idx + 1).trim()
                    }
                    // Drain any request body (we ignore it).
                    headers["content-length"]?.toLongOrNull()?.let { n -> if (n in 1..1_000_000) input.skipNBytesCompat(n) }

                    val parts = requestLine.split(' ')
                    val method = parts.getOrNull(0).orEmpty().uppercase(Locale.ROOT)
                    val target = parts.getOrNull(1).orEmpty()
                    val version = parts.getOrNull(2).orEmpty()
                    val keepAlive = when {
                        headers["connection"].equals("close", true) -> false
                        version == "HTTP/1.0" -> headers["connection"].equals("keep-alive", true)
                        else -> true
                    }
                    respond(out, method, target, keepAlive)
                    served++
                    if (!keepAlive) return
                }
            } catch (_: Exception) {
                // client went away / timeout
            }
        }
    }

    private fun respond(out: OutputStream, method: String, target: String, keepAlive: Boolean) {
        if (method != "GET" && method != "HEAD") {
            sendText(out, 405, "Method Not Allowed", "Only GET and HEAD are supported.", keepAlive, method == "HEAD", extra = "Allow: GET, HEAD\r\n")
            return
        }
        val root = rootDir?.canonicalFile
        if (root == null || !root.isDirectory) {
            sendText(out, 503, "Service Unavailable", "No project is being previewed.", keepAlive, method == "HEAD")
            return
        }
        val rawPath = target.substringBefore('?').substringBefore('#')
        val decoded = try {
            URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            sendText(out, 400, "Bad Request", "Malformed URL.", keepAlive, method == "HEAD"); return
        }
        if (decoded.contains('\u0000') || decoded.split('/', '\\').any { it == ".." }) {
            sendText(out, 403, "Forbidden", "Path traversal is not allowed.", keepAlive, method == "HEAD"); return
        }
        var file = File(root, decoded.trimStart('/', '\\')).canonicalFile
        if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) {
            sendText(out, 403, "Forbidden", "Outside the project.", keepAlive, method == "HEAD"); return
        }
        if (file.isDirectory) {
            if (!rawPath.endsWith("/")) {
                // Redirect so relative URLs inside the directory's index resolve correctly.
                val location = rawPath + "/" + (target.substringAfter('?', "").let { if (it.isNotEmpty()) "?$it" else "" })
                sendText(out, 301, "Moved Permanently", "Moved", keepAlive, method == "HEAD", extra = "Location: $location\r\n")
                return
            }
            val index = listOf("index.html", "index.htm").map { File(file, it) }.firstOrNull { it.isFile }
            if (index == null) {
                sendListing(out, root, file, keepAlive, method == "HEAD"); return
            }
            file = index
        }
        if (!file.isFile) {
            val notFound = File(root, "404.html")
            if (notFound.isFile) {
                sendFile(out, 404, "Not Found", notFound, keepAlive, method == "HEAD")
            } else {
                sendText(out, 404, "Not Found", "404 — ${escapeHtml(decoded)} not found in project.", keepAlive, method == "HEAD", html = true)
            }
            return
        }
        sendFile(out, 200, "OK", file, keepAlive, method == "HEAD")
    }

    private fun sendFile(out: OutputStream, code: Int, status: String, file: File, keepAlive: Boolean, headOnly: Boolean) {
        val len = file.length()
        val sb = StringBuilder()
            .append("HTTP/1.1 $code $status\r\n")
            .append("Content-Type: ${mimeType(file.name)}\r\n")
            .append("Content-Length: $len\r\n")
            .append(commonHeaders(keepAlive))
            .append("Last-Modified: ${httpDate(file.lastModified())}\r\n")
            .append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) file.inputStream().use { it.copyTo(out, 32 * 1024) }
        out.flush()
    }

    private fun sendText(
        out: OutputStream, code: Int, status: String, body: String, keepAlive: Boolean, headOnly: Boolean,
        extra: String = "", html: Boolean = false
    ) {
        val bytes = if (html) "<!doctype html><meta charset=utf-8><title>$code</title><body style=\"font-family:sans-serif;padding:24px\"><h2>$code $status</h2><p>$body</p>".toByteArray()
        else body.toByteArray()
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: ${if (html) "text/html" else "text/plain"}; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" + commonHeaders(keepAlive) + extra + "\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(bytes)
        out.flush()
    }

    private fun sendListing(out: OutputStream, root: File, dir: File, keepAlive: Boolean, headOnly: Boolean) {
        val rel = dir.path.removePrefix(root.path).replace(File.separatorChar, '/').ifEmpty { "/" }
        val items = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
        val body = buildString {
            append("<ul>")
            items.forEach { f ->
                val name = escapeHtml(f.name) + if (f.isDirectory) "/" else ""
                append("<li><a href=\"").append(name).append("\">").append(name).append("</a></li>")
            }
            append("</ul><p>No index.html in ").append(escapeHtml(rel)).append("</p>")
        }
        sendText(out, 200, "OK", body, keepAlive, headOnly, html = true)
    }

    private fun commonHeaders(keepAlive: Boolean): String =
        "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n" +
            "Pragma: no-cache\r\nExpires: 0\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Server: JarvisCodeStudio\r\n" +
            "Date: ${httpDate(System.currentTimeMillis())}\r\n" +
            "Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n"

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) throw IllegalStateException("header too long")
            sb.append(b.toChar())
        }
    }

    private fun InputStream.skipNBytesCompat(n: Long) {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) { if (read() < 0) return else left-- } else left -= s
        }
    }

    private fun httpDate(ms: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(ms))

    private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs", "cjs" -> "text/javascript; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "webmanifest" -> "application/manifest+json; charset=utf-8"
        "txt", "md", "log" -> "text/plain; charset=utf-8"
        "csv" -> "text/csv; charset=utf-8"
        "xml" -> "application/xml; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "eot" -> "application/vnd.ms-fontobject"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "wasm" -> "application/wasm"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "glsl", "vert", "frag" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }
}
