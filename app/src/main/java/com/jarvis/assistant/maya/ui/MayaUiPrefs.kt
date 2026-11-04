package com.jarvis.assistant.maya.ui

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import com.jarvis.assistant.JarvisApp

/**
 * Settings that belong to the Maya UI only (character stage on Home, the
 * floating character, its size, the wallpaper character mode).
 *
 * Kept in their own SharedPreferences file so AppPreferences stays untouched.
 */
object MayaUiPrefs {
    private const val FILE = "maya_ui_prefs"
    private const val KEY_CHARACTER_ON_HOME = "character_on_home"
    private const val KEY_SHOW_DESPITE_BATTERY_SAVER = "show_despite_battery_saver"
    private const val KEY_OVERLAY_ENABLED = "overlay_character_enabled"
    private const val KEY_CHARACTER_SIZE_DP = "character_size_dp"
    private const val KEY_OVERLAY_X = "overlay_x"
    private const val KEY_OVERLAY_Y = "overlay_y"
    private const val KEY_WALLPAPER_CHARACTER = "wallpaper_character_mode"
    private const val KEY_INTIMATE_AGE_CONFIRMED = "intimate_age_confirmed"

    /** The wallpaper mode value stored in AppPreferences.wallpaperMode for the Maya look. */
    const val WALLPAPER_MODE_MAYA = "maya_character"

    const val DEFAULT_CHARACTER_SIZE_DP = 168
    const val MIN_CHARACTER_SIZE_DP = 96
    const val MAX_CHARACTER_SIZE_DP = 320

    /** Phones with less RAM than this show the orb instead of the WebGL character. */
    const val MIN_RAM_FOR_CHARACTER_BYTES = 3L * 1024L * 1024L * 1024L

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** "A filmed character inside the app, instead of the orb." */
    fun isCharacterOnHome(context: Context): Boolean = prefs(context).getBoolean(KEY_CHARACTER_ON_HOME, true)
    fun setCharacterOnHome(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_CHARACTER_ON_HOME, on).apply()

    /** "Battery saver is on and would show the orb instead. She stays because this is on." */
    fun showDespiteBatterySaver(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_DESPITE_BATTERY_SAVER, false)
    fun setShowDespiteBatterySaver(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_DESPITE_BATTERY_SAVER, on).apply()

    fun isOverlayEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_OVERLAY_ENABLED, false)
    fun setOverlayEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_OVERLAY_ENABLED, on).apply()

    fun characterSizeDp(context: Context): Int =
        prefs(context).getInt(KEY_CHARACTER_SIZE_DP, DEFAULT_CHARACTER_SIZE_DP).coerceIn(MIN_CHARACTER_SIZE_DP, MAX_CHARACTER_SIZE_DP)
    fun setCharacterSizeDp(context: Context, dp: Int) =
        prefs(context).edit().putInt(KEY_CHARACTER_SIZE_DP, dp.coerceIn(MIN_CHARACTER_SIZE_DP, MAX_CHARACTER_SIZE_DP)).apply()

    fun overlayPosition(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        return p.getInt(KEY_OVERLAY_X, -1) to p.getInt(KEY_OVERLAY_Y, -1)
    }
    fun setOverlayPosition(context: Context, x: Int, y: Int) =
        prefs(context).edit().putInt(KEY_OVERLAY_X, x).putInt(KEY_OVERLAY_Y, y).apply()

    fun isWallpaperCharacter(context: Context): Boolean = prefs(context).getBoolean(KEY_WALLPAPER_CHARACTER, false)
    fun setWallpaperCharacter(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_WALLPAPER_CHARACTER, on).apply()

    fun isIntimateAgeConfirmed(context: Context): Boolean = prefs(context).getBoolean(KEY_INTIMATE_AGE_CONFIRMED, false)
    fun setIntimateAgeConfirmed(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_INTIMATE_AGE_CONFIRMED, on).apply()

    // ── Decisions ────────────────────────────────────────────────────────────

    fun isBatterySaverOn(): Boolean = try {
        JarvisApp.instance.preferences.isBatterySaverEnabled
    } catch (_: Throwable) {
        false
    }

    fun totalRamBytes(context: Context): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem
    } catch (_: Throwable) {
        Long.MAX_VALUE
    }

    fun isLowRam(context: Context): Boolean = totalRamBytes(context) < MIN_RAM_FOR_CHARACTER_BYTES

    /**
     * Why the orb is shown instead of the 3D character on the Home stage, or null
     * when the character should be shown.
     */
    fun characterFallbackReason(context: Context): String? {
        if (!isCharacterOnHome(context)) return "Character is off, so the orb is shown instead."
        if (isBatterySaverOn() && !showDespiteBatterySaver(context)) {
            return "Battery saver is on, so the orb is shown instead of the character."
        }
        if (isLowRam(context)) return "This phone has less than 3 GB of RAM, so the orb is shown instead of the character."
        return null
    }

    fun shouldShowCharacter(context: Context): Boolean = characterFallbackReason(context) == null

    /** Low-power rendering (reduced pixel ratio, fewer particles) for the WebGL stage. */
    fun lowPowerStage(context: Context): Boolean = isBatterySaverOn() || isLowRam(context)
}
