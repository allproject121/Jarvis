package com.jarvis.assistant.maya.media

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.notification.JarvisNotificationListener
import com.jarvis.assistant.automation.youtube.YouTubeController
import com.jarvis.assistant.util.ActivityLauncherHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Music playback across apps (play_music / play_song / play_favorite_song / now playing / recents). */
object MusicPlayback {

    private const val TAG = "MayaMusic"

    data class MusicApp(val id: String, val label: String, val pkg: String)

    val APPS = listOf(
        MusicApp("youtube_music", "YouTube Music", "com.google.android.apps.youtube.music"),
        MusicApp("spotify", "Spotify", "com.spotify.music"),
        MusicApp("gaana", "Gaana", "com.gaana"),
        MusicApp("jiosaavn", "JioSaavn", "com.jio.media.jiobeats"),
        MusicApp("wynk", "Wynk", "com.bsbportal.music"),
        MusicApp("amazon_music", "Amazon Music", "com.amazon.mp3"),
        MusicApp("youtube", "YouTube", "com.google.android.youtube")
    )

    fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) != null }.getOrDefault(false)

    fun installedApps(context: Context): List<MusicApp> = APPS.filter { isInstalled(context, it.pkg) }

    fun normalizeAppId(raw: String?): String {
        val s = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            s.isBlank() -> ""
            s.contains("yt music") || s.contains("youtube music") || s == "ytm" || s == "youtube_music" -> "youtube_music"
            s.contains("spotify") -> "spotify"
            s.contains("gaana") -> "gaana"
            s.contains("saavn") -> "jiosaavn"
            s.contains("wynk") -> "wynk"
            s.contains("amazon") -> "amazon_music"
            s.contains("youtube") || s == "yt" -> "youtube"
            else -> s
        }
    }

    /** Picks the app: explicit arg → saved default → first installed (YT Music/Spotify first) → YouTube. */
    fun chooseApp(context: Context, requested: String?): MusicApp? {
        val prefs = MediaPrefs.get(context)
        val id = normalizeAppId(requested).ifBlank { prefs.defaultMusicApp }
        if (id.isNotBlank()) APPS.firstOrNull { it.id == id }?.let { app -> if (isInstalled(context, app.pkg)) return app }
        return installedApps(context).firstOrNull()
    }

    fun play(context: Context, query: String, requestedApp: String?): Pair<Boolean, String> {
        val q = query.trim()
        if (q.isBlank()) return false to "What should I play?"
        val app = chooseApp(context, requestedApp)
        val ok = when (app?.id) {
            null -> launchGenericSearch(context, q)
            "youtube" -> YouTubeController.searchAndPlay(context, q).success
            "spotify" -> launchSpotify(context, q)
            "youtube_music" -> launchUrl(context, app.pkg, "https://music.youtube.com/search?q=" + Uri.encode(q)) || launchPlayFromSearch(context, app.pkg, q)
            "gaana" -> launchUrl(context, app.pkg, "https://gaana.com/search/" + Uri.encode(q)) || launchPlayFromSearch(context, app.pkg, q)
            "jiosaavn" -> launchUrl(context, app.pkg, "https://www.jiosaavn.com/search/" + Uri.encode(q)) || launchPlayFromSearch(context, app.pkg, q)
            else -> launchPlayFromSearch(context, app.pkg, q) || launchGenericSearch(context, q)
        }
        if (ok) {
            MediaPrefs.get(context).addRecentSong(q)
            runCatching { JarvisApp.instance.memoryManager.trackSongPlay(q) }
            return true to "Playing \"$q\" on ${app?.label ?: "your music app"}."
        }
        return false to "Couldn't open ${app?.label ?: "a music app"} for \"$q\"."
    }

    private fun launchPlayFromSearch(context: Context, pkg: String?, q: String): Boolean {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, q)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(MediaStore.EXTRA_MEDIA_TITLE, q)
            if (pkg != null) setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { ActivityLauncherHelper.startActivitySafely(context, intent) }.getOrDefault(false)
    }

    private fun launchGenericSearch(context: Context, q: String): Boolean = launchPlayFromSearch(context, null, q)

    private fun launchSpotify(context: Context, q: String): Boolean {
        val uri = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q))).apply {
            setPackage("com.spotify.music"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { ActivityLauncherHelper.startActivitySafely(context, uri) }.getOrDefault(false) ||
            launchPlayFromSearch(context, "com.spotify.music", q)
    }

    private fun launchUrl(context: Context, pkg: String, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { setPackage(pkg); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return runCatching { ActivityLauncherHelper.startActivitySafely(context, intent) }.getOrDefault(false)
    }

    fun favoriteSong(): String = runCatching { JarvisApp.instance.preferences.favoriteSong }.getOrDefault("")

    /** Active media sessions (needs notification access, which Jarvis' listener already provides). */
    fun nowPlaying(context: Context): JSONObject {
        val out = JSONObject()
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, JarvisNotificationListener::class.java)
            val sessions = msm.getActiveSessions(component)
            val arr = JSONArray()
            for (c in sessions) {
                val md = c.metadata
                val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
                val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
                val state = when (c.playbackState?.state) {
                    PlaybackState.STATE_PLAYING -> "playing"
                    PlaybackState.STATE_PAUSED -> "paused"
                    PlaybackState.STATE_BUFFERING -> "buffering"
                    PlaybackState.STATE_STOPPED -> "stopped"
                    else -> "idle"
                }
                arr.put(JSONObject().put("app", c.packageName).put("title", title).put("artist", artist).put("album", album).put("state", state))
            }
            out.put("sessions", arr)
            val active = (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("state") == "playing" }
                ?: (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull()
            out.put("message", when {
                active == null -> "Nothing is playing right now."
                active.optString("title").isBlank() -> "A media session is ${active.optString("state")} in ${active.optString("app")}, but it didn't share the track name."
                else -> "${active.optString("state").replaceFirstChar { it.uppercase() }}: \"${active.optString("title")}\"" +
                    (if (active.optString("artist").isNotBlank()) " by ${active.optString("artist")}" else "") + " (${active.optString("app")})."
            })
        } catch (e: SecurityException) {
            out.put("message", "Notification access is needed to read what's playing. Enable it for Jarvis in Settings → Notification access.")
        } catch (e: Exception) {
            out.put("message", "Could not read media sessions: ${e.message}")
        }
        return out
    }

    fun recentSongs(context: Context): JSONObject {
        val recent = MediaPrefs.get(context).recentSongs()
        val counts = runCatching { JarvisApp.instance.preferences.getSongPlayCounts() }.getOrDefault(mutableMapOf())
        val top = counts.entries.sortedByDescending { it.value }.take(10)
        return JSONObject().apply {
            put("recent", JSONArray(recent))
            put("most_played", JSONArray(top.map { JSONObject().put("song", it.key).put("plays", it.value) }))
            put("favorite", favoriteSong())
            put("message", when {
                recent.isEmpty() && top.isEmpty() -> "No song history yet."
                else -> "Recent: ${recent.take(5).joinToString(", ")}" + (if (top.isNotEmpty()) ". Most played: ${top.take(3).joinToString(", ") { "${it.key} (${it.value})" }}" else "") +
                    (if (favoriteSong().isNotBlank()) ". Favorite: ${favoriteSong()}." else ".")
            })
        }
    }
}

