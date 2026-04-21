package com.github.codeplangui.execution.executors

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
 * Lists directory contents. Max 200 entries.
 * Always READ_ONLY, always concurrency-safe.
 */
class ListFilesExecutor : ToolExecutor {

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val path = input["path"]?.jsonPrimitive?.contentOrNull ?: "."
        val resolvedPath = ReadFileExecutor.resolveToolPath(path, context.cwd)
            ?: return ToolResult(ok = false, output = "Path resolves outside workspace: $path")

        return withContext(Dispatchers.IO) {
            val dir = File(resolvedPath)
            if (!dir.exists()) {
                return@withContext ToolResult(ok = false, output = "Directory not found: $path")
            }
            if (!dir.isDirectory) {
                return@withContext ToolResult(ok = false, output = "Not a directory: $path")
            }

            val entries = dir.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                ?.take(200)
                ?: emptyList()

            if (entries.isEmpty()) {
                return@withContext ToolResult(ok = true, output = "(empty directory)")
            }

            val output = entries.joinToString("\n") { entry ->
                val kind = if (entry.isDirectory) "dir" else "file"
                "$kind ${entry.name}"
            }

            val suffix = if ((dir.listFiles()?.size ?: 0) > 200) {
                "\n\n(showing first 200 entries)"
            } else ""

            ToolResult(ok = true, output = output + suffix)
        }
    }
}
