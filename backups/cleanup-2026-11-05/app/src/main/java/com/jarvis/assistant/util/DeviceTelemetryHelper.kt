package com.jarvis.assistant.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.SystemClock
import java.util.concurrent.TimeUnit

object DeviceTelemetryHelper {

    data class TelemetryData(
        val batteryPercent: Int,
        val isCharging: Boolean,
        val batteryStatusText: String,
        val ramUsedGb: Float,
        val ramTotalGb: Float,
        val ramPercent: Int,
        val networkType: String,
        val isOnline: Boolean,
        val uptimeFormatted: String
    )

    fun getTelemetry(context: Context): TelemetryData {
        // 1. Battery Telemetry (Real Android BatteryManager)
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent = context.registerReceiver(null, batteryFilter)
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val batteryPercent = if (level >= 0 && scale > 0) {
            ((level.toFloat() / scale.toFloat()) * 100).toInt().coerceIn(0, 100)
        } else {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 85
        }

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val batteryStatusText = when {
            status == BatteryManager.BATTERY_STATUS_FULL -> "ARC CORE FULL"
            isCharging -> "CHARGING • ONLINE"
            batteryPercent <= 15 -> "LOW ENERGY WARNING"
            else -> "NOMINAL DISCHARGE"
        }

        // 2. RAM Telemetry (Real ActivityManager.MemoryInfo)
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val totalBytes = memInfo.totalMem.coerceAtLeast(1L)
        val availBytes = memInfo.availMem
        val usedBytes = (totalBytes - availBytes).coerceAtLeast(0L)

        val totalGb = totalBytes.toFloat() / (1024f * 1024f * 1024f)
        val usedGb = usedBytes.toFloat() / (1024f * 1024f * 1024f)
        val ramPercent = ((usedBytes.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)

        // 3. Network Telemetry (Real ConnectivityManager)
        val connManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = connManager?.activeNetwork
        val caps = connManager?.getNetworkCapabilities(activeNetwork)

        val isOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val networkType = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WI-FI LINK ACTIVE"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR DATA ACTIVE"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET SECURE"
            !isOnline -> "OFFLINE • STANDBY"
            else -> "NETWORK NOMINAL"
        }

        // 4. System Uptime (Real SystemClock)
        val uptimeMs = SystemClock.elapsedRealtime()
        val hours = TimeUnit.MILLISECONDS.toHours(uptimeMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMs) % 60
        val uptimeFormatted = String.format("%02d:%02d UPTIME", hours, minutes)

        return TelemetryData(
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            batteryStatusText = batteryStatusText,
            ramUsedGb = String.format("%.1f", usedGb).toFloatOrNull() ?: usedGb,
            ramTotalGb = String.format("%.1f", totalGb).toFloatOrNull() ?: totalGb,
            ramPercent = ramPercent,
            networkType = networkType,
            isOnline = isOnline,
            uptimeFormatted = uptimeFormatted
        )
    }
}
