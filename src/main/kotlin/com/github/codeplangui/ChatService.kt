package com.github.codeplangui

import com.github.codeplangui.api.OkHttpSseClient
import com.github.codeplangui.model.ChatSession
import com.github.codeplangui.model.Message
import com.github.codeplangui.model.MessageRole
import com.github.codeplangui.settings.ApiKeyStore
import com.github.codeplangui.settings.PluginSettings
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource
import java.util.UUID

class ChatService(private val project: Project) {

    private val client = OkHttpSseClient()
    private val session = ChatSession()
    private var activeStream: EventSource? = null
    private var activeMessageId: String? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var bridgeHandler: BridgeHandler? = null

    fun sendMessage(text: String, includeContext: Boolean) {
        activeStream?.cancel()
        activeStream = null
        activeMessageId = null

        val settings = PluginSettings.getInstance()
        val provider = settings.getActiveProvider()
        if (provider == null) {
            bridgeHandler?.notifyError("请先在 Settings > Tools > CodePlanGUI 中配置 API Provider")
            return
        }

        val apiKey = ApiKeyStore.load(provider.id) ?: ""
        if (apiKey.isBlank()) {
            bridgeHandler?.notifyError("API Key 未设置，请在设置中重新配置")
            return
        }

        val systemContent = buildSystemContent(includeContext)
        session.setSystemMessage(systemContent)
        session.add(Message(MessageRole.USER, text))

        val msgId = UUID.randomUUID().toString()
        activeMessageId = msgId
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
                        bridgeHandler?.notifyEnd(msgId)
                    }
                },
                onError = { message ->
                    if (activeMessageId == msgId) {
                        activeStream = null
                        activeMessageId = null
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
}
