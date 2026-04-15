package com.github.codeplangui.action

import com.github.codeplangui.api.OkHttpSseClient
import com.github.codeplangui.settings.ApiKeyStore
import com.github.codeplangui.settings.PluginSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangesUtil
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Method
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

class GenerateCommitMessageAction : AnAction() {
    private val client = OkHttpSseClient()

    override fun update(e: AnActionEvent) {
        val project = e.project
        val provider = if (project != null) PluginSettings.getInstance().getActiveProvider() else null
        e.presentation.isEnabled = project != null && provider != null
        e.presentation.description = if (provider == null) {
            "请先在 Settings > Tools > CodePlanGUI 配置 API Provider"
        } else {
            "使用 ${provider.name} 生成 Commit Message"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val activeProvider = PluginSettings.getInstance().getActiveProvider() ?: run {
            Messages.showErrorDialog(project, "请先配置 API Provider", "CodePlanGUI")
            return
        }
        if (project == null) {
            return
        }
        val apiKey = ApiKeyStore.load(activeProvider.id) ?: ""
        if (apiKey.isBlank()) {
            Messages.showErrorDialog(project, "当前 Provider 尚未配置 API Key", "CodePlanGUI")
            return
        }
        val settings = PluginSettings.getInstance().getState()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "生成 Commit Message...") {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val projectDir = project.basePath ?: return

                // Get selected files (from Git Commit dialog or all staged)
                val selectedFiles = buildSelectedFiles(e, project)

                if (selectedFiles.isEmpty()) {
                    // Fallback: use git diff --staged
                    val stagedDiff = readStagedDiff(projectDir)
                    if (stagedDiff.isBlank()) {
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showInfoMessage(project, "没有可用于生成 commit 的变更（请先勾选或 git add）", "CodePlanGUI")
                        }
                        return
                    }
                    // Fallback to old single-stage generation using raw diff
                    generateFromDiff(stagedDiff, settings, e, project, activeProvider, apiKey, indicator)
                    return
                }

                // Two-stage generation based on selected files
                generateTwoStage(selectedFiles, settings, e, project, activeProvider, apiKey, indicator)
            }
        })
    }

    private fun generateTwoStage(
        files: List<CommitPromptFile>,
        settings: com.github.codeplangui.settings.SettingsState,
        e: AnActionEvent,
        project: com.intellij.openapi.project.Project,
        activeProvider: com.github.codeplangui.settings.ProviderConfig,
        apiKey: String,
        indicator: com.intellij.openapi.progress.ProgressIndicator
    ) {
        val generator = TwoStageCommitGenerator(client, activeProvider, apiKey)

        val result = runBlocking {
            generator.generate(files, settings, indicator)
        }

        ApplicationManager.getApplication().invokeLater {
            result.fold(
                onSuccess = { generated ->
                    val cleaned = CommitPromptBuilder.stripThinkContent(generated)
                    applyCommitMessage(e, project, cleaned.trim())
                },
                onFailure = { err ->
                    Messages.showErrorDialog(project, err.message ?: "API 调用失败", "生成失败")
                }
            )
        }
    }

    private fun generateFromDiff(
        diff: String,
        settings: com.github.codeplangui.settings.SettingsState,
        e: AnActionEvent,
        project: com.intellij.openapi.project.Project,
        activeProvider: com.github.codeplangui.settings.ProviderConfig,
        apiKey: String,
        indicator: com.intellij.openapi.progress.ProgressIndicator
    ) {
        // Use the same prompt style as two-stage generation for consistent output
        val systemPrompt = CommitPromptBuilder.buildStage2Prompt(settings.commitLanguage)
        val userMessage = CommitPromptBuilder.buildSingleStageUserMessage(diff, settings.commitLanguage)
        val messages = listOf(
            com.github.codeplangui.model.Message(com.github.codeplangui.model.MessageRole.SYSTEM, systemPrompt),
            com.github.codeplangui.model.Message(com.github.codeplangui.model.MessageRole.USER, userMessage)
        )
        val request = client.buildRequest(
            config = activeProvider,
            apiKey = apiKey,
            messages = messages,
            temperature = 0.3,
            maxTokens = 500,
            stream = false
        )

        val result = client.callCommitSync(request)
        ApplicationManager.getApplication().invokeLater {
            result.fold(
                onSuccess = { generated ->
                    val cleaned = CommitPromptBuilder.stripThinkContent(generated)
                    applyCommitMessage(e, project, cleaned.trim())
                },
                onFailure = { err ->
                    Messages.showErrorDialog(project, err.message ?: "API 调用失败", "生成失败")
                }
            )
        }
    }

    private fun buildSelectedFiles(e: AnActionEvent, project: com.intellij.openapi.project.Project): List<CommitPromptFile> {
        val changes = getSelectedChanges(e, project)
        if (changes.isEmpty()) return emptyList()

        return changes.mapNotNull { change ->
            try {
                CommitPromptFile(
                    path = ChangesUtil.getFilePath(change).path,
                    changeType = change.type.name,
                    beforeContent = change.beforeRevision?.content,
                    afterContent = change.afterRevision?.content
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun getSelectedChanges(
        e: AnActionEvent,
        project: com.intellij.openapi.project.Project
    ): Collection<Change> {
        val workflowHandler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER)
        val workflowChanges = workflowHandler?.let(::getIncludedChangesViaReflection)
        if (!workflowChanges.isNullOrEmpty()) {
            return workflowChanges
        }

        val directChanges = e.getData(VcsDataKeys.CHANGES)
        if (!directChanges.isNullOrEmpty()) {
            return directChanges.asList()
        }

        return ChangeListManager.getInstance(project).allChanges
    }

    private fun getIncludedChangesViaReflection(workflowHandler: Any): Collection<Change>? {
        return try {
            val getUiMethod: Method = workflowHandler.javaClass.getMethod("getUi")
            val ui = getUiMethod.invoke(workflowHandler) ?: return null
            val getIncludedChangesMethod: Method = ui.javaClass.getMethod("getIncludedChanges")
            val result = getIncludedChangesMethod.invoke(ui) as? Collection<*> ?: return null
            result.filterIsInstance<Change>()
        } catch (_: Exception) {
            null
        }
    }

    private fun readStagedDiff(projectDir: String): String {
        return try {
            ProcessBuilder("git", "diff", "--staged", "--no-color")
                .directory(File(projectDir))
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun applyCommitMessage(
        e: AnActionEvent,
        project: com.intellij.openapi.project.Project,
        message: String
    ) {
        val commitMessageControl = VcsDataKeys.COMMIT_MESSAGE_CONTROL.getData(e.dataContext)
        if (commitMessageControl != null) {
            commitMessageControl.setCommitMessage(message)
            return
        }

        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(message), null)
        Messages.showInfoMessage(
            project,
            "Commit Message 已复制到剪贴板（未找到提交对话框，请手动粘贴）",
            "CodePlanGUI"
        )
    }
}
