package com.github.codeplangui.execution

import com.github.codeplangui.api.FunctionDefinition
import com.github.codeplangui.api.ToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

private val WHITESPACE = Regex("\\s+")

private fun Char.isSpaceOrTab() = this == ' ' || this == '\t'

// Trade-off: a fully quoted absolute path such as `cat "/etc/passwd"` no
// longer trips the workspace check. User approval remains the real gate —
// this helper reduces false positives on legitimate commands (e.g. Java
// source written via heredoc), not security.
private fun stripLiteralsAndHeredocs(command: String): String {
    val n = command.length
    val out = StringBuilder(n)
    var i = 0
    while (i < n) {
        val c = command[i]
        when {
            c == '\'' || c == '"' -> {
                val end = command.indexOf(c, i + 1)
                out.append(' ')
                i = if (end < 0) n else end + 1
            }
            c == '<' && i + 1 < n && command[i + 1] == '<' && (i + 2 >= n || command[i + 2] != '<') -> {
                val end = consumeHeredoc(command, i)
                if (end > i) { out.append(' '); i = end } else { out.append(c); i++ }
            }
            else -> { out.append(c); i++ }
        }
    }
    return out.toString()
}

// Returns the index just past the heredoc closing delimiter, or `start` if
// the construct is not a well-formed heredoc. The closing delimiter is
// matched even with leading whitespace (pragmatic relaxation of bash's
// tab-only `<<-` rule).
private fun consumeHeredoc(command: String, start: Int): Int {
    val n = command.length
    var j = start + 2
    if (j < n && command[j] == '-') j++
    while (j < n && command[j].isSpaceOrTab()) j++

    val quoteChar = if (j < n && (command[j] == '\'' || command[j] == '"')) command[j] else null
    if (quoteChar != null) j++
    val delimStart = j
    val delimEnd = if (quoteChar != null) {
        val close = command.indexOf(quoteChar, j)
        if (close < 0) return start
        close
    } else {
        var k = j
        while (k < n && (command[k].isLetterOrDigit() || command[k] == '_')) k++
        k
    }
    val delimLen = delimEnd - delimStart
    if (delimLen == 0) return start
    j = delimEnd + if (quoteChar != null) 1 else 0

    var pos = j
    while (pos < n) {
        val nl = command.indexOf('\n', pos)
        if (nl < 0) return n
        var ls = nl + 1
        while (ls < n && command[ls].isSpaceOrTab()) ls++
        if (ls + delimLen <= n &&
            command.regionMatches(ls, command, delimStart, delimLen)) {
            val after = ls + delimLen
            if (after == n || command[after] == '\n' || command[after].isSpaceOrTab()) {
                return if (after < n && command[after] == '\n') after + 1 else after
            }
        }
        pos = nl + 1
    }
    return n
}

sealed class ShellPlatform {

    abstract fun buildProcess(command: String, workDir: File): ProcessBuilder
    abstract fun extractBaseCommand(command: String): String
    abstract fun hasPathsOutsideWorkspace(command: String, basePath: String): Boolean
    abstract fun toolName(): String
    abstract fun shellHint(): String
    abstract fun defaultWhitelist(): List<String>
    abstract fun toolDefinition(): ToolDefinition

    object Unix : ShellPlatform() {

        private val SAFE_DEV_PATHS = setOf(
            "/dev/null", "/dev/stdin", "/dev/stdout", "/dev/stderr",
            "/dev/zero", "/dev/urandom", "/dev/random"
        )

        override fun buildProcess(command: String, workDir: File): ProcessBuilder =
            ProcessBuilder("sh", "-c", command).directory(workDir)

        override fun extractBaseCommand(command: String): String {
            val base = command.trimStart().split(" ", "|", ";", ">", "<", "&").first().trim()
            return base.substringAfterLast('/')
        }

        override fun hasPathsOutsideWorkspace(command: String, basePath: String): Boolean {
            val home = System.getProperty("user.home") ?: ""
            val normalizedBase = basePath.trimEnd('/')
            val stripped = stripLiteralsAndHeredocs(command)
            return stripped.split(WHITESPACE).any { token ->
                if (token.startsWith('-')) return@any false
                val expanded = if (token.startsWith("~/")) home + token.drop(1) else token
                if (!expanded.startsWith('/')) return@any false
                if (expanded in SAFE_DEV_PATHS) return@any false
                val trimmedExpanded = expanded.trimEnd('/')
                trimmedExpanded != normalizedBase && !trimmedExpanded.startsWith("$normalizedBase/")
            }
        }

