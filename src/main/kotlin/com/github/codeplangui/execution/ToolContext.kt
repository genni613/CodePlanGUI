package com.github.codeplangui.execution

import com.github.codeplangui.settings.SettingsState
import com.intellij.openapi.project.Project

/**
 * Execution context passed to every tool executor.
 */
data class ToolContext(
    val project: Project,
    val cwd: String,
    val settings: SettingsState
)
