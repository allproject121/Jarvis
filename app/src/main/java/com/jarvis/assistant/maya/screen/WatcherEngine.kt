package com.jarvis.assistant.maya.screen

import android.accessibilityservice.AccessibilityServiceInfo
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Maya's standing watches ("X ho to batana") + automatic phone-event announcements +
 * screen awareness. Watches are one-shot, persisted to filesDir/maya_watches.json and re-armed
 * on start. Screen kinds are polled from the accessibility tree every 1.5 s while armed;
 * device kinds come from runtime BroadcastReceivers / a Wi-Fi NetworkCallback.
 */
object WatcherEngine {

    private const val TAG = "MayaWatcher"
    private const val FILE = "maya_watches.json"
    private const val POLL_MS = 1_500L
    private const val AWARENESS_MIN_GAP_MS = 20_000L

    private val SCREEN_KINDS = setOf("screen_text", "app_opens", "screen_change")
    private val IGNORED_PKGS = setOf("com.android.systemui", "android")

    @Volatile private var started = false
    private lateinit var appCtx: Context
    private val watches = ArrayList<Watch>()
    private var nextId = 1
    private var pollJob: Job? = null
    private var receiversRegistered = false
    /** app_opens watches armed while that app was already in front: wait until it leaves first. */
    private val waitingForLeave = java.util.Collections.synchronizedSet(HashSet<Int>())

    // battery/device state
    @Volatile private var batteryLevel = -1
    @Volatile private var charging = false
    @Volatile private var lowFired = false
    @Volatile private var criticalFired = false
    @Volatile private var fullFired = false
    @Volatile private var wifiConnected: Boolean? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    // screen awareness
    @Volatile private var lastAwarePkg: String = ""
    @Volatile private var lastAwareAt = 0L
    @Volatile private var pendingAwarePkg: String? = null
    private var awarenessJob: Job? = null
    @Volatile private var lastForegroundPkg: String = ""

    // accessibility event-type bits we added (removed again when no longer needed)
    @Volatile private var addedNotificationBit = false

    fun isStarted(): Boolean = started

    @Synchronized
    fun start(context: Context) {
        if (started) return
        appCtx = context.applicationContext
        started = true
        load()
        registerReceivers()
        rearm()
        Log.i(TAG, "WatcherEngine started with ${watches.size} watch(es)")
    }

    // ------------------------------------------------------------------ persistence

    private fun file(): File = File(appCtx.filesDir, FILE)

