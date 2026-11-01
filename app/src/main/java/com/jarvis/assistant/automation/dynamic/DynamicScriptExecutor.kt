package com.jarvis.assistant.automation.dynamic

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.jarvis.assistant.automation.macro.MacroEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Sandboxed Execution Engine for dynamic scripts authored by Jarvis at runtime.
 * Supports JavaScript (headless WebKit), Shell (/system/bin/sh), and Macro JSON.
 */
class DynamicScriptExecutor(private val context: Context) {

    companion object {
        private const val TAG = "DynamicScriptExecutor"
        private const val EXECUTION_TIMEOUT_MS = 10_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var headlessWebView: WebView? = null

    data class ExecutionResult(
        val isSuccess: Boolean,
        val outputJson: JSONObject,
        val errorMessage: String? = null
    )

    /**
     * Executes any dynamic tool according to its ScriptType.
     */
    suspend fun execute(tool: DynamicTool, arguments: JSONObject): ExecutionResult {
        return when (tool.scriptType) {
            DynamicTool.ScriptType.JAVASCRIPT -> executeJavaScript(tool, arguments)
            DynamicTool.ScriptType.SHELL -> executeShell(tool, arguments)
            DynamicTool.ScriptType.MACRO_JSON -> executeMacro(tool, arguments)
        }
    }

    // =========================================================================
    // JAVASCRIPT RUNTIME (Headless WebKit + Android Bridge)
    // =========================================================================

    private class NativeJsBridge(private val context: Context) {
        @JavascriptInterface
        fun httpGet(urlStr: String): String {
            return try {
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val response = stream.bufferedReader().use { it.readText() }
                conn.disconnect()
                JSONObject().apply {
                    put("status", conn.responseCode)
                    put("body", response)
                }.toString()
            } catch (e: Exception) {
                JSONObject().apply {
                    put("error", e.message ?: "Network error")
                }.toString()
            }
        }

        @JavascriptInterface
        fun getBatteryPercent(): Int {
            return try {
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            } catch (_: Exception) {
                -1
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun executeJavaScript(tool: DynamicTool, arguments: JSONObject): ExecutionResult = withContext(Dispatchers.Main) {
        val wv = headlessWebView ?: run {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                addJavascriptInterface(NativeJsBridge(context), "JarvisNative")
            }.also { headlessWebView = it }
        }

        val escapedArgs = arguments.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val wrappedScript = """
            (function() {
                try {
                    const args = JSON.parse("$escapedArgs");
                    ${tool.scriptCode}
                    if (typeof runTool === 'function') {
                        const res = runTool(args);
                        return JSON.stringify({ success: true, result: res });
                    } else {
                        return JSON.stringify({ success: true, message: "Script executed successfully" });
                    }
                } catch(e) {
                    return JSON.stringify({ success: false, error: e.toString() });
                }
            })();
        """.trimIndent()

        val rawOutput = withTimeoutOrNull(EXECUTION_TIMEOUT_MS) {
            suspendCancellableCoroutine<String> { continuation ->
                wv.evaluateJavascript(wrappedScript) { value ->
                    if (continuation.isActive) {
                        continuation.resume(value ?: "null")
                    }
                }
            }
        }

        if (rawOutput == null) {
            return@withContext ExecutionResult(
                isSuccess = false,
                outputJson = JSONObject().put("error", "Execution timed out after ${EXECUTION_TIMEOUT_MS}ms"),
                errorMessage = "Dynamic JavaScript execution timed out."
            )
        }

        // evaluateJavascript returns a JSON string, which may be escaped quotes
        try {
            val unquoted = if (rawOutput.startsWith("\"") && rawOutput.endsWith("\"")) {
                org.json.JSONTokener(rawOutput).nextValue().toString()
            } else {
                rawOutput
            }
            val parsed = JSONObject(unquoted)
            val success = parsed.optBoolean("success", true)
            val errMsg = if (!success) parsed.optString("error", "JS evaluation error") else null
            ExecutionResult(isSuccess = success, outputJson = parsed, errorMessage = errMsg)
        } catch (e: Exception) {
            ExecutionResult(
                isSuccess = true,
                outputJson = JSONObject().put("raw_output", rawOutput),
                errorMessage = null
            )
        }
    }

    // =========================================================================
    // SHELL RUNTIME (Sandboxed Toybox)
    // =========================================================================

    private suspend fun executeShell(tool: DynamicTool, arguments: JSONObject): ExecutionResult = withContext(Dispatchers.IO) {
        val workingDir = File(context.filesDir, "dynamic_tools")
        val scriptFile = File(workingDir, "${tool.name}.sh")
        if (!scriptFile.exists()) {
            scriptFile.writeText(tool.scriptCode)
        }

        try {
            val pb = ProcessBuilder("/system/bin/sh", scriptFile.absolutePath, arguments.toString())
            pb.directory(workingDir)
            val process = pb.start()

            val completed = withTimeoutOrNull(EXECUTION_TIMEOUT_MS) {
                process.waitFor()
            }

            if (completed == null) {
                process.destroy()
                return@withContext ExecutionResult(
                    isSuccess = false,
                    outputJson = JSONObject().put("error", "Shell script timed out"),
                    errorMessage = "Shell script timed out after ${EXECUTION_TIMEOUT_MS}ms"
                )
            }

            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
            val exitCode = process.exitValue()

            val success = exitCode == 0
            val outJson = JSONObject().apply {
                put("exit_code", exitCode)
                put("stdout", stdout.trim())
                if (stderr.isNotBlank()) put("stderr", stderr.trim())
            }

            ExecutionResult(
                isSuccess = success,
                outputJson = outJson,
                errorMessage = if (!success) "Exit code $exitCode: $stderr" else null
            )
        } catch (e: Exception) {
            ExecutionResult(
                isSuccess = false,
                outputJson = JSONObject().put("error", e.message),
                errorMessage = e.message
            )
        }
    }

    // =========================================================================
    // MACRO JSON RUNTIME
    // =========================================================================

    private suspend fun executeMacro(tool: DynamicTool, arguments: JSONObject): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val macroRepo = com.jarvis.assistant.automation.macro.MacroRepository(context)
            val macroEngine = MacroEngine(context, macroRepo)
            val gson = com.google.gson.Gson()
            val macro = gson.fromJson(tool.scriptCode, com.jarvis.assistant.automation.macro.Macro::class.java)
            val paramMap = mutableMapOf<String, String>()
            arguments.keys().forEach { key -> paramMap[key] = arguments.optString(key) }

            val result = macroEngine.executeMacro(macro, paramMap)
            when (result) {
                is com.jarvis.assistant.automation.macro.MacroExecutionResult.Success -> {
                    ExecutionResult(
                        isSuccess = true,
                        outputJson = JSONObject().apply {
                            put("success", true)
                            put("message", result.message)
                            if (result.output != null) put("output", result.output)
                        }
                    )
                }
                is com.jarvis.assistant.automation.macro.MacroExecutionResult.Failure -> {
                    ExecutionResult(
                        isSuccess = false,
                        outputJson = JSONObject().apply {
                            put("success", false)
                            put("error", result.error)
                            put("failed_step", result.failedStepIndex)
                        },
                        errorMessage = result.error
                    )
                }
            }
        } catch (e: Exception) {
            ExecutionResult(
                isSuccess = false,
                outputJson = JSONObject().put("error", e.message),
                errorMessage = "Invalid macro JSON: ${e.message}"
            )
        }
    }
}
