package com.github.codeplangui.model

import java.util.UUID

class ChatSession(val threadId: String = UUID.randomUUID().toString()) {
    private val messages = mutableListOf<Message>()

    fun add(msg: Message) {
        messages.add(msg)
    }

    fun getMessages(): List<Message> = messages.toList()

    /** Returns system messages + last 40 non-system messages. */
    fun getApiMessages(): List<Message> {
        val system = messages.filter { it.role == MessageRole.SYSTEM }
        val history = messages.filter { it.role != MessageRole.SYSTEM }.takeLast(40)
        return system + history
    }

    fun setSystemMessage(content: String) {
        messages.removeAll { it.role == MessageRole.SYSTEM }
        messages.add(0, Message(MessageRole.SYSTEM, content))
    }

    fun clear() {
        messages.clear()
    }

    fun nextSeq(): Int = messages.filter { it.role != MessageRole.SYSTEM }.size
}
