package com.github.codeplangui

import com.github.codeplangui.api.SseChunkParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SseChunkParserTest {

    @Test
    fun `parses token from standard OpenAI SSE chunk`() {
        val json = """{"id":"x","choices":[{"delta":{"content":"Hello"},"index":0}]}"""
        assertEquals("Hello", SseChunkParser.extractToken(json))
    }

    @Test
    fun `returns null for DONE sentinel`() {
        assertNull(SseChunkParser.extractToken("[DONE]"))
    }

    @Test
    fun `returns null when delta has no content field`() {
        val json = """{"choices":[{"delta":{"role":"assistant"},"index":0}]}"""
        assertNull(SseChunkParser.extractToken(json))
    }

    @Test
    fun `returns null for empty content string`() {
        val json = """{"choices":[{"delta":{"content":""},"index":0}]}"""
        assertNull(SseChunkParser.extractToken(json))
    }

    @Test
    fun `returns null for malformed json`() {
        assertNull(SseChunkParser.extractToken("not valid json"))
    }

    @Test
    fun `handles newline tokens`() {
        val json = """{"choices":[{"delta":{"content":"\n"},"index":0}]}"""
        assertEquals("\n", SseChunkParser.extractToken(json))
    }

    @Test
    fun `handles empty choices array`() {
        val json = """{"choices":[]}"""
        assertNull(SseChunkParser.extractToken(json))
    }

    // --- extractFinishReason tests ---

    @Test
    fun `extracts finish_reason length`() {
        val json = """{"id":"x","choices":[{"delta":{},"index":0,"finish_reason":"length"}]}"""
        assertEquals("length", SseChunkParser.extractFinishReason(json))
    }

    @Test
    fun `extracts finish_reason stop`() {
        val json = """{"id":"x","choices":[{"delta":{},"index":0,"finish_reason":"stop"}]}"""
        assertEquals("stop", SseChunkParser.extractFinishReason(json))
    }

    @Test
    fun `extracts finish_reason tool_calls`() {
        val json = """{"id":"x","choices":[{"delta":{},"index":0,"finish_reason":"tool_calls"}]}"""
        assertEquals("tool_calls", SseChunkParser.extractFinishReason(json))
    }

    @Test
    fun `extractFinishReason returns null for DONE sentinel`() {
        assertNull(SseChunkParser.extractFinishReason("[DONE]"))
    }

    @Test
    fun `extractFinishReason returns null when absent`() {
        val json = """{"id":"x","choices":[{"delta":{"content":"hi"},"index":0}]}"""
        assertNull(SseChunkParser.extractFinishReason(json))
    }

    @Test
    fun `extractFinishReason returns null for JSON null`() {
        val json = """{"id":"x","choices":[{"delta":{},"index":0,"finish_reason":null}]}"""
        assertNull(SseChunkParser.extractFinishReason(json))
    }

    @Test
    fun `extractFinishReason returns null for malformed json`() {
        assertNull(SseChunkParser.extractFinishReason("not json"))
    }
}
