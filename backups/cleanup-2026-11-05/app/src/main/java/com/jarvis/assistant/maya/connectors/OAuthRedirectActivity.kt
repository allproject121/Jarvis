package com.jarvis.assistant.maya.connectors

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receives `com.jarvis.assistant.oauth:/callback?code=...` from the browser after Google consent,
 * finishes the PKCE exchange and returns to [ConnectorsActivity].
 */
class OAuthRedirectActivity : Activity() {

    companion object {
        private const val TAG = "OAuthRedirectActivity"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val app = applicationContext
        if (uri == null || uri.scheme != GoogleOAuthHelper.SCHEME) {
            Log.w(TAG, "OAuth redirect ignored: $uri")
            finish()
            return
        }
        scope.launch {
            val msg = GoogleOAuthHelper.handleRedirect(app, uri)
            withContext(Dispatchers.Main) {
                try { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() } catch (_: Exception) { }
                app.startActivity(
                    Intent(app, ConnectorsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(ConnectorsActivity.EXTRA_OAUTH_RESULT, msg)
                        .putExtra(ConnectorsActivity.EXTRA_FOCUS, ConnectorService.GOOGLE.id)
                )
            }
        }
        finish()
    }
}
