package com.jarvis.assistant.androllm.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.jarvis.assistant.androllm.accessibility.ocr.MlKitOcrEngine
import com.jarvis.assistant.androllm.accessibility.tools.QrScanTool
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.notification.JarvisNotificationListener
import com.jarvis.assistant.automation.tools.ContactResolver
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * AndroLLM tool implementations (core/tools/tool/impl + core/accessibility/tools)
 * that Jarvis did not already have, adapted from Hilt `Tool` classes to plain
 * functions that JarvisToolDispatcher calls. Tools Jarvis already ships
 * (flashlight, volume, wifi, bluetooth status, clipboard, screenshot, share,
 * weather, web search, calculate, calendar, notes, reminders/timers, contacts,
 * app launching, telemetry/battery) are intentionally NOT duplicated.
 *
 * Ported: scan_qr, ocr_screen, convert_units, convert_currency,
 * open_navigation, search_places, control_music, send_email, send_sms,
 * vibrate, read_notifications.
 */
object AndroLlmTools {

    private const val TAG = "AndroLlmTools"

    val NAMES: Set<String> = setOf(
        "scan_qr", "ocr_screen", "convert_units", "convert_currency",
        "open_navigation", "search_places", "control_music", "send_email",
        "send_sms", "vibrate", "read_notifications"
    )

