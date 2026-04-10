package com.github.codeplangui.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class MessageRole {
    @SerialName("system")
    SYSTEM,

    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT
}

@Serializable
data class Message(
    val role: MessageRole,
    val content: String
)
