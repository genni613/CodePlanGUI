package com.github.codeplangui.execution.hooks

import com.github.codeplangui.execution.ToolHook
import com.github.codeplangui.execution.ToolResult
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.JsonObject

/**
 * Default Pre/Post Hook that logs tool call execution to IDE logs.
 */
class ToolExecutionLogger : ToolHook {

    private val logger = Logger.getInstance(ToolExecutionLogger::class.java)

    override suspend fun beforeExecute(toolName: String, input: JsonObject): ToolResult? {
        logger.info("[ToolCall] Executing: $toolName | input size: ${input.toString().length}")
        return null // Continue execution
    }

    override suspend fun afterExecute(toolName: String, input: JsonObject, result: ToolResult) {
        val status = if (result.ok) "OK" else "FAILED"
        val outputPreview = result.output.take(200).replace("\n", " ")
        logger.info("[ToolCall] Completed: $toolName | $status | output: $outputPreview")
    }
}
