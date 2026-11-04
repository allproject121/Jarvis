package com.jarvis.assistant.automation.termux

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class TermuxBridge(private val context: Context) {
    companion object {
        private const val TAG = "TermuxBridge"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

        private const val EXTRA_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_RUN_COMMAND_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"

        const val DEFAULT_WORKDIR = "/data/data/com.termux/files/home"
        const val BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
    }

    fun isTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Executes a bash command in Termux.
     * @param command Bash command string.
     * @param workDir Working directory (defaults to Termux home).
     * @param inBackground True to execute without opening Termux UI.
     */
    fun runCommand(
        command: String,
        workDir: String = DEFAULT_WORKDIR,
        inBackground: Boolean = true
    ): Boolean {
        if (!isTermuxInstalled()) {
            Log.e(TAG, "Termux is not installed on this device.")
            return false
        }

        return try {
            val intent = Intent().apply {
                component = ComponentName(TERMUX_PACKAGE, TERMUX_SERVICE)
                action = ACTION_RUN_COMMAND
                putExtra(EXTRA_RUN_COMMAND_PATH, BASH_PATH)
                putExtra(EXTRA_RUN_COMMAND_ARGUMENTS, arrayOf("-c", command))
                putExtra(EXTRA_RUN_COMMAND_WORKDIR, workDir)
                putExtra(EXTRA_RUN_COMMAND_BACKGROUND, inBackground)
                putExtra(EXTRA_RUN_COMMAND_SESSION_ACTION, if (inBackground) "0" else "1")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "Dispatched command to Termux: $command")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run command in Termux: ${e.message}", e)
            false
        }
    }

    /**
     * Executes a command inside the PRoot Ubuntu distro in Termux.
     */
    fun runInUbuntu(command: String, inBackground: Boolean = true): Boolean {
        val escapedCommand = command.replace("\"", "\\\"")
        val fullCommand = "proot-distro login ubuntu -- bash -c \"$escapedCommand\""
        return runCommand(fullCommand, DEFAULT_WORKDIR, inBackground)
    }

    /**
     * Installs a package using pkg or apt.
     */
    fun installPackage(packageName: String): Boolean {
        val command = "pkg install -y $packageName"
        return runCommand(command, inBackground = false)
    }

    /**
     * Writes content to a file in Termux home or specified path.
     */
    fun writeFile(filePath: String, content: String): Boolean {
        val escapedContent = content.replace("'", "'\\''")
        val command = "mkdir -p \$(dirname '$filePath') && cat << 'EOF' > '$filePath'\n$content\nEOF"
        return runCommand(command, DEFAULT_WORKDIR, inBackground = true)
    }

    /**
     * Executes a command on the device's native Linux shell (/system/bin/sh) directly.
     * Works on ALL Android devices without needing Termux or root.
     */
    fun executeLocalCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (output.isNotBlank()) output.trim()
            else if (error.isNotBlank()) error.trim()
            else "Command completed (exit code ${process.exitValue()})"
        } catch (e: Exception) {
            "Execution error: ${e.message}"
        }
    }
}