    /** Executes [name]; null when the name is not an AndroLLM tool. */
    suspend fun execute(context: Context, name: String, args: JSONObject): ToolOutcome? = when (name) {
        "scan_qr" -> QrScanTool.execute(args)
        "ocr_screen" -> ocrScreen()
        "convert_units" -> convertUnits(args)
        "convert_currency" -> convertCurrency(args)
        "open_navigation" -> openNavigation(context, args)
        "search_places" -> searchPlaces(context, args)
        "control_music" -> controlMusic(context, args)
        "send_email" -> sendEmail(context, args)
        "send_sms" -> sendSms(context, args)
        "vibrate" -> vibrate(context, args)
        "read_notifications" -> readNotifications(context, args)
        else -> null
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun str(args: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            if (!args.has(k) || args.isNull(k)) continue
            val v = args.opt(k)?.toString()?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    private fun dbl(args: JSONObject, vararg keys: String): Double? {
        for (k in keys) {
            if (!args.has(k) || args.isNull(k)) continue
            val v = args.opt(k)
            val d = when (v) {
                is Number -> v.toDouble()
                is String -> v.trim().replace(",", "").toDoubleOrNull()
                else -> null
            }
            if (d != null && !d.isNaN()) return d
        }
        return null
    }

    private fun launch(context: Context, intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun format(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e12) d.toLong().toString()
        else String.format(Locale.US, "%.4f", d).trimEnd('0').trimEnd('.')

    // ── ocr_screen ──────────────────────────────────────────────────────────

    /** Reads all text visible on screen with ML Kit OCR (works on images / custom UIs). */
    private suspend fun ocrScreen(): ToolOutcome {
        if (!JarvisAccessibilityService.isRunning()) {
            return ToolOutcome.fail("Jarvis Accessibility Service is not enabled. Please enable it in Settings > Accessibility.")
        }
        if (!MlKitOcrEngine.isAvailable) return ToolOutcome.fail("On-device OCR is not available on this device.")
        val bitmap = JarvisAccessibilityService.captureScreenBitmap()
            ?: return ToolOutcome.fail("Could not capture the screen (needs Android 11+).")
        val lines = runCatching { MlKitOcrEngine.recognize(bitmap) }.getOrDefault(emptyList())
        runCatching { bitmap.recycle() }
        if (lines.isEmpty()) return ToolOutcome.ok("No text was recognized on the screen.", JSONObject().put("lines", JSONArray()))
        val text = lines.joinToString("\n")
        return ToolOutcome.ok(
            "Screen text:\n${text.take(3_000)}",
            JSONObject().put("lines", JSONArray(lines.take(200))).put("line_count", lines.size)
        )
    }

    // ── convert_units (AndroLLM UnitConverterTool) ──────────────────────────

    private fun convertUnits(args: JSONObject): ToolOutcome {
        val value = dbl(args, "value", "amount") ?: return ToolOutcome.fail("Missing required argument: value")
        val from = str(args, "from", "unit")?.lowercase() ?: return ToolOutcome.fail("Missing required argument: from")
        val to = str(args, "to")?.lowercase() ?: return ToolOutcome.fail("Missing required argument: to")
        val converted = Units.convert(value, from, to)
            ?: return ToolOutcome.fail("Unknown or incompatible units '$from' -> '$to'.")
        return ToolOutcome.ok(
            "${format(value)} $from = ${format(converted)} $to",
            JSONObject().put("value", value).put("from", from).put("to", to).put("result", converted)
        )
    }

    private object Units {
        val LINEAR = mapOf(
            // length (m)
            "m" to 1.0, "meter" to 1.0, "meters" to 1.0, "km" to 1000.0, "kilometer" to 1000.0,
            "kilometers" to 1000.0, "cm" to 0.01, "centimeter" to 0.01, "mm" to 0.001,
            "mi" to 1609.344, "mile" to 1609.344, "miles" to 1609.344, "ft" to 0.3048,
            "foot" to 0.3048, "feet" to 0.3048, "in" to 0.0254, "inch" to 0.0254, "inches" to 0.0254,
            // mass (kg)
            "kg" to 1.0, "kilogram" to 1.0, "kilograms" to 1.0, "g" to 0.001, "gram" to 0.001,
            "grams" to 0.001, "mg" to 1e-6, "lb" to 0.45359237, "lbs" to 0.45359237,
            "pound" to 0.45359237, "pounds" to 0.45359237, "oz" to 0.028349523125,
            "ounce" to 0.028349523125, "ounces" to 0.028349523125,
            // data (byte)
            "b" to 1.0, "byte" to 1.0, "bytes" to 1.0, "kb" to 1024.0, "kilobyte" to 1024.0,
            "mb" to 1048576.0, "megabyte" to 1048576.0, "gb" to 1073741824.0, "gigabyte" to 1073741824.0,
            "tb" to 1099511627776.0, "terabyte" to 1099511627776.0,
            // volume (L)
            "l" to 1.0, "liter" to 1.0, "liters" to 1.0, "ml" to 0.001, "milliliter" to 0.001,
            "gal" to 3.785411784, "gallon" to 3.785411784, "gallons" to 3.785411784,
            "cup" to 0.2365882365, "cups" to 0.2365882365, "tbsp" to 0.0147867648,
            "tsp" to 0.00492892159,
            // speed (m/s)
            "m/s" to 1.0, "km/h" to 0.277777778, "kph" to 0.277777778,
            "mph" to 0.44704, "mps" to 1.0,
            // time (s)
            "s" to 1.0, "sec" to 1.0, "secs" to 1.0, "second" to 1.0, "seconds" to 1.0,
            "min" to 60.0, "mins" to 60.0, "minute" to 60.0, "minutes" to 60.0,
            "h" to 3600.0, "hr" to 3600.0, "hrs" to 3600.0, "hour" to 3600.0, "hours" to 3600.0,
            "day" to 86400.0, "days" to 86400.0
        )

        /** Each unit's dimension, so "km" -> "kg" is rejected instead of silently computed. */
        private val GROUPS: Map<String, String> = buildMap {
            listOf("m", "meter", "meters", "km", "kilometer", "kilometers", "cm", "centimeter", "mm", "mi", "mile", "miles", "ft", "foot", "feet", "in", "inch", "inches")
                .forEach { put(it, "length") }
            listOf("kg", "kilogram", "kilograms", "g", "gram", "grams", "mg", "lb", "lbs", "pound", "pounds", "oz", "ounce", "ounces")
                .forEach { put(it, "mass") }
            listOf("b", "byte", "bytes", "kb", "kilobyte", "mb", "megabyte", "gb", "gigabyte", "tb", "terabyte")
                .forEach { put(it, "data") }
            listOf("l", "liter", "liters", "ml", "milliliter", "gal", "gallon", "gallons", "cup", "cups", "tbsp", "tsp")
                .forEach { put(it, "volume") }
            listOf("m/s", "km/h", "kph", "mph", "mps").forEach { put(it, "speed") }
            listOf("s", "sec", "secs", "second", "seconds", "min", "mins", "minute", "minutes", "h", "hr", "hrs", "hour", "hours", "day", "days")
                .forEach { put(it, "time") }
        }

        private enum class Temp { C, F, K }

        private val TEMPERATURES = mapOf(
            "c" to Temp.C, "°c" to Temp.C, "celsius" to Temp.C,
            "f" to Temp.F, "°f" to Temp.F, "fahrenheit" to Temp.F,
            "k" to Temp.K, "kelvin" to Temp.K
        )

        fun convert(value: Double, from: String, to: String): Double? {
            val ft = TEMPERATURES[from]
            val tt = TEMPERATURES[to]
            if (ft != null || tt != null) {
                if (ft == null || tt == null) return null
                val c = when (ft) {
                    Temp.C -> value
                    Temp.F -> (value - 32.0) * 5.0 / 9.0
                    Temp.K -> value - 273.15
                }
                return when (tt) {
                    Temp.C -> c
                    Temp.F -> c * 9.0 / 5.0 + 32.0
                    Temp.K -> c + 273.15
                }
            }
            val f = LINEAR[from] ?: return null
            val t = LINEAR[to] ?: return null
            if (GROUPS[from] != GROUPS[to]) return null
            return value * f / t
        }
    }

    // ── convert_currency (AndroLLM CurrencyTool, offline reference table) ───

    /** Value of 1 unit of currency in USD (fixed reference table from AndroLLM). */
    private val RATES = mapOf(
        "USD" to 1.0, "EUR" to 1.09, "GBP" to 1.27, "JPY" to 0.0067,
        "INR" to 0.012, "CAD" to 0.73, "AUD" to 0.66, "CHF" to 1.12,
        "CNY" to 0.14, "BRL" to 0.18, "KRW" to 0.00072, "MXN" to 0.055,
        "SEK" to 0.095, "NOK" to 0.092, "NZD" to 0.60, "SGD" to 0.74,
        "HKD" to 0.128, "ZAR" to 0.055
    )

    private fun convertCurrency(args: JSONObject): ToolOutcome {
        val amount = dbl(args, "amount", "value") ?: return ToolOutcome.fail("Missing required argument: amount")
        val from = str(args, "from", "currency")?.uppercase() ?: return ToolOutcome.fail("Missing required argument: from")
        val to = str(args, "to")?.uppercase() ?: return ToolOutcome.fail("Missing required argument: to")
        val fromRate = RATES[from] ?: return ToolOutcome.fail("Unknown currency '$from'.")
        val toRate = RATES[to] ?: return ToolOutcome.fail("Unknown currency '$to'.")
        val result = amount * fromRate / toRate
        val money = { d: Double -> String.format(Locale.US, "%,.2f", d) }
        return ToolOutcome.ok(
            "${money(amount)} $from is about ${money(result)} $to (approximate offline rates, not live).",
            JSONObject().put("amount", amount).put("from", from).put("to", to)
                .put("result", result).put("approximate", true)
        )
    }

    // ── open_navigation / search_places (AndroLLM MapsTool / MapsSearchTool) ─

    private fun openNavigation(context: Context, args: JSONObject): ToolOutcome {
        val destination = str(args, "destination", "query", "place")
            ?: return ToolOutcome.fail("Missing required argument: destination")
        val mode = str(args, "mode")?.lowercase()
        val m = when (mode) {
            "drive", "driving", "car" -> "d"
            "walk", "walking" -> "w"
            "bicycle", "bike", "cycling" -> "b"
            "transit" -> null // google.navigation has no transit mode; Maps picks the default
            else -> null
        }
        val navUri = Uri.parse("google.navigation:q=${Uri.encode(destination)}" + (m?.let { "&mode=$it" } ?: ""))
        val launched = launch(context, Intent(Intent.ACTION_VIEW, navUri)) ||
            launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}")))
        if (!launched) return ToolOutcome.fail("No maps app found to navigate to '$destination'.")
        return ToolOutcome.ok(
            "Navigation opened to $destination${mode?.let { " ($it)" } ?: ""}.",
            JSONObject().put("destination", destination).put("mode", mode ?: "drive").put("status", "navigation-opened")
        )
    }

    private fun searchPlaces(context: Context, args: JSONObject): ToolOutcome {
        val query = str(args, "query", "place") ?: return ToolOutcome.fail("Missing required argument: query")
        val launched = launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}")))
        if (!launched) return ToolOutcome.fail("No maps app found to search '$query'.")
        return ToolOutcome.ok("Showing map results for \"$query\".", JSONObject().put("query", query).put("status", "search-opened"))
    }

    // ── control_music (AndroLLM MusicTool) ──────────────────────────────────

    private fun controlMusic(context: Context, args: JSONObject): ToolOutcome {
        val action = str(args, "action")?.lowercase() ?: "play"
        if (action !in setOf("play", "pause", "next", "previous", "toggle")) {
            return ToolOutcome.fail("Unknown music action '$action' (use play, pause, next, previous or toggle).")
        }
        // Preferred: active media sessions (blocked on modern Android without
        // MEDIA_CONTENT_CONTROL -> SecurityException -> media-key fallback).
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers = msm.getActiveSessions(null)
            var controlled = false
            for (controller in controllers) {
                val controls = controller.transportControls ?: continue
                when (action) {
                    "play" -> controls.play()
                    "pause" -> controls.pause()
                    "next" -> controls.skipToNext()
                    "previous" -> controls.skipToPrevious()
                    "toggle" -> {
                        val playing = controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                        if (playing) controls.pause() else controls.play()
                    }
                }
                controlled = true
                break
            }
            if (controlled) return ToolOutcome.ok("Music: $action.", JSONObject().put("action", action).put("status", action))
        } catch (t: SecurityException) {
            Log.d(TAG, "media session control blocked - using media-key dispatch")
        } catch (t: Exception) {
            Log.d(TAG, "media session control failed: ${t.message}")
        }
        return runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val key = when (action) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            ToolOutcome.ok("Sent the $action command to your music app.", JSONObject().put("action", action).put("status", "dispatched"))
        }.getOrElse {
            ToolOutcome.fail("This Android version restricts music control (${it.message ?: it.javaClass.simpleName}).")
        }
    }

    // ── send_email (AndroLLM EmailTool — opens a draft, never sends) ────────

    private fun sendEmail(context: Context, args: JSONObject): ToolOutcome {
        val to = str(args, "to", "recipient") ?: return ToolOutcome.fail("Missing required argument: to")
        val subject = str(args, "subject").orEmpty()
        val body = str(args, "body", "message").orEmpty()
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(to)}")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        if (!launch(context, intent)) return ToolOutcome.fail("No email app found to compose to $to.")
        return ToolOutcome.ok(
            "Email draft opened for $to${if (subject.isNotBlank()) " - \"$subject\"" else ""}. The user sends it from their mail app.",
            JSONObject().put("to", to).put("subject", subject).put("status", "draft-opened")
        )
    }

    // ── send_sms (AndroLLM SmsTool, Jarvis confirm-first pattern) ───────────

    private suspend fun sendSms(context: Context, args: JSONObject): ToolOutcome {
        val rawRecipient = str(args, "phone", "to", "number", "recipient")
            ?: return ToolOutcome.fail("Missing required argument: phone")
        val message = str(args, "message", "text", "body")
            ?: return ToolOutcome.fail("Missing required argument: message")
        val confirmed = args.optBoolean("confirmed", false)

        if (!hasPermission(context, Manifest.permission.SEND_SMS)) {
            return ToolOutcome.fail("SMS permission is not granted. Enable \"SMS\" for Jarvis in Android settings, then try again.")
        }
        val phone = if (ContactResolver.isPhoneNumber(rawRecipient)) {
            ContactResolver.normalize(rawRecipient)
        } else {
            val contactsGranted = hasPermission(context, Manifest.permission.READ_CONTACTS)
            val resolved = withContext(Dispatchers.IO) {
                if (contactsGranted) ContactResolver.resolveByName(context, rawRecipient) else null
            }?.let { ContactResolver.normalize(it) }
            if (resolved.isNullOrEmpty()) {
                return ToolOutcome.fail(
                    if (contactsGranted) "No contact named \"$rawRecipient\" was found. Please give me the phone number."
                    else "\"$rawRecipient\" isn't a phone number and Contacts access isn't granted - give me the number directly."
                )
            }
            resolved
        }
        val display = if (rawRecipient == phone) phone else "$rawRecipient ($phone)"
        if (!confirmed) {
            // Jarvis pattern (same as send_whatsapp_message): never send silently.
            return ToolOutcome.fail(
                "Draft ready for $display: \"${message.take(160)}\". Ask the user to confirm, then call send_sms again with confirmed=true.",
                JSONObject().put("requires_confirmation", true).put("phone", phone).put("recipient", rawRecipient)
            )
        }
        return runCatching {
            val manager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            manager.sendMultipartTextMessage(phone, null, manager.divideMessage(message), null, null)
            ToolOutcome.ok(
                "SMS sent to $display: \"${message.take(60)}\"",
                JSONObject().put("recipient", rawRecipient).put("phone", phone).put("status", "sent")
            )
        }.getOrElse { ToolOutcome.fail("SMS could not be sent: ${it.message ?: it.javaClass.simpleName}") }
    }

    // ── vibrate (AndroLLM VibrateTool) ──────────────────────────────────────

    private fun vibrate(context: Context, args: JSONObject): ToolOutcome {
        val duration = args.optInt("duration_ms", 500).coerceIn(50, 5000)
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return ToolOutcome.fail("This device has no vibrator.")
        val ok = runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(duration.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            true
        }.getOrDefault(false)
        return if (ok) ToolOutcome.ok("Vibrated for ${duration}ms.", JSONObject().put("duration_ms", duration))
        else ToolOutcome.fail("Could not vibrate the device.")
    }

    // ── read_notifications (AndroLLM NotificationTool over Jarvis' listener) ─

    private fun readNotifications(context: Context, args: JSONObject): ToolOutcome {
        if (!JarvisNotificationListener.isNotificationServiceEnabled(context)) {
            return ToolOutcome.fail(
                "Notification access is not enabled. Open Android Settings > Notification access and allow Jarvis, then ask again."
            )
        }
        val limit = args.optInt("limit", 5).coerceIn(1, 20)
        val items = JarvisNotificationListener.recentNotifications.value
            .sortedByDescending { it.timestamp }
            .take(limit)
        if (items.isEmpty()) return ToolOutcome.ok("No recent notifications.", JSONObject().put("notifications", JSONArray()))
        val arr = JSONArray()
        val sb = StringBuilder("Notifications:")
        for (item in items) {
            arr.put(
                JSONObject()
                    .put("app", item.appLabel.ifBlank { item.packageName })
                    .put("sender", item.sender)
                    .put("text", item.text.take(200))
                    .put("timestamp", item.timestamp)
            )
            sb.append("\n- ").append(item.appLabel.ifBlank { item.packageName })
            if (item.sender.isNotBlank()) sb.append(" / ").append(item.sender)
            if (item.text.isNotBlank()) sb.append(": ").append(item.text.take(100))
        }
        return ToolOutcome.ok(sb.toString(), JSONObject().put("notifications", arr))
    }

    // ── Schemas (OpenAI / Hermes function format) ───────────────────────────

    fun schemas(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        fun prop(type: String, description: String? = null, enum: List<String>? = null) = JSONObject().apply {
            put("type", type)
            description?.let { put("description", it) }
            enum?.let { put("enum", JSONArray(it)) }
        }
        fun tool(name: String, description: String, properties: JSONObject, required: List<String>) {
            out += JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", name)
                    put("description", description)
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", properties)
                        put("required", JSONArray(required))
                    })
                })
            }
        }

        tool("scan_qr", "Read a QR code currently visible on the screen (e.g. a login code on a website), or from an image file path. Returns the decoded text.",
            JSONObject().put("image_path", prop("string", "Optional: decode from this image file instead of the screen")), emptyList())
        tool("ocr_screen", "Read all text visible on the screen with on-device OCR - use when ui_read_screen shows no text (images, games, custom-drawn apps).",
            JSONObject(), emptyList())
        tool("convert_units", "Convert a value between units: length (m, km, mi, ft, in, cm), mass (kg, g, lb, oz), temperature (c, f, k), data (B, KB, MB, GB, TB), volume (L, mL, gal, cup), speed (km/h, mph, m/s), time (s, min, h, day).",
            JSONObject().put("value", prop("number")).put("from", prop("string")).put("to", prop("string")), listOf("value", "from", "to"))
        tool("convert_currency", "Convert an amount between currencies (USD, EUR, GBP, JPY, INR, CAD, AUD, CHF, CNY, BRL, KRW, MXN, SEK, NOK, NZD, SGD, HKD, ZAR). Rates are approximate (offline table) - mention they may not be live.",
            JSONObject().put("amount", prop("number")).put("from", prop("string")).put("to", prop("string")), listOf("amount", "from", "to"))
        tool("open_navigation", "Open turn-by-turn navigation in Google Maps to a destination or address.",
            JSONObject().put("destination", prop("string", "Destination name, address or 'lat,lon'"))
                .put("mode", prop("string", "Optional travel mode", listOf("drive", "walk", "bicycle", "transit"))), listOf("destination"))
        tool("search_places", "Search nearby places (hospitals, restaurants, ATMs, fuel stations...) and show the results on a map.",
            JSONObject().put("query", prop("string", "What to find, e.g. 'nearest hospital'")), listOf("query"))
        tool("control_music", "Control the active music or media player: play, pause, toggle, next or previous track.",
            JSONObject().put("action", prop("string", null, listOf("play", "pause", "toggle", "next", "previous"))), listOf("action"))
        tool("send_email", "Open the email app with a pre-filled draft (recipient, subject, body) for the user to review and send.",
            JSONObject().put("to", prop("string", "Recipient email address")).put("subject", prop("string")).put("body", prop("string")), listOf("to"))
        tool("send_sms", "Send an SMS text message to a phone number or contact name. The first call returns a draft; only call again with confirmed=true after the user explicitly confirms.",
            JSONObject().put("phone", prop("string", "Phone number with country code, or a contact name like 'Mom'"))
                .put("message", prop("string", "The message text"))
                .put("confirmed", prop("boolean", "True only after the user explicitly confirmed sending")), listOf("phone", "message"))
        tool("vibrate", "Vibrate the phone for a short burst.",
            JSONObject().put("duration_ms", prop("integer", "Vibration length in milliseconds (default 500)")), emptyList())
        tool("read_notifications", "Read the most recent chat / call notifications captured by Jarvis, newest first.",
            JSONObject().put("limit", prop("integer", "Max notifications to return (default 5)")), emptyList())
        return out
    }
}
