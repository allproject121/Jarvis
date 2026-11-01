package com.jarvis.assistant.voice.edge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Jarvis' own client for the Microsoft Edge "Read aloud" neural voice service
 * (the public endpoint the open-source edge-tts project talks to).
 *
 * Protocol, implemented from scratch:
 *  1. Open `wss://speech.platform.bing.com/.../readaloud/edge/v1` with the public
 *     TrustedClientToken, a `Sec-MS-GEC` token (SHA-256 of the 5-minute-rounded Windows
 *     file-time + token, upper-case hex) and `Sec-MS-GEC-Version`.
 *  2. Send a `speech.config` text frame selecting `audio-24khz-48kbitrate-mono-mp3`.
 *  3. Send an `ssml` text frame (voice / rate / pitch prosody).
 *  4. Collect binary frames (2-byte big-endian header length + header + MP3 payload with
 *     `Path:audio`) until a `Path:turn.end` text frame arrives.
 *
 * Results are cached as MP3 files in `cacheDir/edge_tts` (LRU-trimmed).
 * Every failure returns null so callers can fall back to Jarvis' own TTS.
 */
object EdgeTtsClient {

    private const val TAG = "EdgeTtsClient"

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WSS_BASE =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private const val CHROMIUM_FULL_VERSION = "130.0.2849.68"
    private val CHROMIUM_MAJOR = CHROMIUM_FULL_VERSION.substringBefore('.')
    private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

    /** Seconds between 1601-01-01 (Windows epoch) and 1970-01-01 (Unix epoch). */
    private const val WIN_EPOCH_SECONDS = 11_644_473_600L
    private const val SYNTH_TIMEOUT_MS = 20_000L
    private const val MAX_CHUNK_CHARS = 2_000
    private const val CACHE_DIR = "edge_tts"
    private const val CACHE_MAX_BYTES = 25L * 1024L * 1024L

    /** Server-minus-device clock offset learned from a 403 response's Date header. */
    @Volatile private var clockSkewSeconds = 0L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private sealed class ChunkResult {
        class Audio(val bytes: ByteArray) : ChunkResult()
        class Forbidden(val serverDate: String?) : ChunkResult()
        object Failed : ChunkResult()
    }

    /** True when the device has a network that can reach the internet. */
    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Synthesizes [text] with [voice] and returns the cached MP3 file, or null on any failure.
     */
    suspend fun synthesizeToFile(
        context: Context,
        text: String,
        voice: String,
        ratePercent: Int = 0,
        pitchHz: Int = 0
    ): File? = withContext(Dispatchers.IO) {
        val clean = sanitize(text)
        if (clean.isBlank()) return@withContext null
        val dir = File(context.applicationContext.cacheDir, CACHE_DIR).apply { mkdirs() }
        val cacheFile = File(dir, cacheKey(clean, voice, ratePercent, pitchHz) + ".mp3")
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            cacheFile.setLastModified(System.currentTimeMillis())
            return@withContext cacheFile
        }
        if (!isNetworkAvailable(context)) return@withContext null

        val audio = ByteArrayOutputStream()
        for (chunk in splitText(clean)) {
            val bytes = synthesizeChunkWithRetry(chunk, voice, ratePercent, pitchHz) ?: return@withContext null
            audio.write(bytes)
        }
        if (audio.size() == 0) return@withContext null

