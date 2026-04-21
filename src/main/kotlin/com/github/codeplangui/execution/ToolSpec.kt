package com.github.codeplangui.execution

import kotlinx.serialization.json.JsonObject

/**
 * Tool registration info. Each registered tool has one ToolSpec.
 *
 * Dynamic capabilities (isConcurrencySafe, isReadOnly, isDestructive) accept
 * the parsed input and return a boolean — e.g. run_command decides based on
 * the concrete command, while read_file always returns true.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val requiredPermission: PermissionMode,
    val executor: ToolExecutor,
    val isConcurrencySafe: (input: JsonObject) -> Boolean = { false },
    val isReadOnly: (input: JsonObject) -> Boolean = { false },
    val isDestructive: (input: JsonObject) -> Boolean = { false }
)
