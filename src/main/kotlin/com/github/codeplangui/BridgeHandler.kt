package com.github.codeplangui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.util.Queue
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
private data class BridgePayload(
    val type: String,
    val text: String = "",
    val includeContext: Boolean = true,
    val requestId: String = "",
    val decision: String = "",
    val addToWhitelist: Boolean = false,
    val current: Int = 0,
    val max: Int = 0
)

internal interface BridgeCommands {
    fun sendMessage(text: String, includeContext: Boolean)
    fun newChat()
    fun openSettings()
    fun onFrontendReady()
    fun approvalResponse(requestId: String, decision: String, addToWhitelist: Boolean)
    fun debugLog(text: String)
    fun cancelStream()
}

internal fun dispatchBridgeRequest(
    type: String,
    text: String,
    includeContext: Boolean,
    requestId: String = "",
    decision: String = "",
    addToWhitelist: Boolean = false,
    commands: BridgeCommands
) {
    when (type) {
        "sendMessage"      -> commands.sendMessage(text, includeContext)
        "newChat"          -> commands.newChat()
        "openSettings"     -> commands.openSettings()
        "frontendReady"    -> commands.onFrontendReady()
        "approvalResponse" -> commands.approvalResponse(requestId, decision, addToWhitelist)
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
        dispatchBridgeRequest(req.type, req.text, req.includeContext, req.requestId, req.decision, req.addToWhitelist, commands)
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

@Serializable
data class BridgeErrorPayload(
    val type: String,
    val message: String,
    val action: String? = null
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

    private val pendingJs: Queue<String> = ConcurrentLinkedQueue()
    private val flushPending = AtomicBoolean(false)
    private val flushTimer = Timer("bridge-flush", true)
    private val flushLock = Any()

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

                    override fun approvalResponse(requestId: String, decision: String, addToWhitelist: Boolean) {
                        logger.info("[CodePlanGUI Bridge] frontend->ide approvalResponse requestId=$requestId decision=$decision addToWhitelist=$addToWhitelist")
                        chatService.onApprovalResponse(requestId, decision, addToWhitelist)
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
                            approvalResponse: function(requestId, decision, addToWhitelist) {
                                ${sendQuery.inject("""JSON.stringify({type:'approvalResponse',text:'',requestId:requestId,decision:decision,addToWhitelist:!!addToWhitelist})""")}
                            },
                            debugLog: function(text) {
                                ${sendQuery.inject("""JSON.stringify({type:'debugLog',text:text})""")}
                            },
                            onEvent: function(type, payloadJson) {}
                        };
                        document.dispatchEvent(new Event('bridge_ready'));
                    """.trimIndent()
                    browser.executeJavaScript(js, "", 0)

                    // Prevent JCEF WebView freeze on Ctrl+C/Cmd+C by intercepting
                    // the keyboard event and using async clipboard API instead of
                    // letting CEF handle it synchronously. Ref: jetbrains-cc-gui #846
                    val clipboardJs = """
                        document.addEventListener('keydown', function(e) {
                            if ((e.ctrlKey || e.metaKey) && e.key === 'c') {
                                var selection = window.getSelection();
                                if (selection && selection.toString().length > 0) {
                                    e.preventDefault();
                                    var text = selection.toString();
                                    if (navigator.clipboard && navigator.clipboard.writeText) {
                                        navigator.clipboard.writeText(text).catch(function() {});
                                    }
                                }
                            }
                        }, true);
                    """.trimIndent()
                    browser.executeJavaScript(clipboardJs, "", 0)
                }
            }
        }, browser.cefBrowser)
    }

    fun notifyStart(msgId: String) =
        flushAndPush(buildEventJS("start") { put("msgId", JsonPrimitive(msgId)) })

    fun notifyToken(token: String) =
        enqueueJS(buildEventJS("token") { put("text", JsonPrimitive(token)) })

    fun notifyEnd(msgId: String) =
        flushAndPush(buildEventJS("end") { put("msgId", JsonPrimitive(msgId)) })

    fun notifyError(message: String) =
        flushAndPush(buildEventJS("error") { put("message", JsonPrimitive(message)) })

    fun notifyStructuredError(error: BridgeErrorPayload) =
        flushAndPush(buildEventJS("structured_error") {
            put("type", JsonPrimitive(error.type))
            put("message", JsonPrimitive(error.message))
            error.action?.let { put("action", JsonPrimitive(it)) }
        })

    fun notifyStatus(status: BridgeStatusPayload) =
        flushAndPush(buildEventJS("status") {
            put("providerName", JsonPrimitive(status.providerName))
            put("model", JsonPrimitive(status.model))
            put("connectionState", JsonPrimitive(status.connectionState))
        })

    fun notifyContextFile(fileName: String) =
        pushJS(buildEventJS("context_file") { put("fileName", JsonPrimitive(fileName)) })

    fun notifyTheme(theme: String) =
        pushJS(buildEventJS("theme") { put("mode", JsonPrimitive(theme)) })

    fun notifyLog(msgId: String, logLine: String, type: String) =
        enqueueJS(buildEventJS("log") {
            put("requestId", JsonPrimitive(msgId))
            put("line", JsonPrimitive(logLine))
            put("type", JsonPrimitive(type))
        })

    fun notifyExecutionCard(requestId: String, command: String, description: String) =
        flushAndPush(buildEventJS("execution_card") {
            put("requestId", JsonPrimitive(requestId))
            put("command", JsonPrimitive(command))
            put("description", JsonPrimitive(description))
        })

    fun notifyApprovalRequest(requestId: String, command: String, description: String) =
        flushAndPush(buildEventJS("approval_request") {
            put("requestId", JsonPrimitive(requestId))
            put("command", JsonPrimitive(command))
            put("toolInput", JsonPrimitive(command))
            put("description", JsonPrimitive(description))
        }).also {
            logger.info(
                "[CodePlanGUI Bridge] ide->frontend approvalRequest " +
                    "requestId=$requestId command=${command.summarizeForLog()} description=${description.summarizeForLog()}"
            )
        }

    fun notifyFileChangeAuto(path: String, added: Int, removed: Int) =
        flushAndPush(buildEventJS("file_change_auto") {
            put("path", JsonPrimitive(path))
            put("stats", buildJsonObject {
                put("added", JsonPrimitive(added))
                put("removed", JsonPrimitive(removed))
            })
        })

    fun notifyExecutionStatus(requestId: String, status: String, resultJson: String) =
        flushAndPush(buildEventJS("execution_status") {
            put("requestId", JsonPrimitive(requestId))
            put("status", JsonPrimitive(status))
            put("result", JsonPrimitive(resultJson))
        }).also {
            logger.info(
                "[CodePlanGUI Bridge] ide->frontend executionStatus " +
                    "requestId=$requestId status=$status result=${resultJson.summarizeForLog(240)}"
            )
        }

    fun notifyRestoreMessages(messages: String) =
        flushAndPush(buildEventJS("restore_messages") { put("messages", JsonPrimitive(messages)) })

    fun notifyContinuation(current: Int, max: Int) =
        pushJS(buildEventJS("continuation") {
            put("current", JsonPrimitive(current))
            put("max", JsonPrimitive(max))
        })

    fun notifyRemoveMessage(msgId: String) =
        flushAndPush(buildEventJS("remove_message") { put("msgId", JsonPrimitive(msgId)) })

    fun notifyRoundEnd(msgId: String) =
        flushAndPush(buildEventJS("round_end") { put("msgId", JsonPrimitive(msgId)) })

    fun notifyToolStepStart(msgId: String, requestId: String, toolName: String, summary: String) =
        flushAndPush(buildEventJS("tool_step_start") {
            put("msgId", JsonPrimitive(msgId))
            put("requestId", JsonPrimitive(requestId))
            put("toolName", JsonPrimitive(toolName))
            put("summary", JsonPrimitive(summary))
        })

    fun notifyToolStepEnd(msgId: String, requestId: String, status: Boolean, output: String, durationMs: Long, diffStats: String? = null) =
        flushAndPush(buildEventJS("tool_step_end") {
            put("msgId", JsonPrimitive(msgId))
            put("requestId", JsonPrimitive(requestId))
            put("status", JsonPrimitive(status))
            put("output", JsonPrimitive(output))
            put("durationMs", JsonPrimitive(durationMs))
            if (diffStats != null) put("diffStats", JsonPrimitive(diffStats))
        })

    /**
     * Build a JS call that dispatches a unified event through `window.__bridge.onEvent(type, payloadJson)`.
     * The payload is built using kotlinx.serialization's `buildJsonObject` for safe JSON encoding.
     * Only generates the JS string — the caller decides the dispatch strategy (enqueueJS / flushAndPush / pushJS).
     */
    private fun buildEventJS(type: String, builderAction: JsonObjectBuilder.() -> Unit): String {
        val obj = buildJsonObject(builderAction)
        val payloadStr = obj.toString()
        return "window.__bridge.onEvent(${type.quoted()}, ${json.encodeToString(payloadStr)})"
    }

    /**
     * Enqueue a streamable JS call (token / log line) for batch delivery.
     * A daemon timer flushes the queue every ~16 ms, bypassing the EDT entirely.
     */
    private fun enqueueJS(js: String) {
        if (!isReady) {
            logger.debug("[CodePlanGUI Bridge] enqueueJS discarded (bridge not ready): ${js.take(120)}")
            return
        }
        pendingJs.add(js)
        scheduleFlush()
    }

    /**
     * Flush any buffered streamable calls, then push a non-streamable call immediately.
     * Used for structural events (start, end, error, etc.) where ordering matters.
     */
    private fun flushAndPush(js: String) {
        if (!isReady) {
            logger.debug("[CodePlanGUI Bridge] flushAndPush discarded (bridge not ready): ${js.take(120)}")
            return
        }
        flushPendingBuffer()
        executeJS(js)
    }

    /**
     * Schedule a timer-based flush that bypasses the EDT.
     * The [flushPending] flag ensures at most one timer task is outstanding.
     */
    private fun scheduleFlush() {
        if (flushPending.compareAndSet(false, true)) {
            flushTimer.schedule(object : TimerTask() {
                override fun run() {
                    val hasMore: Boolean
                    synchronized(flushLock) {
                        flushPendingBuffer()
                        hasMore = pendingJs.isNotEmpty()
                    }
                    flushPending.set(false)
                    if (hasMore) {
                        scheduleFlush()
                    }
                }
            }, FLUSH_INTERVAL_MS)
        }
    }

    /**
     * Drain the pending queue and execute as a single batched JS call.
     * Called from the flush timer thread or from [flushAndPush].
     */
    internal fun flushPendingBuffer() {
        val batch: List<String>
        synchronized(flushLock) {
            batch = buildList {
                while (true) {
                    val item = pendingJs.poll() ?: break
                    add(item)
                }
            }
        }
        if (batch.isNotEmpty()) {
            executeJS(batch.joinToString(";"))
        }
    }

    private fun pushJS(js: String) {
        if (!isReady) {
            logger.debug("[CodePlanGUI Bridge] pushJS discarded (bridge not ready): ${js.take(120)}")
            return
        }
        executeJS(js)
    }

    /**
     * Execute a JavaScript string in the browser. Called directly without EDT dispatch —
     * CEF's [executeJavaScript] is thread-safe and posts the JS to the renderer process.
     */
    private fun executeJS(js: String) {
        try {
            browser.cefBrowser.executeJavaScript(js, "", 0)
        } catch (e: Exception) {
            logger.debug("[CodePlanGUI Bridge] executeJS failed: ${e.message}")
        }
    }

    /**
     * Cancel the flush timer and drain any remaining pending JS calls.
     */
    fun dispose() {
        flushTimer.cancel()
        flushPendingBuffer()
    }

    companion object {
        /** Flush interval in ms — ~60 fps. */
        internal const val FLUSH_INTERVAL_MS = 16L
    }

    private fun String.quoted() = "'${replace("'", "\\'")}'"

    private fun String.summarizeForLog(maxLength: Int = 120): String {
        val singleLine = replace('\n', ' ').replace('\r', ' ').trim()
        return if (singleLine.length <= maxLength) singleLine else singleLine.take(maxLength) + "..."
    }
}
