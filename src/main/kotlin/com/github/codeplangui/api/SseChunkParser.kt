package com.github.codeplangui.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object SseChunkParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Extracts the text token from an OpenAI-compatible SSE data line.
     * Returns null for [DONE], empty content, or any parse error.
     */
    fun extractToken(data: String): String? {
        if (data.trim() == "[DONE]") return null
        return try {
            val obj = json.parseToJsonElement(data).jsonObject
            val content = obj["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("delta")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.contentOrNull
            content?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
