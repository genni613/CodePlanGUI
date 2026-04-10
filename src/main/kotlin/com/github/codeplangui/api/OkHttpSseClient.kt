package com.github.codeplangui.api

import com.github.codeplangui.model.Message
import com.github.codeplangui.model.MessageRole
import com.github.codeplangui.settings.ProviderConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

@Serializable
private data class ApiMessage(val role: String, val content: String)

@Serializable
private data class ChatRequestBody(
    val model: String,
    val messages: List<ApiMessage>,
    val stream: Boolean,
    val temperature: Double,
    val max_tokens: Int
)

sealed class TestResult {
    data object Success : TestResult()
    data class Failure(val message: String) : TestResult()
}

class OkHttpSseClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val syncClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun buildRequest(
        config: ProviderConfig,
        apiKey: String,
        messages: List<Message>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean
    ): Request {
        val endpoint = config.endpoint.trimEnd('/') + "/chat/completions"
        val apiMessages = messages.map {
            ApiMessage(it.role.name.lowercase(), it.content)
        }
        val body = json.encodeToString(
            ChatRequestBody(
                model = config.model,
                messages = apiMessages,
                stream = stream,
                temperature = temperature,
                max_tokens = maxTokens
            )
        )
        return Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .build()
    }

    fun streamChat(
        request: Request,
        onToken: (String) -> Unit,
        onEnd: () -> Unit,
        onError: (String) -> Unit
    ): EventSource {
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val token = SseChunkParser.extractToken(data)
                if (token != null) onToken(token)
                if (data.trim() == "[DONE]") onEnd()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val msg = buildErrorMessage(response, t)
                onError(msg)
            }
        }
        return EventSources.createFactory(streamClient).newEventSource(request, listener)
    }

    fun callSync(request: Request): Result<String> {
        return try {
            val response = syncClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Result.failure(Exception(buildErrorMessage(response, null)))
            } else {
                val body = response.body?.string() ?: ""
                val content = extractSyncContent(body)
                Result.success(content)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun testConnection(config: ProviderConfig, apiKey: String): TestResult {
        val testMessages = listOf(Message(MessageRole.USER, "hi"))
        val request = buildRequest(config, apiKey, testMessages, 0.0, 1, false)
        return try {
            val response = syncClient.newCall(request).execute()
            if (response.isSuccessful) TestResult.Success
            else TestResult.Failure("HTTP ${response.code}: ${response.body?.string()?.take(200)}")
        } catch (e: java.net.SocketTimeoutException) {
            TestResult.Failure("连接超时（5s）：请检查 endpoint 是否可访问")
        } catch (e: java.net.ConnectException) {
            TestResult.Failure("无法连接：请检查 endpoint URL")
        } catch (e: Exception) {
            TestResult.Failure(e.message ?: "未知错误")
        }
    }

    private fun buildErrorMessage(response: Response?, t: Throwable?): String = when {
        response != null -> when (response.code) {
            401 -> "HTTP 401：API Key 无效或已过期"
            403 -> "HTTP 403：访问被拒绝，请检查 endpoint 和 Key"
            404 -> "HTTP 404：endpoint 路径不正确（应包含 /v1）"
            422, 400 -> "HTTP ${response.code}：请求格式错误，请检查 model 名称"
            429 -> "HTTP 429：已触发限流，请稍候再试"
            in 500..599 -> "HTTP ${response.code}：服务端错误"
            else -> "HTTP ${response.code}: ${response.body?.string()?.take(200)}"
        }
        t is java.net.SocketTimeoutException -> "请求超时（60s），请检查网络"
        t is java.net.ConnectException -> "无法连接 endpoint，请检查 URL 和网络"
        t != null -> t.message ?: "未知网络错误"
        else -> "未知错误"
    }

    private fun extractSyncContent(body: String): String {
        return try {
            val parsed = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(body)
                .let {
                    it.toString().let { _ ->
                        Json { ignoreUnknownKeys = true }
                            .decodeFromString<kotlinx.serialization.json.JsonObject>(body)
                    }
                }
            parsed["choices"]
                ?.let { it as? kotlinx.serialization.json.JsonArray }
                ?.firstOrNull()
                ?.let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("message")
                ?.let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("content")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.content ?: body
        } catch (_: Exception) {
            body
        }
    }
}
