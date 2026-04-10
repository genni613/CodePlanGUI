package com.github.codeplangui

import com.github.codeplangui.settings.PluginSettings
import com.github.codeplangui.settings.ProviderConfig
import com.github.codeplangui.settings.SettingsState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PluginSettingsTest {

    @Test
    fun `getActiveProvider returns provider matching active id`() {
        val first = ProviderConfig(id = "provider-1", name = "First", endpoint = "https://one.example/v1", model = "model-a")
        val second = ProviderConfig(id = "provider-2", name = "Second", endpoint = "https://two.example/v1", model = "model-b")

        val settings = PluginSettings()
        settings.loadState(
            SettingsState(
                providers = mutableListOf(first, second),
                activeProviderId = second.id
            )
        )

        assertEquals(second, settings.getActiveProvider())
    }

    @Test
    fun `getActiveProvider returns null when no provider matches active id`() {
        val settings = PluginSettings()
        settings.loadState(
            SettingsState(
                providers = mutableListOf(
                    ProviderConfig(id = "provider-1", name = "First", endpoint = "https://one.example/v1", model = "model-a")
                ),
                activeProviderId = "missing"
            )
        )

        assertNull(settings.getActiveProvider())
    }
}
