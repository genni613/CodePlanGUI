package com.github.codeplangui.action

import com.github.codeplangui.ChatService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

class AskAiAction : AnAction("Ask AI") {

    override fun update(e: AnActionEvent) {
        val selection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText
        e.presentation.isEnabledAndVisible = e.project != null && !selection.isNullOrBlank()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText?.trim()
        if (selection.isNullOrBlank()) {
            Messages.showInfoMessage(project, "请先选中一段代码或文本", "CodePlanGUI")
            return
        }

        ToolWindowManager.getInstance(project).getToolWindow("CodePlanGUI")?.show(null)
        ChatService.getInstance(project).askAboutSelection(selection)
    }
}
