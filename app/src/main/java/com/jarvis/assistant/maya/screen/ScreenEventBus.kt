package com.jarvis.assistant.maya.screen

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Static listener registry fed by ONE additive hook line in
 * JarvisAccessibilityService.onAccessibilityEvent. Forwards events to [MacroRecorder.onEvent]
 * (training), [WatcherEngine] (notification / app-open watches, screen awareness) and any extra
 * listeners. Runs on the accessibility thread: everything here must be cheap and never throw.
 */
object ScreenEventBus {

    private const val TAG = "MayaScreenEventBus"

    fun interface Listener {
        fun onEvent(event: AccessibilityEvent)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun onEvent(event: AccessibilityEvent) {
        try {
            val type = event.eventType
            // Lazily bring the watcher up when the accessibility service starts before any tool call
            if (!WatcherEngine.isStarted()) {
                ScreenRuntime.appContext()?.let { ctx ->
                    try {
                        WatcherEngine.start(ctx)
                        MacroRecorder.restore(ctx)
                    } catch (_: Throwable) {}
                }
            }
            if (type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                MacroRecorder.onEvent(event)
            }
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                MacroRecorder.ensureEventTypes()
                WatcherEngine.ensureEventTypes()
            }
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                val pkg = event.packageName?.toString().orEmpty()
                val text = if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                    event.text?.joinToString(" ")?.trim().orEmpty()
                } else ""
                WatcherEngine.onAccessibilityEvent(type, pkg, text)
            }
            for (l in listeners) {
                try { l.onEvent(event) } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            Log.d(TAG, "event dispatch failed: ${e.message}")
        }
    }
}
