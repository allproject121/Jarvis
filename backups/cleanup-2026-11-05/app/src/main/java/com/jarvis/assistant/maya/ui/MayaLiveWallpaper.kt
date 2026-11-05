package com.jarvis.assistant.maya.ui

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.wallpaper.JarvisWallpaperService

/** Switches the Jarvis live wallpaper to the Maya character mode and opens the system picker if needed. */
object MayaLiveWallpaper {

    fun isMayaMode(): Boolean = try {
        JarvisApp.instance.preferences.wallpaperMode == MayaUiPrefs.WALLPAPER_MODE_MAYA
    } catch (_: Throwable) {
        false
    }

    fun isJarvisWallpaperActive(context: Context): Boolean = try {
        val info = WallpaperManager.getInstance(context).wallpaperInfo
        info != null && info.packageName == context.packageName &&
            info.serviceName == JarvisWallpaperService::class.java.name
    } catch (_: Throwable) {
        false
    }

    /** Use the Maya stage as the live wallpaper. */
    fun use(activity: Activity) {
        JarvisApp.instance.preferences.wallpaperMode = MayaUiPrefs.WALLPAPER_MODE_MAYA
        MayaUiPrefs.setWallpaperCharacter(activity, true)
        if (isJarvisWallpaperActive(activity)) {
            Toast.makeText(activity, "Live wallpaper now shows the Maya stage", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(activity, JarvisWallpaperService::class.java)
                )
            }
            activity.startActivity(intent)
            Toast.makeText(activity, "Tap \"Set wallpaper\" to finish", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e: Exception) {
                Toast.makeText(activity, "Wallpaper chooser not available: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Leave the Maya mode (back to the previous Jarvis character wallpaper). */
    fun stopUsing(context: Context) {
        val prefs = JarvisApp.instance.preferences
        if (prefs.wallpaperMode == MayaUiPrefs.WALLPAPER_MODE_MAYA) prefs.wallpaperMode = "character"
        MayaUiPrefs.setWallpaperCharacter(context, false)
    }
}
