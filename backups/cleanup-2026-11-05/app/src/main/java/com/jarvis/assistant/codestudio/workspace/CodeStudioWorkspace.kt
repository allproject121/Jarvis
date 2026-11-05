package com.jarvis.assistant.codestudio.workspace

import android.content.Context
import com.jarvis.assistant.codestudio.CodeStudioPaths
import java.io.File
import java.io.IOException

/** One node of a project's file tree (flattened for display, [depth] = indentation level). */
data class FileNode(
    val file: File,
    val relativePath: String,
    val depth: Int,
    val isDirectory: Boolean,
    val size: Long
) {
    val name: String get() = file.name
}

/** A project under [CodeStudioPaths.projectsRoot]. */
data class ProjectInfo(val name: String, val dir: File, val fileCount: Int, val lastModified: Long)

/**
 * All Code Studio file operations. Every path is resolved through [resolveInRoot] / [resolveInProject]
 * which canonicalise the path and reject anything that escapes the projects root (path traversal,
 * absolute paths elsewhere, symlinks pointing out).
 */
class CodeStudioWorkspace(context: Context) {

    private val appContext = context.applicationContext
    val root: File get() = CodeStudioPaths.projectsRoot(appContext)

    // ── Safety ──────────────────────────────────────────────────────────────

    private fun canonicalRoot(): File = root.canonicalFile

    /** True when [file] is the root itself or strictly inside it (after canonicalisation). */
    fun isInsideRoot(file: File): Boolean {
        val r = canonicalRoot().path
        val c = runCatching { file.canonicalFile.path }.getOrNull() ?: return false
        return c == r || c.startsWith(r + File.separator)
    }

    private fun requireInside(file: File, base: File = canonicalRoot()): File {
        val c = file.canonicalFile
        val b = base.canonicalFile.path
        if (c.path != b && !c.path.startsWith(b + File.separator)) {
            throw SecurityException("Path '${file.path}' is outside the Code Studio workspace")
        }
        return c
    }

    /** Resolves [relative] (e.g. "MyApp/src/index.html") against the projects root. */
    fun resolveInRoot(relative: String): File {
        val clean = relative.trim().replace('\\', '/').trimStart('/')
        val f = File(root, clean)
        return requireInside(f)
    }

    /** Resolves a project-relative path inside [projectDir] (which itself must be inside root). */
    fun resolveInProject(projectDir: File, relative: String): File {
        val base = requireInside(projectDir)
        val clean = relative.trim().replace('\\', '/').trimStart('/')
        if (clean.isEmpty()) return base
        return requireInside(File(base, clean), base)
    }

    fun relativeTo(projectDir: File, file: File): String =
        runCatching { file.canonicalFile.relativeTo(projectDir.canonicalFile).path.replace('\\', '/') }
            .getOrDefault(file.name)

    // ── Projects ────────────────────────────────────────────────────────────

    fun listProjects(): List<ProjectInfo> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .map { dir ->
                val files = dir.walkTopDown().onEnter { !isIgnoredDir(it) || it == dir }.filter { it.isFile }.toList()
                ProjectInfo(
                    name = dir.name,
                    dir = dir,
                    fileCount = files.size,
                    lastModified = (files.maxOfOrNull { it.lastModified() } ?: dir.lastModified())
                )
            }
            .sortedByDescending { it.lastModified }

    fun projectExists(name: String): Boolean = File(root, CodeStudioPaths.sanitize(name)).isDirectory

    /** Creates a project folder; when [withStarter] writes a minimal index.html/style.css/script.js. */
    fun createProject(name: String, withStarter: Boolean = true): File {
        val dir = requireInside(File(root, CodeStudioPaths.sanitize(name)))
        if (dir.exists() && (dir.listFiles()?.isNotEmpty() == true)) return dir
        dir.mkdirs()
        if (withStarter) {
            File(dir, "index.html").writeText(STARTER_HTML.replace("{{title}}", dir.name))
            File(dir, "style.css").writeText(STARTER_CSS)
            File(dir, "script.js").writeText(STARTER_JS)
        }
        return dir
    }

    fun renameProject(dir: File, newName: String): File {
        val src = requireInside(dir)
        val dst = requireInside(File(root, CodeStudioPaths.sanitize(newName)))
        if (dst.exists()) throw IOException("A project named '${dst.name}' already exists")
        if (!src.renameTo(dst)) throw IOException("Could not rename project")
        return dst
    }

    fun deleteProject(dir: File): Boolean {
        val d = requireInside(dir)
        if (d == canonicalRoot()) throw SecurityException("Refusing to delete the workspace root")
        return d.deleteRecursively()
    }

    // ── Tree ────────────────────────────────────────────────────────────────

    fun isIgnoredDir(dir: File): Boolean =
        dir.isDirectory && (dir.name in IGNORED_DIRS)

