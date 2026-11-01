package com.jarvis.assistant.models.download

import java.util.Locale

enum class RepairAction {
    RESUME_MISSING_BYTES,
    DELETE_PARTIAL_AND_RESTART,
    DELETE_AND_FULL_REDOWNLOAD,
    SUGGEST_COMPATIBLE_MODEL,
    REQUIRE_USER_ACTION,
    RETRY_WITH_BACKOFF,
    NONE
}

enum class FailureReason {
    DOWNLOAD_INTERRUPTED,
    TRUNCATED_TRANSFER,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    INVALID_CONTAINER,
    UNSUPPORTED_FORMAT,
    GATED_MODEL,
    NETWORK_ERROR,
    INSUFFICIENT_STORAGE,
    UNKNOWN
}

data class ValidationFailure(
    val reason: FailureReason,
    val expectedBytes: Long? = null,
    val actualBytes: Long? = null,
    val expectedSha256: String? = null,
    val actualSha256: String? = null,
    val url: String? = null,
    val detail: String = "",
    val suggestedAction: String = "",
    val repairAction: RepairAction = RepairAction.NONE
) {
    val byteDifference: Long?
        get() = if (expectedBytes != null && actualBytes != null) expectedBytes - actualBytes else null

    fun formatReport(modelName: String): String {
        val sb = StringBuilder()
        sb.appendLine("Validation Failed: $modelName")
        if (expectedBytes != null) sb.appendLine("Expected:   ${expectedBytes.formatBytes()}")
        if (actualBytes != null) sb.appendLine("Downloaded: ${actualBytes.formatBytes()}")
        byteDifference?.let {
            val missing = it >= 0
            sb.appendLine("Difference: ${String.format(Locale.US, "%,d", kotlin.math.abs(it))} bytes " + if (missing) "missing" else "extra")
        }
        if (expectedSha256 != null) sb.appendLine("Expected SHA256: ${expectedSha256.take(16)}...")
        if (actualSha256 != null) sb.appendLine("Actual SHA256:   ${actualSha256.take(16)}...")
        sb.appendLine("Reason: $detail")
        if (suggestedAction.isNotBlank()) sb.append("Suggested Action: $suggestedAction")
        return sb.toString().trimEnd()
    }
}

data class PreflightInfo(
    val url: String,
    val httpStatus: Int = 200,
    val contentLength: Long? = null,
    val supportsRanges: Boolean = false,
    val eTag: String? = null,
    val lfsSha256: String? = null,
    val chunked: Boolean = false
) {
    val authoritativeSize: Long? get() = contentLength?.takeIf { it > 0 }
}

fun Long.formatBytes(): String = String.format(Locale.US, "%,d bytes", this)
