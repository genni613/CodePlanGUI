package com.github.codeplangui.settings

data class SettingsFormState(
    var providers: MutableList<ProviderConfig> = mutableListOf(),
    var activeProviderId: String? = null,
    var chatTemperature: Double = 0.7,
    var chatMaxTokens: Int = 4096,
    var commitLanguage: String = "zh",
    var commitFormat: String = "conventional",
    var contextInjectionEnabled: Boolean = true,
    var contextMaxLines: Int = 300,
    var memoryText: String = ""
) {
    fun toSettingsState(): SettingsState = SettingsState(
        providers = providers.toMutableList(),
        activeProviderId = normalizeActiveProviderId(providers, activeProviderId),
        chatTemperature = chatTemperature,
        chatMaxTokens = chatMaxTokens,
        commitLanguage = commitLanguage,
        commitFormat = commitFormat,
        contextInjectionEnabled = contextInjectionEnabled,
        contextMaxLines = contextMaxLines,
        memoryText = memoryText
    )

    companion object {
        fun fromSettingsState(state: SettingsState): SettingsFormState = SettingsFormState(
            providers = state.providers.toMutableList(),
            activeProviderId = normalizeActiveProviderId(state.providers, state.activeProviderId),
            chatTemperature = state.chatTemperature,
            chatMaxTokens = state.chatMaxTokens,
            commitLanguage = state.commitLanguage,
            commitFormat = state.commitFormat,
            contextInjectionEnabled = state.contextInjectionEnabled,
            contextMaxLines = state.contextMaxLines,
            memoryText = state.memoryText
        )
    }
}

internal fun normalizeActiveProviderId(
    providers: List<ProviderConfig>,
    activeProviderId: String?
): String? {
    if (providers.isEmpty()) return null
    return providers.firstOrNull { it.id == activeProviderId }?.id ?: providers.first().id
}
