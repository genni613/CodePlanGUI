package com.github.codeplangui.execution

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Tracks inline change highlights for trusted file modifications.
 * In the first version, this relies on IntelliJ's built-in VCS change highlighting
 * (line markers + gutter colors), which works automatically when files are modified.
 *
 * Future iterations can add custom highlighting for AI-specific changes.
 */
class InlineChangeHighlighter(private val project: Project) {

    private val logger = Logger.getInstance(InlineChangeHighlighter::class.java)

    /**
     * Notifies the highlighter that a file was changed by a tool.
     * For now, this is a no-op — IntelliJ's built-in VCS integration
     * handles gutter change markers automatically.
     */
    fun onFileChanged(virtualFile: VirtualFile) {
        // IntelliJ's built-in line-level change tracking (changelist-based)
        // already provides gutter markers for modified files.
        // No custom highlighting needed in v1.
    }
}
