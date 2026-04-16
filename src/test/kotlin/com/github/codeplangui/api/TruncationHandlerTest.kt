package com.github.codeplangui.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TruncationHandlerTest {

    private lateinit var handler: TruncationHandler

    @BeforeEach
    fun setUp() {
        handler = TruncationHandler()
    }

    // --- reset ---

    @Test
    fun `reset clears continuation count`() {
        val buffer = StringBuilder("partial")
        handler.handleFinishReasonLength(buffer, false)
        handler.handleFinishReasonLength(buffer, false)
        assertEquals(2, handler.count)

        handler.reset()
        assertEquals(0, handler.count)
    }

    @Test
    fun `reset allows full continuation cycle again`() {
        val buffer = StringBuilder("partial")
        // Exhaust all continuations
        repeat(TruncationHandler.MAX_CONTINUATIONS + 1) {
            handler.handleFinishReasonLength(buffer, false)
        }
        // Next call after reset should auto-continue again
        handler.reset()
        val decision = handler.handleFinishReasonLength(StringBuilder("x"), false)
        assertInstanceOf(TruncationDecision.AutoContinue::class.java, decision)
    }

    // --- isPendingContinuation ---

    @Test
    fun `isPendingContinuation is false initially`() {
        assertFalse(handler.isPendingContinuation)
    }

    @Test
    fun `isPendingContinuation is true after AutoContinue`() {
        handler.handleFinishReasonLength(StringBuilder("x"), false)
        assertTrue(handler.isPendingContinuation)
    }

    @Test
    fun `isPendingContinuation is false after Exhausted`() {
        val buffer = StringBuilder("x")
        repeat(TruncationHandler.MAX_CONTINUATIONS) {
            handler.handleFinishReasonLength(buffer, false)
        }
        // MAX_CONTINUATIONS-th call returns Exhausted
        handler.handleFinishReasonLength(buffer, false)
        assertFalse(handler.isPendingContinuation)
    }

    @Test
    fun `clearPendingContinuation clears the flag`() {
        handler.handleFinishReasonLength(StringBuilder("x"), false)
        assertTrue(handler.isPendingContinuation)
        handler.clearPendingContinuation()
        assertFalse(handler.isPendingContinuation)
    }

    @Test
    fun `reset clears isPendingContinuation`() {
        handler.handleFinishReasonLength(StringBuilder("x"), false)
        assertTrue(handler.isPendingContinuation)
        handler.reset()
        assertFalse(handler.isPendingContinuation)
    }

    // --- AutoContinue decisions ---

    @Test
    fun `first truncation returns AutoContinue with count 1`() {
        val decision = handler.handleFinishReasonLength(StringBuilder("partial"), false)
        val auto = assertInstanceOf(TruncationDecision.AutoContinue::class.java, decision)
        assertEquals(1, auto.count)
        assertEquals(TruncationHandler.MAX_CONTINUATIONS, auto.max)
    }

    @Test
    fun `second truncation returns AutoContinue with count 2`() {
        handler.handleFinishReasonLength(StringBuilder("a"), false)
        val decision = handler.handleFinishReasonLength(StringBuilder("b"), false)
        val auto = assertInstanceOf(TruncationDecision.AutoContinue::class.java, decision)
        assertEquals(2, auto.count)
    }

    @Test
    fun `third truncation returns AutoContinue with count 3`() {
        handler.handleFinishReasonLength(StringBuilder("a"), false)
        handler.handleFinishReasonLength(StringBuilder("b"), false)
        val decision = handler.handleFinishReasonLength(StringBuilder("c"), false)
        val auto = assertInstanceOf(TruncationDecision.AutoContinue::class.java, decision)
        assertEquals(3, auto.count)
    }

    @Test
    fun `AutoContinue uses text prompt when no incomplete tool calls`() {
        val decision = handler.handleFinishReasonLength(StringBuilder("partial"), false)
        val auto = assertInstanceOf(TruncationDecision.AutoContinue::class.java, decision)
        assertEquals(TruncationHandler.TEXT_CONTINUATION_PROMPT, auto.continuationPrompt)
    }

    @Test
    fun `AutoContinue uses tool call prompt when incomplete tool calls exist`() {
        val decision = handler.handleFinishReasonLength(StringBuilder("partial"), true)
        val auto = assertInstanceOf(TruncationDecision.AutoContinue::class.java, decision)
        assertEquals(TruncationHandler.TOOL_CALL_CONTINUATION_PROMPT, auto.continuationPrompt)
    }

    @Test
    fun `AutoContinue does not modify responseBuffer`() {
        val buffer = StringBuilder("hello world")
        handler.handleFinishReasonLength(buffer, false)
        assertEquals("hello world", buffer.toString())
    }

    // --- Exhausted decision ---

    @Test
    fun `fourth truncation returns Exhausted`() {
        val buffer = StringBuilder("x")
        handler.handleFinishReasonLength(buffer, false)
        handler.handleFinishReasonLength(buffer, false)
        handler.handleFinishReasonLength(buffer, false)
        val decision = handler.handleFinishReasonLength(buffer, false)
        assertInstanceOf(TruncationDecision.Exhausted::class.java, decision)
    }

    @Test
    fun `Exhausted appends truncation marker to responseBuffer`() {
        val buffer = StringBuilder("partial content")
        handler.handleFinishReasonLength(buffer, false)
        handler.handleFinishReasonLength(buffer, false)
        handler.handleFinishReasonLength(buffer, false)
        handler.handleFinishReasonLength(buffer, false)

        assertTrue(buffer.toString().endsWith(TruncationHandler.TRUNCATION_MARKER))
    }

    @Test
    fun `Exhausted preserves original buffer content`() {
        val buffer = StringBuilder("partial content")
        handler.handleFinishReasonLength(buffer, false)
        handler.handleFinishReasonLength(buffer, false)
        handler.handleFinishReasonLength(buffer, false)
        handler.handleFinishReasonLength(buffer, false)

        assertTrue(buffer.toString().startsWith("partial content"))
    }

    @Test
    fun `Exhausted contains correct marker`() {
        val buffer = StringBuilder("x")
        handler.handleFinishReasonLength(buffer, false)
        handler.handleFinishReasonLength(buffer, false)
        handler.handleFinishReasonLength(buffer, false)
        val decision = handler.handleFinishReasonLength(buffer, false)
        val exhausted = assertInstanceOf(TruncationDecision.Exhausted::class.java, decision)
        assertEquals(TruncationHandler.TRUNCATION_MARKER, exhausted.marker)
    }

    // --- fifth+ truncation continues returning Exhausted ---

    @Test
    fun `fifth truncation also returns Exhausted`() {
        val buffer = StringBuilder("x")
        repeat(4) { handler.handleFinishReasonLength(buffer, false) }
        val decision = handler.handleFinishReasonLength(buffer, false)
        assertInstanceOf(TruncationDecision.Exhausted::class.java, decision)
    }

    // --- count tracking ---

    @Test
    fun `count increments with each truncation`() {
        assertEquals(0, handler.count)
        handler.handleFinishReasonLength(StringBuilder(), false)
        assertEquals(1, handler.count)
        handler.handleFinishReasonLength(StringBuilder(), false)
        assertEquals(2, handler.count)
        handler.handleFinishReasonLength(StringBuilder(), false)
        assertEquals(3, handler.count)
        handler.handleFinishReasonLength(StringBuilder(), false)
        assertEquals(4, handler.count)
    }

    // --- boundary: exactly MAX_CONTINUATIONS ---

    @Test
    fun `truncation at exactly MAX returns AutoContinue`() {
        val buffer = StringBuilder("x")
        for (i in 1 until TruncationHandler.MAX_CONTINUATIONS) {
            handler.handleFinishReasonLength(buffer, false)
        }
        // This is the Nth truncation (equal to MAX_CONTINUATIONS)
        val decision = handler.handleFinishReasonLength(buffer, false)
        assertInstanceOf(TruncationDecision.AutoContinue::class.java, decision)
        assertEquals(TruncationHandler.MAX_CONTINUATIONS, (decision as TruncationDecision.AutoContinue).count)
    }

    @Test
    fun `truncation at MAX plus 1 returns Exhausted`() {
        val buffer = StringBuilder("x")
        repeat(TruncationHandler.MAX_CONTINUATIONS) {
            handler.handleFinishReasonLength(buffer, false)
        }
        val decision = handler.handleFinishReasonLength(buffer, false)
        assertInstanceOf(TruncationDecision.Exhausted::class.java, decision)
    }

    // --- custom configuration ---

    @Test
    fun `custom maxContinuations is respected`() {
        val customHandler = TruncationHandler(maxContinuations = 1)
        val buffer = StringBuilder("x")

        // First truncation: auto-continue
        val first = customHandler.handleFinishReasonLength(buffer, false)
        assertInstanceOf(TruncationDecision.AutoContinue::class.java, first)
        assertEquals(1, (first as TruncationDecision.AutoContinue).count)

        // Second truncation: exhausted
        val second = customHandler.handleFinishReasonLength(buffer, false)
        assertInstanceOf(TruncationDecision.Exhausted::class.java, second)
    }

    @Test
    fun `custom prompts are used`() {
        val customHandler = TruncationHandler(
            textContinuationPrompt = "custom text prompt",
            toolCallContinuationPrompt = "custom tool prompt"
        )
        val textDecision = customHandler.handleFinishReasonLength(StringBuilder("x"), false)
        assertEquals("custom text prompt", (textDecision as TruncationDecision.AutoContinue).continuationPrompt)

        customHandler.reset()

        val toolDecision = customHandler.handleFinishReasonLength(StringBuilder("x"), true)
        assertEquals("custom tool prompt", (toolDecision as TruncationDecision.AutoContinue).continuationPrompt)
    }

    @Test
    fun `custom truncation marker is used`() {
        val customHandler = TruncationHandler(truncationMarker = "[CUSTOM]")
        val buffer = StringBuilder("x")
        repeat(TruncationHandler.MAX_CONTINUATIONS) {
            customHandler.handleFinishReasonLength(buffer, false)
        }
        customHandler.handleFinishReasonLength(buffer, false)
        assertTrue(buffer.toString().endsWith("[CUSTOM]"))
    }

    // --- constants ---

    @Test
    fun `MAX_CONTINUATIONS is 3`() {
        assertEquals(3, TruncationHandler.MAX_CONTINUATIONS)
    }

    @Test
    fun `TEXT_CONTINUATION_PROMPT is non-blank`() {
        assertTrue(TruncationHandler.TEXT_CONTINUATION_PROMPT.isNotBlank())
    }

    @Test
    fun `TOOL_CALL_CONTINUATION_PROMPT is non-blank`() {
        assertTrue(TruncationHandler.TOOL_CALL_CONTINUATION_PROMPT.isNotBlank())
    }

    @Test
    fun `TRUNCATION_MARKER is non-blank`() {
        assertTrue(TruncationHandler.TRUNCATION_MARKER.isNotBlank())
    }

    @Test
    fun `text and tool call prompts are different`() {
        assertNotEquals(TruncationHandler.TEXT_CONTINUATION_PROMPT, TruncationHandler.TOOL_CALL_CONTINUATION_PROMPT)
    }

    // --- empty buffer edge case ---

    @Test
    fun `handles empty responseBuffer on truncation`() {
        val buffer = StringBuilder("")
        val decision = handler.handleFinishReasonLength(buffer, false)
        assertInstanceOf(TruncationDecision.AutoContinue::class.java, decision)
    }

    @Test
    fun `handles empty responseBuffer on exhaustion`() {
        val buffer = StringBuilder("")
        repeat(TruncationHandler.MAX_CONTINUATIONS) {
            handler.handleFinishReasonLength(buffer, false)
        }
        handler.handleFinishReasonLength(buffer, false)
        assertEquals(TruncationHandler.TRUNCATION_MARKER, buffer.toString())
    }
}
