package com.github.codeplangui.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

enum class MessageRole {
    @SerialName("system")
    SYSTEM,

    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,

    @SerialName("tool")
    TOOL
}

@Serializable
data class ToolCallRecord(
    val id: String,
    val functionName: String,
    val arguments: String
)

@Serializable
data class Message(
    val role: MessageRole,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCallRecord>? = null,
    val id: String = UUID.randomUUID().toString(),
    val seq: Int = 0,
    /** If true, this message is internal (e.g. auto-continuation prompt) and should not be shown to the user. */
    val hidden: Boolean = false
)
