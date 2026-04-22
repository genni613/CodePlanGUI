package com.github.codeplangui.execution

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color
import javax.swing.Timer

/**
 * Tracks inline change highlights for trusted file modifications.
 *
 * §8.4 — In trust mode, file changes are applied directly and visualized in the editor:
 * - Added lines: green background highlight
 * - Modified lines: blue background highlight
 *
 * Changes are also visualized through IntelliJ's built-in VCS change highlighting
 * (gutter markers), which activates automatically when files are modified.
 * Users can undo any change with Ctrl+Z.
 */
class InlineChangeHighlighter(private val project: Project) {

    private val logger = Logger.getInstance(InlineChangeHighlighter::class.java)

    /** Tracks original content before tool-written changes, keyed by file path. */
    private val originalSnapshots = mutableMapOf<String, String>()

    /**
     * Notifies the highlighter that a file is about to be changed by a tool.
     * Stores the original content for diff computation after write.
     */
    fun beforeFileChanged(virtualFile: VirtualFile) {
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
        originalSnapshots[virtualFile.path] = document.text
    }

    /**
     * Notifies the highlighter that a file was changed by a tool.
     * Computes the diff and applies inline highlights in the editor.
     * Also opens the file in the editor if not already open.
     */
    fun onFileChanged(virtualFile: VirtualFile) {
        val originalContent = originalSnapshots.remove(virtualFile.path) ?: return

        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
        val newContent = document.text

        // Open file in editor and apply highlights
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor != null && editor.document == document) {
            applyChangeHighlights(editor, originalContent, newContent)
        } else {
            // Open the file in editor first, then highlight
            val descriptor = OpenFileDescriptor(project, virtualFile)
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
                    ?.let { applyChangeHighlights(it, originalContent, newContent) }
            }
        }
    }

    /**
     * Apply highlighters to show added/modified lines.
     * Green bg for added lines, blue bg for modified lines.
     */
    private fun applyChangeHighlights(editor: Editor, oldContent: String, newContent: String) {
        val oldLines = oldContent.lines()
        val newLines = newContent.lines()

        val changedLineIndices = computeChangedLines(oldLines, newLines)
        val markupModel = editor.markupModel
        val document = editor.document

        val addedAttrs = TextAttributes().apply {
            backgroundColor = Color(200, 255, 200) // Light green
        }
        val modifiedAttrs = TextAttributes().apply {
            backgroundColor = Color(200, 220, 255) // Light blue
        }

        // Collect our own highlighters so we only remove these, not others
        val ourHighlighters = mutableListOf<RangeHighlighter>()

        for (lineIdx in changedLineIndices.added) {
            if (lineIdx < document.lineCount) {
                val startOffset = document.getLineStartOffset(lineIdx)
                val endOffset = document.getLineEndOffset(lineIdx)
                val highlighter = markupModel.addRangeHighlighter(
                    startOffset,
                    endOffset,
                    HighlighterLayer.SELECTION - 1,
                    addedAttrs,
                    HighlighterTargetArea.LINES_IN_RANGE
                )
                ourHighlighters.add(highlighter)
            }
        }

        for (lineIdx in changedLineIndices.modified) {
            if (lineIdx < document.lineCount) {
                val startOffset = document.getLineStartOffset(lineIdx)
                val endOffset = document.getLineEndOffset(lineIdx)
                val highlighter = markupModel.addRangeHighlighter(
                    startOffset,
                    endOffset,
                    HighlighterLayer.SELECTION - 1,
                    modifiedAttrs,
                    HighlighterTargetArea.LINES_IN_RANGE
                )
                ourHighlighters.add(highlighter)
            }
        }

        // Auto-remove our highlights after 5 seconds using javax.swing.Timer (EDT-safe, non-blocking)
        Timer(5000) { _ ->
            ourHighlighters.forEach { markupModel.removeHighlighter(it) }
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun computeChangedLines(oldLines: List<String>, newLines: List<String>): ChangeLines {
        val added = mutableListOf<Int>()
        val modified = mutableListOf<Int>()

        val oldSet = oldLines.toSet()

        for (i in newLines.indices) {
            if (i >= oldLines.size) {
                added.add(i)
            } else if (newLines[i] != oldLines[i]) {
                modified.add(i)
            }
        }

        // Lines in new content that weren't in old content (added)
        for (i in newLines.indices) {
            if (newLines[i] !in oldSet && i !in added && i !in modified) {
                added.add(i)
            }
        }

        return ChangeLines(added.sorted(), modified.sorted())
    }

    /** Clear all tracked snapshots (e.g., on session reset). */
    fun clearSnapshots() {
        originalSnapshots.clear()
    }

    private data class ChangeLines(val added: List<Int>, val modified: List<Int>)
}
