package com.jarvis.assistant.automation.skills.builtin

import android.content.Context
import android.util.Log
import com.jarvis.assistant.automation.skills.JarvisSkill
import com.jarvis.assistant.automation.skills.SkillCategory
import com.jarvis.assistant.automation.skills.SkillExecutionContext
import com.jarvis.assistant.automation.skills.SkillExecutionResult
import com.jarvis.assistant.automation.skills.SkillExecutionStatus
import com.jarvis.assistant.automation.skills.SkillMetadata
import com.jarvis.assistant.automation.skills.SkillRiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Built-in skill for secure, read-only inspection and analysis of local repositories or ZIP archives.
 * Enforces strict security safeguards:
 *  - Full Zip-Slip / Path Traversal defense.
 *  - Strict read-only mode (no script execution, no code modification).
 *  - Zip-Bomb protection (file count & byte size limits).
 *  - Automatic redaction of detected secrets, API keys, and tokens.
 */
class RepositoryAnalysisSkill : JarvisSkill {

    companion object {
        private const val TAG = "RepoAnalysisSkill"
        private const val MAX_ZIP_EXTRACTED_BYTES = 50 * 1024 * 1024L // 50 MB limit
        private const val MAX_ZIP_FILES = 1000

        private val IGNORED_DIRS = setOf(
            ".git", "node_modules", "build", ".gradle", "venv", ".env",
            "dist", "out", ".idea", ".vscode", "__pycache__", ".svn"
        )

        private val SECRET_PATTERNS = listOf(
            Regex("""(?i)(?:api_?key|secret|token|password|auth|bearer)\s*[:=]\s*["']?([a-zA-Z0-9_\-.~]{8,})["']?"""),
            Regex("""AIza[0-9A-Za-z\\-_]{35}"""),
            Regex("""sk-[a-zA-Z0-9]{20,}"""),
            Regex("""ghp_[a-zA-Z0-9]{36}"""),
            Regex("""AKIA[0-9A-Z]{16}""")
        )

        /**
         * Redacts API keys, credentials, and tokens from any text or logs.
         */
        fun redactSecrets(input: String): String {
            var sanitized = input
            for (pattern in SECRET_PATTERNS) {
                sanitized = pattern.replace(sanitized) { matchResult ->
                    val full = matchResult.value
                    if (matchResult.groups.size > 1 && matchResult.groups[1] != null) {
                        val secretVal = matchResult.groups[1]!!.value
                        full.replace(secretVal, "[REDACTED_SECRET]")
                    } else {
                        "[REDACTED_SECRET]"
                    }
                }
            }
            return sanitized
        }

        private fun buildSchemas(): List<JSONObject> {
            val list = mutableListOf<JSONObject>()
            list.add(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "analyze_code_repository")
                    put("description", "Analyzes a local code repository directory or ZIP archive in strictly read-only mode, reporting structure, detected frameworks, dependencies, and integration points with security protections.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("repo_path", JSONObject().apply {
                                put("type", "string")
                                put("description", "Absolute path to the local repository directory or .zip archive (e.g. '/sdcard/Download/project.zip')")
                            })
                            put("max_depth", JSONObject().apply {
                                put("type", "integer")
                                put("description", "Maximum directory scan depth (1 to 8, default 4)")
                            })
                            put("include_dependencies", JSONObject().apply {
                                put("type", "boolean")
                                put("description", "Whether to extract and summarize declared dependencies (default true)")
                            })
                        })
                        put("required", JSONArray().apply { put("repo_path") })
                    })
                })
            })
            return list
        }
    }

    override val metadata: SkillMetadata = SkillMetadata(
        id = "repo_analysis_skill",
        name = "Repository & Code Analyzer",
        description = "Performs secure, read-only analysis of local repositories or ZIP archives, identifying structure, technologies, dependencies, and integration points.",
        version = "1.0.0",
        category = SkillCategory.DEVELOPER_TOOLS,
        riskLevel = SkillRiskLevel.SAFE,
        requiredPermissions = emptyList(),
        isNonIdempotent = false,
        timeoutMs = 30_000L,
        supportedTools = listOf("analyze_code_repository"),
        toolSchemas = buildSchemas()
    )

    override suspend fun execute(context: SkillExecutionContext): SkillExecutionResult = withContext(Dispatchers.IO) {
        val name = context.toolName
        val args = context.arguments

        if (name != "analyze_code_repository") {
            return@withContext SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "Unsupported tool '$name' in RepositoryAnalysisSkill.",
                errorMessage = "Unsupported tool: $name"
            )
        }

        val rawPath = args.optString("repo_path", "").ifBlank { args.optString("path", "").ifBlank { args.optString("url", "") } }
        val maxDepth = args.optInt("max_depth", 4).coerceIn(1, 8)
        val includeDependencies = args.optBoolean("include_dependencies", true)

        if (rawPath.isBlank()) {
            return@withContext SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "Please specify a repository directory path or ZIP file path to analyze.",
                errorMessage = "Missing repo_path argument"
            )
        }

        // Normalize URL if entered without protocol or with http
        var cleanedPath = rawPath.trim()
        if (cleanedPath.startsWith("github.com/", ignoreCase = true) || cleanedPath.startsWith("www.github.com/", ignoreCase = true)) {
            cleanedPath = "https://" + cleanedPath.removePrefix("www.")
        } else if (cleanedPath.startsWith("http://github.com/", ignoreCase = true)) {
            cleanedPath = "https://" + cleanedPath.substring("http://".length)
        }

        // ── REMOTE REPOSITORY PROTOCOL VALIDATION & COMPANION DELEGATION ────
        val isRemoteUrl = cleanedPath.startsWith("https://github.com/", ignoreCase = true) ||
                cleanedPath.contains("://") ||
                cleanedPath.startsWith("git@")

        if (isRemoteUrl) {
            Log.i(TAG, "Routing repository request as remote URL: $cleanedPath")
            if (!cleanedPath.startsWith("https://github.com/", ignoreCase = true)) {
                return@withContext SkillExecutionResult(
                    status = SkillExecutionStatus.FAILED,
                    userFacingMessage = "Unsupported repository URL. Only public HTTPS GitHub URLs ('https://github.com/owner/repo') are supported. Protocols like ssh://, ftp://, and http:// are rejected for security.",
                    errorMessage = "Unsupported repository protocol: $cleanedPath"
                )
            }

            val client = com.jarvis.assistant.automation.skills.companion.CompanionApiClient(context.context)
            if (!client.isCompanionAvailable()) {
                Log.w(TAG, "Companion service unavailable for remote Git clone")
                return@withContext SkillExecutionResult(
                    status = SkillExecutionStatus.BLOCKED,
                    userFacingMessage = "Analyzing remote GitHub repository '$cleanedPath' requires the Jarvis Laptop Companion. Please start the companion service on your PC ('node companion/server.js'), or download the repository ZIP file onto your device for offline analysis.",
                    errorMessage = "Companion service unavailable for remote Git clone"
                )
            }

            val companionRes = client.analyzeRepository(cleanedPath, maxDepth)
            if (companionRes == null) {
                Log.e(TAG, "Companion returned null response for analyzeRepository")
                return@withContext SkillExecutionResult(
                    status = SkillExecutionStatus.FAILED,
                    userFacingMessage = "Failed to analyze remote repository: Laptop Companion did not return a response.",
                    errorMessage = "Companion returned null response"
                )
            }

            if (companionRes.has("error")) {
                val err = companionRes.optString("error", "Unknown companion error")
                Log.e(TAG, "Companion analysis failed: $err")
                return@withContext SkillExecutionResult(
                    status = SkillExecutionStatus.FAILED,
                    userFacingMessage = "Repository analysis failed: $err",
                    errorMessage = err
                )
            }

            if (companionRes.has("analysis")) {
                val analysis = companionRes.getJSONObject("analysis")
                val techArr = analysis.optJSONArray("technologies")
                val techStr = if (techArr != null && techArr.length() > 0) {
                    (0 until techArr.length()).map { techArr.getString(it) }.joinToString()
                } else "Generic"
                val filesCount = analysis.optInt("totalFiles", 0)
                val dirsCount = analysis.optInt("totalDirs", 0)
                val previewArr = analysis.optJSONArray("fileTreePreview")
                val previewStr = if (previewArr != null && previewArr.length() > 0) {
                    (0 until previewArr.length().coerceAtMost(5)).map { previewArr.getString(it) }.joinToString(", ")
                } else ""

                val summarySb = StringBuilder()
                summarySb.append("Analyzed remote GitHub repository: $cleanedPath (via Laptop Companion)\n")
                summarySb.append("• Technologies detected: $techStr\n")
                summarySb.append("• Total files scanned: $filesCount across $dirsCount directories")
                if (previewStr.isNotBlank()) {
                    summarySb.append("\n• Files preview: $previewStr")
                }

                Log.i(TAG, "Remote analysis succeeded: $filesCount files, technologies=$techStr")
                return@withContext SkillExecutionResult(
                    status = SkillExecutionStatus.SUCCESS,
                    data = companionRes,
                    userFacingMessage = redactSecrets(summarySb.toString()),
                    isVerified = true
                )
            } else {
                Log.e(TAG, "Invalid companion response structure: missing 'analysis' field")
                return@withContext SkillExecutionResult(
                    status = SkillExecutionStatus.FAILED,
                    userFacingMessage = "Repository analysis returned invalid structure from Companion.",
                    errorMessage = "Missing analysis object in companion response"
                )
            }
        }

        val sourceFile = File(rawPath)
        if (!sourceFile.exists()) {
            return@withContext SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "The specified path does not exist: '$rawPath'. Please check the file path.",
                errorMessage = "File not found: $rawPath"
            )
        }

        val targetDir: File
        var isTemporaryExtraction = false

        try {
            if (sourceFile.isFile && sourceFile.name.endsWith(".zip", ignoreCase = true)) {
                // Securely unzip with Zip-Slip defense
                val sandboxDir = File(context.context.cacheDir, "repo_sandbox_${System.currentTimeMillis()}").apply { mkdirs() }
                unzipWithSecurityCheck(sourceFile, sandboxDir)
                targetDir = sandboxDir
                isTemporaryExtraction = true
            } else if (sourceFile.isDirectory) {
                targetDir = sourceFile
            } else {
                return@withContext SkillExecutionResult(
                    status = SkillExecutionStatus.FAILED,
                    userFacingMessage = "Target must be a directory or a .zip file. Given: '${sourceFile.name}'",
                    errorMessage = "Target is not a directory or zip file"
                )
            }

            // Perform analysis
            val analysis = analyzeDirectory(targetDir, maxDepth, includeDependencies)

            val summarySb = StringBuilder()
            summarySb.append("Analyzed repository: ${sourceFile.name}\n")
            summarySb.append("• Technologies detected: ${if (analysis.technologies.isEmpty()) "Generic" else analysis.technologies.joinToString()}\n")
            summarySb.append("• Total files scanned: ${analysis.totalFiles} across ${analysis.totalDirs} directories\n")
            if (analysis.entryPoints.isNotEmpty()) {
                summarySb.append("• Key entry points: ${analysis.entryPoints.joinToString()}\n")
            }
            if (analysis.keyDependencies.isNotEmpty()) {
                summarySb.append("• Key dependencies: ${analysis.keyDependencies.take(6).joinToString()}\n")
            }
            if (analysis.potentialIntegrationPoints.isNotEmpty()) {
                summarySb.append("• Integration points: ${analysis.potentialIntegrationPoints.joinToString()}\n")
            }

            val dataJson = JSONObject().apply {
                put("repository_name", sourceFile.name)
                put("is_zip", sourceFile.isFile)
                put("total_files", analysis.totalFiles)
                put("total_dirs", analysis.totalDirs)
                put("technologies", JSONArray(analysis.technologies))
                put("entry_points", JSONArray(analysis.entryPoints))
                put("key_dependencies", JSONArray(analysis.keyDependencies))
                put("integration_points", JSONArray(analysis.potentialIntegrationPoints))
                put("file_tree_preview", JSONArray(analysis.fileTreePreview.take(50)))
            }

            SkillExecutionResult(
                status = SkillExecutionStatus.SUCCESS,
                data = dataJson,
                userFacingMessage = redactSecrets(summarySb.toString().trim()),
                isVerified = true
            )
        } catch (sec: SecurityException) {
            Log.e(TAG, "Security barrier triggered during repo analysis: ${sec.message}")
            SkillExecutionResult(
                status = SkillExecutionStatus.BLOCKED,
                userFacingMessage = "Security violation detected: ${sec.message}",
                errorMessage = sec.message
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing repository: ${e.message}", e)
            SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "Failed to analyze repository: ${e.message}",
                errorMessage = e.message
            )
        } finally {
            if (isTemporaryExtraction) {
                try {
                    val sandbox = File(context.context.cacheDir, "repo_sandbox_${System.currentTimeMillis()}")
                    // Clean up temp directories
                    context.context.cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith("repo_sandbox_") }
                        ?.forEach { it.deleteRecursively() }
                } catch (ignored: Exception) {}
            }
        }
    }

    /**
     * Unzips a ZIP archive with strict Zip-Slip (path traversal) and Zip-Bomb protections.
     */
    fun unzipWithSecurityCheck(zipFile: File, destinationDir: File) {
        val destCanonical = destinationDir.canonicalFile
        var totalBytes = 0L
        var totalFiles = 0

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                totalFiles++
                if (totalFiles > MAX_ZIP_FILES) {
                    throw SecurityException("Archive contains too many entries (exceeds $MAX_ZIP_FILES files). Rejected to prevent DOS.")
                }

                val outputFile = File(destinationDir, entry.name)
                val outputCanonical = outputFile.canonicalFile

                // Zip-Slip Path Traversal Defense
                if (!outputCanonical.path.startsWith(destCanonical.path + File.separator) &&
                    outputCanonical.path != destCanonical.path) {
                    throw SecurityException("Zip-Slip path traversal vulnerability detected in entry '${entry.name}'. Extraction aborted.")
                }

                if (entry.isDirectory) {
                    outputCanonical.mkdirs()
                } else {
                    outputCanonical.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(outputCanonical)).use { bos ->
                        val buffer = ByteArray(4096)
                        var read: Int
                        while (zis.read(buffer).also { read = it } != -1) {
                            totalBytes += read
                            if (totalBytes > MAX_ZIP_EXTRACTED_BYTES) {
                                throw SecurityException("Archive uncompressed size exceeds limit (${MAX_ZIP_EXTRACTED_BYTES / (1024 * 1024)} MB). Rejected to prevent Zip-Bomb.")
                            }
                            bos.write(buffer, 0, read)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private data class AnalysisReport(
        val totalFiles: Int,
        val totalDirs: Int,
        val technologies: List<String>,
        val entryPoints: List<String>,
        val keyDependencies: List<String>,
        val potentialIntegrationPoints: List<String>,
        val fileTreePreview: List<String>
    )

    private fun analyzeDirectory(dir: File, maxDepth: Int, includeDependencies: Boolean): AnalysisReport {
        var totalFiles = 0
        var totalDirs = 0
        val detectedTech = mutableSetOf<String>()
        val entryPoints = mutableListOf<String>()
        val dependencies = mutableListOf<String>()
        val integrationPoints = mutableListOf<String>()
        val fileTree = mutableListOf<String>()

        fun scan(current: File, depth: Int, relativePath: String) {
            if (depth > maxDepth) return
            val children = current.listFiles() ?: return

            for (file in children) {
                val rel = if (relativePath.isEmpty()) file.name else "$relativePath/${file.name}"

                if (file.isDirectory) {
                    if (file.name in IGNORED_DIRS || file.name.startsWith(".")) continue
                    totalDirs++
                    fileTree.add("$rel/")
                    scan(file, depth + 1, rel)
                } else {
                    totalFiles++
                    fileTree.add(rel)
                    val name = file.name.lowercase()

                    // Technology detection
                    when {
                        name == "build.gradle" || name == "build.gradle.kts" || name == "settings.gradle" -> {
                            detectedTech.add("Android / Kotlin Gradle")
                            if (includeDependencies && dependencies.size < 15) {
                                extractGradleDependencies(file, dependencies)
                            }
                        }
                        name == "androidmanifest.xml" -> detectedTech.add("Android Application")
                        name == "package.json" -> {
                            detectedTech.add("Node.js / JavaScript")
                            if (includeDependencies && dependencies.size < 15) {
                                extractPackageJsonDependencies(file, dependencies)
                            }
                        }
                        name == "tsconfig.json" -> detectedTech.add("TypeScript")
                        name == "requirements.txt" || name == "pyproject.toml" || name == "setup.py" -> {
                            detectedTech.add("Python")
                            if (includeDependencies && name == "requirements.txt" && dependencies.size < 15) {
                                extractRequirementsDependencies(file, dependencies)
                            }
                        }
                        name == "pom.xml" -> detectedTech.add("Java (Maven)")
                        name == "cargo.toml" -> detectedTech.add("Rust")
                        name == "go.mod" -> detectedTech.add("Go")
                        name == "dockerfile" || name.startsWith("docker-compose") -> detectedTech.add("Docker")
                    }

                    // Key entry points
                    if (name in setOf("mainactivity.kt", "mainactivity.java", "index.js", "index.ts", "server.js", "main.py", "app.py", "main.rs", "main.go")) {
                        entryPoints.add(rel)
                    }

                    // Potential integration points
                    val lowerRel = rel.lowercase()
                    if (lowerRel.contains("api/") || lowerRel.contains("routes/") || lowerRel.contains("endpoints/") || lowerRel.contains("webhook")) {
                        if (integrationPoints.size < 8) integrationPoints.add(rel)
                    }
                }
            }
        }

        scan(dir, 1, "")

        return AnalysisReport(
            totalFiles = totalFiles,
            totalDirs = totalDirs,
            technologies = detectedTech.toList(),
            entryPoints = entryPoints.distinct(),
            keyDependencies = dependencies.distinct().take(12),
            potentialIntegrationPoints = integrationPoints.distinct(),
            fileTreePreview = fileTree
        )
    }

    private fun extractPackageJsonDependencies(file: File, out: MutableList<String>) {
        try {
            val content = file.readText().take(10_000)
            val json = JSONObject(content)
            val deps = json.optJSONObject("dependencies")
            if (deps != null) {
                val keys = deps.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    out.add("$k (${deps.optString(k)})")
                }
            }
        } catch (ignored: Exception) {}
    }

    private fun extractRequirementsDependencies(file: File, out: MutableList<String>) {
        try {
            file.forEachLine { line ->
                val clean = line.trim()
                if (clean.isNotBlank() && !clean.startsWith("#")) {
                    out.add(clean.take(40))
                }
            }
        } catch (ignored: Exception) {}
    }

    private fun extractGradleDependencies(file: File, out: MutableList<String>) {
        try {
            file.forEachLine { line ->
                val clean = line.trim()
                if (clean.startsWith("implementation") || clean.startsWith("api")) {
                    val dep = clean.substringAfter(" ").trim().trim('"', '\'', '(', ')')
                    if (dep.isNotBlank() && dep.contains(":")) {
                        out.add(dep.take(50))
                    }
                }
            }
        } catch (ignored: Exception) {}
    }
}
