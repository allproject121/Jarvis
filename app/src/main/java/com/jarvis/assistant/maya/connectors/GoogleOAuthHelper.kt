package com.jarvis.assistant.maya.connectors

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.FormBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Google OAuth 2.0 authorization-code flow with PKCE for a USER-SUPPLIED OAuth client id.
 *
 * Flow: [buildAuthIntent] opens the browser → Google redirects to
 * `com.jarvis.assistant.oauth:/callback?code=...&state=...` → [OAuthRedirectActivity] calls
 * [handleRedirect], which exchanges the code and stores the refresh/access token in
 * [ConnectorSecrets]. [accessToken] refreshes silently when needed.
 *
 * Fallback: [setManualAccessToken] stores a short-lived token pasted from the OAuth Playground.
 */
object GoogleOAuthHelper {

    private const val TAG = "GoogleOAuthHelper"
    private const val SERVICE = "Google"
    const val SCHEME = "com.jarvis.assistant.oauth"
    const val REDIRECT_URI = "$SCHEME:/callback"
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    /** Last redirect outcome ("ok" / "error:<msg>") so the Connectors screen can show it. */
    private val _lastResult = MutableStateFlow<String?>(null)
    val lastResult: StateFlow<String?> = _lastResult.asStateFlow()

    fun clearLastResult() { _lastResult.value = null }

    fun clientId(context: Context): String? = ConnectorSecrets.get(context, ConnectorSecrets.GOOGLE_CLIENT_ID)

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun randomToken(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return base64Url(b)
    }

