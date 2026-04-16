package com.github.codeplangui.settings

import com.github.codeplangui.execution.ShellPlatform

data class SettingsFormState(
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
) {
    fun toSettingsState(): SettingsState = SettingsState(
        providers = providers.toMutableList(),
        activeProviderId = normalizeActiveProviderId(providers, activeProviderId),
        chatTemperature = chatTemperature,
        chatMaxTokens = chatMaxTokens,
        commitLanguage = commitLanguage,
        commitFormat = commitFormat,
        commitMultiMode = commitMultiMode,
        commitMaxFiles = commitMaxFiles,
        commitDiffLineLimit = commitDiffLineLimit,
        contextInjectionEnabled = contextInjectionEnabled,
        contextMaxLines = contextMaxLines,
        memoryText = memoryText,
        commandExecutionEnabled = commandExecutionEnabled,
        commandWhitelist = commandWhitelist.toMutableList(),
        commandTimeoutSeconds = commandTimeoutSeconds,
    )

    companion object {
        fun fromSettingsState(state: SettingsState): SettingsFormState = SettingsFormState(
            providers = state.providers.toMutableList(),
            activeProviderId = normalizeActiveProviderId(state.providers, state.activeProviderId),
            chatTemperature = state.chatTemperature,
            chatMaxTokens = state.chatMaxTokens,
            commitLanguage = state.commitLanguage,
            commitFormat = state.commitFormat,
            commitMultiMode = state.commitMultiMode,
            commitMaxFiles = state.commitMaxFiles,
            commitDiffLineLimit = state.commitDiffLineLimit,
            contextInjectionEnabled = state.contextInjectionEnabled,
            contextMaxLines = state.contextMaxLines,
            memoryText = state.memoryText,
            commandExecutionEnabled = state.commandExecutionEnabled,
            commandWhitelist = state.commandWhitelist.toMutableList(),
            commandTimeoutSeconds = state.commandTimeoutSeconds,
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
