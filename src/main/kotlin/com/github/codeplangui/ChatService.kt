package com.github.codeplangui

import com.github.codeplangui.api.OkHttpSseClient
import com.github.codeplangui.api.ToolCallAccumulator
import com.github.codeplangui.api.ToolCallDelta
import com.github.codeplangui.api.ToolDefinition
import com.github.codeplangui.api.FunctionDefinition
import com.github.codeplangui.execution.CommandExecutionService
import com.github.codeplangui.execution.ExecutionResult
import com.github.codeplangui.model.ChatSession
import com.github.codeplangui.model.Message
import com.github.codeplangui.model.MessageRole
import com.github.codeplangui.model.ToolCallRecord
import com.github.codeplangui.settings.ApiKeyStore
import com.github.codeplangui.settings.PluginSettings
import com.github.codeplangui.settings.PluginSettingsConfigurable
import com.github.codeplangui.settings.SettingsState
import com.github.codeplangui.storage.SessionStore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.options.ShowSettingsUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.sse.EventSource
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class ChatService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(ChatService::class.java)

    private val client = OkHttpSseClient()
    private var session: ChatSession = ChatSession()
    private var activeStream: EventSource? = null
    private var activeMessageId: String? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingPrompt: PendingPrompt? = null

    var bridgeHandler: BridgeHandler? = null
        private set

    private val sessionStore = SessionStore()

    private var contextFileCallback: ((String) -> Unit)? = null
    private var onFrontendReadyCallback: (() -> Unit)? = null
    private var isFrontendReady: Boolean = false

    // Tool call state machine
    private val toolCallAccumulator = ToolCallAccumulator()

    // Approval gate: suspended coroutines wait on these futures
    private val pendingApprovals = ConcurrentHashMap<String, CompletableFuture<Boolean>>()

    // Tracks which msgIds have had notifyStart sent to the frontend
    // When tools are enabled, notifyStart is deferred until the final response round
    // so ExecutionCards appear before the assistant bubble
    private val bridgeNotifiedStart = mutableSetOf<String>()

    fun attachBridge(handler: BridgeHandler) {
        bridgeHandler = handler
        isFrontendReady = false
    }

    fun onFrontendReady() {
        isFrontendReady = true
        publishStatus()
        restoreSessionIfNeeded()
        onFrontendReadyCallback?.invoke()
        pendingPrompt?.let { prompt ->
            pendingPrompt = null
            sendMessage(prompt.text, prompt.includeContext, prompt.contextLabel)
        }
    }

    fun setContextFileCallback(callback: (String) -> Unit) {
        contextFileCallback = callback
    }

    fun setOnFrontendReadyCallback(callback: () -> Unit) {
        onFrontendReadyCallback = callback
    }

    fun sendMessage(text: String, includeContext: Boolean, contextLabelOverride: String? = null) {
        activeStream?.cancel()
        activeStream = null
        activeMessageId = null

        val settings = PluginSettings.getInstance()
        val provider = settings.getActiveProvider()
        if (provider == null) {
            publishStatus()
            bridgeHandler?.notifyError("请先在 Settings > Tools > CodePlanGUI 中配置 API Provider")
            return
        }

        val apiKey = ApiKeyStore.load(provider.id) ?: ""
        if (apiKey.isBlank()) {
            publishStatus()
            bridgeHandler?.notifyError("API Key 未设置或未保存，请在 Settings 中重新配置并点 Apply/OK")
            return
        }

        val settingsState = settings.getState()
        val commandExecutionEnabled = settingsState.commandExecutionEnabled

        val contextSnapshot = if (includeContext && settingsState.contextInjectionEnabled) {
            capturePromptContextSnapshot()
        } else {
            null
        }
        contextFileCallback?.invoke(resolveUiContextLabel(contextLabelOverride, contextSnapshot))
        val systemContent = formatSystemContent(
            base = buildBaseSystemPrompt(commandExecutionEnabled),
            snapshot = contextSnapshot,
            memoryText = settingsState.memoryText
        )
        session.setSystemMessage(systemContent)
        val userMsg = Message(
            role = MessageRole.USER,
            content = text,
            id = UUID.randomUUID().toString(),
            seq = session.nextSeq()
        )
        session.add(userMsg)
        persistSession()

        // Reset state machine before each new user-initiated request
        resetToolCallState()

        val msgId = UUID.randomUUID().toString()
        activeMessageId = msgId
        publishStatus()

        val request = client.buildRequest(
            config = provider,
            apiKey = apiKey,
            messages = session.getApiMessages(),
            temperature = settingsState.chatTemperature,
            maxTokens = settingsState.chatMaxTokens,
            stream = true,
            tools = if (commandExecutionEnabled) listOf(runCommandToolDefinition()) else null
        )

        // When tools are enabled, defer notifyStart so ExecutionCards appear before the assistant bubble.
        // The bubble is created only on the final response round (no tool calls).
        if (!commandExecutionEnabled) {
            bridgeHandler?.notifyStart(msgId)
            bridgeNotifiedStart.add(msgId)
        }
        startStreamingRound(msgId, request, toolsEnabled = commandExecutionEnabled)
    }

    fun cancelStream() {
        val wasStreaming = activeMessageId != null
        activeStream?.cancel()
        activeStream = null
        val msgId = activeMessageId
        activeMessageId = null
        if (wasStreaming && msgId != null) {
            bridgeNotifiedStart.remove(msgId)
            publishStatus()
            bridgeHandler?.notifyEnd(msgId)
        }
    }

    fun newChat() {
        activeStream?.cancel()
        activeStream = null
        activeMessageId = null
        resetToolCallState()
        bridgeNotifiedStart.clear()
        session = ChatSession()
        pendingApprovals.values.forEach { it.complete(false) }
        pendingApprovals.clear()
        sessionStore.clearSession()
        contextFileCallback?.invoke("")
        publishStatus()
    }

    fun askAboutSelection(selection: String, contextLabel: String) {
        val prompt = buildSelectionPrompt(selection)
        if (bridgeHandler?.isReady == true && isFrontendReady) {
            sendMessage(prompt, false, contextLabel)
        } else {
            pendingPrompt = PendingPrompt(prompt, false, contextLabel)
        }
    }

    fun openSettings() {
        openSettingsOnEdt(
            openDialog = {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginSettingsConfigurable::class.java)
            },
            enqueue = { action ->
                ApplicationManager.getApplication().invokeLater {
                    action()
                }
            }
        )
    }

    fun onApprovalResponse(requestId: String, decision: String) {
        logger.info(
            "[CodePlanGUI Approval] received frontend decision " +
                "requestId=$requestId decision=$decision hasPending=${pendingApprovals.containsKey(requestId)}"
        )
        pendingApprovals[requestId]?.complete(decision == "allow")
    }

    fun refreshBridgeStatus() {
        publishStatus()
    }

    private fun runCommandToolDefinition(): ToolDefinition = ToolDefinition(
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
                        put("description", "The shell command to execute")
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

    private fun handleToolCallChunk(delta: ToolCallDelta) {
        toolCallAccumulator.append(delta)
    }

    private suspend fun handleToolCallComplete(msgId: String, responseBuffer: StringBuilder) {
        val preparedToolCalls = prepareToolCallsForExecution(msgId) ?: return
        val state = PluginSettings.getInstance().getState()
        val completedToolCalls = mutableListOf<CompletedToolCall>()

        logger.info(
            "[CodePlanGUI Approval] executing tool-call batch " +
                "msgId=$msgId toolCallCount=${preparedToolCalls.size}"
        )

        for (toolCall in preparedToolCalls) {
            completedToolCalls += executeToolCallWithApproval(msgId, toolCall, state)
        }

        continueWithToolResults(msgId, responseBuffer, completedToolCalls)
    }

    private fun continueWithToolResults(
        msgId: String,
        responseBuffer: StringBuilder,
        completedToolCalls: List<CompletedToolCall>
    ) {
        logger.info(
            "[CodePlanGUI Approval] continuing conversation with tool results " +
                "msgId=$msgId toolCallCount=${completedToolCalls.size} " +
                "results=${completedToolCalls.joinToString { "index=${it.toolCall.index}:${it.result.summarizeForLog()}" }}"
        )
        // Assistant message must carry tool_calls for the OpenAI API to accept the follow-up tool result
        session.add(Message(
            role = MessageRole.ASSISTANT,
            content = responseBuffer.toString(),
            id = UUID.randomUUID().toString(),
            seq = session.nextSeq(),
            toolCalls = completedToolCalls.map {
                ToolCallRecord(
                    id = it.toolCall.id,
                    functionName = it.toolCall.functionName,
                    arguments = it.toolCall.argumentsJson
                )
            }
        ))
        completedToolCalls.forEach {
            session.add(Message(
                role = MessageRole.TOOL,
                content = it.result.toToolResultContent(),
                toolCallId = it.toolCall.id,
                id = UUID.randomUUID().toString(),
                seq = session.nextSeq()
            ))
        }
        persistSession()

        // Reset state machine
        resetToolCallState()
        responseBuffer.clear()

        // Do NOT call notifyStart here — the next model round might produce more tool calls.
        // The assistant bubble is only created in startStreamingRound's onEnd (final round, no tool calls).
        sendMessageInternal(msgId)
    }

    private fun sendMessageInternal(msgId: String) {
        val pluginSettings = PluginSettings.getInstance()
        val provider = pluginSettings.getActiveProvider() ?: return
        val apiKey = ApiKeyStore.load(provider.id) ?: return
        val commandExecutionEnabled = pluginSettings.getState().commandExecutionEnabled
        logger.info("[CodePlanGUI Approval] starting follow-up model round msgId=$msgId")

        val request = client.buildRequest(
            config = provider,
            apiKey = apiKey,
            messages = session.getApiMessages(),
            temperature = pluginSettings.getState().chatTemperature,
            maxTokens = pluginSettings.getState().chatMaxTokens,
            stream = true,
            tools = if (commandExecutionEnabled) listOf(runCommandToolDefinition()) else null
        )

        startStreamingRound(msgId, request, toolsEnabled = commandExecutionEnabled)
    }

    private fun capturePromptContextSnapshot(): PromptContextSnapshot? {
        return try {
            ReadAction.compute<PromptContextSnapshot?, RuntimeException> {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@compute null
                val file = editor.virtualFile ?: return@compute null
                buildPromptContextSnapshot(
                    fileName = file.name,
                    extension = file.extension,
                    selectedText = editor.selectionModel.selectedText,
                    documentText = editor.document.text,
                    maxLines = PluginSettings.getInstance().getState().contextMaxLines
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildSelectionPrompt(selection: String): String = """
请分析下面这段选中的代码，并说明它的作用、关键逻辑和潜在风险：

```
$selection
```
    """.trimIndent()

    private fun publishStatus() {
        val provider = PluginSettings.getInstance().getActiveProvider()
        val hasApiKey = provider?.let { !ApiKeyStore.load(it.id).isNullOrBlank() } == true
        val status = BridgeStatusPayload(
            providerName = provider?.name.orEmpty(),
            model = provider?.model.orEmpty(),
            connectionState = deriveConnectionState(
                hasProvider = provider != null,
                hasApiKey = hasApiKey,
                isStreaming = activeMessageId != null
            )
        )
        bridgeHandler?.notifyStatus(status)
    }

    /** Terminates an in-progress stream with an error and resets all state, preventing a permanent stuck spinner. */
    private fun abortStream(msgId: String, errorMessage: String) {
        if (activeMessageId != msgId) return
        logger.warn("[CodePlanGUI Approval] aborting stream msgId=$msgId error=${errorMessage.summarizeForLog(240)}")
        activeStream?.cancel()
        activeStream = null
        activeMessageId = null
        bridgeNotifiedStart.remove(msgId)
        resetToolCallState()
        publishStatus()
        bridgeHandler?.notifyError(errorMessage)
    }

    private fun startStreamingRound(msgId: String, request: okhttp3.Request, toolsEnabled: Boolean) {
        val responseBuffer = StringBuilder()
        scope.launch {
            val stream = client.streamChat(
                request = request,
                onToken = { token ->
                    if (activeMessageId == msgId) {
                        responseBuffer.append(token)
                        // Only push tokens to the frontend if the assistant bubble has been started.
                        // When tools are enabled, the bubble is deferred until the final response round
                        // so ExecutionCards appear first.
                        if (msgId in bridgeNotifiedStart) {
                            bridgeHandler?.notifyToken(token)
                        }
                    }
                },
                onEnd = {
                    if (activeMessageId == msgId) {
                        // If the bubble hasn't been started yet (no tool calls in this round),
                        // start it now and flush the buffered content.
                        if (msgId !in bridgeNotifiedStart) {
                            bridgeHandler?.notifyStart(msgId)
                            bridgeNotifiedStart.add(msgId)
                            if (responseBuffer.isNotEmpty()) {
                                bridgeHandler?.notifyToken(responseBuffer.toString())
                            }
                        }
                        logger.info("[CodePlanGUI Approval] model round completed msgId=$msgId")
                        session.add(Message(
                            role = MessageRole.ASSISTANT,
                            content = responseBuffer.toString(),
                            id = UUID.randomUUID().toString(),
                            seq = session.nextSeq()
                        ))
                        persistSession()
                        activeStream = null
                        activeMessageId = null
                        bridgeNotifiedStart.remove(msgId)
                        publishStatus()
                        bridgeHandler?.notifyEnd(msgId)
                    }
                },
                onError = { message ->
                    if (activeMessageId == msgId) {
                        logger.warn("[CodePlanGUI Approval] model round failed msgId=$msgId error=$message")
                        activeStream = null
                        activeMessageId = null
                        bridgeNotifiedStart.remove(msgId)
                        publishStatus()
                        bridgeHandler?.notifyError(message)
                    }
                },
                onToolCallChunk = { delta ->
                    if (toolsEnabled && activeMessageId == msgId) {
                        handleToolCallChunk(delta)
                    }
                },
                onFinishReason = { reason ->
                    if (toolsEnabled && reason == "tool_calls" && activeMessageId == msgId) {
                        // Tool calls detected — do NOT start the bubble.
                        // The first round's buffered text (usually empty) is discarded.
                        val capturedBuffer = responseBuffer
                        scope.launch { handleToolCallComplete(msgId, capturedBuffer) }
                    }
                }
            )
            if (activeMessageId == msgId) {
                activeStream = stream
            } else {
                stream.cancel()
            }
        }
    }

    private fun resetToolCallState() {
        toolCallAccumulator.clear()
    }

    private fun persistSession() {
        sessionStore.saveSession(
            session.threadId,
            session.getMessages().filter { it.role != MessageRole.SYSTEM }
        )
    }

    private fun prepareToolCallsForExecution(msgId: String): List<PreparedToolCall>? {
        val accumulatedToolCalls = toolCallAccumulator.snapshot()
        if (accumulatedToolCalls.isEmpty()) {
            abortStream(msgId, "AI sent a tool_calls finish_reason but no tool call deltas were captured")
            return null
        }

        return accumulatedToolCalls.map { accumulated ->
            val toolCallId = accumulated.id ?: run {
                abortStream(
                    msgId,
                    "AI sent a tool_calls finish_reason but tool call index ${accumulated.index} had no id"
                )
                return null
            }
            val argsJson = accumulated.argumentsJson
            val argsObj = try {
                kotlinx.serialization.json.Json.parseToJsonElement(argsJson).jsonObject
            } catch (_: Exception) {
                abortStream(msgId, "AI returned malformed tool call arguments for index ${accumulated.index}: '$argsJson'")
                return null
            }
            val command = argsObj["command"]?.jsonPrimitive?.contentOrNull ?: run {
                abortStream(msgId, "AI tool call index ${accumulated.index} is missing required 'command' field")
                return null
            }
            val description = argsObj["description"]?.jsonPrimitive?.contentOrNull ?: ""

            PreparedToolCall(
                index = accumulated.index,
                id = toolCallId,
                functionName = accumulated.functionName ?: "run_command",
                argumentsJson = argsJson,
                command = command,
                description = description
            )
        }
    }

    private suspend fun executeToolCallWithApproval(
        msgId: String,
        toolCall: PreparedToolCall,
        state: SettingsState
    ): CompletedToolCall {
        val requestId = UUID.randomUUID().toString()
        logger.info(
            "[CodePlanGUI Approval] prepared approval request " +
                "requestId=$requestId msgId=$msgId toolCallId=${toolCall.id} index=${toolCall.index} " +
                "function=${toolCall.functionName} command=${toolCall.command.summarizeForLog()} " +
                "description=${toolCall.description.summarizeForLog()}"
        )

        bridgeHandler?.notifyApprovalRequest(requestId, toolCall.command, toolCall.description)

        if (!CommandExecutionService.isWhitelisted(toolCall.command, state.commandWhitelist)) {
            logger.info(
                "[CodePlanGUI Approval] blocked by whitelist " +
                    "requestId=$requestId index=${toolCall.index} command=${toolCall.command.summarizeForLog()}"
            )
            val result = ExecutionResult.Blocked(
                toolCall.command,
                "'${CommandExecutionService.extractBaseCommand(toolCall.command)}' is not in the allowed command list"
            )
            bridgeHandler?.notifyExecutionStatus(requestId, "blocked", result.toToolResultContent())
            return CompletedToolCall(toolCall, result)
        }

        val basePath = project.basePath ?: ""
        if (CommandExecutionService.hasPathsOutsideWorkspace(toolCall.command, basePath)) {
            logger.info(
                "[CodePlanGUI Approval] blocked by workspace path check " +
                    "requestId=$requestId index=${toolCall.index} command=${toolCall.command.summarizeForLog()} " +
                    "basePath=${basePath.summarizeForLog()}"
            )
            val result = ExecutionResult.Blocked(toolCall.command, "Command accesses paths outside the project")
            bridgeHandler?.notifyExecutionStatus(requestId, "blocked", result.toToolResultContent())
            return CompletedToolCall(toolCall, result)
        }

        bridgeHandler?.notifyLog(requestId, "Security check passed", "info")

        val future = CompletableFuture<Boolean>()
        pendingApprovals[requestId] = future
        bridgeHandler?.notifyExecutionStatus(requestId, "waiting", "{}")
        bridgeHandler?.notifyLog(requestId, "Waiting for approval...", "info")
        logger.info("[CodePlanGUI Approval] waiting for user decision requestId=$requestId index=${toolCall.index}")

        val approved = try {
            withContext(Dispatchers.IO) { future.get(60, TimeUnit.SECONDS) }
        } catch (e: Exception) {
            logger.info(
                "[CodePlanGUI Approval] decision wait failed " +
                    "requestId=$requestId index=${toolCall.index} error=${e.javaClass.simpleName}:${e.message ?: ""}"
            )
            false
        } finally {
            pendingApprovals.remove(requestId)
        }
        logger.info(
            "[CodePlanGUI Approval] resolved user decision " +
                "requestId=$requestId index=${toolCall.index} approved=$approved"
        )

        if (!approved) {
            val result = ExecutionResult.Denied(toolCall.command, "User rejected the command")
            bridgeHandler?.notifyExecutionStatus(requestId, "denied", result.toToolResultContent())
            return CompletedToolCall(toolCall, result)
        }

        bridgeHandler?.notifyExecutionStatus(requestId, "running", "{}")
        bridgeHandler?.notifyLog(requestId, "Executing: ${toolCall.command}", "info")
        logger.info(
            "[CodePlanGUI Approval] starting command execution " +
                "requestId=$requestId index=${toolCall.index} timeoutSeconds=${state.commandTimeoutSeconds} " +
                "command=${toolCall.command.summarizeForLog()}"
        )
        val execService = CommandExecutionService.getInstance(project)
        val result = execService.executeAsyncWithStream(
            toolCall.command,
            state.commandTimeoutSeconds
        ) { line, isError ->
            bridgeHandler?.notifyLog(requestId, line, if (isError) "stderr" else "stdout")
        }
        val bridgeStatus = if (result is ExecutionResult.TimedOut) "timeout" else "done"
        bridgeHandler?.notifyExecutionStatus(requestId, bridgeStatus, result.toToolResultContent())
        val durationMs = when (result) {
            is ExecutionResult.Success -> result.durationMs
            is ExecutionResult.Failed -> result.durationMs
            else -> 0L
        }
        val exitCode = when (result) {
            is ExecutionResult.Success -> result.exitCode
            is ExecutionResult.Failed -> result.exitCode
            else -> -1
        }
        bridgeHandler?.notifyLog(requestId, "Finished: exit $exitCode, ${durationMs}ms", "info")
        logger.info(
            "[CodePlanGUI Approval] command execution finished " +
                "requestId=$requestId index=${toolCall.index} bridgeStatus=$bridgeStatus result=${result.summarizeForLog()}"
        )
        return CompletedToolCall(toolCall, result)
    }

    private fun String.summarizeForLog(maxLength: Int = 160): String {
        val singleLine = replace('\n', ' ').replace('\r', ' ').trim()
        return if (singleLine.length <= maxLength) singleLine else singleLine.take(maxLength) + "..."
    }

    private fun ExecutionResult.summarizeForLog(): String = when (this) {
        is ExecutionResult.Success ->
            "success exit=$exitCode durationMs=$durationMs stdoutLen=${stdout.length} stderrLen=${stderr.length} truncated=$truncated"
        is ExecutionResult.Failed ->
            "failed exit=$exitCode durationMs=$durationMs stdoutLen=${stdout.length} stderrLen=${stderr.length} truncated=$truncated"
        is ExecutionResult.Blocked ->
            "blocked reason=${reason.summarizeForLog()}"
        is ExecutionResult.Denied ->
            "denied reason=${reason.summarizeForLog()}"
        is ExecutionResult.TimedOut ->
            "timeout timeoutSeconds=$timeoutSeconds stdoutLen=${stdout.length}"
    }

    override fun dispose() {
        activeStream?.cancel()
        pendingApprovals.values.forEach { it.complete(false) }
        pendingApprovals.clear()
        bridgeNotifiedStart.clear()
        scope.cancel()
    }

    private val bridgeJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun restoreSessionIfNeeded() {
        if (session.getMessages().any { it.role != MessageRole.SYSTEM }) {
            return
        }
        val data = sessionStore.loadSession() ?: return
        session = ChatSession(data.threadId)
        data.messages.forEach { session.add(it) }
        val restoredMessages = data.messages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .filterNot { it.role == MessageRole.ASSISTANT && it.content.isBlank() }
            .map {
                RestoredMessagePayload(
                    id = it.id,
                    role = it.role.name.lowercase(),
                    content = it.content
                )
            }
        bridgeHandler?.notifyRestoreMessages(
            bridgeJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(RestoredMessagePayload.serializer()),
                restoredMessages
            )
        )
    }

    companion object {
        fun getInstance(project: Project): ChatService = project.getService(ChatService::class.java)
    }

    private data class PendingPrompt(
        val text: String,
        val includeContext: Boolean,
        val contextLabel: String? = null
    )

    private data class PreparedToolCall(
        val index: Int,
        val id: String,
        val functionName: String,
        val argumentsJson: String,
        val command: String,
        val description: String
    )

    private data class CompletedToolCall(
        val toolCall: PreparedToolCall,
        val result: ExecutionResult
    )

    @kotlinx.serialization.Serializable
    private data class RestoredMessagePayload(
        val id: String,
        val role: String,
        val content: String
    )
}

internal data class PromptContextSnapshot(
    val fileName: String,
    val extension: String,
    val content: String,
    val contextLabel: String
)

internal fun buildPromptContextSnapshot(
    fileName: String,
    extension: String?,
    selectedText: String?,
    documentText: String,
    maxLines: Int
): PromptContextSnapshot {
    val preferredContent = selectedText?.takeIf { it.isNotBlank() } ?: documentText
    val content = preferredContent
        .lines()
        .take(maxLines)
        .joinToString("\n")
        .take(12000)
    val contextLabel = if (selectedText.isNullOrBlank()) {
        "$fileName · 当前文件"
    } else {
        buildSelectionContextLabel(fileName, selectedText.lines().size)
    }

    return PromptContextSnapshot(
        fileName = fileName,
        extension = extension ?: "txt",
        content = content,
        contextLabel = contextLabel
    )
}

internal fun buildSelectionContextLabel(fileName: String?, lineCount: Int): String {
    val lineText = "选中 ${lineCount.coerceAtLeast(1)} 行"
    return if (fileName.isNullOrBlank()) lineText else "$fileName · $lineText"
}

internal fun buildBaseSystemPrompt(commandExecutionEnabled: Boolean = false): String =
    if (commandExecutionEnabled) {
        """
你是一个代码助手。请简洁准确地回答用户问题。
你拥有 run_command 工具，可以在用户项目根目录执行 shell 命令。
当用户请求运行命令、查看文件、执行构建或测试时，主动调用该工具获取真实结果后再作答。
        """.trimIndent()
    } else {
        """
你是一个代码助手。请简洁准确地回答用户问题。
你当前没有终端、文件系统或工具调用能力。
不要声称你已经执行命令、读取文件、修改代码或查看了运行结果。
如果用户要求你直接运行命令或检查本地文件，请明确说明当前插件暂不支持该能力，并要求用户粘贴结果或手动提供内容。
        """.trimIndent()
    }

internal fun resolveUiContextLabel(
    contextLabelOverride: String?,
    snapshot: PromptContextSnapshot?
): String = contextLabelOverride ?: snapshot?.contextLabel.orEmpty()

internal fun openSettingsOnEdt(
    openDialog: () -> Unit,
    enqueue: ((() -> Unit) -> Unit)
) {
    enqueue(openDialog)
}

internal fun formatSystemContent(
    base: String,
    snapshot: PromptContextSnapshot?,
    memoryText: String = ""
): String {
    var result = base

    if (memoryText.isNotBlank()) {
        result = """$result

[User Memory]
$memoryText"""
    }

    if (snapshot == null) {
        return result
    }

    return """$result

当前文件：${snapshot.fileName}
```${snapshot.extension}
${snapshot.content}
```"""
}

internal fun deriveConnectionState(
    hasProvider: Boolean,
    hasApiKey: Boolean,
    isStreaming: Boolean
): String = when {
    !hasProvider -> "unconfigured"
    isStreaming -> "streaming"
    !hasApiKey -> "error"
    else -> "ready"
}
