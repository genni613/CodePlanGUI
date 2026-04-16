package com.github.codeplangui.settings

import com.github.codeplangui.execution.ShellPlatform
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ProviderConfig(
    var id: String = UUID.randomUUID().toString(),
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
    var commitMultiMode: String = "merge",
    var commitMaxFiles: Int = 20,
    var commitDiffLineLimit: Int = 500,
    var contextInjectionEnabled: Boolean = true,
    var contextMaxLines: Int = 300,
    var memoryText: String = "",
    var commandExecutionEnabled: Boolean = true,
    var commandWhitelist: MutableList<String> = ShellPlatform.current().defaultWhitelist().toMutableList(),
    var commandTimeoutSeconds: Int = 30
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
        val migratedProviders = recoverLegacyProviderIds(
            providers = state.providers.toMutableList(),
            activeProviderId = state.activeProviderId,
            hasApiKey = { providerId ->
                try {
                    !ApiKeyStore.load(providerId).isNullOrBlank()
                } catch (_: Exception) {
                    false
                }
            }
        )
        this.state = SettingsFormState.fromSettingsState(
            state.copy(providers = migratedProviders)
        ).toSettingsState()
    }

    fun getActiveProvider(): ProviderConfig? =
        state.providers.find { it.id == state.activeProviderId } ?: state.providers.firstOrNull()

    companion object {
        fun getInstance(): PluginSettings =
            com.intellij.openapi.application.ApplicationManager
                .getApplication()
                .getService(PluginSettings::class.java)
    }
}

internal fun recoverLegacyProviderIds(
    providers: MutableList<ProviderConfig>,
    activeProviderId: String?,
    hasApiKey: (String) -> Boolean
): MutableList<ProviderConfig> {
    if (providers.size != 1 || activeProviderId.isNullOrBlank()) {
        return providers
    }

    val provider = providers.single()
    if (provider.id == activeProviderId || hasApiKey(provider.id) || !hasApiKey(activeProviderId)) {
        return providers
    }

    provider.id = activeProviderId
    return providers
}
