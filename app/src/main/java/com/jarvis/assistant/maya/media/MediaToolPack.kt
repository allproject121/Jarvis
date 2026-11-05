package com.jarvis.assistant.maya.media

import android.content.Context
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry.ToolOutcome
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry.prop
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry.props
import org.json.JSONArray
import org.json.JSONObject

/**
 * WP9 MEDIA & COMMS pack: image generation, WhatsApp extras, reels/stories, music & YouTube
 * analytics, commentary/viewer/camera modes and misc device tools ported from Maya.
 *
 * Maya tools intentionally NOT registered because Jarvis already has them:
 *  qr_scan → scan_qr, navigate_to → open_navigation, write_email → send_email,
 *  find_contact → find_contacts, youtube_search_and_play → search_and_play_youtube,
 *  daily_quote / post_story (business pack), take_screenshot / share_file → take_screenshot / share_text.
 */
object MediaToolPack {

    private const val FAMILY = "media"

    // ---------------- arg helpers ----------------

    private fun str(a: JSONObject, vararg keys: String): String? {
        for (k in keys) if (a.has(k) && !a.isNull(k)) {
            val v = a.optString(k, "").trim()
            if (v.isNotEmpty()) return v
        }
        return null
    }

    private fun bool(a: JSONObject, key: String, default: Boolean): Boolean {
        if (!a.has(key) || a.isNull(key)) return default
        val v = a.opt(key)
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            else -> v.toString().lowercase().let { it == "true" || it == "on" || it == "yes" || it == "haan" || it == "1" || it == "enable" || it == "enabled" }
        }
    }

    private fun int(a: JSONObject, key: String, default: Int): Int {
        if (!a.has(key) || a.isNull(key)) return default
        val v = a.opt(key)
        return when (v) {
            is Number -> v.toInt()
            else -> v.toString().trim().toIntOrNull() ?: TimerParsing.parseSeconds(v.toString()).takeIf { it > 0 } ?: default
        }
    }

    private fun ok(msg: String, extra: JSONObject = JSONObject()) = ToolOutcome.ok(msg, extra)
    private fun fail(msg: String, extra: JSONObject = JSONObject()) = ToolOutcome.fail(msg, extra)
    private fun fromPair(p: Pair<Boolean, String>) = if (p.first) ok(p.second) else fail(p.second)
    private fun fromJson(p: Pair<Boolean, JSONObject>) =
        if (p.first) ok(p.second.optString("message"), p.second) else fail(p.second.optString("message"), p.second)

    private fun reg(
        name: String,
        description: String,
        properties: JSONObject = JSONObject(),
        required: List<String> = emptyList(),
        risky: Boolean = false,
        handler: suspend (Context, JSONObject) -> ToolOutcome
    ) = MayaToolRegistry.register(name, description, properties, required, FAMILY, risky, handler)

    private fun resolveTarget(ctx: Context, a: JSONObject): Pair<WhatsAppExtras.Target.Resolved?, String?> {
        val who = str(a, "contact", "number", "name", "phone", "to")
            ?: return null to "Kisko bhejna hai? Contact name ya number batao."
        return when (val t = WhatsAppExtras.resolve(ctx, who)) {
            is WhatsAppExtras.Target.Resolved -> t to null
            else -> null to WhatsAppExtras.targetFailure(t)
        }
    }

    fun install(context: Context) {
        installImage()
        installWhatsApp()
        installSocial()
        installMusicYouTube()
        installModes()
        installMisc()
    }

    // ================= A. Image generation =================

    private fun installImage() {
        reg(
            "generate_image",
            "Generate a picture from a text description (AI image) and save it to the gallery (Pictures/Jarvis), then show it. " +
                "Returns the file path, which can be passed to whatsapp_send_file or post_story. For readable text/quotes use generate_quote_image instead.",
            props(
                "prompt" to prop("string", "What to draw, in detail"),
                "style" to prop("string", "Optional style: photorealistic, anime, watercolor, cinematic, 3d, logo..."),
                "size" to prop("string", "square | portrait | landscape | instagram_story | instagram_post | WxH"),
                "provider" to prop("string", "Optional override", MediaPrefs.IMAGE_PROVIDERS)
            ),
            listOf("prompt")
        ) { ctx, a ->
            val prompt = str(a, "prompt", "description", "query") ?: return@reg fail("Kya banana hai? Image ka description do.")
            val size = str(a, "size")?.let { ImageSize.parse(it) }
            try {
                val r = ImageGen.generate(ctx, prompt, str(a, "style"), size, str(a, "provider"))
                r.saved?.let { MediaFiles.openViewer(ctx, it.uri, "image/jpeg") }
                val path = r.saved?.ref ?: ""
                ok(
                    "Image ready (${r.provider}, ${r.width}x${r.height}) and saved to the gallery${if (path.isNotBlank()) ": $path" else ""}." +
                        (if (r.note.isNotBlank()) " ${r.note}" else ""),
                    JSONObject().put("path", path).put("provider", r.provider).put("width", r.width).put("height", r.height)
                )
            } catch (e: Exception) {
                fail("Image nahi ban payi: ${e.message}. Check get_image_setup.")
            }
        }

        reg(
            "generate_quote_image",
            "Make a QUOTE / TEXT POSTER: the words are drawn onto the picture by the phone, so they are always spelt correctly — use for motivational quotes, " +
                "shayari, greetings, story/status posters. Saves to the gallery and shows it. No API needed (gradient background) unless ai_background=true.",
            props(
                "quote" to prop("string", "Exact text to write on the poster"),
                "author" to prop("string", "Optional author / signature line"),
                "style" to prop("string", "Optional mood/colour: sunset, ocean, night, forest, royal, peach, lavender, fire, mono, gold, serif..."),
                "size" to prop("string", "instagram_story (default) | instagram_post | square | landscape"),
                "ai_background" to prop("boolean", "Use an AI-generated backdrop instead of a gradient (default false)")
            ),
            listOf("quote")
        ) { ctx, a ->
            val quote = str(a, "quote", "text") ?: return@reg fail("Poster pe kya likhna hai?")
            val size = ImageSize.parse(str(a, "size"), ImageSize.STORY)
            val (bmp, saved) = QuoteImageRenderer.renderAndSave(ctx, quote, str(a, "author"), str(a, "style"), size, bool(a, "ai_background", false))
            bmp.recycle()
            if (saved == null) return@reg fail("Poster bana but gallery me save nahi ho paya (storage permission?).")
            MediaFiles.openViewer(ctx, saved.uri, "image/jpeg")
            ok("Quote poster ready and saved: ${saved.ref}", JSONObject().put("path", saved.ref).put("width", size.width).put("height", size.height))
        }

        reg("get_image_setup", "Show which image-generation provider is active (Pollinations / Gemini Nano Banana / Imagen / OpenAI), which keys are present and recent images.") { ctx, _ ->
            val s = ImageGen.setupSummary(ctx)
            ok("Image provider: ${s.optString("provider")} (default size ${s.optString("default_size")}). Gemini key ${if (s.optBoolean("gemini_key_present")) "set" else "missing"}, " +
                "OpenAI key ${if (s.optBoolean("openai_key_present")) "set" else "missing"}. Change it in Media Studio.", s)
        }
    }

    // ================= B. WhatsApp extras =================

    private fun installWhatsApp() {
        reg(
            "whatsapp_send_file",
            "Send a PHOTO / VIDEO / FILE to a WhatsApp contact or number. path can be an absolute path, a content:// URI, 'last' (newest photo), " +
                "'screenshot', 'video' or 'generated' (latest AI image). For text only use send_whatsapp_message.",
            props(
                "contact" to prop("string", "Contact name or phone number"),
                "path" to prop("string", "File path / URI / 'last' / 'screenshot' / 'video' / 'generated'"),
                "caption" to prop("string", "Optional caption")
            ),
            listOf("contact"), risky = true
        ) { ctx, a ->
            val (t, err) = resolveTarget(ctx, a)
            if (t == null) return@reg fail(err ?: "Contact not found")
            fromPair(WhatsAppExtras.sendFile(ctx, t, str(a, "path", "file", "query"), str(a, "caption", "message")))
        }

        reg(
            "whatsapp_send_to_number",
            "Send a WhatsApp text to a phone number that is NOT saved in contacts.",
            props("number" to prop("string", "Phone number with or without country code"), "message" to prop("string", "Text to send")),
            listOf("number", "message"), risky = true
        ) { ctx, a ->
            val num = PhoneNumbers.normalize(str(a, "number", "phone"))
            val msg = str(a, "message", "text") ?: return@reg fail("Message kya bhejna hai?")
            if (num.length < 8) return@reg fail("That doesn't look like a valid phone number.")
            val (okk, m) = WhatsAppExtras.openChat(ctx, num, msg, autoSend = true)
            if (okk) ok("Sending \"$msg\" to +$num on WhatsApp." + (if (!JarvisAccessibilityService.isRunning()) " Accessibility is off — tap Send yourself." else ""))
            else fail(m)
        }

        reg(
            "whatsapp_write_message",
            "Open a WhatsApp chat and TYPE a message WITHOUT sending it — the user reviews and presses Send.",
            props("contact" to prop("string", "Contact name or number"), "message" to prop("string", "Draft text")),
            listOf("contact", "message")
        ) { ctx, a ->
            val (t, err) = resolveTarget(ctx, a)
            if (t == null) return@reg fail(err ?: "Contact not found")
            val msg = str(a, "message", "text") ?: return@reg fail("Kya likhna hai?")
            val (okk, m) = WhatsAppExtras.openChat(ctx, t.phone, msg, autoSend = false)
            if (okk) ok("Message typed in ${t.displayName}'s chat but NOT sent — check it and press Send.") else fail(m)
        }

        reg(
            "whatsapp_read_chat",
            "Open a WhatsApp chat and read the last messages visible on screen (needs accessibility). Leave contact empty to read the chat already open.",
            props("contact" to prop("string", "Contact name or number (optional)"), "count" to prop("integer", "How many lines (default 10)"))
        ) { ctx, a ->
            val who = str(a, "contact", "name", "number")
            val target = if (who == null) null else when (val t = WhatsAppExtras.resolve(ctx, who)) {
                is WhatsAppExtras.Target.Resolved -> t
                else -> return@reg fail(WhatsAppExtras.targetFailure(t))
            }
            fromJson(WhatsAppExtras.readChat(ctx, target, int(a, "count", 10)))
        }

        reg(
            "whatsapp_delete_message",
            "Delete the LAST (most recent) message in a WhatsApp chat (the open chat, or the given contact's chat), for me or for everyone. For an older message the assistant sent earlier ('kuch din pehle wala message'), use whatsapp_delete_sent_message instead.",
            props("contact" to prop("string", "Optional contact"), "for_everyone" to prop("boolean", "Delete for everyone (default true)")),
            risky = true
        ) { _, a ->
            val res = JarvisApp.instance.whatsAppAssistant.deleteLastMessage(str(a, "contact", "name"), bool(a, "for_everyone", true))
            if (res.startsWith("Could") || res.startsWith("I couldn't") || res.startsWith("Which")) fail(res) else ok(res)
        }

        reg(
            "whatsapp_delete_sent_message",
            "Delete a WhatsApp message that YOU (the assistant) sent earlier — e.g. 'jo message kuch din pehle bheja tha wo delete kar do', 'Rahul ko jo bheja tha wo hata do'. Finds it in the log of sent messages, opens that chat, scrolls up to the exact message and deletes it. Use this (not search) for any 'delete the message you sent' request; use whatsapp_delete_message only for the last message in the open chat.",
            props(
                "contact" to prop("string", "Who it was sent to (name or number), if the user said"),
                "text_hint" to prop("string", "Any words from the message, if the user said"),
                "for_everyone" to prop("boolean", "Delete for everyone (default true; WhatsApp allows this only for ~60 hours)")
            ),
            risky = true
        ) { ctx, a ->
            val contact = str(a, "contact", "name")
            val hint = str(a, "text_hint", "text", "message")
            if (com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance == null) {
                return@reg fail("Accessibility service OFF hai — Settings > Accessibility me Jarvis ON karo, phir bolo.")
            }
            var entry: SentMessageLog.Entry? = SentMessageLog.find(SentMessageLog.all(ctx), contact, hint)
            var ageKnown = entry != null
            if (entry == null && contact != null && hint != null) {
                // Not in the log (e.g. sent before logging existed): with a contact + some words we
                // can still open that chat and search for the bubble.
                val match = com.jarvis.assistant.util.ContactCaller.findMatches(ctx, contact).firstOrNull()
                var d = match?.number?.filter { it.isDigit() }.orEmpty()
                if (d.length == 10) d = "91$d"
                if (match != null && d.isNotEmpty()) {
                    entry = SentMessageLog.Entry(match.name, d, hint, "com.whatsapp", System.currentTimeMillis())
                    ageKnown = false
                }
            }
            if (entry == null) {
                return@reg fail(
                    if (contact == null || hint == null) "Ye message mere record me nahi hai. Kise bheja tha aur message me kya likha tha? (dono batao, main chat khol ke dhoondh lunga)"
                    else "'$contact' naam ka contact nahi mila. Naam dobara batao."
                )
            }
            val wantEveryone = bool(a, "for_everyone", true)
            val everyone = wantEveryone && SentMessageLog.canDeleteForEveryone(entry)
            // Arm first so accessibility acts as soon as the chat is on screen, then open that chat.
            com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.triggerWhatsAppDeleteMatching(entry.text, everyone)
            try {
                val open = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/${entry.digits}")).apply {
                    setPackage(entry.pkg)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                com.jarvis.assistant.util.ActivityLauncherHelper.startActivitySafely(ctx, open)
            } catch (e: Exception) {
                return@reg fail("WhatsApp chat nahi khul paayi: ${e.message}")
            }
            val days = ((System.currentTimeMillis() - entry.sentAt) / (24L * 60 * 60 * 1000)).toInt()
            val age = if (!ageKnown) "pehle" else if (days <= 0) "aaj" else "$days din pehle"
            val how = when {
                everyone && !ageKnown -> "sab ke liye agar WhatsApp allow kare (60 ghante se purana ho to sirf mere liye)"
                everyone -> "sab ke liye (delete for everyone)"
                wantEveryone -> "sirf mere liye — message 60 ghante se purana hai, WhatsApp sab ke liye delete nahi karne deta"
                else -> "sirf mere liye"
            }
            ok(
                "${entry.contact} ko $age bheja message \"${entry.text.take(40)}\" dhoondh ke delete kar raha hoon, $how.",
                org.json.JSONObject().put("contact", entry.contact).put("text", entry.text).put("sent_at", entry.sentAt).put("for_everyone", everyone)
            )
        }

        reg("whatsapp_status","WhatsApp status overview: installed app, accessibility/notification access, auto-reply/support mode, handled contacts and recent messages.") { ctx, _ ->
            ok(WhatsAppExtras.statusSummary(ctx), WhatsAppExtras.status(ctx))
        }

        reg(
            "send_screenshot",
            "Take a screenshot of the current screen and send it on WhatsApp to a contact.",
            props("contact" to prop("string", "Contact name or number"), "caption" to prop("string", "Optional caption")),
            listOf("contact"), risky = true
        ) { ctx, a ->
            val (t, err) = resolveTarget(ctx, a)
            if (t == null) return@reg fail(err ?: "Contact not found")
            fromPair(WhatsAppExtras.sendScreenshot(ctx, t, str(a, "caption")))
        }

    }

    // ================= C. Reels / social =================

    private fun installSocial() {
        val reelProps = props(
            "video_path" to prop("string", "Video path / URI, or empty / 'video' for the newest video"),
            "caption" to prop("string", "Caption + hashtags (tell the user and get a clear 'haan' before Share)")
        )
        reg(
            "instagram_post_reel",
            "Upload a VIDEO as an Instagram REEL: loads it into Instagram's reel composer and continues step by step. Never press Share more than once; " +
                "tell the user the caption and wait for a clear 'haan' before publishing; say 'Post ho gayi' only when the screen shows upload/posted.",
            reelProps, listOf("caption"), risky = true
        ) { ctx, a -> fromJson(SocialReelFlows.postReel(ctx, "instagram", str(a, "video_path", "video", "path"), str(a, "caption"))) }

        reg(
            "facebook_post_reel",
            "Upload a VIDEO as a Facebook REEL (same rules as instagram_post_reel: one Share press, 'haan' first, never touch Boost/cross-posting).",
            reelProps, listOf("caption"), risky = true
        ) { ctx, a -> fromJson(SocialReelFlows.postReel(ctx, "facebook", str(a, "video_path", "video", "path"), str(a, "caption"))) }

        // post_story is owned by the business pack (maya/business/BusinessToolPack.kt); SocialReelFlows.postStory()
        // remains available to it / other packs as the composer-opening implementation.

        reg(
            "skip_ads",
            "Turn the YouTube auto 'Skip ad' watcher on or off (taps Skip ad whenever it appears).",
            props("enabled" to prop("boolean", "true = start, false = stop")), listOf("enabled")
        ) { _, a ->
            val on = bool(a, "enabled", true)
            val msg = SocialReelFlows.setSkipAds(on)
            if (on && !SocialReelFlows.skipAdsEnabled) fail(msg) else ok(msg)
        }
    }

    // ================= D. Music & YouTube =================

    private fun installMusicYouTube() {
        val musicProps = props(
            "query" to prop("string", "Song / artist / playlist"),
            "app" to prop("string", "Optional: youtube_music, spotify, gaana, jiosaavn, wynk, amazon_music, youtube")
        )
        reg("play_music", "Play music (song, artist, playlist or mood) in the user's music app (default app from set_music_preferences).", musicProps, listOf("query")) { ctx, a ->
            fromPair(MusicPlayback.play(ctx, str(a, "query", "song", "name") ?: return@reg fail("Kya bajaun?"), str(a, "app")))
        }
        reg("play_song", "Play a specific song by name in the music app.", musicProps, listOf("query")) { ctx, a ->
            fromPair(MusicPlayback.play(ctx, str(a, "query", "song", "name") ?: return@reg fail("Kaunsa gaana?"), str(a, "app")))
        }
        reg("play_favorite_song", "Play the user's favourite (most played) song.", props("app" to prop("string", "Optional music app"))) { ctx, a ->
            val fav = MusicPlayback.favoriteSong()
            if (fav.isBlank()) fail("Abhi tak koi favourite song nahi pata — koi gaana chalao, main yaad rakh lunga.")
            else fromPair(MusicPlayback.play(ctx, fav, str(a, "app")))
        }
        reg(
            "set_music_preferences", "Set the default music app used by play_music.",
            props("default_app" to prop("string", "youtube_music | spotify | gaana | jiosaavn | wynk | amazon_music | youtube")), listOf("default_app")
        ) { ctx, a ->
            val id = MusicPlayback.normalizeAppId(str(a, "default_app", "app"))
            val app = MusicPlayback.APPS.firstOrNull { it.id == id } ?: return@reg fail("Unknown music app. Options: ${MusicPlayback.APPS.joinToString { it.id }}")
            MediaPrefs.get(ctx).defaultMusicApp = app.id
            ok("Default music app set to ${app.label}." + (if (!MusicPlayback.isInstalled(ctx, app.pkg)) " (Note: ${app.label} isn't installed.)" else ""))
        }
        reg("get_now_playing", "What song/media is playing right now (any app).") { ctx, _ ->
            val j = MusicPlayback.nowPlaying(ctx); ok(j.optString("message"), j)
        }
        reg("recent_songs", "List recently played and most played songs plus the favourite.") { ctx, _ ->
            val j = MusicPlayback.recentSongs(ctx); ok(j.optString("message"), j)
        }

        val chProps = props("channel" to prop("string", "Channel name, @handle, URL or channel id"))
        reg("youtube_channel_stats", "YouTube channel statistics (subscribers, views, video count) via the YouTube Data API.", chProps, listOf("channel")) { ctx, a ->
            try { val j = YouTubeDataApi.channelStats(ctx, str(a, "channel", "query") ?: ""); ok(j.optString("message"), j) } catch (e: Exception) { fail(e.message ?: "YouTube API error") }
        }
        reg(
            "youtube_video_stats", "YouTube video statistics (views, likes, comments) for a URL, id or title.",
            props("video" to prop("string", "Video URL, id or title")), listOf("video")
        ) { ctx, a ->
            try { val j = YouTubeDataApi.videoStats(ctx, str(a, "video", "url", "query") ?: ""); ok(j.optString("message"), j) } catch (e: Exception) { fail(e.message ?: "YouTube API error") }
        }
        reg(
            "youtube_recent_videos", "Latest uploads of a YouTube channel.",
            props("channel" to prop("string", "Channel name, @handle, URL or id"), "count" to prop("integer", "How many (default 5)")), listOf("channel")
        ) { ctx, a ->
            try { val j = YouTubeDataApi.recentVideos(ctx, str(a, "channel", "query") ?: "", int(a, "count", 5)); ok(j.optString("message"), j) } catch (e: Exception) { fail(e.message ?: "YouTube API error") }
        }
        reg(
            "youtube_thumbnail", "Get (and optionally save to gallery) a YouTube video's thumbnail.",
            props("video" to prop("string", "Video URL, id or title"), "save" to prop("boolean", "Save to gallery and show (default true)")), listOf("video")
        ) { ctx, a ->
            try { val j = YouTubeDataApi.thumbnail(ctx, str(a, "video", "url") ?: "", bool(a, "save", true)); ok(j.optString("message"), j) } catch (e: Exception) { fail(e.message ?: "YouTube API error") }
        }
    }

    // ================= E. Modes / camera =================

    private fun installModes() {
        reg(
            "start_commentary",
            "Start LIVE continuous game/esports commentary on the shared screen until the user says stop ('commentary karo', 'mere game pe bolo'). Needs live session + screen share.",
            props("game" to prop("string", "Optional game name (BGMI, Free Fire, Valorant...)"))
        ) { ctx, a -> fromPair(CommentaryMode.start(ctx, str(a, "game"))) }
        reg("stop_commentary", "Stop live game commentary.") { _, _ -> ok(CommentaryMode.stop()) }

        reg(
            "viewer_describe",
            "Viewer mode: look at the current SCREEN (or camera) and describe it / answer a question about it.",
            props("question" to prop("string", "Optional question"), "source" to prop("string", "screen (default) | camera", listOf("screen", "camera")))
        ) { ctx, a ->
            val q = str(a, "question")
            try {
                if (str(a, "source") == "camera") {
                    val bmp = CameraCapture.takeStill(ctx, false) ?: return@reg fail("Camera unavailable or permission missing.")
                    val ans = try { VisionDescribe.describeBitmap(ctx, bmp, q ?: "Describe what the camera sees.") } finally { bmp.recycle() }
                    return@reg ok(ans)
                }
                val jpeg = JarvisAccessibilityService.captureScreenJpeg(80)
                    ?: return@reg fail("Couldn't capture the screen — enable the accessibility service (Android 11+) or start screen share.")
                ok(VisionDescribe.describe(ctx, jpeg, q))
            } catch (e: Exception) {
                fail(e.message ?: "Vision failed")
            }
        }

        reg(
            "look_through_camera",
            "Take a quick still with the camera (no UI) and answer a question about what it sees.",
            props("question" to prop("string", "What to look for"), "camera" to prop("string", "back (default) | front", listOf("back", "front")))
        ) { ctx, a ->
            val bmp = CameraCapture.takeStill(ctx, str(a, "camera") == "front") ?: return@reg fail("Camera unavailable (permission missing or in use).")
            try {
                ok(VisionDescribe.describeBitmap(ctx, bmp, str(a, "question") ?: "Describe what you see in this camera photo."))
            } catch (e: Exception) {
                fail(e.message ?: "Vision failed")
            } finally {
                bmp.recycle()
            }
        }

        reg(
            "take_photo", "Take a photo with the camera silently and save it to the gallery (Pictures/Jarvis).",
            props("camera" to prop("string", "back (default) | front", listOf("back", "front")))
        ) { ctx, a ->
            val bmp = CameraCapture.takeStill(ctx, str(a, "camera") == "front") ?: return@reg fail("Camera unavailable (permission missing or in use).")
            val saved = MediaFiles.saveImage(ctx, bmp, "JARVIS_PHOTO", 92)
            bmp.recycle()
            if (saved == null) fail("Photo li but save nahi hui.") else ok("Photo saved: ${saved.ref}", JSONObject().put("path", saved.ref))
        }

        reg(
            "record_video", "Record a video with the camera (headless). Stops after `seconds` (default until stop_video_recording, max 10 min).",
            props("seconds" to prop("integer", "Duration in seconds"), "camera" to prop("string", "back (default) | front", listOf("back", "front")))
        ) { ctx, a -> fromPair(CameraCapture.startVideo(ctx, int(a, "seconds", 0), str(a, "camera") == "front")) }

        reg("stop_video_recording", "Stop the running camera video or screen recording and save it to the gallery.") { ctx, _ ->
            val (okk, msg, path) = CameraCapture.stop(ctx)
            if (okk) ok(msg, JSONObject().put("path", path ?: "")) else fail(msg)
        }

        reg(
            "record_screen", "Record the screen to a video (uses the active screen share). Stop with stop_video_recording.",
            props("seconds" to prop("integer", "Duration in seconds (default until stopped)"))
        ) { ctx, a -> fromPair(CameraCapture.startScreenRecording(ctx, int(a, "seconds", 0))) }
    }

    // ================= Misc device tools =================

    private fun installMisc() {
        reg("tell_joke", "Tell a short clean joke (Hinglish by default).", props("topic" to prop("string", "Optional topic"))) { ctx, a ->
            ok(DeviceMisc.tellJoke(ctx, str(a, "topic")))
        }
        reg("get_current_time", "Current time (abhi ka time), optionally in another city/timezone.", props("timezone" to prop("string", "City, country or tz id"))) { _, a ->
            val j = DeviceMisc.currentTime(str(a, "timezone", "city")); ok(j.optString("message"), j)
        }
        reg("get_date", "Today's date and day (aaj ki date).") { _, _ -> val j = DeviceMisc.currentDate(); ok(j.optString("message"), j) }

        reg(
            "set_timer", "Start a countdown timer in the clock app.",
            props("seconds" to prop("string", "Seconds, or text like '5 min', '1:30', '1h 20m'"), "label" to prop("string", "Optional label")), listOf("seconds")
        ) { ctx, a ->
            val secs = a.opt("seconds").let { if (it is Number) it.toInt() else TimerParsing.parseSeconds(it?.toString()) }
                .takeIf { it > 0 } ?: TimerParsing.parseSeconds(str(a, "duration", "time"))
            fromPair(DeviceMisc.setTimer(ctx, secs, str(a, "label")))
        }
        reg("cancel_timer", "Stop/dismiss the running timer (opens timers if it can't be cancelled directly).") { ctx, _ -> fromPair(DeviceMisc.cancelTimer(ctx)) }

        reg(
            "set_ringer_mode", "Set ringer to normal, vibrate or silent.",
            props("mode" to prop("string", "normal | vibrate | silent", listOf("normal", "vibrate", "silent"))), listOf("mode")
        ) { ctx, a -> fromPair(DeviceMisc.setRingerMode(ctx, str(a, "mode") ?: "normal")) }
        reg("set_auto_brightness", "Turn adaptive/auto brightness on or off.", props("enabled" to prop("boolean", "on/off")), listOf("enabled")) { ctx, a ->
            fromPair(DeviceMisc.setAutoBrightness(ctx, bool(a, "enabled", true)))
        }
        reg("set_earpiece_mode", "Play assistant/call audio through the earpiece instead of the loudspeaker (private listening).", props("enabled" to prop("boolean", "on/off")), listOf("enabled")) { ctx, a ->
            fromPair(DeviceMisc.setEarpieceMode(ctx, bool(a, "enabled", true)))
        }
        reg("open_wifi_settings", "Open the Wi-Fi settings panel.") { ctx, _ -> fromPair(DeviceMisc.openSettingsPanel(ctx, "wifi")) }
        reg("open_bluetooth_settings", "Open Bluetooth settings.") { ctx, _ -> fromPair(DeviceMisc.openSettingsPanel(ctx, "bluetooth")) }
        reg("open_hotspot_settings", "Open mobile hotspot / tethering settings.") { ctx, _ -> fromPair(DeviceMisc.openSettingsPanel(ctx, "hotspot")) }
        reg("open_airplane_settings", "Open airplane-mode settings (apps can't toggle it directly).") { ctx, _ -> fromPair(DeviceMisc.openSettingsPanel(ctx, "airplane")) }

        reg("find_email_address", "Find a contact's saved email address.", props("name" to prop("string", "Contact name")), listOf("name")) { ctx, a ->
            val j = DeviceMisc.findEmail(ctx, str(a, "name", "contact") ?: "")
            if (j.optBoolean("ok")) ok(j.optString("message"), j) else fail(j.optString("message"), j)
        }
        reg(
            "save_contact", "Save a new contact (name + number, optional email).",
            props("name" to prop("string", "Name"), "number" to prop("string", "Phone number"), "email" to prop("string", "Optional email")), listOf("name", "number")
        ) { ctx, a -> fromPair(DeviceMisc.saveContact(ctx, str(a, "name") ?: return@reg fail("Naam?"), str(a, "number", "phone") ?: return@reg fail("Number?"), str(a, "email"))) }
        reg(
            "edit_contact", "Edit a contact field (phone, email, name, company, note, address) — opens the editor pre-filled for confirmation.",
            props("name" to prop("string", "Contact name"), "field" to prop("string", "phone | email | name | company | note | address"), "value" to prop("string", "New value")),
            listOf("name", "field", "value")
        ) { ctx, a -> fromPair(DeviceMisc.editContact(ctx, str(a, "name") ?: "", str(a, "field") ?: "note", str(a, "value") ?: "")) }
        reg("add_favorite", "Star a contact (add to favourites).", props("name" to prop("string", "Contact name")), listOf("name")) { ctx, a ->
            fromPair(DeviceMisc.setFavorite(ctx, str(a, "name", "contact") ?: "", true))
        }
        reg("remove_favorite", "Un-star a contact (remove from favourites).", props("name" to prop("string", "Contact name")), listOf("name")) { ctx, a ->
            fromPair(DeviceMisc.setFavorite(ctx, str(a, "name", "contact") ?: "", false))
        }

        reg("search_nearby", "Find nearby places and READ the list (Google Places key from Media Studio); without a key it opens a map search.",
            props("query" to prop("string", "e.g. 'petrol pump', 'chemist', 'pizza'")), listOf("query")) { ctx, a ->
            val j = DeviceMisc.searchNearby(ctx, str(a, "query", "place") ?: "")
            if (j.optBoolean("ok")) ok(j.optString("message"), j) else fail(j.optString("message"), j)
        }
        reg("show_on_map", "Show a place or address on the map (no navigation).", props("place" to prop("string", "Place or address")), listOf("place")) { ctx, a ->
            fromPair(DeviceMisc.showOnMap(ctx, str(a, "place", "query") ?: return@reg fail("Kaunsi jagah?")))
        }

        reg("find_files", "Search files on the phone by name (photos, videos, PDFs, downloads...).",
            props("query" to prop("string", "Part of the file name"), "limit" to prop("integer", "Max results (default 10)")), listOf("query")) { ctx, a ->
            val q = str(a, "query", "name") ?: return@reg fail("Kya dhoondhna hai?")
            val found = MediaFiles.findFiles(ctx, q, int(a, "limit", 10))
            if (found.isEmpty()) return@reg fail("No files matching '$q'.")
            val arr = JSONArray(found.map { JSONObject().put("name", it.name).put("path", it.path).put("size", MediaFiles.humanSize(it.sizeBytes)).put("mime", it.mime) })
            ok("Found ${found.size}: " + found.take(5).joinToString("; ") { "${it.name} (${MediaFiles.humanSize(it.sizeBytes)})" }, JSONObject().put("files", arr))
        }
        reg("open_file", "Open a file in its default app.", props("path" to prop("string", "Path, URI or file name")), listOf("path")) { ctx, a ->
            fromPair(DeviceMisc.openFile(ctx, str(a, "path", "file", "name") ?: return@reg fail("Kaunsi file?")))
        }
        reg("read_document", "Read the text of a document (PDF, TXT, MD, CSV, JSON) so you can summarise or answer questions about it.",
            props("path" to prop("string", "Path or file name")), listOf("path")) { ctx, a ->
            val j = DeviceMisc.readDocument(ctx, str(a, "path", "file", "name") ?: "")
            if (j.optBoolean("ok")) ok(j.optString("message") + "\n\n" + j.optString("text"), j) else fail(j.optString("message"), j)
        }
        reg("look_at_file", "Look at an IMAGE file (path, 'last' photo, 'screenshot', 'generated') and answer a question about it.",
            props("path" to prop("string", "Image path / 'last' / 'screenshot'"), "question" to prop("string", "What to check")), listOf("path")) { ctx, a ->
            val ref = str(a, "path", "file") ?: "last"
            val (uri, mime) = MediaFiles.resolveToUri(ctx, ref) ?: return@reg fail("Image not found: $ref")
            if (!mime.startsWith("image")) return@reg fail("That isn't an image ($mime). Use read_document for documents.")
            try {
                val bmp = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return@reg fail("Couldn't decode the image.")
                try { ok(VisionDescribe.describeBitmap(ctx, bmp, str(a, "question") ?: "Describe this image in detail.")) } finally { bmp.recycle() }
            } catch (e: Exception) {
                fail(e.message ?: "Vision failed")
            }
        }

        reg("open_whiteboard", "Open the full-screen study whiteboard overlay.", props("title" to prop("string", "Optional board title"))) { ctx, a ->
            val m = WhiteboardOverlay.open(ctx, str(a, "title"))
            if (WhiteboardOverlay.canDraw(ctx)) ok(m) else fail(m)
        }
        reg(
            "draw_on_whiteboard",
            "Write/teach on the whiteboard. 'content' is a board script, ONE element per line: '# Heading', '## Sub heading', '- point', " +
                "'$ a^2 + b^2 = c^2 $', '[shape: triangle right-angled]' (box, circle, triangle, arrow, line), '---'. Opens the board automatically.",
            props("content" to prop("string", "Board script"), "append" to prop("boolean", "Add below current content (default false = replace)"), "title" to prop("string", "Optional title")),
            listOf("content")
        ) { ctx, a ->
            val m = WhiteboardOverlay.draw(ctx, str(a, "content", "text") ?: return@reg fail("Board pe kya likhna hai?"), bool(a, "append", false), str(a, "title"))
            if (WhiteboardOverlay.canDraw(ctx)) ok(m) else fail(m)
        }
        reg("clear_whiteboard", "Erase everything on the whiteboard.") { _, _ -> ok(WhiteboardOverlay.clear()) }
        reg("close_whiteboard", "Close the whiteboard and return to the normal screen.") { ctx, _ -> ok(WhiteboardOverlay.close(ctx)) }
    }
}
