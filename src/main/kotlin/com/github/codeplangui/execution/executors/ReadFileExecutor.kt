package com.github.codeplangui.execution.executors

import com.github.codeplangui.execution.ToolContext
import com.github.codeplangui.execution.ToolExecutor
import com.github.codeplangui.execution.ToolResult
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Reads file content by line range using IntelliJ VFS.
 * Always returns READ_ONLY permission; always concurrency-safe.
 */
class ReadFileExecutor : ToolExecutor {

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val path = input["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "Missing required parameter: path")

        val lineNumber = input["line_number"]?.jsonPrimitive?.intOrNull ?: 1
        val limit = input["limit"]?.jsonPrimitive?.intOrNull ?: 500

        // Validate range
        if (lineNumber < 1) return ToolResult(ok = false, output = "line_number must be >= 1")
        if (limit < 1 || limit > 1000) return ToolResult(ok = false, output = "limit must be between 1 and 1000")

        val resolvedPath = resolveToolPath(path, context.cwd)
            ?: return ToolResult(ok = false, output = "Path resolves outside workspace: $path")

        return withContext(Dispatchers.IO) {
            val file = File(resolvedPath)
            if (!file.exists()) {
                return@withContext ToolResult(ok = false, output = "File not found: $path")
            }
            if (!file.isFile) {
                return@withContext ToolResult(ok = false, output = "Not a file: $path")
            }

            // Binary check
            val headBytes = file.inputStream().buffered().use { it.readNBytes(8192) }
            if (headBytes.any { it == 0.toByte() }) {
                return@withContext ToolResult(
                    ok = false,
                    output = "Binary file, cannot display: $path (${file.length()} bytes)"
                )
            }

            val allLines = file.readLines()
            val totalLines = allLines.size

            val startIdx = (lineNumber - 1).coerceIn(0, totalLines)
            val endIdx = (startIdx + limit).coerceAtMost(totalLines)
            val selectedLines = allLines.subList(startIdx, endIdx)
            val truncated = endIdx < totalLines

            val sb = StringBuilder()
            sb.appendLine("FILE: $path")
            sb.appendLine("LINES: ${startIdx + 1}-${endIdx}")
            sb.appendLine("TOTAL_LINES: $totalLines")
            sb.appendLine("TRUNCATED: ${if (truncated) "yes" else "no"}")
            sb.appendLine()

            val maxLineNumWidth = (endIdx).toString().length
            for ((i, line) in selectedLines.withIndex()) {
                val lineNum = (startIdx + i + 1).toString().padStart(maxLineNumWidth)
                sb.appendLine("$lineNum→$line")
            }

            ToolResult(ok = true, output = sb.toString())
        }
    }

    companion object {
        fun resolveToolPath(path: String, cwd: String): String? {
            val resolved = File(cwd, path).canonicalPath
            val canonicalCwd = File(cwd).canonicalPath
            return if (resolved.startsWith(canonicalCwd)) resolved else null
        }
    }
}
