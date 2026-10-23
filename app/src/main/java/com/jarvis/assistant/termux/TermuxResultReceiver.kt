package com.jarvis.assistant.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * BroadcastReceiver for asynchronous execution results from Termux (RUN_COMMAND).
 */
class TermuxResultReceiver : BroadcastReceiver() {

    data class TermuxResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "TermuxResultReceiver"
        const val EXTRA_EXEC_ID = "jarvis_exec_id"

        private val pendingCallbacks = ConcurrentHashMap<Int, (TermuxResult) -> Unit>()

        fun registerCallback(execId: Int, callback: (TermuxResult) -> Unit) {
            pendingCallbacks[execId] = callback
        }

        fun unregisterCallback(execId: Int) {
            pendingCallbacks.remove(execId)
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        intent ?: return
        val execId = intent.getIntExtra(EXTRA_EXEC_ID, -1)
        val resultBundle = intent.getBundleExtra("result")

        val stdout = resultBundle?.getString("stdout").orEmpty()
        val stderr = resultBundle?.getString("stderr").orEmpty()
        val exitCode = resultBundle?.getInt("exitCode", -1) ?: -1

        Log.i(TAG, "Termux execution finished: id=$execId, exitCode=$exitCode, stdout=${stdout.take(80)}")

        val res = TermuxResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            timestamp = System.currentTimeMillis()
        )

        val callback = pendingCallbacks.remove(execId)
        callback?.invoke(res)
    }
}