    fun codeChallenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Builds the browser intent for the consent screen; stores PKCE verifier + state. */
    fun buildAuthIntent(context: Context): Intent {
        val clientId = clientId(context)?.trim()
            ?: throw ConnectorException("Pehle apna Google OAuth client id daalo (Google Cloud Console → Credentials).")
        val verifier = randomToken(48)
        val state = randomToken(16)
        ConnectorSecrets.set(context, ConnectorSecrets.GOOGLE_PKCE_VERIFIER, verifier)
        ConnectorSecrets.set(context, ConnectorSecrets.GOOGLE_OAUTH_STATE, state)
        val uri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", codeChallenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        return Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /** Handles the redirect URI; exchanges the code for tokens. Returns a user message. */
    suspend fun handleRedirect(context: Context, uri: Uri): String {
        try {
            if (!uri.isHierarchical) throw ConnectorException("OAuth redirect samajh nahi aaya (not a hierarchical URI)")
            val error = uri.getQueryParameter("error")
            if (!error.isNullOrBlank()) throw ConnectorException("Google ne link karne se mana kiya: $error")
            val code = uri.getQueryParameter("code") ?: throw ConnectorException("Google ne code nahi bheja")
            val state = uri.getQueryParameter("state")
            val expected = ConnectorSecrets.get(context, ConnectorSecrets.GOOGLE_OAUTH_STATE)
            if (expected == null || state != expected) throw ConnectorException("OAuth state mismatch — dobara 'Link Google account' dabao")
            val verifier = ConnectorSecrets.get(context, ConnectorSecrets.GOOGLE_PKCE_VERIFIER)
                ?: throw ConnectorException("PKCE verifier nahi mila — dobara link karo")
            val clientId = clientId(context) ?: throw ConnectorException("Client id gayab hai")
            val form = FormBody.Builder()
                .add("code", code)
                .add("client_id", clientId)
                .add("redirect_uri", REDIRECT_URI)
                .add("grant_type", "authorization_code")
                .add("code_verifier", verifier)
            ConnectorSecrets.get(context, ConnectorSecrets.GOOGLE_CLIENT_SECRET)?.let { form.add("client_secret", it) }
            val resp = ConnectorHttp.request(SERVICE, "POST", TOKEN_URL, body = form.build())
            if (!resp.ok) throw ConnectorException("Google token exchange fail (${resp.code}): ${ConnectorHttp.errorSnippet(resp.body)}", resp.code)
            storeTokens(context, resp.json())
            ConnectorSecrets.remove(context, ConnectorSecrets.GOOGLE_PKCE_VERIFIER, ConnectorSecrets.GOOGLE_OAUTH_STATE, ConnectorSecrets.GOOGLE_MANUAL_TOKEN)
            val email = try { GoogleDriveClient(context).aboutUser().also { ConnectorSecrets.set(context, ConnectorSecrets.GOOGLE_ACCOUNT_EMAIL, it) } } catch (_: Exception) { "" }
            val msg = "Google Drive linked${if (email.isNotBlank()) " ($email)" else ""}"
            ConnectorSecrets.setLastStatus(context, ConnectorService.GOOGLE, "ok")
            _lastResult.value = "ok:$msg"
            return msg
        } catch (e: ConnectorException) {
            Log.w(TAG, "OAuth completion failed: ${e.userMessage}")
            ConnectorSecrets.setLastStatus(context, ConnectorService.GOOGLE, "error:${e.userMessage}")
            _lastResult.value = "error:${e.userMessage}"
            return e.userMessage
        } catch (e: Exception) {
            Log.e(TAG, "OAuth completion threw", e)
            _lastResult.value = "error:${e.message}"
            return "Google link fail: ${e.message}"
        }
    }

    private fun storeTokens(context: Context, j: JSONObject) {
        val access = j.optString("access_token")
        if (access.isBlank()) throw ConnectorException("Google ne access token nahi diya")
        ConnectorSecrets.set(context, ConnectorSecrets.GOOGLE_ACCESS_TOKEN, access)
        val refresh = j.optString("refresh_token")
        if (refresh.isNotBlank()) ConnectorSecrets.set(context, ConnectorSecrets.GOOGLE_REFRESH_TOKEN, refresh)
        val expiresIn = j.optLong("expires_in", 3600L)
        ConnectorSecrets.setLong(context, ConnectorSecrets.GOOGLE_TOKEN_EXPIRY, System.currentTimeMillis() + expiresIn * 1000L)
    }

    /** "Paste access token" fallback (OAuth Playground). Lives ~1h; no refresh possible. */
    fun setManualAccessToken(context: Context, token: String) {
        ConnectorSecrets.set(context, ConnectorSecrets.GOOGLE_MANUAL_TOKEN, token.trim())
        ConnectorSecrets.set(context, ConnectorSecrets.GOOGLE_ACCESS_TOKEN, token.trim())
        ConnectorSecrets.setLong(context, ConnectorSecrets.GOOGLE_TOKEN_EXPIRY, System.currentTimeMillis() + 55 * 60 * 1000L)
    }

    /** Returns a usable access token, refreshing with the stored refresh token when expired. */
    suspend fun accessToken(context: Context): String {
        val manual = ConnectorSecrets.get(context, ConnectorSecrets.GOOGLE_MANUAL_TOKEN)
        val refresh = ConnectorSecrets.get(context, ConnectorSecrets.GOOGLE_REFRESH_TOKEN)
        val access = ConnectorSecrets.get(context, ConnectorSecrets.GOOGLE_ACCESS_TOKEN)
        val expiry = ConnectorSecrets.getLong(context, ConnectorSecrets.GOOGLE_TOKEN_EXPIRY)
        if (refresh == null) {
            if (manual != null) return manual
            throw ConnectorHttp.notLinked(ConnectorService.GOOGLE)
        }
        if (access != null && expiry > System.currentTimeMillis() + 60_000L) return access
        val clientId = clientId(context) ?: throw ConnectorException("Google client id gayab hai — Settings > Connectors me dobara link karo")
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
        ConnectorSecrets.get(context, ConnectorSecrets.GOOGLE_CLIENT_SECRET)?.let { form.add("client_secret", it) }
        val resp = ConnectorHttp.request(SERVICE, "POST", TOKEN_URL, body = form.build())
        if (!resp.ok) {
            if (resp.code == 400 || resp.code == 401) {
                ConnectorSecrets.remove(context, ConnectorSecrets.GOOGLE_REFRESH_TOKEN, ConnectorSecrets.GOOGLE_ACCESS_TOKEN)
                throw ConnectorException("Google ka link expire/revoke ho gaya — Settings > Connectors me 'Link Google account' dobara dabao", resp.code)
            }
            throw ConnectorException("Google token refresh fail (${resp.code})", resp.code)
        }
        storeTokens(context, resp.json())
        return ConnectorSecrets.get(context, ConnectorSecrets.GOOGLE_ACCESS_TOKEN)!!
    }

    /** Human description of how Google is linked, for the Connectors screen. */
    fun linkMode(context: Context): String = when {
        ConnectorSecrets.get(context, ConnectorSecrets.GOOGLE_REFRESH_TOKEN) != null ->
            "OAuth" + (ConnectorSecrets.get(context, ConnectorSecrets.GOOGLE_ACCOUNT_EMAIL)?.let { " · $it" } ?: "")
        ConnectorSecrets.get(context, ConnectorSecrets.GOOGLE_MANUAL_TOKEN) != null -> "Pasted access token (expires ~1h)"
        else -> "Not linked"
    }
}
