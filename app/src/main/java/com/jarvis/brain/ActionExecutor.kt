package com.jarvis.brain

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import java.io.File

/** Parsed command ko local Android action me badalta hai. Koi internet nahi. */
class ActionExecutor(private val ctx: Context) {

    /** Apne custom tasks yahan register karein: name -> handler */
    val customTasks = mutableMapOf<String, (String) -> String>()

    fun execute(c: JarvisCommand): String = runCatching {
        when (c.action) {
            "open_app"      -> openApp(c.str("app"))
            "call"          -> call(c.str("contact"))
            "sms"           -> sms(c.str("contact"), c.str("text"))
            "whatsapp_send" -> whatsapp(c.str("contact"), c.str("message").ifBlank { c.str("text") })
            "set_alarm"     -> start(Intent(AlarmClock.ACTION_SET_ALARM)
                                .putExtra(AlarmClock.EXTRA_HOUR, c.int("hour"))
                                .putExtra(AlarmClock.EXTRA_MINUTES, c.int("minute"))
                                .putExtra(AlarmClock.EXTRA_MESSAGE, c.str("label"))
                                .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Alarm set")
            "set_timer"     -> start(Intent(AlarmClock.ACTION_SET_TIMER)
                                .putExtra(AlarmClock.EXTRA_LENGTH, c.int("seconds"))
                                .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Timer set")
            "flashlight"    -> flashlight(c.str("state") == "on")
            "volume"        -> volume(c.int("level", 50))
            "wifi"          -> start(Intent(Settings.Panel.ACTION_WIFI), "Wi-Fi panel")        // Android 10+ direct toggle allowed nahi
            "bluetooth"     -> start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Bluetooth settings")
            "media"         -> media(c.str("command"))
            "device_info"   -> deviceInfo()
            "note"          -> { File(ctx.filesDir, "notes.txt").appendText(c.str("text") + "\n"); "Note saved" }
            "custom"        -> customTasks[c.str("name")]?.invoke(c.str("args")) ?: "Unknown task ${c.str("name")}"
            "answer"        -> c.str("text")
            "web_search"    -> "Offline mode: web search unavailable"
            else            -> "Unknown action ${c.action}"
        }
    }.getOrElse { "Failed: ${it.message}" }

    private fun start(i: Intent, ok: String): String {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return ok
    }

    private fun openApp(name: String): String {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .minByOrNull { (_, label) -> distance(label.lowercase(), name.lowercase()) }
            ?: return "App not found"
        val i = pm.getLaunchIntentForPackage(match.first) ?: return "App not found"
        return start(i, "Opening ${match.second}")
    }

    private fun resolveNumber(contact: String): String {
        if (contact.any(Char::isDigit)) return contact
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?", arrayOf("%$contact%"), null
        )?.use { if (it.moveToFirst()) return it.getString(0) }
        return contact
    }

    private fun call(contact: String) =
        start(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + resolveNumber(contact))), "Calling $contact")

    private fun sms(contact: String, text: String) =
        start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + resolveNumber(contact))).putExtra("sms_body", text), "SMS ready")

    private fun whatsapp(contact: String, message: String): String {
        return runCatching {
            val phone = resolveNumber(contact).filter { it.isDigit() }
            val cleanPhone = if (phone.length == 10) "91$phone" else phone
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            "Opening WhatsApp for $contact"
        }.getOrElse {
            "Failed sending WhatsApp: ${it.message}"
        }
    }

    private fun deviceInfo(): String {
        val battery = (ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return "Device: ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}, Battery: $battery%"
    }

    private fun flashlight(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.setTorchMode(cm.cameraIdList.first(), on); return if (on) "Torch on" else "Torch off"
    }

    private fun volume(level: Int): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, 100) * max / 100, 0)
        return "Volume $level%"
    }

    private fun media(cmd: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val key = when (cmd) {
            "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
        }
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
        return "Media $cmd"
    }

    private fun distance(a: String, b: String): Int {
        if (a.contains(b) || b.contains(a)) return 0
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i-1][j] + 1, d[i][j-1] + 1, d[i-1][j-1] + if (a[i-1] == b[j-1]) 0 else 1)
        return d[a.length][b.length]
    }
}
