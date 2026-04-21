package com.github.codeplangui.execution.executors

import com.github.codeplangui.execution.CommandExecutionService
import com.github.codeplangui.execution.ExecutionResult
import com.github.codeplangui.execution.ToolContext
import com.github.codeplangui.execution.ToolExecutor
import com.github.codeplangui.execution.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Text search using IntelliJ FindInProjectUtil (preferred) or external rg/grep (fallback).
 * Always READ_ONLY, always concurrency-safe.
 *
 * First version: uses external rg/grep directly. IntelliJ FindInProjectUtil
 * integration requires complex setup and will be added in a future iteration.
 */
class GrepFilesExecutor : ToolExecutor {

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "Missing required parameter: pattern")

        val path = input["path"]?.jsonPrimitive?.contentOrNull ?: "."
        val resolvedPath = ReadFileExecutor.resolveToolPath(path, context.cwd)
            ?: return ToolResult(ok = false, output = "Path resolves outside workspace: $path")

        return searchWithExternalTool(pattern, resolvedPath, context)
    }

    private suspend fun searchWithExternalTool(
        pattern: String,
        directoryPath: String,
        context: ToolContext
    ): ToolResult {
        return withContext(Dispatchers.IO) {
            val escapedPattern = pattern.replace("'", "'\\''")
            val excludeDirs = ".git,.idea,build,.gradle,node_modules,.intellijPlatform,.claude"
            // Try rg first, then grep
            val command = if (isRgAvailable()) {
                "rg -n --no-heading --max-count 50 --glob '!{$excludeDirs}' -- '$escapedPattern' '$directoryPath'"
            } else {
                "grep -rn --max-count=50 --exclude-dir={$excludeDirs} -- '$escapedPattern' '$directoryPath'"
            }

            val service = CommandExecutionService.getInstance(context.project)
            val result = service.executeAsync(command, context.settings.commandTimeoutSeconds)

            when (result) {
                is ExecutionResult.Success -> {
                    val output = result.stdout.trim()
                    if (output.isEmpty()) {
                        ToolResult(ok = true, output = "(no matches)")
                    } else {
                        val lines = output.lines().take(50)
                        ToolResult(ok = true, output = lines.joinToString("\n"))
                    }
                }
                is ExecutionResult.Failed -> {
                    // grep exits with 1 when no matches
                    if (result.exitCode == 1) {
                        ToolResult(ok = true, output = "(no matches)")
                    } else {
                        ToolResult(ok = false, output = result.stderr.ifEmpty { "Search failed" })
                    }
                }
                else -> result.toToolResult()
            }
        }
    }

    companion object {
        private var rgChecked: Boolean? = null
        private var rgAvailable: Boolean = false

        private fun isRgAvailable(): Boolean {
            if (rgChecked != null) return rgAvailable
            rgAvailable = try {
                val process = ProcessBuilder("rg", "--version").start()
                process.waitFor() == 0
            } catch (_: Exception) {
                false
            }
            rgChecked = true
            return rgAvailable
        }
    }
}
