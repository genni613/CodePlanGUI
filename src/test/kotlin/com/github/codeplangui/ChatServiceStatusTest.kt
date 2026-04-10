package com.github.codeplangui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatServiceStatusTest {

    @Test
    fun `deriveConnectionState returns unconfigured when no provider exists`() {
        assertEquals("unconfigured", deriveConnectionState(hasProvider = false, hasApiKey = false, isStreaming = false))
    }

    @Test
    fun `deriveConnectionState returns error when provider exists but key is missing`() {
        assertEquals("error", deriveConnectionState(hasProvider = true, hasApiKey = false, isStreaming = false))
    }

    @Test
    fun `deriveConnectionState returns ready when provider and key exist and no request is active`() {
        assertEquals("ready", deriveConnectionState(hasProvider = true, hasApiKey = true, isStreaming = false))
    }

    @Test
    fun `deriveConnectionState returns streaming while request is active`() {
        assertEquals("streaming", deriveConnectionState(hasProvider = true, hasApiKey = true, isStreaming = true))
    }
}
