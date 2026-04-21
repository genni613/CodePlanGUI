package com.github.codeplangui.execution

import kotlinx.serialization.Serializable

/**
 * Unified result type for all tools (built-in and MCP).
 * Every tool returns this, never throws.
 */
data class ToolResult(
    val ok: Boolean,
    val output: String,
    val awaitUser: Boolean = false,
    val backgroundTask: BackgroundTask? = null,
    val truncated: Boolean = false,
    val totalBytes: Int? = null,
    val outputPath: String? = null
)

@Serializable
data class BackgroundTask(
    val id: String,
    val command: String,
    val status: BackgroundTaskStatus
)

@Serializable
enum class BackgroundTaskStatus {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
}
