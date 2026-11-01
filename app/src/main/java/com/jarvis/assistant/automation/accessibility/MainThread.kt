package com.jarvis.assistant.automation.accessibility

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Ported from AndroLLM (core/accessibility/util/MainThread).
 *
 * AccessibilityNodeInfo instances must be read and acted on from the main
 * thread. Every node read/write in the automation engine funnels through
 * [node]; exceptions (the common "node vanished" case) become a default value
 * so a flaky node never crashes a tool call.
 */
object MainThread {

    private const val TAG = "JarvisMainThread"
    private val handler = Handler(Looper.getMainLooper())

    val isMain: Boolean get() = Looper.myLooper() == Looper.getMainLooper()

    /** Runs [block] on the main thread and suspends until it completes. */
    suspend fun <T> node(block: () -> T, default: T): T {
        if (isMain) {
            return try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "node action failed: ${t.message}")
                default
            }
        }
        return suspendCancellableCoroutine { cont ->
            handler.post {
                val value = try {
                    block()
                } catch (t: Throwable) {
                    Log.w(TAG, "node action failed: ${t.message}")
                    default
                }
                if (cont.isActive) cont.resume(value)
            }
        }
    }

    /** Null when [block] threw — used for reads. */
    suspend fun <T> node(block: () -> T): T? = node(block, null)

    /** Boolean variant — the common case for performAction results. */
    suspend fun action(block: () -> Boolean): Boolean = node(block, false)

    /** Fire-and-forget main-thread post (for async callback APIs). */
    fun post(block: () -> Unit) {
        if (isMain) block() else handler.post(block)
    }
}
