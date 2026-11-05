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

    // ── Additive extensions used by the Maya HUB (com.jarvis.assistant.maya.hub) ───────────

    /** True when Termux granted us com.termux.permission.RUN_COMMAND. */
    fun isRunCommandPermissionGranted(): Boolean = try {
        context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    /**
     * Like [executeBashCommand] but when the timeout elapses the receiver callback stays
     * registered and [onLateResult] is invoked whenever Termux finally reports back, so long
     * jobs are never lost. Returns null on timeout.
     */
    suspend fun executeBashCommandWithLateResult(
        command: String,
        workDir: String = "/data/data/com.termux/files/home",
        timeoutMs: Long = 15000L,
        onLateResult: (TermuxResultReceiver.TermuxResult) -> Unit
    ): TermuxResultReceiver.TermuxResult? {
        val execId = nextExecId.incrementAndGet()
        val intent = buildRunIntent(command, workDir, execId, background = true, sessionAction = "0")
        var delivered = false
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                TermuxResultReceiver.registerCallback(execId) { result ->
                    delivered = true
                    if (continuation.isActive) continuation.resume(result, null) else onLateResult(result)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                    else context.startService(intent)
                    Log.i(TAG, "Dispatched command to Termux (id=$execId, late-capable): ${command.take(120)}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed dispatching command to Termux: ${e.message}", e)
                    TermuxResultReceiver.unregisterCallback(execId)
                    if (continuation.isActive) {
                        continuation.resume(
                            TermuxResultReceiver.TermuxResult(-1, "", e.message ?: "Dispatch failed", System.currentTimeMillis()),
                            null
                        )
                    }
                }
            }
        }
        if (result == null && !delivered) {
            // Keep listening: re-register so the late broadcast reaches onLateResult.
            TermuxResultReceiver.registerCallback(execId) { late -> onLateResult(late) }
        }
        return result
    }

    /**
     * Opens Termux on screen with [command] running in a visible session the user can type into
     * (RUN_COMMAND_BACKGROUND=false). There is no output to read back. Returns false when the
     * intent could not even be dispatched.
     */
    fun openSession(command: String, workDir: String = "/data/data/com.termux/files/home"): Boolean {
        if (!isTermuxInstalled()) return false
        val execId = nextExecId.incrementAndGet()
        val intent = buildRunIntent(command, workDir, execId, background = false, sessionAction = "0")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
            // Also try to bring Termux to the front; without "Display over other apps" Termux
            // itself cannot, and the session command only starts once Termux is on screen.
            try {
                val launch = context.packageManager.getLaunchIntentForPackage("com.termux")
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not bring Termux to front: ${e.message}")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed opening Termux session: ${e.message}", e)
            false
        }
    }

    private fun buildRunIntent(
        command: String,
        workDir: String,
        execId: Int,
        background: Boolean,
        sessionAction: String
    ): Intent = Intent(ACTION_RUN_COMMAND).apply {
        setClassName("com.termux", "com.termux.app.RunCommandService")
        putExtra(EXTRA_RUN_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
        putExtra(EXTRA_RUN_COMMAND_ARGUMENTS, arrayOf("-c", command))
        putExtra(EXTRA_RUN_COMMAND_WORKDIR, workDir)
        putExtra(EXTRA_RUN_COMMAND_BACKGROUND, background)
        putExtra(EXTRA_RUN_COMMAND_SESSION_ACTION, sessionAction)
        val callbackIntent = Intent(context, TermuxResultReceiver::class.java).apply {
            putExtra(TermuxResultReceiver.EXTRA_EXEC_ID, execId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        putExtra(EXTRA_PENDING_INTENT, PendingIntent.getBroadcast(context, execId, callbackIntent, flags))
    }
}
