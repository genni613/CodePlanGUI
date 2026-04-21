package com.github.codeplangui.execution

import com.github.codeplangui.execution.executors.*
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject

/**
 * ToolSpec definitions for all 6 built-in tools.
 * The bashExecutor is shared for dynamic permission classification.
 */
class ToolSpecs {

    private val bashExecutor = BashExecutor()
    private val fileChangeReview = FileChangeReview()

    val readFileExecutor = ReadFileExecutor()
    val listFilesExecutor = ListFilesExecutor()
    val grepFilesExecutor = GrepFilesExecutor()
    val editFileExecutor = EditFileExecutor(fileChangeReview)
    val writeFileExecutor = WriteFileExecutor(fileChangeReview)

    fun allSpecs(): List<ToolSpec> = listOf(
        runCommandSpec(),
        readFileSpec(),
        listFilesSpec(),
        grepFilesSpec(),
        editFileSpec(),
        writeFileSpec()
    )

    fun runCommandSpec(): ToolSpec {
        val toolName = com.github.codeplangui.execution.ShellPlatform.current().toolName()
        return ToolSpec(
            name = toolName,
            description = "Execute a shell command in the project root directory. " +
                "Only use when the user asks you to run something or when you need to " +
                "inspect state to answer accurately.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "The shell command to execute")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "One-line explanation of why you are running this command")
                    })
                })
                put("required", buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("command"))
                    add(kotlinx.serialization.json.JsonPrimitive("description"))
                })
            },
            requiredPermission = PermissionMode.READ_ONLY, // Dynamic — overridden in dispatch
            executor = bashExecutor,
            isConcurrencySafe = { input ->
                input["command"]?.let { cmd ->
                    bashExecutor.isConcurrencySafe(cmd.jsonPrimitive.content)
                } ?: false
            },
            isReadOnly = { input ->
                input["command"]?.let { cmd ->
                    bashExecutor.isReadOnly(cmd.jsonPrimitive.content)
                } ?: false
            },
            isDestructive = { input ->
                input["command"]?.let { cmd ->
                    bashExecutor.isDestructive(cmd.jsonPrimitive.content)
                } ?: false
            }
        )
    }

    fun readFileSpec() = ToolSpec(
        name = "read_file",
        description = "Read file contents. Supports line-based pagination. " +
            "Returns content with line numbers. Use line_number and limit for pagination.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Path relative to project root")
                })
                put("line_number", buildJsonObject {
                    put("type", "integer")
                    put("description", "Starting line number (1-based). Default: 1")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Number of lines to read. Max 1000. Default: 500")
                })
            })
            put("required", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("path"))
            })
        },
        requiredPermission = PermissionMode.READ_ONLY,
        executor = readFileExecutor,
        isConcurrencySafe = { true },
        isReadOnly = { true },
        isDestructive = { false }
    )

    fun listFilesSpec() = ToolSpec(
        name = "list_files",
        description = "List directory contents. Returns files and subdirectories. " +
            "Use this to explore the project structure.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Directory path relative to project root. Default: '.'")
                })
            })
        },
        requiredPermission = PermissionMode.READ_ONLY,
        executor = listFilesExecutor,
        isConcurrencySafe = { true },
        isReadOnly = { true },
        isDestructive = { false }
    )

    fun grepFilesSpec() = ToolSpec(
        name = "grep_files",
        description = "Search for text patterns in project files. " +
            "Returns matching lines with file paths and line numbers.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Search pattern")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Directory to search in. Default: '.'")
                })
            })
            put("required", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("pattern"))
            })
        },
        requiredPermission = PermissionMode.READ_ONLY,
        executor = grepFilesExecutor,
        isConcurrencySafe = { true },
        isReadOnly = { true },
        isDestructive = { false }
    )

    fun editFileSpec() = ToolSpec(
        name = "edit_file",
        description = "Replace text in a file. Use for precise, targeted edits. " +
            "If multiple matches exist, provide line_number to disambiguate. " +
            "The change will be reviewed by the user before applying.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "File path relative to project root")
                })
                put("search", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to search for")
                })
                put("replace", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to replace with")
                })
                put("replaceAll", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Replace all occurrences. Default: false")
                })
                put("line_number", buildJsonObject {
                    put("type", "integer")
                    put("description", "Target line number (1-based) to disambiguate multiple matches")
                })
            })
            put("required", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("path"))
                add(kotlinx.serialization.json.JsonPrimitive("search"))
                add(kotlinx.serialization.json.JsonPrimitive("replace"))
            })
        },
        requiredPermission = PermissionMode.WORKSPACE_WRITE,
        executor = editFileExecutor,
        isConcurrencySafe = { false },
        isReadOnly = { false },
        isDestructive = { false }
    )

    fun writeFileSpec() = ToolSpec(
        name = "write_file",
        description = "Create or overwrite a file with complete content. " +
            "Use for creating new files or when changes are too large for edit_file. " +
            "The change will be reviewed by the user before applying.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "File path relative to project root")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Complete file content")
                })
            })
            put("required", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("path"))
                add(kotlinx.serialization.json.JsonPrimitive("content"))
            })
        },
        requiredPermission = PermissionMode.WORKSPACE_WRITE,
        executor = writeFileExecutor,
        isConcurrencySafe = { false },
        isReadOnly = { false },
        isDestructive = { false }
    )
}
