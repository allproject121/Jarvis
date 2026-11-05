package com.jarvis.assistant.automation.developer

import android.content.Context
import android.util.Log
import com.jarvis.assistant.termux.TermuxBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GitHub Developer Engine.
 * Supports repository inspection, cloning via Termux, file updates, and commit workflows.
 */
class GitHubDevManager(private val context: Context) {

    companion object {
        private const val TAG = "GitHubDevManager"
    }

    private val termuxBridge = TermuxBridge(context)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class GitOpResult(
        val success: Boolean,
        val output: String,
        val message: String
    )

    suspend fun inspectRepository(owner: String, repo: String): GitOpResult = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$owner/$repo"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Jarvis-Assistant-Mobile")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext GitOpResult(
                    success = false,
                    output = "",
                    message = "GitHub API returned HTTP ${response.code} for $owner/$repo"
                )
            }

            val body = response.body?.string() ?: "{}"
            val json = JSONObject(body)
            val stars = json.optInt("stargazers_count", 0)
            val forks = json.optInt("forks_count", 0)
            val desc = json.optString("description", "No description")
            val defaultBranch = json.optString("default_branch", "main")

            // Fetch root contents
            val contentsUrl = "https://api.github.com/repos/$owner/$repo/contents"
            val contentsReq = Request.Builder()
                .url(contentsUrl)
                .header("User-Agent", "Jarvis-Assistant-Mobile")
                .build()
            val contentsRes = httpClient.newCall(contentsReq).execute()
            val fileNames = mutableListOf<String>()
            if (contentsRes.isSuccessful) {
                val contentsJson = JSONArray(contentsRes.body?.string() ?: "[]")
                for (i in 0 until contentsJson.length()) {
                    val item = contentsJson.getJSONObject(i)
                    fileNames.add(item.optString("name", ""))
                }
            }

            val summary = """
                Repository: $owner/$repo
                Description: $desc
                Stars: $stars | Forks: $forks | Default Branch: $defaultBranch
                Root Files: ${fileNames.joinToString(", ")}
            """.trimIndent()

            GitOpResult(true, summary, "Inspected repository $owner/$repo successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting repo: ${e.message}", e)
            GitOpResult(false, "", "Error inspecting GitHub repo: ${e.message}")
        }
    }

    suspend fun cloneRepository(repoUrl: String, destinationFolder: String? = null): GitOpResult = withContext(Dispatchers.IO) {
        val folder = destinationFolder ?: repoUrl.substringAfterLast("/").removeSuffix(".git")
        if (termuxBridge.isTermuxInstalled()) {
            val cmd = "git clone $repoUrl $folder 2>&1"
            Log.i(TAG, "Executing git clone via Termux: $cmd")
            val result = termuxBridge.executeBashCommand(cmd, timeoutMs = 45000L)
            if (result != null && result.exitCode == 0) {
                GitOpResult(true, result.stdout, "Repository cloned into Termux: $folder")
            } else {
                GitOpResult(false, result?.stderr.orEmpty(), "Git clone failed: ${result?.stderr ?: "Timeout"}")
            }
        } else {
            GitOpResult(false, "", "Termux is not installed. Please install Termux with git to run local repository cloning.")
        }
    }

    suspend fun commitAndPush(repoFolder: String, commitMessage: String): GitOpResult = withContext(Dispatchers.IO) {
        if (termuxBridge.isTermuxInstalled()) {
            val cmd = "cd $repoFolder && git add -A && git commit -m \"$commitMessage\" && git push 2>&1"
            Log.i(TAG, "Executing git commit & push via Termux: $cmd")
            val result = termuxBridge.executeBashCommand(cmd, timeoutMs = 30000L)
            if (result != null && result.exitCode == 0) {
                GitOpResult(true, result.stdout, "Git commit & push successful: $commitMessage")
            } else {
                GitOpResult(false, result?.stderr.orEmpty(), "Git commit/push failed: ${result?.stderr ?: "Error"}")
            }
        } else {
            GitOpResult(false, "", "Termux is required to execute git commits on device.")
        }
    }
}