/** YouTube Data API v3 analytics helpers (channel / video stats, recent uploads, thumbnails). */
object YouTubeDataApi {

    private const val TAG = "YouTubeDataApi"
    private const val BASE = "https://www.googleapis.com/youtube/v3/"
    private val client: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build() }

    class ApiException(message: String) : Exception(message)

    fun apiKey(context: Context): String {
        val own = MediaPrefs.get(context).youtubeApiKey
        if (own.isNotBlank()) return own
        return runCatching { JarvisApp.instance.youTubePreferences.youtubeApiKey }.getOrDefault("")
    }

    private fun get(url: String): JSONObject {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull() ?: body.take(160)
                throw ApiException("YouTube API HTTP ${resp.code}: $msg")
            }
            return JSONObject(body)
        }
    }

    fun extractVideoId(ref: String): String? {
        val r = ref.trim()
        Regex("^[A-Za-z0-9_-]{11}$").find(r)?.let { return r }
        Regex("(?:v=|youtu\\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})").find(r)?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun requireKey(context: Context): String =
        apiKey(context).ifBlank { throw ApiException("YouTube API key not set. Add it in Media Studio → YouTube API key (or Settings → YouTube).") }

    suspend fun resolveChannelId(context: Context, ref: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val key = requireKey(context)
        val r = ref.trim()
        val fromUrl = Regex("youtube\\.com/(?:channel/)(UC[A-Za-z0-9_-]{20,})").find(r)?.groupValues?.get(1)
        if (fromUrl != null) return@withContext fromUrl to ""
        if (r.startsWith("UC") && r.length >= 22 && !r.contains(' ')) return@withContext r to ""
        val handle = Regex("(?:youtube\\.com/)?@([A-Za-z0-9._-]+)").find(r)?.groupValues?.get(1)
        if (handle != null) {
            val json = get("${BASE}channels?part=snippet&forHandle=${Uri.encode("@$handle")}&key=$key")
            json.optJSONArray("items")?.optJSONObject(0)?.let { return@withContext it.optString("id") to it.optJSONObject("snippet")?.optString("title").orEmpty() }
        }
        if (r.isBlank() || r.equals("my", true) || r.equals("mine", true) || r.equals("me", true)) {
            throw ApiException("Tell me the channel name, @handle or URL (API keys can't see 'my channel' without OAuth).")
        }
        val search = get("${BASE}search?part=snippet&type=channel&maxResults=1&q=${Uri.encode(r)}&key=$key")
        val item = search.optJSONArray("items")?.optJSONObject(0) ?: throw ApiException("No channel found for '$r'.")
        item.optJSONObject("id")?.optString("channelId").orEmpty() to item.optJSONObject("snippet")?.optString("title").orEmpty()
    }

    suspend fun channelStats(context: Context, ref: String): JSONObject = withContext(Dispatchers.IO) {
        val key = requireKey(context)
        val (id, _) = resolveChannelId(context, ref)
        val json = get("${BASE}channels?part=snippet,statistics,contentDetails&id=$id&key=$key")
        val item = json.optJSONArray("items")?.optJSONObject(0) ?: throw ApiException("Channel $id not found.")
        val sn = item.optJSONObject("snippet") ?: JSONObject()
        val st = item.optJSONObject("statistics") ?: JSONObject()
        val subs = st.optString("subscriberCount", "hidden")
        JSONObject().apply {
            put("channel_id", id)
            put("title", sn.optString("title"))
            put("handle", sn.optString("customUrl"))
            put("subscribers", subs)
            put("views", st.optString("viewCount"))
            put("videos", st.optString("videoCount"))
            put("created", sn.optString("publishedAt"))
            put("uploads_playlist", item.optJSONObject("contentDetails")?.optJSONObject("relatedPlaylists")?.optString("uploads").orEmpty())
            put("message", "${sn.optString("title")}: ${fmt(subs)} subscribers, ${fmt(st.optString("viewCount"))} total views, ${st.optString("videoCount")} videos.")
        }
    }

    suspend fun videoStats(context: Context, ref: String): JSONObject = withContext(Dispatchers.IO) {
        val key = requireKey(context)
        val id = extractVideoId(ref) ?: run {
            val s = get("${BASE}search?part=snippet&type=video&maxResults=1&q=${Uri.encode(ref)}&key=$key")
            s.optJSONArray("items")?.optJSONObject(0)?.optJSONObject("id")?.optString("videoId").orEmpty().ifBlank { throw ApiException("No video found for '$ref'.") }
        }
        val json = get("${BASE}videos?part=snippet,statistics,contentDetails&id=$id&key=$key")
        val item = json.optJSONArray("items")?.optJSONObject(0) ?: throw ApiException("Video $id not found.")
        val sn = item.optJSONObject("snippet") ?: JSONObject()
        val st = item.optJSONObject("statistics") ?: JSONObject()
        JSONObject().apply {
            put("video_id", id)
            put("title", sn.optString("title"))
            put("channel", sn.optString("channelTitle"))
            put("published", sn.optString("publishedAt"))
            put("views", st.optString("viewCount"))
            put("likes", st.optString("likeCount"))
            put("comments", st.optString("commentCount"))
            put("duration", item.optJSONObject("contentDetails")?.optString("duration").orEmpty())
            put("url", "https://youtu.be/$id")
            put("thumbnail", bestThumb(sn.optJSONObject("thumbnails")))
            put("message", "\"${sn.optString("title")}\" by ${sn.optString("channelTitle")}: ${fmt(st.optString("viewCount"))} views, ${fmt(st.optString("likeCount"))} likes, ${fmt(st.optString("commentCount"))} comments.")
        }
    }

    suspend fun recentVideos(context: Context, ref: String, max: Int): JSONObject = withContext(Dispatchers.IO) {
        val key = requireKey(context)
        val (id, name) = resolveChannelId(context, ref)
        val json = get("${BASE}search?part=snippet&channelId=$id&order=date&type=video&maxResults=${max.coerceIn(1, 20)}&key=$key")
        val items = json.optJSONArray("items") ?: JSONArray()
        val arr = JSONArray()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val sn = it.optJSONObject("snippet") ?: continue
            val vid = it.optJSONObject("id")?.optString("videoId").orEmpty()
            arr.put(JSONObject().put("video_id", vid).put("title", sn.optString("title")).put("published", sn.optString("publishedAt")).put("url", "https://youtu.be/$vid"))
        }
        JSONObject().apply {
            put("channel_id", id)
            put("channel", name)
            put("videos", arr)
            put("message", if (arr.length() == 0) "No recent videos found." else
                "Latest ${arr.length()} videos${if (name.isNotBlank()) " from $name" else ""}:\n" +
                    (0 until arr.length()).joinToString("\n") { i -> "${i + 1}. ${arr.getJSONObject(i).optString("title")} (${arr.getJSONObject(i).optString("published").take(10)})" })
        }
    }

    suspend fun thumbnail(context: Context, ref: String, save: Boolean): JSONObject = withContext(Dispatchers.IO) {
        val id = extractVideoId(ref) ?: videoStats(context, ref).optString("video_id")
        val url = (try {
            val key = requireKey(context)
            val json = get("${BASE}videos?part=snippet&id=$id&key=$key")
            bestThumb(json.optJSONArray("items")?.optJSONObject(0)?.optJSONObject("snippet")?.optJSONObject("thumbnails"))
        } catch (e: Exception) {
            "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
        }).ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" }
        val out = JSONObject().put("video_id", id).put("thumbnail_url", url)
        if (save) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val bytes = resp.body?.bytes()
                    if (resp.isSuccessful && bytes != null) {
                        val saved = MediaFiles.saveImageBytes(context, bytes, "YT_THUMB")
                        if (saved != null) {
                            out.put("path", saved.ref)
                            MediaFiles.openViewer(context, saved.uri, "image/jpeg")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "thumbnail download failed: ${e.message}")
            }
        }
        out.put("message", "Thumbnail: $url" + (if (out.has("path")) " (saved to ${out.optString("path")})" else ""))
    }

    private fun bestThumb(th: JSONObject?): String {
        if (th == null) return ""
        for (k in listOf("maxres", "standard", "high", "medium", "default")) {
            th.optJSONObject(k)?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun fmt(n: String): String {
        val v = n.toLongOrNull() ?: return n
        return when {
            v >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", v / 1e9)
            v >= 1_000_000 -> String.format(Locale.US, "%.1fM", v / 1e6)
            v >= 1_000 -> String.format(Locale.US, "%.1fK", v / 1e3)
            else -> v.toString()
        }
    }
}
