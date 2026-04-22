package com.github.codeplangui.execution

import com.github.codeplangui.BridgeHandler
import com.github.codeplangui.execution.executors.BashExecutor
import com.github.codeplangui.settings.PluginSettings
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * Unified tool dispatcher. Owns the complete dispatch pipeline:
 * lookup → parse → dynamic permission → deny_rules → allow_rules →
 * session mode → approval → execution → output truncation.
 */
class ToolCallDispatcher(
    private val registry: ToolRegistry,
    private val fileChangeReview: FileChangeReview,
    private val fileWriteLock: FileWriteLock,
    private val project: com.intellij.openapi.project.Project
) {
    private val logger = Logger.getInstance(ToolCallDispatcher::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Approval suspension
    private val pendingApprovals = ConcurrentHashMap<String, CancellableContinuation<Boolean>>()

    // Hooks
    private val hooks = mutableListOf<ToolHook>()

    // Rate limiting
    private var roundToolCallCount = 0
    private val consecutiveCalls = mutableMapOf<String, Int>()

    companion object {
        const val MAX_TOOL_OUTPUT_BYTES = 50 * 1024 // 50KB
        const val MAX_TOOL_CALLS_PER_ROUND = 20
        const val MAX_TOOL_CALLS_PER_RESPONSE = 10
        const val CONSECUTIVE_CALL_WARNING = 5
        const val APPROVAL_TIMEOUT_MS = 60_000L
    }

    fun addHook(hook: ToolHook) {
        hooks.add(hook)
    }

    /** Build the tools parameter for API requests. */
    fun buildToolsParam(): List<com.github.codeplangui.api.ToolDefinition> {
        return registry.buildOpenAiTools()
    }

    /** Reset per-session state (new chat). */
    fun resetSession() {
        fileChangeReview.resetSessionTrust()
        roundToolCallCount = 0
        consecutiveCalls.clear()
        cancelAllPendingApprovals()
    }

    /** Reset per-round state. */
    fun resetRound() {
        roundToolCallCount = 0
    }

    /** Called by Bridge when user responds to an approval request. */
    fun onApprovalResponse(requestId: String, decision: String) {
        val cont = pendingApprovals.remove(requestId) ?: return
        val approved = decision == "allow"
        if (approved) {
            cont.resume(true)
        } else {
            cont.resume(false)
        }
    }

    /**
     * Dispatch a single tool call through the full pipeline.
     */
    suspend fun dispatch(
        toolName: String,
        argsJson: String,
        msgId: String,
        bridgeHandler: BridgeHandler?
    ): ToolResult {
        return try {
            dispatchInternal(toolName, argsJson, msgId, bridgeHandler)
        } catch (e: Exception) {
            ToolResult(ok = false, output = "Tool execution error: ${e.message}")
        }
    }

    /**
     * Dispatch multiple tool calls with concurrent scheduling.
     * Results are returned in original order.
     */
    suspend fun dispatchAll(
        calls: List<PendingToolCall>,
        msgId: String,
        bridgeHandler: BridgeHandler?
    ): List<Pair<PendingToolCall, ToolResult>> {
        // Rate limit check
        if (calls.size > MAX_TOOL_CALLS_PER_RESPONSE) {
            return calls.map { call ->
                call to ToolResult(
                    ok = false,
                    output = "Too many tool calls in a single response (${calls.size} > $MAX_TOOL_CALLS_PER_RESPONSE)"
                )
            }
        }

        // Partition into batches
        val batches = partitionToolCalls(calls)

        // Execute batches sequentially
        val results = arrayOfNulls<Pair<PendingToolCall, ToolResult>>(calls.size)

        for (batch in batches) {
            if (batch.isConcurrencySafe && batch.entries.size > 1) {
                // Concurrent batch
                val hasBashError = java.util.concurrent.atomic.AtomicBoolean(false)
                val batchResults = coroutineScope {
                    batch.entries.map { (index, call) ->
                        async {
                            if (hasBashError.get() && isBashCommand(call.name)) {
                                index to (call to ToolResult(
                                    ok = false,
                                    output = "Skipped: previous bash command in batch failed"
                                ))
                            } else {
                                val result = dispatch(call.name, call.arguments, msgId, bridgeHandler)
                                if (!result.ok && isBashCommand(call.name)) {
                                    hasBashError.set(true)
                                }
                                index to (call to result)
                            }
                        }
                    }.awaitAll()
                }
                for ((index, result) in batchResults) {
                    results[index] = result
                }
            } else {
                // Serial batch
                for ((index, call) in batch.entries) {
                    results[index] = call to dispatch(call.name, call.arguments, msgId, bridgeHandler)
                }
            }
        }

        return results.map { it!! }
    }

    private suspend fun dispatchInternal(
        toolName: String,
        argsJson: String,
        msgId: String,
        bridgeHandler: BridgeHandler?
    ): ToolResult {
        // Rate limit check
        roundToolCallCount++
        if (roundToolCallCount > MAX_TOOL_CALLS_PER_ROUND) {
            return ToolResult(ok = false, output = "Round tool call limit exceeded ($roundToolCallCount > $MAX_TOOL_CALLS_PER_ROUND)")
        }

        // Consecutive call warning
        val count = consecutiveCalls.getOrDefault(toolName, 0) + 1
        consecutiveCalls[toolName] = count

        // 1. Build context
        val settings = PluginSettings.getInstance().getState()
        val project = (registry as? ToolRegistry)?.let {
            // Get project from registry — we need it from context
            null // Will be resolved from ToolContext construction in dispatch
        }

        // 2. Parse arguments
        val input: JsonObject = try {
            Json.parseToJsonElement(argsJson).jsonObject
        } catch (_: Exception) {
            return ToolResult(ok = false, output = "Invalid arguments: not valid JSON")
        }

        // 3. Find tool
        val spec = registry.find(toolName)
            ?: return ToolResult(ok = false, output = "Unknown tool: $toolName")

        // 4. Build summary and emit tool_step_start
        val stepRequestId = UUID.randomUUID().toString()
        val summary = buildTargetSummary(toolName, input)
        bridgeHandler?.notifyToolStepStart(msgId, stepRequestId, toolName, summary)

        // 5. Dynamic permission resolution
        val requiredPermission = resolvePermission(spec, input)

        // 6. Authorization
        val authDecision = authorize(toolName, input, requiredPermission, settings)
        when (authDecision) {
            is AuthDecision.Deny -> {
                bridgeHandler?.notifyToolStepEnd(msgId, stepRequestId, false, authDecision.reason, 0L)
                return ToolResult(ok = false, output = authDecision.reason)
            }
            is AuthDecision.Ask -> {
                val requestId = UUID.randomUUID().toString()
                val toolInput = if (toolName == "run_command" || toolName == "run_powershell") {
                    input["command"]?.jsonPrimitive?.contentOrNull ?: argsJson
                } else {
                    argsJson.take(200)
                }
                val description = input["description"]?.jsonPrimitive?.contentOrNull ?: ""

                bridgeHandler?.notifyApprovalRequest(
                    requestId = requestId,
                    command = toolInput,
                    description = description
                )

                val approved = awaitApproval(requestId)
                if (!approved) {
                    bridgeHandler?.notifyToolStepEnd(msgId, stepRequestId, false, "User denied permission for $toolName", 0L)
                    return ToolResult(ok = false, output = "User denied permission for $toolName")
                }
            }
            is AuthDecision.Allow -> { /* proceed */ }
        }

        // 7. Pre-Hooks
        var intercepted: ToolResult? = null
        for (hook in hooks) {
            try {
                val result = hook.beforeExecute(toolName, input)
                if (result != null) {
                    intercepted = result
                    break // Short-circuit
                }
            } catch (e: Exception) {
                logger.warn("Pre-Hook threw exception for $toolName", e)
            }
        }

        // 8. Execute (if not intercepted) with timing
        val startTime = System.currentTimeMillis()
        val finalResult = intercepted ?: runWithFileLock(spec, input, toolName)
        val durationMs = System.currentTimeMillis() - startTime

        // 9. Output truncation
        val truncatedResult = truncateOutput(finalResult, msgId)

        // 9.5 Notify frontend of trusted file changes (§8.7.4 file_change_auto)
        if (truncatedResult.ok && fileChangeReview.sessionFileWriteTrusted &&
            (toolName == "edit_file" || toolName == "write_file")) {
            val path = input["path"]?.jsonPrimitive?.contentOrNull
            if (path != null) {
                val stats = extractDiffStatsFromOutput(truncatedResult.output)
                bridgeHandler?.notifyFileChangeAuto(path, stats.first, stats.second)
            }
        }

        // 10. Emit tool_step_end
        val diffStats = extractDiffStats(toolName, input, truncatedResult)
        bridgeHandler?.notifyToolStepEnd(
            msgId, stepRequestId, truncatedResult.ok, truncatedResult.output, durationMs, diffStats
        )

        // 11. Post-Hooks
        for (hook in hooks) {
            try {
                hook.afterExecute(toolName, input, truncatedResult)
            } catch (e: Exception) {
                logger.warn("Post-Hook threw exception for $toolName", e)
            }
        }

        return truncatedResult
    }

    private fun buildTargetSummary(toolName: String, input: JsonObject): String {
        return when (toolName) {
            "read_file" -> input["path"]?.jsonPrimitive?.contentOrNull ?: toolName
            "list_files" -> input["path"]?.jsonPrimitive?.contentOrNull ?: "."
            "grep_files" -> "\"${input["pattern"]?.jsonPrimitive?.contentOrNull ?: ""}\""
            "edit_file" -> input["path"]?.jsonPrimitive?.contentOrNull ?: toolName
            "write_file" -> input["path"]?.jsonPrimitive?.contentOrNull ?: toolName
            "run_command", "run_powershell" -> {
                val cmd = input["command"]?.jsonPrimitive?.contentOrNull ?: toolName
                if (cmd.length > 60) cmd.take(57) + "..." else cmd
            }
            else -> toolName
        }
    }

    private fun extractDiffStats(toolName: String, input: JsonObject, result: ToolResult): String? {
        if (toolName != "edit_file" && toolName != "write_file") return null
        val stats = extractDiffStatsFromOutput(result.output)
        return "+${stats.first}/-${stats.second}"
    }

    private fun extractDiffStatsFromOutput(output: String): Pair<Int, Int> {
        // Try to parse "+N lines" or "-N lines" from result output
        val plusMatch = Regex("""\+(\d+) lines""").find(output)
        val minusMatch = Regex("""-(\d+) lines""").find(output)
        val added = plusMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val removed = minusMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return added to removed
    }

    private fun resolvePermission(spec: ToolSpec, input: JsonObject): PermissionMode {
        // Bash commands use dynamic classification
        if (spec.name == "run_command" || spec.name == "run_powershell") {
            val command = input["command"]?.jsonPrimitive?.contentOrNull ?: return PermissionMode.DANGER_FULL_ACCESS
            return BashExecutor().classifyPermission(command)
        }
        return spec.requiredPermission
    }

    private fun authorize(
        toolName: String,
        input: JsonObject,
        requiredPermission: PermissionMode,
        settings: com.github.codeplangui.settings.SettingsState
    ): AuthDecision {
        val sessionMode = PermissionMode.fromString(settings.permissionMode)

        // deny_rules check for bash commands
        if (toolName == "run_command" || toolName == "run_powershell") {
            val command = input["command"]?.jsonPrimitive?.contentOrNull ?: return AuthDecision.Deny("Missing command")
            val bashExecutor = BashExecutor()

            // Check deny rules via executor (already done in execute, but check here for pre-execution denial)
            val denied = checkDenyRulesEarly(command)
            if (denied != null) return AuthDecision.Deny(denied)

            // Whitelist check
            if (CommandExecutionService.isWhitelisted(command, settings.commandWhitelist)) {
                if (sessionMode >= requiredPermission) return AuthDecision.Allow
            }
        }

        // Path traversal check for file tools
        val path = input["path"]?.jsonPrimitive?.contentOrNull
        if (path != null && (path.contains("../") || path.contains("..\\"))) {
            return AuthDecision.Deny("Path traversal detected")
        }

        // Session mode check
        if (sessionMode >= requiredPermission) {
            return AuthDecision.Allow
        }

        // Trusted file write
        if ((toolName == "edit_file" || toolName == "write_file") && fileChangeReview.sessionFileWriteTrusted) {
            return AuthDecision.Allow
        }

        // Fallback: ask
        return AuthDecision.Ask
    }

    private fun checkDenyRulesEarly(command: String): String? {
        val cmd = command.lowercase()
        // Path traversal (case-insensitive, handle URL encoding)
        if (cmd.contains("../") || cmd.contains("..\\") ||
            cmd.contains("..%2f") || cmd.contains("..%5c")) return "Path traversal detected"
        // Dangerous delete
        if (Regex("""rm\s+(-\w*\s*)*(-r|--recursive).*\s+(/|~)""", RegexOption.IGNORE_CASE).containsMatchIn(cmd))
            return "Dangerous delete command detected"
        // Network exfiltration
        if (Regex("""(\|\s*(curl|wget)\s)|(>\s*/dev/tcp/)""", RegexOption.IGNORE_CASE).containsMatchIn(cmd))
            return "Potential network exfiltration detected"
        // Fork bomb
        if (Regex(""":\(\)\{.*:\|:&\}|fork\s*bomb""", RegexOption.IGNORE_CASE).containsMatchIn(cmd))
            return "Fork bomb pattern detected"
        // Privilege escalation
        if (Regex("""sudo\s+|chmod\s+[0-7]*77|chown\s+""", RegexOption.IGNORE_CASE).containsMatchIn(cmd))
            return "Privilege escalation detected"
        return null
    }

    private suspend fun runWithFileLock(spec: ToolSpec, input: JsonObject, toolName: String): ToolResult {
        val settings = PluginSettings.getInstance().getState()
        val project = resolveProject()
        val cwd = project?.basePath ?: return ToolResult(ok = false, output = "Project path unavailable")
        val context = ToolContext(project = project, cwd = cwd, settings = settings)

        val needsLock = !spec.isConcurrencySafe(input)
        return if (needsLock) {
            val path = input["path"]?.jsonPrimitive?.contentOrNull ?: toolName
            fileWriteLock.withFileLock(path) {
                spec.executor.execute(input, context)
            }
        } else {
            spec.executor.execute(input, context)
        }
    }

    private suspend fun awaitApproval(requestId: String): Boolean {
        return try {
            withTimeout(APPROVAL_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    pendingApprovals[requestId] = cont
                    cont.invokeOnCancellation {
                        pendingApprovals.remove(requestId)
                    }
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            pendingApprovals.remove(requestId)
            false
        }
    }

    private fun cancelAllPendingApprovals() {
        pendingApprovals.forEach { (_, cont) ->
            try { cont.cancel() } catch (_: Exception) {}
        }
        pendingApprovals.clear()
    }

    private fun truncateOutput(result: ToolResult, msgId: String): ToolResult {
        if (result.output.toByteArray().size <= MAX_TOOL_OUTPUT_BYTES) return result

        val totalBytes = result.output.toByteArray().size
        val truncatedOutput = String(
            result.output.toByteArray(),
            0,
            min(MAX_TOOL_OUTPUT_BYTES, result.output.toByteArray().size)
        )

        // Write full output to temp file
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "codeplan-tool-output")
        tmpDir.mkdirs()
        val tmpFile = File(tmpDir, "tool-output-$msgId-${System.currentTimeMillis()}.log")
        tmpFile.writeText(result.output)

        return result.copy(
            output = truncatedOutput +
                "\n\n... [OUTPUT TRUNCATED: $totalBytes bytes total, showing first 50KB]",
            truncated = true,
            totalBytes = totalBytes,
            outputPath = tmpFile.absolutePath
        )
    }

    private fun partitionToolCalls(calls: List<PendingToolCall>): List<Batch> {
        val result = mutableListOf<Batch>()
        for ((index, call) in calls.withIndex()) {
            val spec = registry.find(call.name)
            val input = try {
                Json.parseToJsonElement(call.arguments).jsonObject
            } catch (_: Exception) {
                null
            }
            val safe = spec?.let { s -> input?.let { i -> s.isConcurrencySafe(i) } } ?: false

            if (safe && result.isNotEmpty() && result.last().isConcurrencySafe) {
                val last = result.last()
                result[result.lastIndex] = last.copy(
                    entries = last.entries + IndexedValue(index, call)
                )
            } else {
                result.add(Batch(safe, listOf(IndexedValue(index, call))))
            }
        }
        return result
    }

    private fun isBashCommand(name: String): Boolean =
        name == "run_command" || name == "run_powershell"

    private fun resolveProject(): com.intellij.openapi.project.Project = project
}

// Helper types

data class PendingToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val index: Int
)

data class Batch(
    val isConcurrencySafe: Boolean,
    val entries: List<IndexedValue<PendingToolCall>>
)

sealed class AuthDecision {
    data object Allow : AuthDecision()
    data class Deny(val reason: String) : AuthDecision()
    data object Ask : AuthDecision()
}
