package com.jarvis.assistant.automation.tools

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Ported from AndroLLM (core/tools/tool/impl/AppIndex).
 *
 * Cached index of launchable apps, built from ONE PackageManager launcher
 * query (instead of N getLaunchIntentForPackage() calls) and de-duplicated
 * by package — apps exposing several launcher activities appear once.
 * The cache lives for [TTL_MS] so newly installed apps show up without an
 * explicit [refresh].
 */
object AppIndex {

    private const val TAG = "JarvisAppIndex"
    private const val TTL_MS = 5 * 60_000L

    @Volatile private var cache: List<AppEntry>? = null
    @Volatile private var builtAt: Long = 0L

    fun all(context: Context): List<AppEntry> {
        val now = System.currentTimeMillis()
        cache?.let { if (now - builtAt < TTL_MS) return it }
        return synchronized(this) {
            cache?.let { if (System.currentTimeMillis() - builtAt < TTL_MS) return it }
            build(context.applicationContext).also {
                cache = it
                builtAt = System.currentTimeMillis()
            }
        }
    }

    /** Drops the cache (after installs/uninstalls). */
    fun refresh() {
        synchronized(this) {
            cache = null
            builtAt = 0L
        }
    }

    /** Convenience: search the cached index. */
    fun search(context: Context, query: String): AppSearchResult = AppSearch.search(all(context), query)

    private fun build(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = runCatching { pm.queryIntentActivities(launcherIntent, 0) }.getOrDefault(emptyList())

        val byPackage = LinkedHashMap<String, AppEntry>()
        for (ri in resolveInfos) {
            val info = ri.activityInfo ?: continue
            val pkg = info.packageName ?: continue
            if (byPackage.containsKey(pkg)) continue // dedupe: first launcher activity wins
            val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: pkg
            val launch = Intent(launcherIntent).apply {
                setClassName(pkg, info.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            byPackage[pkg] = AppEntry(
                label = label,
                packageName = pkg,
                hasLauncher = true,
                launchIntent = launch
            )
        }
        val entries = byPackage.values.sortedBy { it.label.lowercase() }
        Log.i(TAG, "indexed ${entries.size} launchable apps")
        return entries
    }
}
