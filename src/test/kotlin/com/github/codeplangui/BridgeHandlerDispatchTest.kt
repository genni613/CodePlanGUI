package com.github.codeplangui

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class BridgeHandlerDispatchTest {

    @Test
    fun `dispatchBridgeRequest routes sendMessage with includeContext`() {
        val commands = RecordingBridgeCommands()

        dispatchBridgeRequest(
            type = "sendMessage",
            text = "hello",
            includeContext = true,
            commands = commands
        )

        assertEquals(listOf(SentMessage("hello", true)), commands.sentMessages)
    }

    @Test
    fun `dispatchBridgeRequest routes frontendReady`() {
        val commands = RecordingBridgeCommands()

        dispatchBridgeRequest(
            type = "frontendReady",
            text = "",
            includeContext = false,
            commands = commands
        )

        assertEquals(1, commands.frontendReadyCalls)
    }

    @Test
    fun `handleBridgePayload reports bridge command failures instead of swallowing them`() {
        val result = handleBridgePayload(
            payload = """{"type":"sendMessage","text":"hello","includeContext":true}""",
            json = Json { ignoreUnknownKeys = true },
            commands = object : BridgeCommands {
                override fun sendMessage(text: String, includeContext: Boolean) {
                    throw IllegalStateException("boom")
                }

                override fun newChat() = Unit

                override fun openSettings() = Unit

                override fun onFrontendReady() = Unit

                override fun approvalResponse(requestId: String, decision: String) = Unit

                override fun debugLog(text: String) = Unit

                override fun cancelStream() = Unit
            }
        )

        val error = assertInstanceOf(BridgePayloadHandlingResult.CommandError::class.java, result)
        assertEquals("发送消息失败：boom", error.message)
    }

    @Test
    fun `dispatchBridgeRequest routes approvalResponse`() {
        val commands = RecordingBridgeCommands()

        dispatchBridgeRequest(
            type = "approvalResponse",
            text = "",
            includeContext = false,
            requestId = "req-123",
            decision = "allow",
            commands = commands
        )

        assertEquals(listOf(Pair("req-123", "allow")), commands.approvalResponses)
    }

    @Test
    fun `dispatchBridgeRequest routes debugLog`() {
        val commands = RecordingBridgeCommands()

        dispatchBridgeRequest(
            type = "debugLog",
            text = "approval modal allow clicked",
            includeContext = false,
            commands = commands
        )

        assertEquals(listOf("approval modal allow clicked"), commands.debugLogs)
    }

    @Test
    fun `dispatchBridgeRequest routes cancelStream`() {
        val commands = RecordingBridgeCommands()

        dispatchBridgeRequest(
            type = "cancelStream",
            text = "",
            includeContext = false,
            commands = commands
        )

        assertEquals(1, commands.cancelStreamCalls)
    }

    @Test
    fun `handleBridgePayload routes cancelStream via JSON payload`() {
        val commands = RecordingBridgeCommands()

        val result = handleBridgePayload(
            payload = """{"type":"cancelStream","text":""}""",
            json = Json { ignoreUnknownKeys = true },
            commands = commands
        )

        assertInstanceOf(BridgePayloadHandlingResult.Success::class.java, result)
        assertEquals(1, commands.cancelStreamCalls)
    }

    private class RecordingBridgeCommands : BridgeCommands {
        val sentMessages = mutableListOf<SentMessage>()
        var frontendReadyCalls: Int = 0
        val approvalResponses = mutableListOf<Pair<String, String>>()
        val debugLogs = mutableListOf<String>()
        var cancelStreamCalls: Int = 0

        override fun sendMessage(text: String, includeContext: Boolean) {
            sentMessages += SentMessage(text, includeContext)
        }

        override fun newChat() = Unit

        override fun openSettings() = Unit

        override fun onFrontendReady() {
            frontendReadyCalls += 1
        }

        override fun approvalResponse(requestId: String, decision: String) {
            approvalResponses += Pair(requestId, decision)
        }

        override fun debugLog(text: String) {
            debugLogs += text
        }

        override fun cancelStream() {
            cancelStreamCalls += 1
        }
    }
}

data class SentMessage(val text: String, val includeContext: Boolean)
