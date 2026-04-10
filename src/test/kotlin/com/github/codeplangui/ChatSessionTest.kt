package com.github.codeplangui

import com.github.codeplangui.model.ChatSession
import com.github.codeplangui.model.Message
import com.github.codeplangui.model.MessageRole
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChatSessionTest {

    @Test
    fun `empty session returns empty api messages`() {
        val session = ChatSession()
        assertEquals(0, session.getApiMessages().size)
    }

    @Test
    fun `added messages appear in getApiMessages`() {
        val session = ChatSession()
        session.add(Message(MessageRole.USER, "hello"))
        session.add(Message(MessageRole.ASSISTANT, "hi there"))
        val msgs = session.getApiMessages()
        assertEquals(2, msgs.size)
        assertEquals("hello", msgs[0].content)
        assertEquals("hi there", msgs[1].content)
    }

    @Test
    fun `getApiMessages caps history at 40 non-system messages`() {
        val session = ChatSession()
        session.add(Message(MessageRole.SYSTEM, "system prompt"))
        repeat(25) { session.add(Message(MessageRole.USER, "user $it")) }
        repeat(25) { session.add(Message(MessageRole.ASSISTANT, "reply $it")) }

        val msgs = session.getApiMessages()
        // system + last 40 of the 50 non-system
        assertEquals(41, msgs.size)
        assertEquals(MessageRole.SYSTEM, msgs[0].role)
        assertEquals("user 10", msgs[1].content)
        assertEquals("reply 24", msgs.last().content)
    }

    @Test
    fun `clear removes all messages`() {
        val session = ChatSession()
        session.add(Message(MessageRole.USER, "hello"))
        session.clear()
        assertEquals(0, session.getApiMessages().size)
    }

    @Test
    fun `setSystemMessage replaces existing system prompt and keeps history`() {
        val session = ChatSession()
        session.setSystemMessage("system prompt 1")
        session.add(Message(MessageRole.USER, "hello"))
        session.setSystemMessage("system prompt 2")

        val msgs = session.getApiMessages()
        assertEquals(2, msgs.size)
        assertEquals(MessageRole.SYSTEM, msgs[0].role)
        assertEquals("system prompt 2", msgs[0].content)
        assertEquals("hello", msgs[1].content)
    }
}
