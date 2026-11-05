package com.jarvis.assistant.termux

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

/**
 * Trampoline activity for requesting com.termux.permission.RUN_COMMAND.
 */
class TermuxPermissionActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_TERMUX = 4107
        const val PERMISSION_TERMUX = "com.termux.permission.RUN_COMMAND"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(PERMISSION_TERMUX) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(PERMISSION_TERMUX), REQUEST_CODE_TERMUX)
        } else {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_TERMUX) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Log.i("TermuxPermission", "Termux RUN_COMMAND permission granted: $granted")
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
