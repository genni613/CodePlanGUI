package com.github.codeplangui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

@Serializable
private data class BridgePayload(
    val type: String,
    val text: String = "",
    val includeContext: Boolean = true,
    val requestId: String = "",
    val decision: String = ""
)

internal interface BridgeCommands {
    fun sendMessage(text: String, includeContext: Boolean)
    fun newChat()
    fun openSettings()
    fun onFrontendReady()
    fun approvalResponse(requestId: String, decision: String)
    fun debugLog(text: String)
    fun cancelStream()
}

internal fun dispatchBridgeRequest(
    type: String,
    text: String,
    includeContext: Boolean,
    requestId: String = "",
    decision: String = "",
    commands: BridgeCommands
) {
    when (type) {
        "sendMessage"      -> commands.sendMessage(text, includeContext)
        "newChat"          -> commands.newChat()
        "openSettings"     -> commands.openSettings()
        "frontendReady"    -> commands.onFrontendReady()
        "approvalResponse" -> commands.approvalResponse(requestId, decision)
        "debugLog"         -> commands.debugLog(text)
        "cancelStream"     -> commands.cancelStream()
    }
}

internal sealed interface BridgePayloadHandlingResult {
    data object Success : BridgePayloadHandlingResult
    data object MalformedPayload : BridgePayloadHandlingResult
    data class CommandError(val message: String, val cause: Throwable) : BridgePayloadHandlingResult
}

internal fun handleBridgePayload(
    payload: String,
    json: Json,
    commands: BridgeCommands
): BridgePayloadHandlingResult {
    val req = try {
        json.decodeFromString<BridgePayload>(payload)
    } catch (_: Exception) {
        return BridgePayloadHandlingResult.MalformedPayload
    }

    return try {
        dispatchBridgeRequest(req.type, req.text, req.includeContext, req.requestId, req.decision, commands)
        BridgePayloadHandlingResult.Success
    } catch (e: Exception) {
        BridgePayloadHandlingResult.CommandError(
            message = "发送消息失败：${e.message ?: e.javaClass.simpleName}",
            cause = e
        )
    }
}

@Serializable
data class BridgeStatusPayload(
    val providerName: String = "",
    val model: String = "",
    val connectionState: String = "unconfigured"
)

