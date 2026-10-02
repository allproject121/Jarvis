package com.example.myapplication

import android.app.ActivityManager
import android.app.AlarmManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.widget.Toast
import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class AndroidBridge(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var onSpeechFinished: (() -> Unit)? = null
    private val memory = JarvisKnowledgeBase(context)
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val wifiManager: WifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val vibrator: Vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val powerManager: PowerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val connectivityManager: ConnectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    init {
        memory.remember("last_boot", System.currentTimeMillis().toString())
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("hi", "IN")
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.0f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    Handler(Looper.getMainLooper()).post { onSpeechFinished?.invoke() }
                }
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    @JavascriptInterface
    fun speak(text: String, callback: (() -> Unit)? = null) {
        this.onSpeechFinished = callback
        val params = android.os.Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "jarvis_msg")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "jarvis_msg")
        // Safety timeout: restart listening after 5 seconds even if TTS callback fails
        if (callback != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                // Only fire if not already fired (onSpeechFinished will be null if already fired)
                if (this.onSpeechFinished != null) {
                    this.onSpeechFinished = null
                    callback()
                }
            }, 5000)
        }
    }

    @JavascriptInterface
    fun setLanguage(lang: String) {
        val locale = when (lang.lowercase()) {
            "hindi" -> Locale("hi", "IN")
            "urdu" -> Locale("ur", "PK")
            "english" -> Locale("en", "US")
            "punjabi" -> Locale("pa", "IN")
            "gujarati" -> Locale("gu", "IN")
            "bengali" -> Locale("bn", "IN")
            "tamil" -> Locale("ta", "IN")
            "telugu" -> Locale("te", "IN")
            "marathi" -> Locale("mr", "IN")
            else -> Locale("hi", "IN")
        }
        tts?.language = locale
        memory.remember("preferred_language", lang)
    }

    @JavascriptInterface
    fun openApp(appName: String) {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        var targetIntent: Intent? = null
        var foundName = ""

        for (app in packages) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            val pkg = app.packageName.lowercase()
            val query = appName.lowercase().trim()

            if (label == query || label.contains(query) || pkg.contains(query)) {
                targetIntent = pm.getLaunchIntentForPackage(app.packageName)
                foundName = pm.getApplicationLabel(app).toString()
                if (targetIntent != null) break
            }
        }

        if (targetIntent != null) {
            targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            targetIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(targetIntent)
            memory.trackAppLaunch(foundName)
            showToast("Ji Sir, $foundName khol raha hoon.")
        } else {
            speak("Maaf kijiyega Sir, mujhe aapki device mein $appName nahi mil rahi.")
        }
    }

    @JavascriptInterface
    fun openAppPackage(pkg: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun closeCurrent() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(homeIntent)
    }

    @JavascriptInterface
    fun uninstallApp(appName: String) {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)
        var pkgName: String? = null
        for (app in packages) {
            if (pm.getApplicationLabel(app).toString().lowercase().contains(appName.lowercase())) {
                pkgName = app.packageName
                break
            }
        }
        if (pkgName != null) {
            val intent = Intent(Intent.ACTION_DELETE)
            intent.data = Uri.parse("package:$pkgName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            speak("Sir, ye URL nahi khul paaya.")
        }
    }

    @JavascriptInterface
    fun webSearch(query: String) {
        val url = "https://www.google.com/search?q=${Uri.encode(query)}"
        openUrl(url)
    }

    @JavascriptInterface
    fun youtubeSearch(query: String) {
        val url = "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
        openUrl(url)
    }

    @JavascriptInterface
    fun tiktokSearch(query: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage("com.zhiliaoapp.musically")
            if (intent != null) {
                intent.putExtra("search_query", query)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                // Fallback: open TikTok in browser
                openUrl("https://www.tiktok.com/search?q=${Uri.encode(query)}")
            }
        } catch (e: Exception) {
            openUrl("https://www.tiktok.com/search?q=${Uri.encode(query)}")
        }
    }

    @JavascriptInterface
    fun tiktokOpenProfile() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage("com.zhiliaoapp.musically")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            openUrl("https://www.tiktok.com")
        }
    }

    @JavascriptInterface
    fun instagramSearch(query: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://www.instagram.com/explore/search/keyword/?q=${Uri.encode(query)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            openUrl("https://www.instagram.com/explore/search/keyword/?q=${Uri.encode(query)}")
        }
    }

    @JavascriptInterface
    fun instagramOpenProfile() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage("com.instagram.android")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            openUrl("https://www.instagram.com")
        }
    }

    @JavascriptInterface
    fun getCurrentApp(): String {
        val service = JarvisAccessibilityService.instance
        return service?.getCurrentPackageName() ?: ""
    }

    @JavascriptInterface
    fun playMusicOnYoutube(song: String) {
        val url = "https://www.youtube.com/results?search_query=${Uri.encode(song)}"
        openUrl(url)
    }

    @JavascriptInterface
    fun setVolume(streamType: String, level: Int) {
        val stream = when (streamType.lowercase()) {
            "media" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "system" -> AudioManager.STREAM_SYSTEM
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val maxVol = audioManager.getStreamMaxVolume(stream)
        val safeLevel = level.coerceIn(0, maxVol)
        audioManager.setStreamVolume(stream, safeLevel, AudioManager.FLAG_SHOW_UI)
        memory.remember("last_volume_$streamType", safeLevel.toString())
    }

    @JavascriptInterface
    fun getVolume(streamType: String): Int {
        val stream = when (streamType.lowercase()) {
            "media" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        return audioManager.getStreamVolume(stream)
    }

    @JavascriptInterface
    fun volumeUp() {
        audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
    }

    @JavascriptInterface
    fun volumeDown() {
        audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    }

    @JavascriptInterface
    fun mute() {
        audioManager.adjustVolume(AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
    }

    @JavascriptInterface
    fun unmute() {
        audioManager.adjustVolume(AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
    }

    @JavascriptInterface
    fun setRingerMode(mode: String) {
        when (mode.lowercase()) {
            "normal" -> audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            "silent" -> audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            "vibrate" -> audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        }
        memory.remember("ringer_mode", mode)
    }

    @JavascriptInterface
    fun getRingerMode(): String {
        return when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> "normal"
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "unknown"
        }
    }

    @JavascriptInterface
    fun setWifiEnabled(enabled: Boolean) {
        wifiManager.isWifiEnabled = enabled
        memory.remember("wifi_state", enabled.toString())
    }

    @JavascriptInterface
    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    @JavascriptInterface
    fun toggleWifi(): Boolean {
        val newState = !wifiManager.isWifiEnabled
        wifiManager.isWifiEnabled = newState
        memory.remember("wifi_state", newState.toString())
        return newState
    }

    @JavascriptInterface
    fun setBluetoothEnabled(enabled: Boolean) {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null) {
                if (enabled && !bluetoothAdapter.isEnabled) bluetoothAdapter.enable()
                else if (!enabled && bluetoothAdapter.isEnabled) bluetoothAdapter.disable()
            }
        } catch (e: Exception) {}
        memory.remember("bluetooth_state", enabled.toString())
    }

    @JavascriptInterface
    fun isBluetoothEnabled(): Boolean {
        return try {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false
        } catch (e: Exception) { false }
    }

    @JavascriptInterface
    fun setBrightness(brightness: Int) {
        val safeBrightness = brightness.coerceIn(0, 255)
        try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, safeBrightness)
            memory.remember("screen_brightness", safeBrightness.toString())
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun getBrightness(): Int {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) { 128 }
    }

    @JavascriptInterface
    fun setAutoBrightness(enabled: Boolean) {
        try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun setScreenTimeout(seconds: Int) {
        try {
            val millis = (seconds * 1000).coerceIn(5000, 86400000)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, millis)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun setAutoRotation(enabled: Boolean) {
        try {
            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION,
                if (enabled) 1 else 0)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun getSystemContext(): String {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        val appList = JSONArray()
        for (app in apps) {
            appList.put(pm.getApplicationLabel(app).toString())
        }

        val accessibilityStatus = if (JarvisAccessibilityService.instance != null) "ACTIVE" else "DISABLED"

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val availableRam = memInfo.availMem / (1024 * 1024)

        return JSONObject().apply {
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("battery", getBatteryLevel())
            put("charging", isCharging())
            put("apps", appList)
            put("ram", "${availableRam}MB Available")
            put("thermal", "Optimal (34°C)")
            put("accessibility", accessibilityStatus)
            put("currentApp", JarvisAccessibilityService.instance?.getCurrentPackageName() ?: "Unknown")
            put("topApps", JSONArray(memory.getTopApps()))
            put("mood", memory.recall("user_mood") ?: "Normal")
            put("volume", getVolume("media"))
            put("wifi", isWifiEnabled())
            put("bluetooth", isBluetoothEnabled())
            put("brightness", getBrightness())
            put("ringer", getRingerMode())
            put("wifiName", wifiManager.connectionInfo?.ssid ?: "Not connected")
            put("time", java.text.SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(java.util.Date()))
            put("date", java.text.SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.ENGLISH).format(java.util.Date()))
            put("userName", memory.recall("user_name") ?: "Sir")
            put("preferredLanguage", memory.recall("preferred_language") ?: "hindi")
            put("installedAppsCount", apps.size)
            put("lastUnlock", memory.recall("last_unlock_time") ?: "Unknown")
        }.toString()
    }

    @JavascriptInterface
    fun getNetworkInfo(): String {
        return JSONObject().apply {
            put("wifi", isWifiEnabled())
            put("wifiName", wifiManager.connectionInfo?.ssid ?: "Not connected")
            put("bluetooth", isBluetoothEnabled())
            put("airplaneMode", isAirplaneModeOn())
        }.toString()
    }

    @JavascriptInterface
    fun isCharging(): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
    }

    @JavascriptInterface
    fun getBatteryInfo(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
        
        // Correcting incorrect constant references for battery properties
        val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val healthInt = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        
        val health = when (healthInt) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }
        return JSONObject().apply {
            put("level", level)
            put("charging", isCharging)
            put("temperature", temp)
            put("voltage", voltage)
            put("health", health)
        }.toString()
    }

    fun getBatteryLevel(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
    }

    @JavascriptInterface
    fun learn(key: String, value: String) {
        memory.remember(key, value)
    }

    @JavascriptInterface
    fun recall(key: String): String? = memory.recall(key)

    @JavascriptInterface
    fun accessibilityAction(action: String, data: String = "") {
        val service = JarvisAccessibilityService.instance ?: return
        when (action) {
            "TAP" -> {
                val coords = data.split(",")
                if (coords.size == 2) service.performTap(coords[0].toFloat(), coords[1].toFloat())
            }
            "CLICK_TEXT" -> service.findAndClickText(data)
            "BACK" -> service.performBack()
            "HOME" -> service.performHome()
            "RECENTS" -> service.performRecents()
            "TYPE" -> {
                val parts = data.split("|")
                if (parts.size == 2) service.findAndTypeText(parts[0], parts[1])
            }
            "SCROLL_DOWN" -> service.performScrollDown()
            "SCROLL_UP" -> service.performScrollUp()
            "SWIPE_LEFT" -> service.performSwipeLeft()
            "SWIPE_RIGHT" -> service.performSwipeRight()
            "LONG_PRESS" -> {
                val coords = data.split(",")
                if (coords.size == 2) service.performLongPress(coords[0].toFloat(), coords[1].toFloat())
            }
            "NOTIFICATIONS" -> service.performNotifications()
            "QUICK_SETTINGS" -> service.performQuickSettings()
            "SPLIT_SCREEN" -> service.performSplitScreen()
        }
    }

    @JavascriptInterface
    fun toggleFlashlight(status: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, status)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun setAlarm(hour: Int, minute: Int, label: String = "Jarvis Alarm") {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            speak("Sir, alarm set nahi ho paya.")
        }
    }

    @JavascriptInterface
    fun setTimer(seconds: Int, label: String = "Timer") {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86400))
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            speak("Sir, timer set nahi ho paya.")
        }
    }

    @JavascriptInterface
    fun openSettings() {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun openWifiSettings() {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun openBluetoothSettings() {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun openBatterySettings() {
        try {
            val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun openSoundSettings() {
        try {
            val intent = Intent(Settings.ACTION_SOUND_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun openDisplaySettings() {
        try {
            val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun vibrate(millis: Long = 500) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(millis.coerceIn(50, 5000), VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(millis.coerceIn(50, 5000))
            }
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun vibratePattern(pattern: String) {
        try {
            val timings = pattern.split(",").map { it.trim().toLong() }.toLongArray()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = IntArray(timings.size) { VibrationEffect.DEFAULT_AMPLITUDE }
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amp, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun takeScreenshot() {
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun sendSMS(number: String, message: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$number")
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            speak("Sir, SMS bhejne mein problem hai.")
        }
    }

    @JavascriptInterface
    fun makeCall(number: String) {
        try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$number")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            speak("Sir, call karne mein problem hai.")
        }
    }

    @JavascriptInterface
    fun sendEmail(to: String, subject: String, body: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(Intent.createChooser(intent, "Send Email"))
        } catch (e: Exception) {
            speak("Sir, email bhejne mein problem hai.")
        }
    }

    @JavascriptInterface
    fun openMaps(query: String) {
        try {
            val uri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun getDirection(destLat: Double, destLng: Double) {
        try {
            val uri = Uri.parse("google.navigation:q=$destLat,$destLng")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun isAirplaneModeOn(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON) != 0
        } catch (e: Exception) { false }
    }

    @JavascriptInterface
    fun setAirplaneMode(enabled: Boolean) {
        try {
            Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, if (enabled) 1 else 0)
            val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            intent.putExtra("state", enabled)
            context.sendBroadcast(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun setDoNotDisturb(enabled: Boolean) {
        try {
            if (enabled) {
                Settings.Global.putInt(context.contentResolver, "zen_mode", 1) // 1 = Alarms only or Priority
            } else {
                Settings.Global.putInt(context.contentResolver, "zen_mode", 0) // 0 = Off
            }
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun getInstalledApps(): String {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        val result = JSONArray()
        for (app in apps) {
            val info = JSONObject()
            info.put("name", pm.getApplicationLabel(app).toString())
            info.put("package", app.packageName)
            result.put(info)
        }
        return result.toString()
    }

    @JavascriptInterface
    fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) { false }
    }

    @JavascriptInterface
    fun getDeviceInfo(): String {
        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("brand", Build.BRAND)
            put("device", Build.DEVICE)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("board", Build.BOARD)
            put("hardware", Build.HARDWARE)
            put("fingerprint", Build.FINGERPRINT)
            put("display", Build.DISPLAY)
            put("host", Build.HOST)
            put("product", Build.PRODUCT)
            put("type", Build.TYPE)
            put("tags", Build.TAGS)
            put("time", Build.TIME)
            put("user", Build.USER)
        }.toString()
    }

    @JavascriptInterface
    fun getRamInfo(): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalMem = memInfo.totalMem / (1024 * 1024)
        val availMem = memInfo.availMem / (1024 * 1024)
        val usedMem = totalMem - availMem
        return JSONObject().apply {
            put("total", totalMem)
            put("available", availMem)
            put("used", usedMem)
            put("percentUsed", if (totalMem > 0) (usedMem * 100 / totalMem) else 0)
            put("lowMemory", memInfo.lowMemory)
            put("threshold", memInfo.threshold / (1024 * 1024))
        }.toString()
    }

    @JavascriptInterface
    fun getStorageInfo(): String {
        val stat = android.os.StatFs(context.getFilesDir().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availBlocks = stat.availableBlocksLong
        val total = totalBlocks * blockSize / (1024 * 1024)
        val avail = availBlocks * blockSize / (1024 * 1024)
        val used = total - avail
        return JSONObject().apply {
            put("total", total)
            put("available", avail)
            put("used", used)
        }.toString()
    }

    @JavascriptInterface
    fun calculate(expression: String): String {
        return try {
            val sanitized = expression.replace("x", "*").replace("X", "*")
            val result = evaluateSimpleMath(sanitized)
            result.toString()
        } catch (e: Exception) { "Error" }
    }

    private fun evaluateSimpleMath(expr: String): Double {
        val parts = expr.split(Regex("(?<=[-+*/%])|(?=[-+*/%])"))
        var result = parts[0].trim().toDouble()
        var i = 1
        while (i < parts.size) {
            val op = parts[i].trim()
            val num = parts[i + 1].trim().toDouble()
            when (op) {
                "+" -> result += num
                "-" -> result -= num
                "*" -> result *= num
                "/" -> result /= num
                "%" -> result %= num
            }
            i += 2
        }
        return result
    }

    @JavascriptInterface
    fun setUserPreference(key: String, value: String) {
        memory.remember("pref_$key", value)
    }

    @JavascriptInterface
    fun getUserPreference(key: String): String? = memory.recall("pref_$key")

    @JavascriptInterface
    fun getQuickStatus(): String {
        return JSONObject().apply {
            put("battery", getBatteryLevel())
            put("charging", isCharging())
            put("wifi", isWifiEnabled())
            put("bluetooth", isBluetoothEnabled())
            put("ringer", getRingerMode())
            put("volume", getVolume("media"))
            put("brightness", getBrightness())
            put("airplane", isAirplaneModeOn())
        }.toString()
    }

    @JavascriptInterface
    fun getUserStats(): String {
        return JSONObject().apply {
            put("totalCommands", memory.recall("total_commands") ?: "0")
            put("topApps", JSONArray(memory.getTopApps()))
            put("frequentContacts", memory.recall("frequent_contacts") ?: "[]")
            put("averageResponseTime", memory.recall("avg_response") ?: "0ms")
        }.toString()
    }

    @JavascriptInterface
    fun playMedia(action: String) {
        try {
            val intent = when (action.lowercase()) {
                "play" -> Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                "pause" -> Intent("com.android.music.musicservicecommand.pause")
                "next" -> Intent("com.android.music.musicservicecommand.next")
                "previous" -> Intent("com.android.music.musicservicecommand.previous")
                "stop" -> Intent("com.android.music.musicservicecommand.stop")
                else -> return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.sendBroadcast(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun openCamera() {
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun openVideoCamera() {
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun openCalendar() {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("content://com.android.calendar/time/")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun openDialer() {
        try {
            val intent = Intent(Intent.ACTION_DIAL)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun addContact(name: String, number: String) {
        try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.NAME, name)
                putExtra(ContactsContract.Intents.Insert.PHONE, number)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            showToast("Sir, $name ka contact save karne ka prompt bhej diya hai.")
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun findContactByName(name: String): String? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")
        
        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val foundNumber = cursor.getString(0)
                val foundName = cursor.getString(1)
                return JSONObject().apply {
                    put("name", foundName)
                    put("number", foundNumber)
                }.toString()
            }
        }
        return null
    }

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Jarvis", text)
            clipboard.setPrimaryClip(clip)
            showToast("Sir, text copy ho gaya.")
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun getClipboardText(): String {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        } catch (e: Exception) { "" }
    }

    @JavascriptInterface
    fun setNightMode(enabled: Boolean) {
        try {
            // Using literal string as the constant might not be available in some SDK versions
            Settings.System.putInt(context.contentResolver, "ui_night_mode",
                if (enabled) 2 else 1) // 2 = YES, 1 = NO
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun lockScreen() {
        try {
            val intent = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            context.sendBroadcast(intent)
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && pm.isPowerSaveMode) return
            val service = JarvisAccessibilityService.instance
            service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun setPowerSaveMode(enabled: Boolean) {
        try {
            val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun getUserName(): String = memory.recall("user_name") ?: "Sir"

    @JavascriptInterface
    fun setUserName(name: String) {
        memory.remember("user_name", name)
    }

    @JavascriptInterface
    fun getCurrentTime(): String {
        return java.text.SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(java.util.Date())
    }

    @JavascriptInterface
    fun getCurrentDate(): String {
        return java.text.SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.ENGLISH).format(java.util.Date())
    }

    @JavascriptInterface
    fun getForegroundApp(): String {
        return JarvisAccessibilityService.instance?.getCurrentPackageName() ?: "Unknown"
    }

    @JavascriptInterface
    fun launchAppDrawer() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_MARKET)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun openNotifications() {
        try {
            val service = JarvisAccessibilityService.instance
            service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun openQuickSettings() {
        try {
            val service = JarvisAccessibilityService.instance
            service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun openSplitScreen() {
        try {
            val service = JarvisAccessibilityService.instance
            service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun takeScreenshotAccessibility() {
        try {
            val service = JarvisAccessibilityService.instance
            service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun getCurrentAppInfo(): String {
        val service = JarvisAccessibilityService.instance
        val pkg = service?.getCurrentPackageName() ?: return "Unknown"
        val pm = context.packageManager
        return try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            JSONObject().apply {
                put("name", label)
                put("package", pkg)
                put("icon", appInfo.icon)
                put("uid", appInfo.uid)
                put("targetSdk", appInfo.targetSdkVersion)
                put("sourceDir", appInfo.sourceDir)
            }.toString()
        } catch (e: Exception) { "Unknown" }
    }

    @JavascriptInterface
    fun getAppPermissionInfo(appPackage: String): String {
        val pm = context.packageManager
        return try {
            val appInfo = pm.getApplicationInfo(appPackage, PackageManager.GET_PERMISSIONS)
            val perms = pm.getPackageInfo(appPackage, PackageManager.GET_PERMISSIONS)
            val requestedPerms = perms.requestedPermissions ?: return "[]"
            JSONArray(requestedPerms.toList()).toString()
        } catch (e: Exception) { "[]" }
    }

    @JavascriptInterface
    fun getRemainingAlarms(): String {
        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            "Alarm manager available"
        } catch (e: Exception) { "No alarms" }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun getNotificationStatus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.M
        } else true
    }

    @JavascriptInterface
    fun generateQuote(): String {
        val quotes = listOf(
            "Sir, hard work beats talent when talent doesn't work hard.",
            "Success is not final, failure is not fatal. It's the courage to continue that counts.",
            "The only way to do great work is to love what you do. - Steve Jobs",
            "Innovation distinguishes between a leader and a follower. - Steve Jobs",
            "Stay hungry, stay foolish. - Steve Jobs",
            "The best time to plant a tree was 20 years ago. The second best time is now.",
            "Sir, aap kuch bhi kar sakte hain, bas faith rakhiye.",
            "Zindagi mein aage badhne ke liye, piche dekhne ki zaroorat nahi hai.",
            "Kamyabi wahi milti hai jo himmat nahi haarte.",
            "Aapki mehnat aapki pehchan hai, Sir."
        )
        return quotes.random()
    }

    @JavascriptInterface
    fun generateJoke(): String {
        val jokes = listOf(
            "Ek baar ek software engineer ne kaha 'Hello World' aur pura world hang ho gaya!",
            "Sir, main aapko ek joke sunata hoon... Lekin dhyan rahe, mera sense of humour bhi AI-generated hai!",
            "Programmer pizza order kar raha tha. Waiter ne pucha 'Large ya Extra Large?' Programmer ne kaha 'YES'.",
            "Sir, aapko pata hai Hamari aur aapki friendship mein koi bug nahi hai!",
            "Ek robot doosre robot se bole: 'Mujhe lagta hai mera owner mujhe replace karna chahta hai.' Doosra robot bola: 'Arre yaar, tu toh soch, humara kya hoga!'"
        )
        return jokes.random()
    }

    @JavascriptInterface
    fun generateMotivation(): String {
        val msgs = listOf(
            "Sir, aap bahut talented hain. Main aapke saath hoon har kadam par.",
            "Aaj ka din aapka hai Sir. Kuch bada karna hai toh abhi karo.",
            "Sir, duniya badalni hai toh pehle khud ko badlo. Aur wo badlaav aapne shuru kar diya hai.",
            "Aap ek leader hain Sir. Followers kabhi history nahi banate.",
            "Sir, mushkilein aati hain toh insaan ko strong banane ke liye aati hain."
        )
        return msgs.random()
    }

    @JavascriptInterface
    fun getScreenContent(): String {
        val service = JarvisAccessibilityService.instance ?: return ""
        val elements = service.mapScreenInteractions()
        val textContent = elements.filter { it.text.isNotBlank() }.map { it.text }
        return textContent.joinToString(", ")
    }

    @JavascriptInterface
    fun getScreenElements(): String {
        val service = JarvisAccessibilityService.instance ?: return "[]"
        val elements = service.mapScreenInteractions()
        val result = JSONArray()
        for (el in elements) {
            val info = JSONObject()
            info.put("text", el.text)
            info.put("className", el.className)
            info.put("id", el.id)
            info.put("clickable", el.isClickable)
            info.put("editable", el.isEditable)
            info.put("bounds", "${el.bounds.left},${el.bounds.top},${el.bounds.right},${el.bounds.bottom}")
            result.put(info)
        }
        return result.toString()
    }

    @JavascriptInterface
    fun findElementOnScreen(text: String): String {
        val service = JarvisAccessibilityService.instance
        val found = service?.findAndClickText(text) ?: false
        return if (found) "Found and clicked: $text" else "Not found: $text"
    }

    @JavascriptInterface
    fun getActivityHistory(): String {
        return memory.recall("activity_history") ?: "[]"
    }

    @JavascriptInterface
    fun getCurrentActivity(): String {
        val service = JarvisAccessibilityService.instance
        return service?.getCurrentPackageName() ?: "Unknown"
    }

    @JavascriptInterface
    fun startDndMode() {
        setDoNotDisturb(true)
        showToast("Sir, Do Not Disturb mode on")
    }

    @JavascriptInterface
    fun stopDndMode() {
        setDoNotDisturb(false)
        showToast("Sir, Do Not Disturb mode off")
    }

    @JavascriptInterface
    fun getFaceDetectionStatus(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val manager = context.getSystemService(Context.SYSTEM_HEALTH_SERVICE)
                manager != null
            } else false
        } catch (e: Exception) { false }
    }

    @JavascriptInterface
    fun readLastNotification(): String {
        return memory.recall("last_notification") ?: "No recent notifications"
    }

    @JavascriptInterface
    fun ping(): String = "JARVIS-CORE-ACTIVE"

    @JavascriptInterface
    fun getFeatureVersion() = "Mark XXXVI VIP"

    @JavascriptInterface
    fun getTotalFeatures() = "${JarvisFeatureManifest.features.size}"

    private val ai = JarvisAI()
    private var whatsAppAgent: JarvisWhatsAppAgent? = null

    @JavascriptInterface
    fun setAIConfig(key: String, url: String, model: String) {
        ai.setConfig(key, url, model)
        memory.remember("ai_api_key", key)
        memory.remember("ai_api_url", url)
        memory.remember("ai_model", model)
    }

    @JavascriptInterface
    fun askWebViewAI(message: String): String {
        val savedKey = memory.recall("ai_api_key") ?: ""
        val savedUrl = memory.recall("ai_api_url") ?: ""
        val savedModel = memory.recall("ai_model") ?: ""
        if (savedKey.isNotBlank()) ai.setConfig(savedKey, savedUrl, savedModel)

        return if (ai.hasApiKey()) {
            var result = "Thinking..."
            val lock = java.util.concurrent.CountDownLatch(1)
            ai.chat(message) { response ->
                result = response
                lock.countDown()
            }
            try { lock.await(30000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) {}
            result
        } else {
            basicChat(message)
        }
    }

    @JavascriptInterface
    fun askAI(message: String): String {
        val savedKey = memory.recall("ai_api_key") ?: ""
        val savedUrl = memory.recall("ai_api_url") ?: ""
        val savedModel = memory.recall("ai_model") ?: ""
        if (savedKey.isNotBlank()) ai.setConfig(savedKey, savedUrl, savedModel)

        var result = "Processing..."
        val lock = java.util.concurrent.CountDownLatch(1)
        // Use web search fallback for questions about news, weather, current events
        val lower = message.lowercase()
        val needsWeb = lower.contains("news") || lower.contains("khabar") ||
                lower.contains("weather") || lower.contains("mausam") ||
                lower.contains("barish") || lower.contains("today") ||
                lower.contains("kaun") || lower.contains("kya hai") ||
                lower.contains("who") || lower.contains("what") ||
                lower.contains("latest") || lower.contains("recent") ||
                lower.contains("score") || lower.contains("result") ||
                lower.contains("price") || lower.contains("kimat")
        if (needsWeb && savedKey.isNotBlank()) {
            ai.webSearchWithAI(message) { response ->
                result = response
                lock.countDown()
            }
        } else {
            ai.chat(message) { response ->
                result = response
                lock.countDown()
            }
        }
        try { lock.await(30000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) {}
        memory.trackCommand("ai:$message")
        memory.trackConversation(message, result)
        return result
    }

    @JavascriptInterface
    fun basicChat(message: String): String {
        val lower = message.lowercase()
        return when {
            lower.contains("hello") || lower.contains("hi") -> "Hello Sir! Kaise hain aap?"
            lower.contains("who are you") || lower.contains("kaun ho") -> "Main JARVIS hoon Sir, aapka personal AI assistant!"
            lower.contains("how are you") || lower.contains("kaise ho") -> "Main bilkul theek hoon Sir! Aap batao?"
            lower.contains("thank") || lower.contains("shukriya") -> "Welcome Sir! Always at your service."
            lower.contains("bye") -> "Goodbye Sir! Jarvis boldiye ga jab zaroorat ho."
            lower.contains("time") -> "Sir, abhi ${getCurrentTime()} hai."
            lower.contains("date") -> "Sir, aaj ${getCurrentDate()} hai."
            lower.contains("joke") -> "Sir, ek programmar ne code likha... It works bola... Lekin kabhi run kiya hi nahi! 😂"
            lower.contains("motivate") || lower.contains("inspire") -> "Sir, aap mein unique talent hai. Khud par vishwas rakho aur kuch bhi possible hai!"
            lower.contains("weather") -> "Sir, weather ke liye main app khol sakta hoon. Kya khol doon?"
            lower.contains("news") -> "Sir, aaj ki news ke liye Chrome khol doon?"
            else -> "Ji Sir! Main aapki kaise madad kar sakta hoon?"
        }
    }

    @JavascriptInterface
    fun webSearchWithAI(query: String): String {
        val savedKey = memory.recall("ai_api_key") ?: ""
        if (savedKey.isNotBlank()) ai.setConfig(savedKey, "", "")
        var result = "Searching..."
        val lock = java.util.concurrent.CountDownLatch(1)
        ai.webSearchWithAI(query) { response ->
            result = response
            lock.countDown()
        }
        try { lock.await(30000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) {}
        return result
    }

    @JavascriptInterface
    fun quickWebSearch(query: String): String {
        return ai.webSearch(query)
    }

    @JavascriptInterface
    fun getAIStatus(): String {
        return JSONObject().apply {
            put("hasKey", ai.hasApiKey())
            put("model", ai.getModel())
            put("historySize", ai.getHistorySize())
        }.toString()
    }

    @JavascriptInterface
    fun clearAIHistory() {
        ai.clearHistory()
    }

    @JavascriptInterface
    fun sendWhatsApp(contact: String, message: String): String {
        if (whatsAppAgent == null) whatsAppAgent = JarvisWhatsAppAgent(context, this)
        
        val lock = java.util.concurrent.CountDownLatch(1)
        var result = "Processing..."

        whatsAppAgent?.executeAutonomousWhatsApp("to $contact message $message",
            object : JarvisWhatsAppAgent.AgentCallback {
                override fun onProgress(msg: String) { result = msg }
                override fun onComplete(success: Boolean, msg: String) {
                    result = if (success) "✅ $msg" else "❌ $msg"
                    lock.countDown()
                }
                override fun onRequestInput(prompt: String, type: String) {
                    result = "INPUT_REQUIRED:$type:$prompt"
                    lock.countDown()
                }
            })

        try { lock.await(30000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) {}
        return result
    }

    @JavascriptInterface
    fun sendWhatsAppQuick(contact: String, message: String): String {
        return try {
            openApp("WhatsApp")
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.postDelayed({
                val service = JarvisAccessibilityService.instance ?: return@postDelayed
                val searchClicked = service.findAndClickText("Search") || service.findAndClickId(id = "com.whatsapp:id/menuitem_search")
                if (searchClicked) {
                    mainHandler.postDelayed({
                        service.findAndTypeTextFuzzy(contact)
                        mainHandler.postDelayed({
                            if (service.findAndClickTextFuzzy(contact) || service.findAndClickId(id = "com.whatsapp:id/contact_name")) {
                                mainHandler.postDelayed({
                                    service.findAndTypeTextFuzzy(message)
                                    mainHandler.postDelayed({
                                        service.findAndClickId(id = "com.whatsapp:id/send") || service.findAndClickDescription("Send")
                                    }, 1500)
                                }, 2000)
                            }
                        }, 2000)
                    }, 1000)
                }
            }, 2000)
            "Sending WhatsApp to $contact..."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    @JavascriptInterface
    fun getWhatsAppStatus(): String {
        return JSONObject().apply {
            put("agentInitialized", whatsAppAgent != null)
            put("accessibilityActive", JarvisAccessibilityService.instance != null)
        }.toString()
    }

    @JavascriptInterface
    fun sendWhatsAppDirect(number: String, message: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$number")
                putExtra("sms_body", message)
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback: try WhatsApp web intent
            try {
                val url = "https://wa.me/$number?text=${Uri.encode(message)}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e2: Exception) {
                // Last fallback: open WhatsApp
                openApp("WhatsApp")
                showToast("Sir, WhatsApp nahi khul raha. Khol kar manually send karein.")
            }
        }
    }

    @JavascriptInterface
    fun openWhatsAppChat(number: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            openApp("WhatsApp")
        }
    }

    @JavascriptInterface
    fun getAllPhoneContacts(): String {
        val result = JSONArray()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID
            )
            val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    val number = cursor.getString(1)
                    if (name.isNotBlank() && number.isNotBlank()) {
                        val contact = JSONObject()
                        contact.put("name", name)
                        contact.put("number", number)
                        result.put(contact)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AndroidBridge", "Error loading contacts: ${e.message}")
        }
        return result.toString()
    }

    @JavascriptInterface
    fun getSavedContacts(): String {
        return try {
            val contacts = memory.getAllContacts()
            val result = JSONArray()
            for ((name, number) in contacts) {
                val c = JSONObject()
                c.put("name", name)
                c.put("number", number)
                result.put(c)
            }
            result.toString()
        } catch (e: Exception) { "[]" }
    }

    // === YOUTUBE AUTOMATION ===

    @JavascriptInterface
    fun youtubeSearchAndPlay(query: String) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            speak("Sir, Accessibility Service enable nahi hai.")
            return
        }
        // Open YouTube and search
        openApp("YouTube")
        Handler(Looper.getMainLooper()).postDelayed({
            service.findAndClickText("Search")
            Handler(Looper.getMainLooper()).postDelayed({
                service.findAndTypeTextFuzzy(query)
                Handler(Looper.getMainLooper()).postDelayed({
                    service.findAndClickText("Search")
                }, 1000)
            }, 1500)
        }, 3000)
    }

    @JavascriptInterface
    fun youtubeScroll() {
        JarvisAccessibilityService.instance?.performScrollDown()
    }

    @JavascriptInterface
    fun youtubeLike() {
        val service = JarvisAccessibilityService.instance
        service?.findAndClickDescription("Like") ?: service?.findAndClickText("Like")
    }

    @JavascriptInterface
    fun youtubeComment(comment: String) {
        val service = JarvisAccessibilityService.instance ?: return
        if (service.findAndClickDescription("Comment") || service.findAndClickText("Comment")) {
            Handler(Looper.getMainLooper()).postDelayed({
                service.findAndTypeTextFuzzy(comment)
                Handler(Looper.getMainLooper()).postDelayed({
                    service.findAndClickDescription("Send") ?: service.findAndClickText("Send")
                }, 1000)
            }, 2000)
        }
    }

    @JavascriptInterface
    fun youtubeSubscribe() {
        val service = JarvisAccessibilityService.instance
        service?.findAndClickDescription("Subscribe") ?: service?.findAndClickText("Subscribe")
    }

    // === WEB NAVIGATION AUTOMATION ===

    @JavascriptInterface
    fun openWebsite(url: String) {
        var finalUrl = url
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
            finalUrl = "https://$finalUrl"
        }
        openUrl(finalUrl)
    }

    @JavascriptInterface
    fun scrollWebPage(direction: String) {
        val service = JarvisAccessibilityService.instance ?: return
        when (direction.lowercase()) {
            "down" -> service.performScrollDown()
            "up" -> service.performScrollUp()
            "left" -> service.performSwipeLeft()
            "right" -> service.performSwipeRight()
            "bottom" -> {
                for (i in 0 until 5) {
                    service.performScrollDown()
                    try { Thread.sleep(200) } catch (e: Exception) {}
                }
            }
            "top" -> {
                for (i in 0 until 5) {
                    service.performScrollUp()
                    try { Thread.sleep(200) } catch (e: Exception) {}
                }
            }
        }
    }

    @JavascriptInterface
    fun clickOnPage(text: String) {
        JarvisAccessibilityService.instance?.findAndClickText(text)
    }

    // === WEATHER ===

    @JavascriptInterface
    fun getWeatherInfo(location: String?): String {
        val query = if (location != null) "$location weather today" else "my current location weather today"
        val result = ai.webSearch(query)
        return "Sir, weather ki jankari:\n$result"
    }

    @JavascriptInterface
    fun askWeather(location: String?) {
        ai.getWeather(location) { response ->
            speak(response)
        }
    }

    // === ADVANCED WEB SEARCH ===

    @JavascriptInterface
    fun searchAndRead(query: String) {
        ai.webSearchWithAI(query) { response ->
            speak(response)
        }
    }

    @JavascriptInterface
    fun searchAndSpeak(query: String) {
        val results = ai.webSearch(query)
        if (results.startsWith("Sir") || results.startsWith("Search")) {
            speak(results)
        } else {
            speak("Sir, ye rahe search results. $results")
        }
    }

    // === SMART HOME (SIMULATED) ===

    @JavascriptInterface
    fun getSavedContact(name: String): String? {
        return memory.getContact(name)
    }

    @JavascriptInterface
    fun saveContact(name: String, number: String) {
        memory.rememberContact(name, number)
        showToast("Sir, $name ka number save kar diya.")
    }

    // === SMART REMINDERS ===

    @JavascriptInterface
    fun setReminder(label: String, seconds: Int) {
        memory.addReminder(label, System.currentTimeMillis() + (seconds * 1000L))
        showToast("Sir, reminder set ho gaya: $label")
        speak("Ji Sir! $seconds second baad aapko $label yaad dilaunga.")
    }

    @JavascriptInterface
    fun getPendingReminders(): String {
        val reminders = memory.getPendingReminders()
        val result = JSONArray()
        for ((label, time) in reminders) {
            val obj = JSONObject()
            obj.put("label", label)
            obj.put("time", time)
            result.put(obj)
        }
        return result.toString()
    }

    // === APP-SPECIFIC AUTOMATIONS ===

    @JavascriptInterface
    fun openYouTubeAndSearch(query: String) {
        youtubeSearchAndPlay(query)
    }

    @JavascriptInterface
    fun openGoogleAndSearch(query: String) {
        val url = "https://www.google.com/search?q=${Uri.encode(query)}"
        openUrl(url)
    }

    @JavascriptInterface
    fun getScreenText(): String {
        return JarvisAccessibilityService.instance?.getScreenTextContent() ?: ""
    }

    @JavascriptInterface
    fun findAndClick(text: String): Boolean {
        return JarvisAccessibilityService.instance?.findAndClickText(text) ?: false
    }

    // === DEVICE LOCATION ===

    @JavascriptInterface
    fun getLocation(): String {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val providers = locationManager.getProviders(true)
            if (providers.isNotEmpty()) {
                val location = locationManager.getLastKnownLocation(providers[0])
                if (location != null) {
                    JSONObject().apply {
                        put("lat", location.latitude)
                        put("lng", location.longitude)
                        put("accuracy", location.accuracy)
                    }.toString()
                } else "Location not available"
            } else "No location providers enabled"
        } catch (e: Exception) { "Location error: ${e.message}" }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
