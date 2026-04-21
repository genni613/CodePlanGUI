package com.github.codeplangui.execution

import kotlinx.serialization.json.JsonObject

/**
 * Hook extension point for cross-cutting concerns around tool execution.
 * Registered in ToolCallDispatcher via addHook().
 *
 * Pre-Hooks use short-circuit semantics: the first non-null return stops the chain.
 * Post-Hooks always all execute, even on failure or interception.
 */
interface ToolHook {
    /**
     * Called before tool execution.
     * Return null → continue execution.
     * Return ToolResult → intercept, skip executor, return this result.
     */
    suspend fun beforeExecute(toolName: String, input: JsonObject): ToolResult? = null

    /**
     * Called after tool execution (success, failure, or interception).
     * For logging, metrics, result enrichment.
     */
    suspend fun afterExecute(toolName: String, input: JsonObject, result: ToolResult) {}
}
