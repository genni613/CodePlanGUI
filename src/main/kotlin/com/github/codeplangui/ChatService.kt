package com.github.codeplangui

import com.github.codeplangui.api.OkHttpSseClient
import com.github.codeplangui.model.ChatSession
import com.github.codeplangui.model.Message
import com.github.codeplangui.model.MessageRole
import com.github.codeplangui.settings.ApiKeyStore
import com.github.codeplangui.settings.PluginSettings
import com.github.codeplangui.settings.PluginSettingsConfigurable
import com.github.codeplangui.storage.SessionStore
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
import okhttp3.sse.EventSource
import java.util.UUID

@Service(Service.Level.PROJECT)
class ChatService(private val project: Project) : Disposable {

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

        val contextSnapshot = if (includeContext && settings.getState().contextInjectionEnabled) {
            capturePromptContextSnapshot()
        } else {
            null
        }
        contextFileCallback?.invoke(resolveUiContextLabel(contextLabelOverride, contextSnapshot))
        val systemContent = formatSystemContent(
            base = buildBaseSystemPrompt(),
            snapshot = contextSnapshot,
            memoryText = settings.getState().memoryText
        )
        session.setSystemMessage(systemContent)
        val userMsg = Message(MessageRole.USER, text, UUID.randomUUID().toString(), session.nextSeq())
        session.add(userMsg)
        sessionStore.saveSession(session.threadId, session.getMessages().filter { it.role != MessageRole.SYSTEM })

        val msgId = UUID.randomUUID().toString()
        activeMessageId = msgId
        publishStatus()
        val request = client.buildRequest(
            config = provider,
            apiKey = apiKey,
            messages = session.getApiMessages(),
            temperature = settings.getState().chatTemperature,
            maxTokens = settings.getState().chatMaxTokens,
            stream = true
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
                        session.add(Message(MessageRole.ASSISTANT, responseBuffer.toString(), UUID.randomUUID().toString(), session.nextSeq()))
                        sessionStore.saveSession(session.threadId, session.getMessages().filter { it.role != MessageRole.SYSTEM })
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
        session = ChatSession()
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

    fun refreshBridgeStatus() {
        publishStatus()
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

    private val bridgeJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun restoreSessionIfNeeded() {
        val data = sessionStore.loadSession() ?: return
        session = ChatSession(data.threadId)
        data.messages.forEach { session.add(it) }
        val restoredJson = data.messages.map {
            mapOf("id" to it.id, "role" to it.role.name.lowercase(), "content" to it.content)
        }
        bridgeHandler?.notifyRestoreMessages(
            bridgeJson.encodeToString(kotlinx.serialization.serializer(), restoredJson)
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

internal fun buildBaseSystemPrompt(): String = """
你是一个代码助手。请简洁准确地回答用户问题。
你当前没有终端、文件系统或工具调用能力。
不要声称你已经执行命令、读取文件、修改代码或查看了运行结果。
如果用户要求你直接运行命令或检查本地文件，请明确说明当前插件暂不支持该能力，并要求用户粘贴结果或手动提供内容。
""".trimIndent()

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
