package com.jarvis.assistant.androllm.engine.support

import android.util.Log

/**
 * Minimal android.util.Log facade replacing the Timber calls of the ported
 * AndroLLM sources (Jarvis does not ship Timber). Supports the same call
 * shapes used upstream: `d/i/w/e(message, args)`, `w/e(throwable, message, args)`
 * and `tag(TAG).x(...)`.
 */
object AlLog {
    private const val DEFAULT_TAG = "AndroLLM"

    class Tagged internal constructor(private val tag: String) {
        fun v(message: String, vararg args: Any?) = log(Log.VERBOSE, tag, null, message, args)
        fun d(message: String, vararg args: Any?) = log(Log.DEBUG, tag, null, message, args)
        fun i(message: String, vararg args: Any?) = log(Log.INFO, tag, null, message, args)
        fun w(message: String, vararg args: Any?) = log(Log.WARN, tag, null, message, args)
        fun w(t: Throwable?, message: String, vararg args: Any?) = log(Log.WARN, tag, t, message, args)
        fun e(message: String, vararg args: Any?) = log(Log.ERROR, tag, null, message, args)
        fun e(t: Throwable?, message: String, vararg args: Any?) = log(Log.ERROR, tag, t, message, args)
    }

    fun tag(tag: String): Tagged = Tagged(tag.take(23))

    fun v(message: String, vararg args: Any?) = log(Log.VERBOSE, DEFAULT_TAG, null, message, args)
    fun d(message: String, vararg args: Any?) = log(Log.DEBUG, DEFAULT_TAG, null, message, args)
    fun i(message: String, vararg args: Any?) = log(Log.INFO, DEFAULT_TAG, null, message, args)
    fun w(message: String, vararg args: Any?) = log(Log.WARN, DEFAULT_TAG, null, message, args)
    fun w(t: Throwable?, message: String, vararg args: Any?) = log(Log.WARN, DEFAULT_TAG, t, message, args)
    fun e(message: String, vararg args: Any?) = log(Log.ERROR, DEFAULT_TAG, null, message, args)
    fun e(t: Throwable?, message: String, vararg args: Any?) = log(Log.ERROR, DEFAULT_TAG, t, message, args)

    private fun log(priority: Int, tag: String, t: Throwable?, message: String, args: Array<out Any?>) {
        val text = if (args.isEmpty()) message else try { String.format(message, *args) } catch (_: Exception) { message }
        try {
            if (t != null) {
                when (priority) {
                    Log.ERROR -> Log.e(tag, text, t)
                    Log.WARN -> Log.w(tag, text, t)
                    else -> Log.println(priority, tag, text + "\n" + Log.getStackTraceString(t))
                }
            } else {
                Log.println(priority, tag, text)
            }
        } catch (_: Throwable) {
            // android.util.Log is stubbed in local unit tests — ignore.
        }
    }
}
