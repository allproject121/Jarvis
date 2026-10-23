package com.jarvis.assistant.automation.developer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Autonomous Website Creation & Sandbox Deployment.
 * Builds responsive single-page or multi-page websites directly on-device,
 * writes HTML/CSS/JS assets, and opens instant live preview in Google Chrome.
 */
class WebsiteSandboxManager(private val context: Context) {

    companion object {
        private const val TAG = "WebsiteSandboxManager"
    }

    data class WebBuildResult(
        val success: Boolean,
        val indexPath: String,
        val previewUrl: String,
        val message: String
    )

    fun createAndDeployWebsite(
        projectName: String,
        title: String = "My Website",
        htmlBody: String = "",
        customCss: String = "",
        customJs: String = "",
        autoOpenInBrowser: Boolean = true
    ): WebBuildResult {
        return try {
            val cleanSlug = projectName.replace(Regex("[^a-zA-Z0-9_-]"), "_").lowercase().ifBlank { "project_${System.currentTimeMillis()}" }
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val webDir = File(downloadsDir, "JarvisWebsites/$cleanSlug").apply { mkdirs() }

            val indexFile = File(webDir, "index.html")
            val cssFile = File(webDir, "styles.css")
            val jsFile = File(webDir, "app.js")

            // Default modern Tailwind + cyberpunk aesthetic if body is simple
            val fullHtml = if (htmlBody.contains("<!DOCTYPE html>", ignoreCase = true) || htmlBody.contains("<html", ignoreCase = true)) {
                htmlBody
            } else {
                """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>$title</title>
                    <script src="https://cdn.tailwindcss.com"></script>
                    <link rel="stylesheet" href="styles.css">
                </head>
                <body class="bg-gray-950 text-gray-100 font-sans min-h-screen flex flex-col justify-between">
                    <header class="p-6 border-b border-gray-800 flex justify-between items-center max-w-5xl mx-auto w-full">
                        <div class="text-xl font-bold tracking-wider text-cyan-400">JARVIS // WEB SANDBOX</div>
                        <span class="text-xs text-gray-500 uppercase tracking-widest">$cleanSlug</span>
                    </header>
                    <main class="max-w-4xl mx-auto px-6 py-12 flex-1 w-full">
                        <h1 class="text-4xl font-extrabold text-white mb-6">$title</h1>
                        <div class="prose prose-invert max-w-none text-gray-300">
                            $htmlBody
                        </div>
                    </main>
                    <footer class="p-6 border-t border-gray-900 text-center text-xs text-gray-600">
                        Generated autonomously by JARVIS AI • Mobile Web Sandbox
                    </footer>
                    <script src="app.js"></script>
                </body>
                </html>
                """.trimIndent()
            }

            indexFile.writeText(fullHtml)
            cssFile.writeText(customCss.ifBlank { "/* Custom Styles */" })
            jsFile.writeText(customJs.ifBlank { "// Interactive Logic\nconsole.log('Jarvis Website Sandbox Active');" })

            Log.i(TAG, "Successfully built website '$cleanSlug' at: ${indexFile.absolutePath}")

            if (autoOpenInBrowser) {
                try {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        indexFile
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "text/html")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Log.i(TAG, "Launched Chrome preview for: ${indexFile.name}")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open browser via FileProvider: ${e.message}")
                }
            }

            WebBuildResult(
                success = true,
                indexPath = indexFile.absolutePath,
                previewUrl = "file://${indexFile.absolutePath}",
                message = "Created website '$cleanSlug' with index.html, styles.css, and app.js."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating website: ${e.message}", e)
            WebBuildResult(
                success = false,
                indexPath = "",
                previewUrl = "",
                message = "Failed to create website: ${e.message}"
            )
        }
    }
}