        override fun toolName() = "run_command"

        override fun shellHint() = ""

        override fun toolDefinition(): ToolDefinition = ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = "run_command",
                description = "Execute a shell command in the project root directory. " +
                    "Only use when the user asks you to run something or when you need to " +
                    "inspect state to answer accurately.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "The bash/shell command to execute")
                        })
                        put("description", buildJsonObject {
                            put("type", "string")
                            put("description", "One-line explanation of why you are running this command")
                        })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("command"))
                        add(JsonPrimitive("description"))
                    })
                }
            )
        )

        override fun defaultWhitelist(): List<String> = mutableListOf(
            "cargo", "gradle", "mvn", "npm", "yarn", "pnpm",
            "git", "ls", "cat", "grep", "find", "echo", "pwd"
        )
    }

    object Windows : ShellPlatform() {

        override fun buildProcess(command: String, workDir: File): ProcessBuilder =
            ProcessBuilder("powershell", "-NoProfile", "-Command", command).directory(workDir)

        override fun extractBaseCommand(command: String): String {
            val trimmed = command.trimStart()
            // Detect absolute paths: Windows drive (C:\...), UNC (\\...), or Unix-style (/c/...)
            val isAbsolutePath = trimmed.matches(Regex("^(?:[A-Za-z]:\\\\|\\\\\\\\|/[a-zA-Z]/).*"))
            if (isAbsolutePath) {
                // Normalize to backslashes, find last path separator, extract exe name
                val normalized = trimmed.replace('/', '\\')
                val lastSep = normalized.lastIndexOfAny(charArrayOf('\\'))
                val nameWithArgs = if (lastSep >= 0) normalized.substring(lastSep + 1) else normalized
                return nameWithArgs.split(" ").first().removeSuffix(".exe")
            }
            // Plain cmdlet or bare command: take first token before shell operators
            val base = trimmed.split(" ", "|", ";", ">", "<", "&").first().trim()
            return base.substringAfterLast('\\').substringAfterLast('/').removeSuffix(".exe")
        }

        override fun hasPathsOutsideWorkspace(command: String, basePath: String): Boolean {
            val normalizedBase = basePath.replace('/', '\\').trimEnd('\\')
            val stripped = stripLiteralsAndHeredocs(command)
            return stripped.split(WHITESPACE).any { token ->
                if (token.startsWith('-')) return@any false
                val normalized = token.replace('/', '\\')
                val isAbsolute = normalized.matches(Regex("[A-Za-z]:\\\\.*")) || normalized.startsWith("\\\\")
                if (!isAbsolute) return@any false
                normalized != normalizedBase && !normalized.startsWith("$normalizedBase\\")
            }
        }

        override fun toolName() = "run_powershell"

        override fun shellHint() =
            "\n当前运行在 Windows 环境，请使用 PowerShell 语法调用 run_powershell 工具。"

        override fun toolDefinition(): ToolDefinition = ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = "run_powershell",
                description = "Execute a PowerShell command in the project root directory. " +
                    "Only use when the user asks you to run something or when you need to " +
                    "inspect state to answer accurately. Use PowerShell syntax.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "The PowerShell command to execute")
                        })
                        put("description", buildJsonObject {
                            put("type", "string")
                            put("description", "One-line explanation of why you are running this command")
                        })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("command"))
                        add(JsonPrimitive("description"))
                    })
                }
            )
        )

        override fun defaultWhitelist(): List<String> = mutableListOf(
            "cargo", "gradle", "mvn", "npm", "yarn", "pnpm",
            "git",
            "Get-ChildItem", "Get-Content", "Select-String",
            "Get-Location", "Write-Output", "Where-Object"
        )
    }

    companion object {
        fun current(): ShellPlatform =
            if (System.getProperty("os.name").lowercase().contains("win")) Windows else Unix
    }
}