class BridgeHandler(
    private val browser: JBCefBrowser,
    private val chatService: ChatService
) {
    private val logger = Logger.getInstance(BridgeHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var sendQuery: JBCefJSQuery
    var isReady: Boolean = false
        private set

    fun register() {
        sendQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        sendQuery.addHandler { payload ->
            when (val result = handleBridgePayload(payload, json, object : BridgeCommands {
                    override fun sendMessage(text: String, includeContext: Boolean) {
                        chatService.sendMessage(text, includeContext)
                    }

                    override fun newChat() {
                        chatService.newChat()
                    }

                    override fun openSettings() {
                        chatService.openSettings()
                    }

                    override fun onFrontendReady() {
                        chatService.onFrontendReady()
                    }

                    override fun approvalResponse(requestId: String, decision: String) {
                        logger.info("[CodePlanGUI Bridge] frontend->ide approvalResponse requestId=$requestId decision=$decision")
                        chatService.onApprovalResponse(requestId, decision)
                    }

                    override fun debugLog(text: String) {
                        logger.info("[CodePlanGUI Frontend] $text")
                    }

                    override fun cancelStream() {
                        chatService.cancelStream()
                    }
                })) {
                BridgePayloadHandlingResult.Success -> null
                BridgePayloadHandlingResult.MalformedPayload -> {
                    logger.warn("Ignoring malformed bridge payload")
                    null
                }
                is BridgePayloadHandlingResult.CommandError -> {
                    logger.warn(result.message, result.cause)
                    notifyError(result.message)
                    null
                }
            }
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) {
                    isReady = true
                    chatService.attachBridge(this@BridgeHandler)
                    val js = """
                        window.__bridge = {
                            isReady: true,
                            sendMessage: function(text, includeContext) {
                                ${sendQuery.inject("""JSON.stringify({type:'sendMessage',text:text,includeContext:!!includeContext})""")}
                            },
                            newChat: function() {
                                ${sendQuery.inject("""JSON.stringify({type:'newChat',text:''})""")}
                            },
                            openSettings: function() {
                                ${sendQuery.inject("""JSON.stringify({type:'openSettings',text:''})""")}
                            },
                            cancelStream: function() {
                                ${sendQuery.inject("""JSON.stringify({type:'cancelStream',text:''})""")}
                            },
                            frontendReady: function() {
                                ${sendQuery.inject("""JSON.stringify({type:'frontendReady',text:''})""")}
                            },
                            approvalResponse: function(requestId, decision) {
                                ${sendQuery.inject("""JSON.stringify({type:'approvalResponse',text:'',requestId:requestId,decision:decision})""")}
                            },
                            debugLog: function(text) {
                                ${sendQuery.inject("""JSON.stringify({type:'debugLog',text:text})""")}
                            },
                            onStart: function(msgId) {},
                            onToken: function(token) {},
                            onEnd: function(msgId) {},
                            onError: function(message) {},
                            onStatus: function(status) {},
                            onContextFile: function(fileName) {},
                            onTheme: function(theme) {},
                            onApprovalRequest: function(requestId, command, description) {},
                            onLog: function(msgId, logLine, type) {},
                            onExecutionStatus: function(requestId, status, result) {},
                            onRestoreMessages: function(messages) {}
                        };
                        document.dispatchEvent(new Event('bridge_ready'));
                    """.trimIndent()
                    browser.executeJavaScript(js, "", 0)
                }
            }
        }, browser.cefBrowser)
    }

    fun notifyStart(msgId: String) = pushJS("window.__bridge.onStart(${msgId.quoted()})")

    fun notifyToken(token: String) = pushJS("window.__bridge.onToken(${json.encodeToString(token)})")

    fun notifyEnd(msgId: String) = pushJS("window.__bridge.onEnd(${msgId.quoted()})")

    fun notifyError(message: String) = pushJS("window.__bridge.onError(${json.encodeToString(message)})")

    fun notifyStatus(status: BridgeStatusPayload) =
        pushJS("window.__bridge.onStatus(${json.encodeToString(status)})")

    fun notifyContextFile(fileName: String) =
        pushJS("window.__bridge.onContextFile(${json.encodeToString(fileName)})")

    fun notifyTheme(theme: String) =
        pushJS("window.__bridge.onTheme(${json.encodeToString(theme)})")

    fun notifyLog(msgId: String, logLine: String, type: String) =
        pushJS(
            "window.__bridge.onLog(" +
            "${json.encodeToString(msgId)}," +
            "${json.encodeToString(logLine)}," +
            "${json.encodeToString(type)})"
        )

    fun notifyApprovalRequest(requestId: String, command: String, description: String) =
        pushJS(
            "window.__bridge.onApprovalRequest(" +
            "${json.encodeToString(requestId)}," +
            "${json.encodeToString(command)}," +
            "${json.encodeToString(description)})"
        ).also {
            logger.info(
                "[CodePlanGUI Bridge] ide->frontend approvalRequest " +
                    "requestId=$requestId command=${command.summarizeForLog()} description=${description.summarizeForLog()}"
            )
        }

    fun notifyExecutionStatus(requestId: String, status: String, resultJson: String) =
        pushJS(
            "window.__bridge.onExecutionStatus(" +
            "${json.encodeToString(requestId)}," +
            "${json.encodeToString(status)}," +
            "${json.encodeToString(resultJson)})"
        ).also {
            logger.info(
                "[CodePlanGUI Bridge] ide->frontend executionStatus " +
                    "requestId=$requestId status=$status result=${resultJson.summarizeForLog(240)}"
            )
        }

    fun notifyRestoreMessages(messages: String) =
        pushJS("window.__bridge.onRestoreMessages(${json.encodeToString(messages)})")

    private fun pushJS(js: String) {
        if (!isReady) return
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(js, "", 0)
        }
    }

    private fun String.quoted() = "'${replace("'", "\\'")}'"

    private fun String.summarizeForLog(maxLength: Int = 120): String {
        val singleLine = replace('\n', ' ').replace('\r', ' ').trim()
        return if (singleLine.length <= maxLength) singleLine else singleLine.take(maxLength) + "..."
    }
}
