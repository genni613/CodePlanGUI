package com.github.codeplangui

import com.github.codeplangui.settings.ProviderDialog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProviderDialogApiKeyTest {

    @Test
    fun `normalizeApiKeyInput trims non blank input`() {
        assertEquals("secret-key", ProviderDialog.normalizeApiKeyInput("  secret-key  "))
    }

    @Test
    fun `normalizeApiKeyInput returns null for blank input`() {
        assertNull(ProviderDialog.normalizeApiKeyInput("   "))
    }
}
