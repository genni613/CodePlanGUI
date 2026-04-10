package com.github.codeplangui

import com.github.codeplangui.api.OkHttpSseClient
import com.github.codeplangui.model.ChatSession
import com.github.codeplangui.model.Message
import com.github.codeplangui.model.MessageRole
import com.github.codeplangui.settings.ApiKeyStore
import com.github.codeplangui.settings.PluginSettings
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
    private val session = ChatSession()
    private var activeStream: EventSource? = null
    private var activeMessageId: String? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingPrompt: PendingPrompt? = null

    var bridgeHandler: BridgeHandler? = null
        private set

    fun attachBridge(handler: BridgeHandler) {
        bridgeHandler = handler
        publishStatus()
        pendingPrompt?.let { prompt ->
            pendingPrompt = null
            sendMessage(prompt.text, prompt.includeContext)
        }
    }

    fun sendMessage(text: String, includeContext: Boolean) {
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
            bridgeHandler?.notifyError("API Key 未设置，请在设置中重新配置")
            return
        }

        val systemContent = buildSystemContent(includeContext && settings.getState().contextInjectionEnabled)
        session.setSystemMessage(systemContent)
        session.add(Message(MessageRole.USER, text))

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
        publishStatus()
    }

    fun askAboutSelection(selection: String) {
        val prompt = buildSelectionPrompt(selection)
        if (bridgeHandler?.isReady == true) {
            sendMessage(prompt, false)
        } else {
            pendingPrompt = PendingPrompt(prompt, false)
        }
    }

    fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "CodePlanGUI")
    }

    fun refreshBridgeStatus() {
        publishStatus()
    }

    private fun buildSystemContent(includeContext: Boolean): String {
        val base = "你是一个代码助手。请简洁准确地回答用户问题。"
        if (!includeContext) return base

        val editor = try {
            FileEditorManager.getInstance(project).selectedTextEditor
        } catch (_: Exception) {
            null
        } ?: return base

        val file = editor.virtualFile ?: return base
        val selected = editor.selectionModel.selectedText
        val content = selected ?: editor.document.text

        val lines = content.lines()
            .take(PluginSettings.getInstance().getState().contextMaxLines)
            .joinToString("\n")
            .take(12000)

        val ext = file.extension ?: "txt"
        return """$base

当前文件：${file.name}
```$ext
$lines
```"""
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

    private data class PendingPrompt(val text: String, val includeContext: Boolean)
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
