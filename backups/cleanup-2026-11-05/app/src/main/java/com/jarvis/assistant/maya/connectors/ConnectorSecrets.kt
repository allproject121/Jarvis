package com.jarvis.assistant.maya.connectors

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * One service the Maya-style "Settings > Connectors" screen can link.
 * [id] doubles as the key prefix in [ConnectorSecrets] and as the `maya:<id>` package name
 * used by the existing App Connectors list.
 */
enum class ConnectorService(val id: String, val label: String, val description: String, val tokenUrl: String, val help: String) {
    NOTION(
        "notion", "Notion",
        "Search, read, create and append to the Notion pages you share with the integration.",
        "https://www.notion.so/my-integrations",
        "Open notion.so/my-integrations and create a new internal integration. Copy its secret here. " +
            "In Notion, open each page you want Jarvis to reach → ••• → Connections → add your integration. " +
            "Jarvis sees only the pages you explicitly share with the integration — nothing else in the workspace."
    ),
    LINEAR(
        "linear", "Linear",
        "List your teams, your open issues, and create issues.",
        "https://linear.app/settings/api",
        "Open Linear → Settings → API → Personal API keys → Create key. " +
            "A personal API key acting as you, limited to the teams your Linear account can already see."
    ),
    TODOIST(
        "todoist", "Todoist",
        "List, add and complete your Todoist tasks.",
        "https://app.todoist.com/app/settings/integrations/developer",
        "Open Todoist → Settings → Integrations → Developer and copy the API token. " +
            "Your personal API token — full access to your own Todoist, and nothing outside it."
    ),
    GITLAB(
        "gitlab", "GitLab",
        "Projects, issues and repository files on gitlab.com or your own GitLab host.",
        "https://gitlab.com/-/user_settings/personal_access_tokens",
        "Open GitLab → Preferences → Access tokens → Add new token with the `api` scope " +
            "(`read_api` can read but cannot commit). Self-hosted? Put the host URL below."
    ),
    VERCEL(
        "vercel", "Vercel",
        "Deploy a static site folder and read deployment status.",
        "https://vercel.com/account/tokens",
        "Open vercel.com and sign in (a free Hobby account is enough). Go to Settings → Tokens, or open vercel.com/account/tokens, " +
            "create a token and paste it here. A Vercel token can reach every project on your account. Jarvis only deploys and reads deployments — it never deletes a project. " +
            "The free Hobby plan deploys fine; it is for personal projects only — commercial sites need a paid plan, which is Vercel's rule."
    ),
    TELEGRAM(
        "telegram", "Telegram",
        "Send messages and files to your own Telegram chat through a bot you own.",
        "https://t.me/BotFather",
        "Open Telegram and message @BotFather → /newbot, then follow the prompts and paste the bot token here. " +
            "Then send your bot any message and tap 'Detect chat'. A bot you own, talking only to the chat you point it at — it cannot read your other Telegram conversations."
    ),
    GOOGLE(
        "google", "Google Drive",
        "List, upload, create, read, share and trash the Drive files Jarvis created or you picked.",
        "https://console.cloud.google.com/apis/credentials",
        "Jarvis uses YOUR OWN Google OAuth client (scope drive.file — only files Jarvis created or you picked). " +
            "In Google Cloud Console → APIs & Services → Credentials create an OAuth client: type 'Android' with package com.jarvis.assistant and your signing SHA-1, " +
            "or type 'Desktop'/'Web' with redirect URI com.jarvis.assistant.oauth:/callback (Web clients also need the client secret). " +
            "Enable the Google Drive API, then tap 'Link Google account'. No client id? Paste an access token from developers.google.com/oauthplayground (scope drive.file) — it lasts about an hour. " +
            "Full Drive access (searching every file) needs Google's restricted scopes, which cost a paid annual security assessment, so this connector deliberately stays on the free, narrow one."
    ),
    GITHUB(
        "github", "GitHub",
        "Repos, files, branches, issues, PRs, commits, Actions, gists — via the GitHub token Jarvis already stores.",
        "https://github.com/settings/tokens",
        "Open github.com → Settings → Developer settings → Personal access tokens → Fine-grained tokens. " +
            "Give it Contents, Issues, Pull requests, Actions (read) and Gists access for the repos you want Jarvis to touch."
    );

    companion object {
        fun fromId(id: String): ConnectorService? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * Encrypted token store for the Maya connectors. Everything lives in the
 * EncryptedSharedPreferences file "maya_connectors_secure" (AES256-SIV keys / AES256-GCM values).
 */
object ConnectorSecrets {

    private const val TAG = "ConnectorSecrets"
    private const val FILE = "maya_connectors_secure"
    private const val FALLBACK_FILE = "maya_connectors_fallback"

    // Key names (per service)
    const val NOTION_TOKEN = "notion_token"
    const val LINEAR_TOKEN = "linear_token"
    const val TODOIST_TOKEN = "todoist_token"
    const val GITLAB_TOKEN = "gitlab_token"
    const val GITLAB_HOST = "gitlab_host"
    const val VERCEL_TOKEN = "vercel_token"
    const val TELEGRAM_BOT_TOKEN = "telegram_bot_token"
    const val TELEGRAM_CHAT_ID = "telegram_chat_id"
    const val TELEGRAM_CHAT_NAME = "telegram_chat_name"
    const val GOOGLE_CLIENT_ID = "google_client_id"
    const val GOOGLE_CLIENT_SECRET = "google_client_secret"
    const val GOOGLE_REFRESH_TOKEN = "google_refresh_token"
    const val GOOGLE_ACCESS_TOKEN = "google_access_token"
    const val GOOGLE_TOKEN_EXPIRY = "google_token_expiry"
    const val GOOGLE_MANUAL_TOKEN = "google_manual_token"
    const val GOOGLE_ACCOUNT_EMAIL = "google_account_email"
    const val GOOGLE_PKCE_VERIFIER = "google_pkce_verifier"
    const val GOOGLE_OAUTH_STATE = "google_oauth_state"

    const val DEFAULT_GITLAB_HOST = "https://gitlab.com"

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val app = context.applicationContext
            val created = try {
                val masterKey = MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    app,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e(TAG, "Encrypted connector storage could not be initialized; using fallback prefs", e)
                app.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
            }
            prefs = created
            return created
        }
    }