    private fun load() {
        try {
            val f = file()
            val list = if (f.exists()) WatchCodec.decode(f.readText()) else emptyList()
            synchronized(watches) {
                watches.clear()
                watches.addAll(list)
                nextId = (list.maxOfOrNull { it.id } ?: 0) + 1
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
        }
    }

    private fun save() {
        try {
            val snapshot = synchronized(watches) { watches.toList() }
            file().writeText(WatchCodec.encode(snapshot))
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ public API

    fun list(): List<Watch> = synchronized(watches) { watches.toList() }

    /** Adds a watch; returns it, or an error message. */
    fun add(context: Context, kindRaw: String, value: String, reason: String): Pair<Watch?, String> {
        start(context)
        val kind = WatchCodec.normalizeKind(kindRaw)
            ?: return null to "Unknown kind '$kindRaw'. Use one of: ${WatchCodec.KINDS.joinToString()}"
        val v = value.trim()
        if (kind in setOf("screen_text", "app_opens") && v.isEmpty()) return null to "'$kind' ke liye value chahiye (kaunse shabd / kaunsa app)."
        if (kind == "battery" && WatchCodec.parseBatteryCondition(v) == null) return null to "Battery ke liye value do, jaise 'below 20' ya 'full'."
        if (kind in SCREEN_KINDS && !ScreenElementSerializer.isServiceOn()) return null to ScreenElementSerializer.SERVICE_OFF
        var baseline = ""
        // A screen_text watch whose text is ALREADY visible waits for it to go away and come back.
        val already = kind == "screen_text" && ScreenElementSerializer.allVisibleText().any { it.contains(v, ignoreCase = true) }
        if (already) baseline = "present"
        if (kind == "screen_change") baseline = ScreenElementSerializer.signature()?.contentHash?.toString() ?: ""
        if (kind == "app_opens") {
            // resolve the app name to a package if we can (label match still works as fallback)
            try {
                val m = com.jarvis.assistant.automation.tools.AppIndex.search(context, v).matches.firstOrNull()
                if (m != null) baseline = m.packageName
            } catch (_: Exception) {}
        }
        val w = synchronized(watches) {
            val created = Watch(nextId++, kind, v, reason.trim(), System.currentTimeMillis(), baseline)
            watches.add(created)
            created
        }
        if (kind == "app_opens") {
            val fg = ScreenElementSerializer.foregroundPackage()
            if (fg.isNotEmpty() && matchesApp(w, fg)) waitingForLeave.add(w.id)
        }
        save()
        rearm()
        Log.i(TAG, "WATCH added id=${w.id} kind=$kind value=$v")
        val msg = "Watch laga diya (#${w.id}): ${w.describe()}." + if (already) " Note: '$v' abhi bhi screen pe dikh raha hai — jab ye hat ke dobara aayega tab bataunga." else ""
        return w to msg
    }

    /** Cancels by id, keyword, or "all". Returns number removed. */
    fun cancel(selector: String): Int {
        val sel = selector.trim().lowercase().removePrefix("#")
        var removed = 0
        synchronized(watches) {
            val before = watches.size
            when {
                sel.isEmpty() -> {}
                sel == "all" || sel == "sab" || sel == "*" -> watches.clear()
                sel.toIntOrNull() != null -> watches.removeAll { it.id == sel.toInt() }
                else -> watches.removeAll { it.value.lowercase().contains(sel) || it.reason.lowercase().contains(sel) || it.kind.contains(sel) }
            }
            removed = before - watches.size
        }
        if (removed > 0) {
            save()
            rearm()
        }
        return removed
    }

    // ------------------------------------------------------------------ arming

    private fun rearm() {
        val list = list()
        val needScreen = list.any { it.kind in SCREEN_KINDS }
        if (needScreen && pollJob?.isActive != true) {
            Log.i(TAG, "WATCH screen poll on")
            pollJob = ScreenRuntime.scope.launch {
                while (isActive) {
                    try { pollScreen() } catch (e: Exception) { Log.d(TAG, "poll error: ${e.message}") }
                    delay(POLL_MS)
                }
            }
        } else if (!needScreen && pollJob != null) {
            Log.i(TAG, "WATCH screen poll off — nothing armed")
            pollJob?.cancel()
            pollJob = null
        }
        updateEventTypes(list.any { it.kind == "notification" })
    }

    @Volatile private var lastEnsureAt = 0L

    /** Re-applies the notification event bit after a service reconnect (throttled). */
    fun ensureEventTypes() {
        if (!started) return
        val now = System.currentTimeMillis()
        if (now - lastEnsureAt < 10_000L) return
        lastEnsureAt = now
        if (list().any { it.kind == "notification" }) updateEventTypes(true)
    }

    /** Adds/removes TYPE_NOTIFICATION_STATE_CHANGED on the accessibility service at runtime. */
    fun updateEventTypes(needNotifications: Boolean) {
        val svc = JarvisAccessibilityService.instance ?: return
        try {
            val info: AccessibilityServiceInfo = svc.serviceInfo ?: return
            val bit = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            if (needNotifications && (info.eventTypes and bit) == 0) {
                info.eventTypes = info.eventTypes or bit
                svc.serviceInfo = info
                addedNotificationBit = true
            } else if (!needNotifications && addedNotificationBit) {
                info.eventTypes = info.eventTypes and bit.inv()
                svc.serviceInfo = info
                addedNotificationBit = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateEventTypes failed: ${e.message}")
        }
    }

    private fun pollScreen() {
        val armed = list().filter { it.kind in SCREEN_KINDS }
        if (armed.isEmpty()) return
        if (!ScreenElementSerializer.isServiceOn()) return
        val pkg = ScreenElementSerializer.foregroundPackage()
        var texts: List<String>? = null
        var sig: String? = null
        for (w in armed) {
            when (w.kind) {
                "screen_text" -> {
                    val t = texts ?: ScreenElementSerializer.allVisibleText().also { texts = it }
                    val hit = t.firstOrNull { it.contains(w.value, ignoreCase = true) }
                    if (hit != null && w.baseline != "present") fire(w, "screen pe dikha: \"${hit.take(120)}\"")
                    else if (hit == null && w.baseline == "present") replace(w.copy(baseline = ""))
                }
                "app_opens" -> checkAppOpen(w, pkg)
                "screen_change" -> {
                    val s = sig ?: (ScreenElementSerializer.signature()?.contentHash?.toString() ?: "").also { sig = it }
                    if (w.baseline.isEmpty()) {
                        // baseline missing (armed while screen unreadable) — take it now
                        replace(w.copy(baseline = s))
                    } else if (s.isNotEmpty() && s != w.baseline) {
                        if (w.value.isBlank() || (texts ?: ScreenElementSerializer.allVisibleText().also { texts = it }).any { it.contains(w.value, ignoreCase = true) }) {
                            fire(w, "screen badal gayi (ab: ${ScreenElementSerializer.appLabel(appCtx, pkg)})")
                        }
                    }
                }
            }
        }
    }

    private fun checkAppOpen(w: Watch, pkg: String) {
        if (pkg.isEmpty()) return
        val match = matchesApp(w, pkg)
        if (w.id in waitingForLeave) {
            if (!match) waitingForLeave.remove(w.id)
            return
        }
        if (match) fire(w, "${ScreenElementSerializer.appLabel(appCtx, pkg)} khul gaya")
    }

    private fun matchesApp(w: Watch, pkg: String): Boolean {
        if (w.baseline.isNotEmpty()) return pkg == w.baseline
        val label = ScreenElementSerializer.appLabel(appCtx, pkg)
        return label.contains(w.value, ignoreCase = true) || pkg.contains(w.value.replace(" ", ""), ignoreCase = true)
    }

    private fun replace(w: Watch) {
        synchronized(watches) {
            val i = watches.indexOfFirst { it.id == w.id }
            if (i >= 0) watches[i] = w
        }
        save()
    }

    // ------------------------------------------------------------------ firing

    private fun fire(w: Watch, detail: String) {
        val removed = synchronized(watches) { watches.removeAll { it.id == w.id } }
        if (!removed) return
        save()
        ScreenRuntime.scope.launch { rearm() }
        Log.i(TAG, "WATCH fired id=${w.id} detail=$detail")
        val reason = w.reason.ifBlank { w.describe() }
        val live = "[SYSTEM EVENT] Jo baat Boss ne batane ko kaha tha wo ho gayi: $reason — $detail. Boss ko abhi ek line me bata do."
        if (!ScreenRuntime.inject(live)) {
            ScreenRuntime.announceLocally(appCtx, "Watch #${w.id}", "Boss, $reason — $detail")
        }
    }

    private fun announceEvent(eventId: String, extra: String = "") {
        if (!ScreenPrefs.eventsMaster(appCtx) || !ScreenPrefs.isEventEnabled(appCtx, eventId)) return
        var line = EventTriggers.line(eventId)
        if (eventId == "battery_low" && batteryLevel > 0) line = "Battery ${batteryLevel}% bachi hai aur charger nahi laga. " + line
        if (extra.isNotBlank()) line = "$line ($extra)"
        Log.i(TAG, "EVENT $eventId → $line")
        val injected = ScreenRuntime.inject("[SYSTEM EVENT] Phone event: $line — Boss ko apni awaaz me ek chhoti line me bata do.")
        if (!injected) ScreenRuntime.announceLocally(appCtx, EventTriggers.byId(eventId)?.label ?: "Phone event", line)
    }

    /** Device-kind watches matching an event (value filters: "in/out/on/off/connected/..."). */
    private fun fireDeviceWatches(kind: String, direction: String, detail: String) {
        for (w in list().filter { it.kind == kind }) {
            val v = w.value.trim().lowercase()
            val ok = when {
                v.isEmpty() || v == "any" || v == "change" -> true
                direction == "in" -> listOf("in", "on", "connect", "plug", "lag", "connected", "plugged").any { v.contains(it) } && !v.contains("dis") && !v.contains("un")
                else -> listOf("out", "off", "disconnect", "unplug", "nikal", "hat", "remove", "lost").any { v.contains(it) }
            }
            if (ok) fire(w, detail)
        }
    }

    // ------------------------------------------------------------------ receivers

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try { onBattery(intent, isInitialStickyBroadcast) } catch (e: Exception) { Log.d(TAG, "battery: ${e.message}") }
        }
    }

    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isInitialStickyBroadcast) return
            try { onDeviceEvent(intent) } catch (e: Exception) { Log.d(TAG, "device event: ${e.message}") }
        }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        try {
            ContextCompat.registerReceiver(appCtx, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(AudioManager.ACTION_HEADSET_PLUG)
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            }
            ContextCompat.registerReceiver(appCtx, deviceReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            registerWifiCallback()
            receiversRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "registerReceivers failed: ${e.message}")
        }
    }

    private fun registerWifiCallback() {
        try {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            wifiConnected = cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (wifiConnected == true) return
                    wifiConnected = true
                    announceEvent("wifi_in")
                    fireDeviceWatches("wifi", "in", "Wi-Fi connect ho gaya")
                }

                override fun onLost(network: Network) {
                    if (wifiConnected == false) return
                    wifiConnected = false
                    announceEvent("wifi_out")
                    fireDeviceWatches("wifi", "out", "Wi-Fi chala gaya")
                }
            }
            cm.registerNetworkCallback(req, cb)
            wifiCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "wifi callback failed: ${e.message}")
        }
    }

    private fun onBattery(intent: Intent, initial: Boolean) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val pct = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val isCharging = plugged || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val prev = batteryLevel
        batteryLevel = pct
        charging = isCharging
        val lowPct = ScreenPrefs.batteryLowPercent(appCtx)
        if (initial || prev < 0) {
            lowFired = pct <= lowPct
            criticalFired = pct <= 10
            fullFired = pct >= 100
            return
        }
        if (pct > lowPct + 3 || isCharging) lowFired = false
        if (pct > 13 || isCharging) criticalFired = false
        if (pct < 97 || !isCharging) fullFired = pct >= 100 && isCharging
        if (!isCharging && pct <= 10 && !criticalFired) {
            criticalFired = true
            lowFired = true
            announceEvent("battery_critical")
        } else if (!isCharging && pct <= lowPct && !lowFired) {
            lowFired = true
            announceEvent("battery_low")
        }
        if (isCharging && pct >= 100 && !fullFired) {
            fullFired = true
            announceEvent("battery_full")
        }
        // battery watches: fire on crossing
        if (prev != pct) {
            for (w in list().filter { it.kind == "battery" }) {
                val c = WatchCodec.parseBatteryCondition(w.value) ?: continue
                val crossed = if (c.below) prev > c.threshold && pct <= c.threshold else prev < c.threshold && pct >= c.threshold
                if (crossed) fire(w, "battery ab $pct% hai")
            }
        }
    }

    private fun onDeviceEvent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                announceEvent("charger_in")
                fireDeviceWatches("charger", "in", "charger lag gaya")
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                announceEvent("charger_out")
                fireDeviceWatches("charger", "out", "charger nikal gaya")
            }
            BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val name = try {
                    @Suppress("DEPRECATION")
                    val dev: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    dev?.name
                } catch (_: SecurityException) { null } catch (_: Exception) { null }
                val inEvent = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
                announceEvent(if (inEvent) "bluetooth_in" else "bluetooth_out", name ?: "")
                fireDeviceWatches("bluetooth", if (inEvent) "in" else "out", (name ?: "Bluetooth device") + if (inEvent) " connect ho gaya" else " disconnect ho gaya")
            }
            AudioManager.ACTION_HEADSET_PLUG -> {
                val state = intent.getIntExtra("state", -1)
                if (state == 1) {
                    announceEvent("headset_in")
                    fireDeviceWatches("headset", "in", "headphone lag gaya")
                } else if (state == 0) {
                    announceEvent("headset_out")
                    fireDeviceWatches("headset", "out", "headphone nikal gaya")
                }
            }
            AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                val mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)
                if (mode == AudioManager.RINGER_MODE_SILENT || mode == AudioManager.RINGER_MODE_VIBRATE) announceEvent("silent_on")
                else if (mode == AudioManager.RINGER_MODE_NORMAL) announceEvent("silent_off")
            }
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                val on = intent.getBooleanExtra("state", false)
                announceEvent(if (on) "airplane_on" else "airplane_off")
            }
        }
    }

    /** Current battery state for set_event_trigger feedback. */
    fun batterySummary(): String = if (batteryLevel < 0) "unknown" else "$batteryLevel%" + if (charging) " (charging)" else ""

    // ------------------------------------------------------------------ accessibility events

    /**
     * Called (via [ScreenEventBus]) on the accessibility thread for every event. Must be cheap:
     * only reads event fields; heavier work is posted to the runtime scope.
     */
    fun onAccessibilityEvent(type: Int, pkg: String, text: String) {
        if (!started) return
        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (pkg == appCtx.packageName) return
            val notifWatches = list().filter { it.kind == "notification" }
            for (w in notifWatches) {
                val v = w.value.trim()
                val appLabel = ScreenElementSerializer.appLabel(appCtx, pkg)
                val hit = v.isEmpty() || text.contains(v, ignoreCase = true) || appLabel.contains(v, ignoreCase = true) || pkg.contains(v, ignoreCase = true)
                if (hit) fire(w, "$appLabel ka notification: \"${text.take(140)}\"")
            }
            return
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg.isNotEmpty() && pkg !in IGNORED_PKGS && !pkg.contains("inputmethod") && !pkg.contains("keyboard")) {
            if (pkg != lastForegroundPkg) {
                lastForegroundPkg = pkg
                // app_opens watches react instantly (the poll is a backstop)
                val appW = list().filter { it.kind == "app_opens" }
                if (appW.isNotEmpty()) ScreenRuntime.scope.launch { for (w in appW) checkAppOpen(w, pkg) }
                onAppSwitch(pkg)
            }
        }
    }

    // ------------------------------------------------------------------ screen awareness

    private fun onAppSwitch(pkg: String) {
        if (!ScreenPrefs.screenAwareness(appCtx)) return
        if (VerbLoopExecutor.running) return
        if (pkg == appCtx.packageName) return
        if (pkg == lastAwarePkg) return
        val now = System.currentTimeMillis()
        if (now - lastAwareAt >= AWARENESS_MIN_GAP_MS) {
            sendAwareness(pkg)
        } else {
            pendingAwarePkg = pkg
            if (awarenessJob?.isActive != true) {
                awarenessJob = ScreenRuntime.scope.launch {
                    delay((AWARENESS_MIN_GAP_MS - (System.currentTimeMillis() - lastAwareAt)).coerceAtLeast(500L))
                    val p = pendingAwarePkg
                    pendingAwarePkg = null
                    if (p != null && p == lastForegroundPkg && p != lastAwarePkg) sendAwareness(p)
                }
            }
        }
    }

    private fun sendAwareness(pkg: String) {
        if (!ScreenRuntime.isLive()) return
        val label = if (ScreenActions.isLauncher(pkg)) "Home screen" else ScreenElementSerializer.appLabel(appCtx, pkg)
        lastAwarePkg = pkg
        lastAwareAt = System.currentTimeMillis()
        ScreenRuntime.inject("[SCREEN — Boss ne nahi bheja] Ab saamne: $label")
    }
}
