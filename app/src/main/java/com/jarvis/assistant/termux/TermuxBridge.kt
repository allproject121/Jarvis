package com.jarvis.assistant.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-level bridge to execute commands and scripts inside Termux Linux environment.
 */
class TermuxBridge(private val context: Context) {

    companion object {
        private const val TAG = "TermuxBridge"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_RUN_COMMAND_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        private val nextExecId = AtomicInteger(1000)
    }

    fun isTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Executes a bash command asynchronously inside Termux and suspends until result is returned.
     */
    suspend fun executeBashCommand(
        command: String,
        workDir: String = "/data/data/com.termux/files/home",
        timeoutMs: Long = 15000L
    ): TermuxResultReceiver.TermuxResult? {
        val execId = nextExecId.incrementAndGet()

        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra(EXTRA_RUN_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
            putExtra(EXTRA_RUN_COMMAND_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_RUN_COMMAND_WORKDIR, workDir)
            putExtra(EXTRA_RUN_COMMAND_BACKGROUND, true)
            putExtra(EXTRA_RUN_COMMAND_SESSION_ACTION, "0")

            val callbackIntent = Intent(context, TermuxResultReceiver::class.java).apply {
                putExtra(TermuxResultReceiver.EXTRA_EXEC_ID, execId)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getBroadcast(context, execId, callbackIntent, flags)
            putExtra(EXTRA_PENDING_INTENT, pending)
        }

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                TermuxResultReceiver.registerCallback(execId) { result ->
                    if (continuation.isActive) {
                        continuation.resume(result, null)
                    }
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    Log.i(TAG, "Dispatched command to Termux (id=$execId): $command")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed dispatching command to Termux: ${e.message}", e)
                    TermuxResultReceiver.unregisterCallback(execId)
                    if (continuation.isActive) {
                        continuation.resumeWith(
                            Result.success(
                                TermuxResultReceiver.TermuxResult(-1, "", e.message ?: "Dispatch failed", System.currentTimeMillis())
                            )
                        )
                    }
                }
            }
        }
    }
}
