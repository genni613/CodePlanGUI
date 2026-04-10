package com.github.codeplangui.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var endpoint: String = "",
    var model: String = ""
)

@Serializable
data class SettingsState(
    var providers: MutableList<ProviderConfig> = mutableListOf(),
    var activeProviderId: String? = null,
    var chatTemperature: Double = 0.7,
    var chatMaxTokens: Int = 4096,
    var commitLanguage: String = "zh",
    var commitFormat: String = "conventional",
    var contextInjectionEnabled: Boolean = true,
    var contextMaxLines: Int = 300
)

@State(
    name = "CodePlanGUISettings",
    storages = [Storage("codePlanGUI.xml")]
)
@Service(Service.Level.APP)
class PluginSettings : PersistentStateComponent<SettingsState> {
    private var state = SettingsState()

    override fun getState(): SettingsState = state

    override fun loadState(state: SettingsState) {
        this.state = state
    }

    fun getActiveProvider(): ProviderConfig? =
        state.providers.find { it.id == state.activeProviderId }

    companion object {
        fun getInstance(): PluginSettings =
            com.intellij.openapi.application.ApplicationManager
                .getApplication()
                .getService(PluginSettings::class.java)
    }
}
