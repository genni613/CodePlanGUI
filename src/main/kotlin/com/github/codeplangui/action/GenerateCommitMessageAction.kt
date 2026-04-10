package com.github.codeplangui.action

import com.github.codeplangui.api.OkHttpSseClient
import com.github.codeplangui.model.Message
import com.github.codeplangui.model.MessageRole
import com.github.codeplangui.settings.ApiKeyStore
import com.github.codeplangui.settings.PluginSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsDataKeys
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
        val provider = PluginSettings.getInstance().getActiveProvider() ?: run {
            Messages.showErrorDialog(project, "请先配置 API Provider", "CodePlanGUI")
            return
        }
        if (project == null) {
            return
        }
        val apiKey = ApiKeyStore.load(provider.id) ?: ""
        if (apiKey.isBlank()) {
            Messages.showErrorDialog(project, "当前 Provider 尚未配置 API Key", "CodePlanGUI")
            return
        }
        val settings = PluginSettings.getInstance().getState()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "生成 Commit Message...") {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val projectDir = project.basePath ?: return
                val diff = readStagedDiff(projectDir)
                if (diff.isBlank()) {
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        Messages.showInfoMessage(project, "没有 staged 的改动（请先 git add）", "CodePlanGUI")
                    }
                    return
                }

                val systemPrompt = CommitPromptBuilder.buildSystemPrompt(settings.commitLanguage, settings.commitFormat)
                val userMessage = CommitPromptBuilder.buildUserMessage(diff, settings.commitLanguage)
                val messages = listOf(
                    Message(MessageRole.SYSTEM, systemPrompt),
                    Message(MessageRole.USER, userMessage)
                )
                val request = client.buildRequest(
                    config = provider,
                    apiKey = apiKey,
                    messages = messages,
                    temperature = 0.3,
                    maxTokens = 500,
                    stream = false
                )

                val result = client.callSync(request)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    result.fold(
                        onSuccess = { generated ->
                            applyCommitMessage(e, project, generated.trim())
                        },
                        onFailure = { err ->
                            Messages.showErrorDialog(project, err.message ?: "API 调用失败", "生成失败")
                        }
                    )
                }
            }
        })
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
