package com.github.codeplangui.api

/**
 * Manages model output truncation (finish_reason="length") state and decisions.
 *
 * Flow:
 *   finish_reason="length"
 *     → auto-continue (up to [maxContinuations] times):
 *        inject continuation prompt → new streaming round → seamless merge
 *     → max reached:
 *        append truncation marker to the response buffer
 */
internal class TruncationHandler(
    private val maxContinuations: Int = MAX_CONTINUATIONS,
    private val textContinuationPrompt: String = TEXT_CONTINUATION_PROMPT,
    private val toolCallContinuationPrompt: String = TOOL_CALL_CONTINUATION_PROMPT,
    private val truncationMarker: String = TRUNCATION_MARKER
) {
    private var continuationCount = 0

    /** True after an AutoContinue decision, cleared when the continuation round begins. */
    var isPendingContinuation: Boolean = false
        private set

    fun reset() {
        continuationCount = 0
        isPendingContinuation = false
    }

    fun clearPendingContinuation() {
        isPendingContinuation = false
    }

    val count: Int get() = continuationCount

    /**
     * Called when finish_reason="length" is received.
     * Returns the appropriate action for the caller to take.
     */
    fun handleFinishReasonLength(
        responseBuffer: StringBuilder,
        hasIncompleteToolCalls: Boolean
    ): TruncationDecision {
        continuationCount++
        return if (continuationCount <= maxContinuations) {
            isPendingContinuation = true
            TruncationDecision.AutoContinue(
                count = continuationCount,
                max = maxContinuations,
                continuationPrompt = if (hasIncompleteToolCalls) toolCallContinuationPrompt else textContinuationPrompt
            )
        } else {
            isPendingContinuation = false
            responseBuffer.append(truncationMarker)
            TruncationDecision.Exhausted(marker = truncationMarker)
        }
    }

    companion object {
        const val MAX_CONTINUATIONS = 3
        const val TEXT_CONTINUATION_PROMPT = "请从中断处继续。不要道歉、不要复述已经输出的内容，直接从中断处继续。"
        const val TOOL_CALL_CONTINUATION_PROMPT = "你上次生成工具调用参数时被截断了。请重新生成完整的工具调用。"
        const val TRUNCATION_MARKER = "\n\n[...内容因长度限制被截断...]"
    }
}

internal sealed class TruncationDecision {
    /** Auto-continue: suppress onEnd, inject continuation prompt, start new round. */
    data class AutoContinue(
        val count: Int,
        val max: Int,
        val continuationPrompt: String
    ) : TruncationDecision()

    /** Max continuations reached: marker appended to responseBuffer. */
    data class Exhausted(val marker: String) : TruncationDecision()
}
