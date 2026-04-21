package com.github.codeplangui.execution.executors

import com.github.codeplangui.execution.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wraps the existing CommandExecutionService for run_command / run_powershell.
 * Adds dynamic permission classification and deny_rules checking.
 */
class BashExecutor : ToolExecutor {

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val command = input["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "Missing required parameter: command")

        val description = input["description"]?.jsonPrimitive?.contentOrNull ?: ""

        // deny_rules check
        val denied = checkDenyRules(command)
        if (denied != null) return ToolResult(ok = false, output = denied)

        // Workspace path check
        val basePath = context.cwd
        if (CommandExecutionService.hasPathsOutsideWorkspace(command, basePath)) {
            return ToolResult(ok = false, output = "Command accesses paths outside the project")
        }

        return withContext(Dispatchers.IO) {
            val service = CommandExecutionService.getInstance(context.project)
            val result = service.executeAsync(command, context.settings.commandTimeoutSeconds)
            result.toToolResult()
        }
    }

    /** Dynamic permission classification based on base command name. */
    fun classifyPermission(command: String): PermissionMode {
        val base = CommandExecutionService.extractBaseCommand(command).lowercase()
        return when {
            base in READ_ONLY_COMMANDS -> PermissionMode.READ_ONLY
            base in DEVELOPMENT_COMMANDS -> PermissionMode.WORKSPACE_WRITE
            else -> PermissionMode.DANGER_FULL_ACCESS
        }
    }

    /** Whether this command is safe to run concurrently with other tools. */
    fun isConcurrencySafe(command: String): Boolean {
        return classifyPermission(command) == PermissionMode.READ_ONLY
    }

    fun isReadOnly(command: String): Boolean =
        classifyPermission(command) == PermissionMode.READ_ONLY

    fun isDestructive(command: String): Boolean {
        val cmd = command.lowercase()
        return DESTRUCTIVE_PATTERNS.any { it.containsMatchIn(cmd) }
    }

    private fun checkDenyRules(command: String): String? {
        // Path traversal
        if (command.contains("../") || command.contains("..\\")) {
            return "Path traversal detected in command"
        }
        // Dangerous delete
        if (DANGEROUS_DELETE_PATTERN.containsMatchIn(command)) {
            return "Dangerous delete command detected"
        }
        // Network exfiltration (basic pattern matching)
        if (NETWORK_EXFIL_PATTERN.containsMatchIn(command)) {
            return "Potential network exfiltration detected"
        }
        // Fork bomb
        if (FORK_BOMB_PATTERN.containsMatchIn(command)) {
            return "Fork bomb pattern detected"
        }
        // Privilege escalation
        if (PRIVILEGE_ESCALATION_PATTERN.containsMatchIn(command)) {
            return "Privilege escalation detected"
        }
        return null
    }

    companion object {
        private val READ_ONLY_COMMANDS = setOf(
            "pwd", "ls", "find", "rg", "grep", "cat", "head", "tail",
            "wc", "echo", "df", "du", "uname", "whoami", "type", "which",
            "get-childitem", "get-content", "select-string", "get-location"
        )

        private val DEVELOPMENT_COMMANDS = setOf(
            "git", "npm", "node", "python3", "python", "pytest", "bash", "sh",
            "bun", "cargo", "gradle", "mvn", "yarn", "pnpm", "go", "rustc",
            "javac", "java", "dotnet", "make", "cmake"
        )

        private val DESTRUCTIVE_PATTERNS = listOf(
            Regex("""rm\s+(-\w*\s*)*(-r|--recursive).*\s+/""", RegexOption.IGNORE_CASE),
            Regex("""rm\s+(-\w*\s*)*(-r|--recursive).*\s+~""", RegexOption.IGNORE_CASE)
        )

        private val DANGEROUS_DELETE_PATTERN =
            Regex("""rm\s+(-\w*\s*)*(-r|--recursive).*\s+(/|~)""", RegexOption.IGNORE_CASE)

        private val NETWORK_EXFIL_PATTERN = Regex(
            """(\|\s*(curl|wget)\s)|(>\s*/dev/tcp/)""",
            RegexOption.IGNORE_CASE
        )

        private val FORK_BOMB_PATTERN = Regex(
            """:\(\)\{.*:\|:&\}|fork\s*bomb""",
            RegexOption.IGNORE_CASE
        )

        private val PRIVILEGE_ESCALATION_PATTERN = Regex(
            """sudo\s+|chmod\s+[0-7]*77|chown\s+""",
            RegexOption.IGNORE_CASE
        )
    }
}