        try {
            val tmp = File(dir, cacheFile.name + ".part")
            tmp.writeBytes(audio.toByteArray())
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeBytes(audio.toByteArray())
                tmp.delete()
            }
            trimCache(dir)
            cacheFile
        } catch (e: Exception) {
            Log.w(TAG, "Could not cache Edge audio: ${e.message}")
            null
        }
    }

    /** Deletes all cached Edge voice audio. */
    fun clearCache(context: Context) {
        runCatching { File(context.applicationContext.cacheDir, CACHE_DIR).deleteRecursively() }
    }

    // ── Protocol ────────────────────────────────────────────────────────────

    private suspend fun synthesizeChunkWithRetry(text: String, voice: String, rate: Int, pitch: Int): ByteArray? {
        when (val first = synthesizeChunk(text, voice, rate, pitch)) {
            is ChunkResult.Audio -> return first.bytes
            is ChunkResult.Forbidden -> {
                // 403 usually means our clock is off: adopt the server's time and retry once.
                adjustClockSkew(first.serverDate)
                return (synthesizeChunk(text, voice, rate, pitch) as? ChunkResult.Audio)?.bytes
            }
            ChunkResult.Failed -> return null
        }
    }

    private suspend fun synthesizeChunk(text: String, voice: String, rate: Int, pitch: Int): ChunkResult {
        val result = withTimeoutOrNull(SYNTH_TIMEOUT_MS) {
            suspendCancellableCoroutine<ChunkResult> { cont ->
                val finished = AtomicBoolean(false)
                val buffer = ByteArrayOutputStream()

                fun finish(r: ChunkResult) {
                    if (finished.compareAndSet(false, true) && cont.isActive) cont.resume(r)
                }

                val connectionId = UUID.randomUUID().toString().replace("-", "")
                val url = "$WSS_BASE?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
                    "&Sec-MS-GEC=${secMsGec()}" +
                    "&Sec-MS-GEC-Version=1-$CHROMIUM_FULL_VERSION" +
                    "&ConnectionId=$connectionId"
                val request = Request.Builder()
                    .url(url)
                    .header("Pragma", "no-cache")
                    .header("Cache-Control", "no-cache")
                    .header("Origin", ORIGIN)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0"
                    )
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", "muid=${randomMuid()};")
                    .build()

                val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val ok = webSocket.send(configMessage()) &&
                            webSocket.send(ssmlMessage(text, voice, rate, pitch))
                        if (!ok) {
                            webSocket.cancel()
                            finish(ChunkResult.Failed)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("Path:turn.end")) {
                            webSocket.close(1000, null)
                            finish(if (buffer.size() > 0) ChunkResult.Audio(buffer.toByteArray()) else ChunkResult.Failed)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        appendAudioFrame(bytes.toByteArray(), buffer)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        finish(if (buffer.size() > 0) ChunkResult.Audio(buffer.toByteArray()) else ChunkResult.Failed)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        finish(if (buffer.size() > 0) ChunkResult.Audio(buffer.toByteArray()) else ChunkResult.Failed)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val code = response?.code
                        Log.w(TAG, "Edge TTS socket failure (http=$code): ${t.message}")
                        if (code == 403) finish(ChunkResult.Forbidden(response?.header("Date")))
                        else finish(ChunkResult.Failed)
                    }
                })
                cont.invokeOnCancellation { runCatching { ws.cancel() } }
            }
        }
        return result ?: ChunkResult.Failed
    }

    private fun appendAudioFrame(frame: ByteArray, out: ByteArrayOutputStream) {
        if (frame.size < 2) return
        val headerLength = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        val audioStart = 2 + headerLength
        if (audioStart > frame.size) return
        val header = String(frame, 2, headerLength, Charsets.UTF_8)
        if (!header.contains("Path:audio")) return
        if (audioStart < frame.size) out.write(frame, audioStart, frame.size - audioStart)
    }

    private fun configMessage(): String {
        val body = "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}"
        return "X-Timestamp:${timestamp()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            body + "\r\n"
    }

    private fun ssmlMessage(text: String, voice: String, rate: Int, pitch: Int): String {
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val rateStr = if (rate >= 0) "+$rate%" else "$rate%"
        val pitchStr = if (pitch >= 0) "+${pitch}Hz" else "${pitch}Hz"
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='${fullVoiceName(voice)}'>" +
            "<prosody pitch='$pitchStr' rate='$rateStr' volume='+0%'>" +
            escapeXml(text) +
            "</prosody></voice></speak>"
        return "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp()}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml
    }

    /** "hi-IN-SwaraNeural" -> "Microsoft Server Speech Text to Speech Voice (hi-IN, SwaraNeural)". */
    private fun fullVoiceName(voice: String): String {
        val match = Regex("^([a-z]{2,3}-[A-Z]{2})-(.+)$").find(voice.trim()) ?: return voice.trim()
        val (locale, name) = match.destructured
        return "Microsoft Server Speech Text to Speech Voice ($locale, $name)"
    }

    /** Sec-MS-GEC: SHA-256("<windows file-time ticks rounded down to 5 min><token>") in upper-case hex. */
    internal fun secMsGec(): String {
        var seconds = System.currentTimeMillis() / 1000L + clockSkewSeconds + WIN_EPOCH_SECONDS
        seconds -= seconds % 300L
        val ticks = seconds * 10_000_000L
        return sha256Hex("$ticks$TRUSTED_CLIENT_TOKEN").uppercase(Locale.ROOT)
    }

    private fun adjustClockSkew(serverDate: String?) {
        if (serverDate.isNullOrBlank()) return
        try {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val server = fmt.parse(serverDate)?.time ?: return
            clockSkewSeconds = (server - System.currentTimeMillis()) / 1000L
            Log.i(TAG, "Edge TTS clock skew adjusted to ${clockSkewSeconds}s")
        } catch (_: Exception) {
        }
    }

    private fun timestamp(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun randomMuid(): String =
        UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT)

    // ── Text helpers ────────────────────────────────────────────────────────

    private fun sanitize(text: String): String =
        text.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun escapeXml(text: String): String = buildString(text.length + 16) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    /** Splits long text at sentence boundaries so every request stays well under the service limit. */
    private fun splitText(text: String): List<String> {
        if (text.length <= MAX_CHUNK_CHARS) return listOf(text)
        val sentences = text.split(Regex("(?<=[.!?\\u0964])\\s+"))
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            var s = sentence
            while (s.length > MAX_CHUNK_CHARS) {
                val cut = s.lastIndexOf(' ', MAX_CHUNK_CHARS).takeIf { it > 0 } ?: MAX_CHUNK_CHARS
                if (current.isNotEmpty()) {
                    chunks += current.toString().trim()
                    current.clear()
                }
                chunks += s.substring(0, cut).trim()
                s = s.substring(cut).trim()
            }
            if (current.length + s.length + 1 > MAX_CHUNK_CHARS && current.isNotEmpty()) {
                chunks += current.toString().trim()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(s)
        }
        if (current.isNotBlank()) chunks += current.toString().trim()
        return chunks.filter { it.isNotBlank() }
    }

    private fun cacheKey(text: String, voice: String, rate: Int, pitch: Int): String =
        sha256Hex("$voice|$rate|$pitch|$OUTPUT_FORMAT|$text").take(40)

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun trimCache(dir: File) {
        try {
            val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp3") } ?: return
            var total = files.sumOf { it.length() }
            if (total <= CACHE_MAX_BYTES) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= CACHE_MAX_BYTES) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        } catch (_: Exception) {
        }
    }
}