    /**
     * Flattened, depth-first tree. Folders first then files, alphabetical. Folders in [collapsed]
     * (project-relative paths) are listed but their children are skipped.
     */
    fun listTree(projectDir: File, collapsed: Set<String> = emptySet(), maxEntries: Int = 2000): List<FileNode> {
        val base = requireInside(projectDir)
        val out = ArrayList<FileNode>()
        fun walk(dir: File, depth: Int) {
            val children = (dir.listFiles() ?: emptyArray())
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            for (c in children) {
                if (out.size >= maxEntries) return
                val rel = relativeTo(base, c)
                out += FileNode(c, rel, depth, c.isDirectory, if (c.isFile) c.length() else 0L)
                if (c.isDirectory && rel !in collapsed && !isIgnoredDir(c)) walk(c, depth + 1)
            }
        }
        walk(base, 0)
        return out
    }

    /** Plain-text tree used as AI context ("existing files" + structure). */
    fun describeTree(projectDir: File, maxLines: Int = 250): String {
        val nodes = listTree(projectDir, maxEntries = maxLines + 1)
        val sb = StringBuilder(projectDir.name).append("/\n")
        nodes.take(maxLines).forEach { n ->
            sb.append("  ".repeat(n.depth + 1))
            if (n.isDirectory) sb.append(n.name).append("/") else sb.append(n.name).append("  (").append(n.size).append(" B)")
            sb.append('\n')
        }
        if (nodes.size > maxLines) sb.append("  … (more files omitted)\n")
        return sb.toString()
    }

    fun listProjectFiles(projectDir: File): List<File> =
        requireInside(projectDir).walkTopDown()
            .onEnter { it == projectDir.canonicalFile || !isIgnoredDir(it) }
            .filter { it.isFile }
            .toList()

    // ── Files ───────────────────────────────────────────────────────────────

    fun readText(file: File, maxBytes: Long = MAX_EDITABLE_BYTES): String {
        val f = requireInside(file)
        if (!f.isFile) throw IOException("Not a file: ${f.name}")
        if (f.length() > maxBytes) throw IOException("File too large to open (${f.length() / 1024} KB)")
        return f.readText()
    }

    fun writeText(file: File, text: String) {
        val f = requireInside(file)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    fun createFile(parent: File, name: String, content: String = ""): File {
        val p = requireInside(parent)
        val f = requireInside(File(p, cleanName(name)))
        if (f.exists()) throw IOException("'${f.name}' already exists")
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    fun createFolder(parent: File, name: String): File {
        val p = requireInside(parent)
        val f = requireInside(File(p, cleanName(name)))
        if (f.exists()) throw IOException("'${f.name}' already exists")
        if (!f.mkdirs()) throw IOException("Could not create folder")
        return f
    }

    fun rename(file: File, newName: String): File {
        val src = requireInside(file)
        val dst = requireInside(File(src.parentFile, cleanName(newName)))
        if (dst.exists()) throw IOException("'${dst.name}' already exists")
        if (!src.renameTo(dst)) throw IOException("Could not rename '${src.name}'")
        return dst
    }

    fun delete(file: File): Boolean {
        val f = requireInside(file)
        if (f == canonicalRoot()) throw SecurityException("Refusing to delete the workspace root")
        return if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    /** File names may contain sub folders ("css/main.css") but no ".." segments. */
    private fun cleanName(name: String): String {
        val n = name.trim().replace('\\', '/').trim('/')
        if (n.isEmpty()) throw IOException("Name cannot be empty")
        if (n.split('/').any { it == ".." || it == "." || it.isBlank() }) throw SecurityException("Invalid name '$name'")
        return n
    }

    companion object {
        const val MAX_EDITABLE_BYTES = 1_500_000L
        val IGNORED_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".idea", "__pycache__", ".codestudio")

        fun isTextFile(file: File): Boolean {
            val ext = file.extension.lowercase()
            if (ext in BINARY_EXT) return false
            return true
        }

        private val BINARY_EXT = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "mp3", "mp4", "wav", "ogg", "webm", "zip",
            "jar", "apk", "aab", "so", "class", "dex", "ttf", "otf", "woff", "woff2", "pdf", "gz", "7z", "rar", "bin"
        )

        private const val STARTER_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>{{title}}</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <main class="container">
    <h1>{{title}}</h1>
    <p>Built with Jarvis Code Studio.</p>
    <button id="hello">Say hello</button>
  </main>
  <script src="script.js"></script>
</body>
</html>
"""
        private const val STARTER_CSS = """* { box-sizing: border-box; }
body {
  margin: 0;
  font-family: system-ui, sans-serif;
  background: #0f172a;
  color: #e2e8f0;
}
.container {
  max-width: 720px;
  margin: 0 auto;
  padding: 48px 20px;
}
button {
  padding: 10px 18px;
  border: 0;
  border-radius: 8px;
  background: #38bdf8;
  color: #0f172a;
  font-weight: 600;
}
"""
        private const val STARTER_JS = """document.getElementById('hello').addEventListener('click', () => {
  console.log('Hello from Code Studio');
  alert('Hello from Code Studio!');
});
"""
    }
}
