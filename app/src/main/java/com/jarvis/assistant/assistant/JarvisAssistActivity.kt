package com.jarvis.assistant.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.jarvis.assistant.ui.home.MainActivity

/**
 * JARVIS Default Digital Assistant Entry Point.
 * Handles system ASSIST intent (e.g. holding home button or power key).
 */
class JarvisAssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_START_VOICE_SESSION", true)
        }
        startActivity(intent)
        finish()
    }
}
