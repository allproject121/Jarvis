package com.jarvis.assistant.automation.dispatcher

import android.content.Context
import android.util.Log

/**
 * Wires every Maya feature tool pack into [MayaToolRegistry].
 * Each pack lives in its own package and exposes `object XxxToolPack { fun install(context: Context) }`.
 * Packs are installed defensively so one broken pack never hides the others.
 */
object MayaToolPacks {

    private const val TAG = "MayaToolPacks"

    private val packs: List<Pair<String, (Context) -> Unit>> = listOf(
        // Filled in as packs land (see docs/CLAUDE.md, session "Maya integration 2026-11-04").
        "screen" to { ctx -> com.jarvis.assistant.maya.screen.ScreenToolPack.install(ctx) },
        "connectors" to { ctx -> com.jarvis.assistant.maya.connectors.ConnectorToolPack.install(ctx) },
        "memory" to { ctx -> com.jarvis.assistant.maya.memory.MemoryToolPack.install(ctx) },
        "brain" to { ctx -> com.jarvis.assistant.maya.brain.BrainToolPack.install(ctx) },
        "hub" to { ctx -> com.jarvis.assistant.maya.hub.HubToolPack.install(ctx) },
        "media" to { ctx -> com.jarvis.assistant.maya.media.MediaToolPack.install(ctx) },
        "guardian" to { ctx -> com.jarvis.assistant.maya.guardian.GuardianToolPack.install(ctx) },
        "business" to { ctx -> com.jarvis.assistant.maya.business.BusinessToolPack.install(ctx) },
    )

    fun installAll(context: Context) {
        for ((name, install) in packs) {
            try {
                install(context)
            } catch (e: Throwable) {
                Log.e(TAG, "Pack '$name' failed to install: ${e.message}", e)
            }
        }
    }
}
