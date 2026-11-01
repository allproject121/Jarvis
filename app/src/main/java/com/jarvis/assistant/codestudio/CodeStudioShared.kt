package com.jarvis.assistant.codestudio

import android.content.Context
import java.io.File

/**
 * Shared contract for Jarvis Code Studio (AI coding IDE, generators, live preview, terminal).
 *
 * Ownership: the IDE (projects, file tree, editor, AI edit, Auto-Fix) and the preview/terminal
 * panels are built by different parts of the code base; both sides use only what is declared here.
 */
object CodeStudioPaths {
    /** Root folder holding one sub-folder per project. Kept in app-specific storage (no extra permission). */
    fun projectsRoot(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "CodeStudio").apply { mkdirs() }

    fun projectDir(context: Context, projectName: String): File =
        File(projectsRoot(context), sanitize(projectName)).apply { mkdirs() }

    fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "project" }.take(60)
}

/** Port used by the local live-preview HTTP server (bound to 127.0.0.1 only). */
const val CODE_STUDIO_PREVIEW_PORT = 8080
