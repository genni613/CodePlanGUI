package com.github.codeplangui

import com.intellij.openapi.application.ApplicationManager
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
    val includeContext: Boolean = true
)

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
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var sendQuery: JBCefJSQuery
    var isReady: Boolean = false
        private set

    fun register() {
        sendQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        sendQuery.addHandler { payload ->
            try {
                val req = json.decodeFromString<BridgePayload>(payload)
                when (req.type) {
                    "sendMessage" -> chatService.sendMessage(req.text, req.includeContext)
                    "newChat" -> chatService.newChat()
                    "openSettings" -> chatService.openSettings()
                }
            } catch (_: Exception) {
                // Ignore malformed bridge payloads.
            }
            null
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) {
                    isReady = true
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
                            onStart: function(msgId) {},
                            onToken: function(token) {},
                            onEnd: function(msgId) {},
                            onError: function(message) {},
                            onStatus: function(status) {}
                        };
                        document.dispatchEvent(new Event('bridge_ready'));
                    """.trimIndent()
                    browser.executeJavaScript(js, "", 0)
                    chatService.attachBridge(this@BridgeHandler)
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

    private fun pushJS(js: String) {
        if (!isReady) return
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(js, "", 0)
        }
    }

    private fun String.quoted() = "'${replace("'", "\\'")}'"
}
