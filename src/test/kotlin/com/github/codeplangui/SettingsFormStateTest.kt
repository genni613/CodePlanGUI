package com.github.codeplangui

import com.github.codeplangui.settings.ProviderConfig
import com.github.codeplangui.settings.SettingsFormState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SettingsFormStateTest {

    @Test
    fun `toSettingsState keeps selected provider and advanced settings`() {
        val first = ProviderConfig(id = "provider-1", name = "First", endpoint = "https://one.example/v1", model = "model-a")
        val second = ProviderConfig(id = "provider-2", name = "Second", endpoint = "https://two.example/v1", model = "model-b")

        val state = SettingsFormState(
            providers = mutableListOf(first, second),
            activeProviderId = second.id,
            chatTemperature = 1.2,
            chatMaxTokens = 2048,
            commitLanguage = "en",
            commitFormat = "freeform"
        ).toSettingsState()

        assertEquals(second.id, state.activeProviderId)
        assertEquals(1.2, state.chatTemperature)
        assertEquals(2048, state.chatMaxTokens)
        assertEquals("en", state.commitLanguage)
        assertEquals("freeform", state.commitFormat)
    }

    @Test
    fun `toSettingsState falls back to first provider when active selection is invalid`() {
        val first = ProviderConfig(id = "provider-1", name = "First", endpoint = "https://one.example/v1", model = "model-a")
        val second = ProviderConfig(id = "provider-2", name = "Second", endpoint = "https://two.example/v1", model = "model-b")

        val state = SettingsFormState(
            providers = mutableListOf(first, second),
            activeProviderId = "missing"
        ).toSettingsState()

        assertEquals(first.id, state.activeProviderId)
    }

    @Test
    fun `toSettingsState keeps active provider empty when provider list is empty`() {
        val state = SettingsFormState(
            providers = mutableListOf(),
            activeProviderId = "missing"
        ).toSettingsState()

        assertNull(state.activeProviderId)
    }
}
