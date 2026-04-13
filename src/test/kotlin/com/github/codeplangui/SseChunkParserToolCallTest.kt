package com.github.codeplangui

import com.github.codeplangui.api.SseChunkParser
import com.github.codeplangui.api.ToolCallDelta
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SseChunkParserToolCallTest {

    @Test
    fun `extractToolCallChunk returns delta on first tool_call chunk`() {
        val data = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","function":{"name":"run_command","arguments":""}}]},"finish_reason":null}]}"""
        val delta = SseChunkParser.extractToolCallChunk(data)
        assertNotNull(delta)
        assertEquals("call_abc", delta!!.id)
        assertEquals("run_command", delta.functionName)
        assertEquals("", delta.argumentsChunk)
    }

    @Test
    fun `extractToolCallChunk returns arguments chunk on subsequent chunks`() {
        val data = """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"co"}}]},"finish_reason":null}]}"""
        val delta = SseChunkParser.extractToolCallChunk(data)
        assertNotNull(delta)
        assertNull(delta!!.id)
        assertEquals("{\"co", delta.argumentsChunk)
    }

    @Test
    fun `extractToolCallChunk returns null for text delta`() {
        val data = """{"choices":[{"delta":{"content":"hello"},"finish_reason":null}]}"""
        assertNull(SseChunkParser.extractToolCallChunk(data))
    }

    @Test
    fun `extractFinishReason returns stop for normal end`() {
        val data = """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        assertEquals("stop", SseChunkParser.extractFinishReason(data))
    }

    @Test
    fun `extractFinishReason returns tool_calls on tool invocation end`() {
        val data = """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
        assertEquals("tool_calls", SseChunkParser.extractFinishReason(data))
    }

    @Test
    fun `extractFinishReason returns null when finish_reason is absent`() {
        val data = """{"choices":[{"delta":{"content":"hi"},"finish_reason":null}]}"""
        assertNull(SseChunkParser.extractFinishReason(data))
    }
}
