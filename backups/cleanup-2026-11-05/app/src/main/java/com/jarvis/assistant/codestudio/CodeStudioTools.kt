package com.jarvis.assistant.codestudio

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jarvis.assistant.codestudio.workspace.CodeStudioWorkspace
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent tools for Code Studio, registered in JarvisToolDispatcher. Every path is confined to
 * [CodeStudioPaths.projectsRoot] (path traversal is rejected by [CodeStudioWorkspace]).
 */
object CodeStudioTools {

    val NAMES = setOf("code_list_projects", "code_read_file", "code_write_file", "code_open_studio")

    private const val MAX_READ_CHARS = 60_000

    fun schemas(): List<JSONObject> {
        fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
        fun tool(name: String, desc: String, props: JSONObject, required: List<String>) = JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", desc)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", props)
                    put("required", JSONArray(required))
                })
            })
        }
        return listOf(
            tool("code_list_projects",
                "Lists Jarvis Code Studio projects. With 'project' given, lists that project's files instead.",
                JSONObject().put("project", prop("string", "Optional project name to list files of")), emptyList()),
            tool("code_read_file",
                "Reads a text file from a Jarvis Code Studio project.",
                JSONObject()
                    .put("project", prop("string", "Project name"))
                    .put("path", prop("string", "File path relative to the project root, e.g. 'index.html'")),
                listOf("project", "path")),
            tool("code_write_file",
                "Creates or overwrites a text file inside a Jarvis Code Studio project (creates the project if needed).",
                JSONObject()
                    .put("project", prop("string", "Project name"))
                    .put("path", prop("string", "File path relative to the project root"))
                    .put("content", prop("string", "Full file content")),
                listOf("project", "path", "content")),
            tool("code_open_studio",
                "Opens the Jarvis Code Studio AI coding IDE, optionally at a project and file.",
                JSONObject()
                    .put("project", prop("string", "Optional project name"))
                    .put("path", prop("string", "Optional file path relative to the project")),
                emptyList())
        )
    }

    /** Executes [name]; writes success/message (+ data) into [result]. Returns success. */
    fun execute(context: Context, name: String, args: JSONObject, result: JSONObject): Boolean {
        val ws = CodeStudioWorkspace(context)
        return try {
            when (name) {
                "code_list_projects" -> {
                    val project = args.optString("project").trim()
                    if (project.isEmpty()) {
                        val arr = JSONArray()
                        val projects = ws.listProjects()
                        projects.forEach { p -> arr.put(JSONObject().put("name", p.name).put("files", p.fileCount)) }
                        result.put("projects", arr)
                        ok(result, if (projects.isEmpty()) "No Code Studio projects yet." else "Projects: " + projects.joinToString { it.name })
                    } else {
                        val dir = projectDir(ws, project) ?: return fail(result, "Project '$project' not found.")
                        val files = ws.listProjectFiles(dir).map { ws.relativeTo(dir, it) }.sorted()
                        result.put("files", JSONArray(files.take(500)))
                        ok(result, "${files.size} file(s) in $project: " + files.take(40).joinToString())
                    }
                }
                "code_read_file" -> {
                    val dir = projectDir(ws, args.optString("project")) ?: return fail(result, "Project not found.")
                    val f = ws.resolveInProject(dir, args.optString("path"))
                    if (!f.isFile) return fail(result, "File not found: ${args.optString("path")}")
                    if (!CodeStudioWorkspace.isTextFile(f)) return fail(result, "Binary file cannot be read as text.")
                    val text = ws.readText(f)
                    result.put("content", text.take(MAX_READ_CHARS))
                    result.put("truncated", text.length > MAX_READ_CHARS)
                    ok(result, "Read ${f.name} (${text.length} chars).")
                }
                "code_write_file" -> {
                    val project = args.optString("project").trim()
                    if (project.isEmpty()) return fail(result, "Project name is required.")
                    val path = args.optString("path").trim()
                    if (path.isEmpty()) return fail(result, "Path is required.")
                    val dir = projectDir(ws, project) ?: ws.createProject(project, withStarter = false)
                    val f = ws.resolveInProject(dir, path)
                    if (f.isDirectory) return fail(result, "Path is a folder.")
                    val existed = f.exists()
                    ws.writeText(f, args.optString("content"))
                    result.put("absolute_path", f.absolutePath)
                    ok(result, "${if (existed) "Updated" else "Created"} ${ws.relativeTo(dir, f)} in ${dir.name}.")
                }
                "code_open_studio" -> {
                    val project = args.optString("project").trim().ifEmpty { null }
                    val path = args.optString("path").trim().ifEmpty { null }
                    if (project != null && projectDir(ws, project) == null) return fail(result, "Project '$project' not found.")
                    Handler(Looper.getMainLooper()).post {
                        runCatching { CodeStudioActivity.open(context, project, path) }
                    }
                    ok(result, "Opening Code Studio" + (project?.let { " at $it" } ?: "") + ".")
                }
                else -> fail(result, "Unknown Code Studio tool '$name'")
            }
        } catch (e: SecurityException) {
            fail(result, "Rejected: ${e.message}")
        } catch (e: Exception) {
            fail(result, "Code Studio error: ${e.message}")
        }
    }

    private fun projectDir(ws: CodeStudioWorkspace, name: String): java.io.File? {
        if (name.isBlank()) return null
        val dir = java.io.File(ws.root, CodeStudioPaths.sanitize(name))
        return if (dir.isDirectory && ws.isInsideRoot(dir)) dir else null
    }

    private fun ok(r: JSONObject, msg: String): Boolean { r.put("success", true); r.put("message", msg); return true }
    private fun fail(r: JSONObject, msg: String): Boolean { r.put("success", false); r.put("message", msg); return false }
}
