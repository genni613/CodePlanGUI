package com.github.codeplangui.execution

import kotlinx.serialization.json.JsonObject

/**
 * Interface that every tool executor implements.
 * Implementations must NOT throw — return ToolResult(ok=false) on error.
 * All IO operations must run on Dispatchers.IO.
 */
interface ToolExecutor {
    suspend fun execute(input: JsonObject, context: ToolContext): ToolResult
}
