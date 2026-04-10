package com.github.codeplangui

import com.github.codeplangui.api.OkHttpSseClient
import com.github.codeplangui.api.TestResult
import com.github.codeplangui.model.Message
import com.github.codeplangui.model.MessageRole
import com.github.codeplangui.settings.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class OkHttpSseClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `buildRequest targets chat completions endpoint and serializes request body`() {
        val client = OkHttpSseClient()
        val request = client.buildRequest(
            config = ProviderConfig(
                id = "provider-1",
                name = "OpenAI",
                endpoint = "https://api.openai.com/v1/",
                model = "gpt-4o-mini"
            ),
            apiKey = "secret-key",
            messages = listOf(
                Message(MessageRole.SYSTEM, "system prompt"),
                Message(MessageRole.USER, "hello")
            ),
            temperature = 0.25,
            maxTokens = 512,
            stream = true
        )

        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val body = json.parseToJsonElement(buffer.readUtf8()).jsonObject

        assertEquals("https://api.openai.com/v1/chat/completions", request.url.toString())
        assertEquals("Bearer secret-key", request.header("Authorization"))
        assertEquals("text/event-stream", request.header("Accept"))
        assertEquals("gpt-4o-mini", body["model"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("0.25", body["temperature"]!!.jsonPrimitive.content)
        assertEquals("512", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("system", body["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hello", body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `callSync returns parsed content from successful response`() {
        val client = OkHttpSseClient(
            syncClient = syncClientFor(
                responseCode = 200,
                responseBody = """{"choices":[{"message":{"content":"hello back"}}]}"""
            )
        )

        val result = client.callSync(simpleRequest())

        assertEquals("hello back", result.getOrThrow())
    }

    @Test
    fun `callSync falls back to raw body when schema is unexpected`() {
        val rawBody = """{"unexpected":true}"""
        val client = OkHttpSseClient(
            syncClient = syncClientFor(
                responseCode = 200,
                responseBody = rawBody
            )
        )

        val result = client.callSync(simpleRequest())

        assertEquals(rawBody, result.getOrThrow())
    }

    @Test
    fun `testConnection returns http failure details for non success status`() {
        val client = OkHttpSseClient(
            syncClient = syncClientFor(
                responseCode = 401,
                responseBody = "bad key"
            )
        )

        val result = client.testConnection(providerConfig(), "secret-key")

        assertEquals(TestResult.Failure("HTTP 401: bad key"), result)
    }

    @Test
    fun `testConnection maps timeout exceptions to user facing message`() {
        val client = OkHttpSseClient(
            syncClient = syncClientFor(thrown = SocketTimeoutException("timed out"))
        )

        val result = client.testConnection(providerConfig(), "secret-key")

        assertEquals(
            TestResult.Failure("连接超时（5s）：请检查 endpoint 是否可访问"),
            result
        )
    }

    @Test
    fun `testConnection uses five second timeout budget`() {
        var observedReadTimeout = -1
        val syncClient = OkHttpClient.Builder()
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                observedReadTimeout = chain.readTimeoutMillis()
                responseFor(chain.request(), 200, """{"choices":[{"message":{"content":"ok"}}]}""")
            }
            .build()
        val client = OkHttpSseClient(syncClient = syncClient)

        val result = client.testConnection(providerConfig(), "secret-key")

        assertEquals(TestResult.Success, result)
        assertEquals(5_000, observedReadTimeout)
    }

    @Test
    fun `streamChat emits tokens and completes on DONE`() {
        val factory = FakeEventSourceFactory()
        val client = OkHttpSseClient(eventSourceFactory = factory)
        val tokens = mutableListOf<String>()
        var ended = false
        var error: String? = null

        val source = client.streamChat(
            request = simpleRequest(),
            onToken = tokens::add,
            onEnd = { ended = true },
            onError = { error = it }
        )

        factory.listener.onEvent(
            source,
            null,
            null,
            """{"choices":[{"delta":{"content":"Hi"}}]}"""
        )
        factory.listener.onEvent(source, null, null, "[DONE]")

        assertEquals(listOf("Hi"), tokens)
        assertTrue(ended)
        assertNull(error)
    }

    @Test
    fun `streamChat forwards mapped error messages`() {
        val factory = FakeEventSourceFactory()
        val client = OkHttpSseClient(eventSourceFactory = factory)
        var error: String? = null

        val source = client.streamChat(
            request = simpleRequest(),
            onToken = {},
            onEnd = {},
            onError = { error = it }
        )

        factory.listener.onFailure(
            source,
            null,
            responseFor(simpleRequest(), 404, "missing")
        )

        assertEquals("HTTP 404：endpoint 路径不正确（应包含 /v1）", error)
    }

    private fun providerConfig() = ProviderConfig(
        id = "provider-1",
        name = "OpenAI",
        endpoint = "https://api.openai.com/v1",
        model = "gpt-4o-mini"
    )

    private fun simpleRequest(): Request =
        Request.Builder()
            .url("https://example.com/v1/chat/completions")
            .build()

    private fun syncClientFor(
        responseCode: Int = 200,
        responseBody: String = "",
        thrown: IOException? = null
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                thrown?.let { throw it }
                responseFor(chain.request(), responseCode, responseBody)
            }
            .build()
    }

    private fun responseFor(request: Request, responseCode: Int, responseBody: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(responseCode)
            .message("HTTP $responseCode")
            .body(responseBody.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private class FakeEventSourceFactory : EventSource.Factory {
        lateinit var listener: EventSourceListener

        override fun newEventSource(request: Request, listener: EventSourceListener): EventSource {
            this.listener = listener
            return FakeEventSource(request)
        }
    }

    private class FakeEventSource(private val request: Request) : EventSource {
        override fun request(): Request = request

        override fun cancel() = Unit
    }
}