    fun get(context: Context, key: String): String? =
        try { prefs(context).getString(key, null)?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }

    fun set(context: Context, key: String, value: String?) {
        try {
            val editor = prefs(context).edit()
            if (value.isNullOrBlank()) editor.remove(key) else editor.putString(key, value.trim())
            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "set($key) failed: ${e.message}")
        }
    }

    fun getLong(context: Context, key: String): Long =
        try { prefs(context).getLong(key, 0L) } catch (_: Exception) { 0L }

    fun setLong(context: Context, key: String, value: Long) {
        try { prefs(context).edit().putLong(key, value).apply() } catch (_: Exception) { }
    }

    fun remove(context: Context, vararg keys: String) {
        try {
            val editor = prefs(context).edit()
            keys.forEach { editor.remove(it) }
            editor.apply()
        } catch (_: Exception) { }
    }

    // ---- per-service convenience ----

    fun notionToken(context: Context) = get(context, NOTION_TOKEN)
    fun linearToken(context: Context) = get(context, LINEAR_TOKEN)
    fun todoistToken(context: Context) = get(context, TODOIST_TOKEN)
    fun gitlabToken(context: Context) = get(context, GITLAB_TOKEN)
    fun gitlabHost(context: Context): String =
        (get(context, GITLAB_HOST) ?: DEFAULT_GITLAB_HOST).trim().trimEnd('/').let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }
    fun vercelToken(context: Context) = get(context, VERCEL_TOKEN)
    fun telegramBotToken(context: Context) = get(context, TELEGRAM_BOT_TOKEN)
    fun telegramChatId(context: Context) = get(context, TELEGRAM_CHAT_ID)

    /** GitHub token is owned by the existing [com.jarvis.assistant.connectors.GitHubConnector]; read it from there. */
    fun githubToken(context: Context): String? =
        try { com.jarvis.assistant.connectors.GitHubConnector(context).token?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }

    /** Linked = the credentials needed for the service's tools are present (no network check). */
    fun isLinked(context: Context, service: ConnectorService): Boolean = when (service) {
        ConnectorService.NOTION -> notionToken(context) != null
        ConnectorService.LINEAR -> linearToken(context) != null
        ConnectorService.TODOIST -> todoistToken(context) != null
        ConnectorService.GITLAB -> gitlabToken(context) != null
        ConnectorService.VERCEL -> vercelToken(context) != null
        ConnectorService.TELEGRAM -> telegramBotToken(context) != null
        ConnectorService.GOOGLE -> get(context, GOOGLE_REFRESH_TOKEN) != null || get(context, GOOGLE_MANUAL_TOKEN) != null
        ConnectorService.GITHUB -> githubToken(context) != null
    }

    /** Clears every stored secret for one service. */
    fun unlink(context: Context, service: ConnectorService) {
        when (service) {
            ConnectorService.NOTION -> remove(context, NOTION_TOKEN)
            ConnectorService.LINEAR -> remove(context, LINEAR_TOKEN)
            ConnectorService.TODOIST -> remove(context, TODOIST_TOKEN)
            ConnectorService.GITLAB -> remove(context, GITLAB_TOKEN, GITLAB_HOST)
            ConnectorService.VERCEL -> remove(context, VERCEL_TOKEN)
            ConnectorService.TELEGRAM -> remove(context, TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID, TELEGRAM_CHAT_NAME)
            ConnectorService.GOOGLE -> remove(
                context, GOOGLE_REFRESH_TOKEN, GOOGLE_ACCESS_TOKEN, GOOGLE_TOKEN_EXPIRY,
                GOOGLE_MANUAL_TOKEN, GOOGLE_ACCOUNT_EMAIL, GOOGLE_PKCE_VERIFIER, GOOGLE_OAUTH_STATE
            )
            ConnectorService.GITHUB -> try { com.jarvis.assistant.connectors.GitHubConnector(context).token = null } catch (_: Exception) { }
        }
        remove(context, "status_${service.id}")
    }

    /** Last "Test" outcome shown on the Connectors screen: "ok", "error:<msg>" or null. */
    fun lastStatus(context: Context, service: ConnectorService): String? = get(context, "status_${service.id}")
    fun setLastStatus(context: Context, service: ConnectorService, value: String?) = set(context, "status_${service.id}", value)

    /** Masks a token for display: first 4 + •••• + last 3. */
    fun mask(token: String?): String {
        if (token.isNullOrBlank()) return ""
        if (token.length <= 8) return "••••••••"
        return token.take(4) + "••••••••" + token.takeLast(3)
    }
}
