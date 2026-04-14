package com.github.codeplangui

import com.github.codeplangui.api.OkHttpSseClient
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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
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

    private val client = OkHttpSseClient()
    private val session = ChatSession()
    private var activeStream: EventSource? = null
    private var activeMessageId: String? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingPrompt: PendingPrompt? = null

    var bridgeHandler: BridgeHandler? = null
        private set

    private var contextFileCallback: ((String) -> Unit)? = null
    private var onFrontendReadyCallback: (() -> Unit)? = null
    private var isFrontendReady: Boolean = false

    // Tool call state machine
    private enum class StreamState { TEXT, ACCUMULATING_TOOL_CALL }
    private var streamState = StreamState.TEXT
    private var pendingToolCallId: String? = null
    private var pendingFunctionName: String? = null
    private val argumentsBuffer = StringBuilder()

    // Approval gate: suspended coroutines wait on these futures
    private val pendingApprovals = ConcurrentHashMap<String, CompletableFuture<Boolean>>()

    fun attachBridge(handler: BridgeHandler) {
        bridgeHandler = handler
        isFrontendReady = false
    }

    fun onFrontendReady() {
        isFrontendReady = true
        publishStatus()
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

        val commandExecutionEnabled = settings.getState().commandExecutionEnabled

        val contextSnapshot = if (includeContext && settings.getState().contextInjectionEnabled) {
            capturePromptContextSnapshot()
        } else {
            null
        }
        contextFileCallback?.invoke(resolveUiContextLabel(contextLabelOverride, contextSnapshot))
        val systemContent = formatSystemContent(
            base = buildBaseSystemPrompt(commandExecutionEnabled),
            snapshot = contextSnapshot
        )
        session.setSystemMessage(systemContent)
        session.add(Message(MessageRole.USER, text))

        // Reset state machine before each new user-initiated request
        streamState = StreamState.TEXT
        pendingToolCallId = null
        pendingFunctionName = null
        argumentsBuffer.clear()

        val msgId = UUID.randomUUID().toString()
        activeMessageId = msgId
        publishStatus()

        val request = client.buildRequest(
            config = provider,
            apiKey = apiKey,
            messages = session.getApiMessages(),
            temperature = settings.getState().chatTemperature,
            maxTokens = settings.getState().chatMaxTokens,
            stream = true,
            tools = if (commandExecutionEnabled) listOf(runCommandToolDefinition()) else null
        )

        bridgeHandler?.notifyStart(msgId)

        val responseBuffer = StringBuilder()
        scope.launch {
            val stream = client.streamChat(
                request = request,
                onToken = { token ->
                    if (activeMessageId == msgId) {
                        responseBuffer.append(token)
                        bridgeHandler?.notifyToken(token)
                    }
                },
                onEnd = {
                    if (activeMessageId == msgId) {
                        session.add(Message(MessageRole.ASSISTANT, responseBuffer.toString()))
                        activeStream = null
                        activeMessageId = null
                        publishStatus()
                        bridgeHandler?.notifyEnd(msgId)
                    }
                },
                onError = { message ->
                    if (activeMessageId == msgId) {
                        activeStream = null
                        activeMessageId = null
                        publishStatus()
                        bridgeHandler?.notifyError(message)
                    }
                },
                onToolCallChunk = { delta ->
                    handleToolCallChunk(delta)
                },
                onFinishReason = { reason ->
                    if (reason == "tool_calls") {
                        val capturedMsgId = msgId
                        val capturedBuffer = responseBuffer
                        scope.launch { handleToolCallComplete(capturedMsgId, capturedBuffer) }
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

    fun newChat() {
        activeStream?.cancel()
        activeStream = null
        activeMessageId = null
        session.clear()
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
        if (delta.id != null) {
            pendingToolCallId = delta.id
            pendingFunctionName = delta.functionName
            streamState = StreamState.ACCUMULATING_TOOL_CALL
        }
        delta.argumentsChunk?.let { argumentsBuffer.append(it) }
    }

    private suspend fun handleToolCallComplete(msgId: String, responseBuffer: StringBuilder) {
        val toolCallId = pendingToolCallId ?: return
        val argsJson = argumentsBuffer.toString()

        val argsObj = try {
            kotlinx.serialization.json.Json.parseToJsonElement(argsJson).jsonObject
        } catch (_: Exception) {
            bridgeHandler?.notifyError("AI returned malformed tool call arguments")
            return
        }
        val command = argsObj["command"]?.jsonPrimitive?.contentOrNull ?: return
        val description = argsObj["description"]?.jsonPrimitive?.contentOrNull ?: ""

        val state = PluginSettings.getInstance().getState()
        val requestId = UUID.randomUUID().toString()

        // Show execution card placeholder in Vue
        bridgeHandler?.notifyApprovalRequest(requestId, command, description)

        // Whitelist check — blocked without showing dialog
        if (!CommandExecutionService.isWhitelisted(command, state.commandWhitelist)) {
            val result = ExecutionResult.Blocked(
                command, "'${CommandExecutionService.extractBaseCommand(command)}' is not in the allowed command list"
            )
            bridgeHandler?.notifyExecutionStatus(requestId, "blocked", result.toToolResultContent())
            continueWithToolResult(msgId, toolCallId, argsJson, responseBuffer, result)
            return
        }

        // Path safety check
        val basePath = project.basePath ?: ""
        if (CommandExecutionService.hasPathsOutsideWorkspace(command, basePath)) {
            val result = ExecutionResult.Blocked(command, "Command accesses paths outside the project")
            bridgeHandler?.notifyExecutionStatus(requestId, "blocked", result.toToolResultContent())
            continueWithToolResult(msgId, toolCallId, argsJson, responseBuffer, result)
            return
        }

        // Suspend and wait for user approval (max 60s)
        val future = CompletableFuture<Boolean>()
        pendingApprovals[requestId] = future
        bridgeHandler?.notifyExecutionStatus(requestId, "waiting", "{}")

        val approved = try {
            withContext(Dispatchers.IO) { future.get(60, TimeUnit.SECONDS) }
        } catch (_: Exception) {
            false
        } finally {
            pendingApprovals.remove(requestId)
        }

        if (!approved) {
            val result = ExecutionResult.Denied(command, "User rejected the command")
            bridgeHandler?.notifyExecutionStatus(requestId, "denied", result.toToolResultContent())
            continueWithToolResult(msgId, toolCallId, argsJson, responseBuffer, result)
            return
        }

        // Execute
        bridgeHandler?.notifyExecutionStatus(requestId, "running", "{}")
        val execService = CommandExecutionService.getInstance(project)
        val result = execService.executeAsync(command, state.commandTimeoutSeconds)
        val bridgeStatus = if (result is ExecutionResult.TimedOut) "timeout" else "done"
        bridgeHandler?.notifyExecutionStatus(requestId, bridgeStatus, result.toToolResultContent())

        continueWithToolResult(msgId, toolCallId, argsJson, responseBuffer, result)
    }

    private fun continueWithToolResult(
        msgId: String,
        toolCallId: String,
        argsJson: String,
        responseBuffer: StringBuilder,
        result: ExecutionResult
    ) {
        // Assistant message must carry tool_calls for the OpenAI API to accept the follow-up tool result
        session.add(Message(
            role = MessageRole.ASSISTANT,
            content = responseBuffer.toString(),
            toolCalls = listOf(ToolCallRecord(
                id = toolCallId,
                functionName = pendingFunctionName ?: "run_command",
                arguments = argsJson
            ))
        ))
        // Tool result message
        session.add(Message(
            role = MessageRole.TOOL,
            content = result.toToolResultContent(),
            toolCallId = toolCallId
        ))

        // Reset state machine
        streamState = StreamState.TEXT
        pendingToolCallId = null
        pendingFunctionName = null
        argumentsBuffer.clear()
        responseBuffer.clear()

        sendMessageInternal(msgId)
    }

    private fun sendMessageInternal(msgId: String) {
        val pluginSettings = PluginSettings.getInstance()
        val provider = pluginSettings.getActiveProvider() ?: return
        val apiKey = ApiKeyStore.load(provider.id) ?: return

        val request = client.buildRequest(
            config = provider,
            apiKey = apiKey,
            messages = session.getApiMessages(),
            temperature = pluginSettings.getState().chatTemperature,
            maxTokens = pluginSettings.getState().chatMaxTokens,
            stream = true,
            tools = null  // no tools on the follow-up round
        )

        scope.launch {
            client.streamChat(
                request = request,
                onToken = { token ->
                    bridgeHandler?.notifyToken(token)
                },
                onEnd = {
                    activeStream = null
                    activeMessageId = null
                    publishStatus()
                    bridgeHandler?.notifyEnd(msgId)
                },
                onError = { message ->
                    activeStream = null
                    activeMessageId = null
                    publishStatus()
                    bridgeHandler?.notifyError(message)
                }
            )
        }
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

    override fun dispose() {
        activeStream?.cancel()
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): ChatService = project.getService(ChatService::class.java)
    }

    private data class PendingPrompt(
        val text: String,
        val includeContext: Boolean,
        val contextLabel: String? = null
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
    snapshot: PromptContextSnapshot?
): String {
    if (snapshot == null) {
        return base
    }

    return """$base

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
