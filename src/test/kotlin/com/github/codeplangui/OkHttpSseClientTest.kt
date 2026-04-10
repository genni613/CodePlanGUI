package com.github.codeplangui

import com.github.codeplangui.api.OkHttpSseClient
import com.github.codeplangui.model.Message
import com.github.codeplangui.model.MessageRole
import com.github.codeplangui.settings.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
}
