package com.github.codeplangui.execution

sealed class ExecutionResult {
    data class Success(
        val command: String,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long,
        val truncated: Boolean = false
    ) : ExecutionResult()

    data class Blocked(val command: String, val reason: String) : ExecutionResult()
    data class Denied(val command: String, val reason: String) : ExecutionResult()
    data class TimedOut(val command: String, val stdout: String, val timeoutSeconds: Int) : ExecutionResult()
    data class Failed(
        val command: String,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long,
        val truncated: Boolean = false
    ) : ExecutionResult()

    /** Serialize to a JSON string suitable for tool_result content. */
    fun toToolResultContent(): String = when (this) {
        is Success  -> """{"status":"ok","exit_code":$exitCode,"stdout":${escape(stdout.take(4000))},"stderr":${escape(stderr.take(2000))},"duration_ms":$durationMs${if (truncated) ""","truncated":true""" else ""}}"""
        is Failed   -> """{"status":"error","exit_code":$exitCode,"stdout":${escape(stdout.take(4000))},"stderr":${escape(stderr.take(2000))},"duration_ms":$durationMs${if (truncated) ""","truncated":true""" else ""}}"""
        is Blocked  -> """{"status":"blocked","reason":${escape(reason)}}"""
        is Denied   -> """{"status":"denied","reason":${escape(reason)}}"""
        is TimedOut -> """{"status":"timeout","timeout_seconds":$timeoutSeconds,"stdout":${escape(stdout.take(4000))}}"""
    }

    private fun escape(s: String): String =
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer<String>(), s
        )

    /** Convert to unified ToolResult for the new tool system. */
    fun toToolResult(): ToolResult = when (this) {
        is Success  -> ToolResult(ok = true, output = buildString {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append(stderr)
            }
        }.ifEmpty { "Command completed with exit code $exitCode" })
        is Failed   -> ToolResult(ok = false, output = stderr.ifEmpty { "Command failed with exit code $exitCode" })
        is Blocked  -> ToolResult(ok = false, output = reason)
        is Denied   -> ToolResult(ok = false, output = reason)
        is TimedOut -> ToolResult(ok = false, output = "Command timed out after ${timeoutSeconds}s")
    }
}
