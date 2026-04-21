package com.github.codeplangui.execution

import com.github.codeplangui.settings.SettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Manages file change review via IDE-native dialogs.
 * Supports session-level trust mode to reduce approval fatigue.
 */
class FileChangeReview {

    @Volatile
    var sessionFileWriteTrusted: Boolean = false
        private set

    fun resetSessionTrust() {
        sessionFileWriteTrusted = false
    }

    fun setSessionTrusted() {
        sessionFileWriteTrusted = true
    }

    /**
     * Review a file modification. Returns true if the change is approved.
     * In trust mode, skips the dialog and returns true directly.
     *
     * First version: uses simple Yes/No confirmation dialog.
     * Future: IntelliJ DiffDialog integration.
     */
    fun reviewFileChange(
        project: Project,
        path: String,
        oldContent: String,
        newContent: String,
        settings: SettingsState
    ): Boolean {
        if (sessionFileWriteTrusted) return true

        val future = CompletableFuture<Boolean>()

        ApplicationManager.getApplication().invokeAndWait {
            // Compute simple diff stats
            val oldLines = oldContent.lines().size
            val newLines = newContent.lines().size
            val added = (newLines - oldLines).coerceAtLeast(0)
            val removed = (oldLines - newLines).coerceAtLeast(0)

            val message = buildString {
                appendLine("Apply changes to $path?")
                appendLine()
                appendLine("Lines: +$added / -$removed (was $oldLines, now $newLines)")
                appendLine()
                // Show first few changed lines as preview
                val oldSet = oldContent.lines().toSet()
                val newLinesList = newContent.lines()
                val changed = newLinesList.filter { it !in oldSet }.take(5)
                if (changed.isNotEmpty()) {
                    appendLine("--- New/changed lines (preview) ---")
                    changed.forEach { appendLine(it) }
                }
            }

            val result = Messages.showYesNoDialog(
                project,
                message,
                "File Change Review: $path",
                Messages.getQuestionIcon()
            )
            future.complete(result == Messages.YES)
        }

        return future.get(60, TimeUnit.SECONDS)
    }

    /**
     * Review a new file creation. Returns true if creation is approved.
     * In trust mode, skips the dialog and returns true directly.
     */
    fun reviewNewFile(
        project: Project,
        path: String,
        content: String,
        settings: SettingsState
    ): Boolean {
        if (sessionFileWriteTrusted) return true

        val future = CompletableFuture<Boolean>()

        ApplicationManager.getApplication().invokeAndWait {
            val lineCount = content.lines().size
            val sizeBytes = content.toByteArray().size

            val message = buildString {
                appendLine("Create new file?")
                appendLine()
                appendLine("Path: $path")
                appendLine("Size: ${formatSize(sizeBytes)} / $lineCount lines")
                appendLine()
                appendLine("--- Preview (first 20 lines) ---")
                content.lines().take(20).forEach { appendLine(it) }
                if (lineCount > 20) appendLine("... ($lineCount lines total)")
            }

            val result = Messages.showOkCancelDialog(
                project,
                message,
                "Create New File",
                "Create", "Cancel",
                Messages.getQuestionIcon()
            )
            future.complete(result == Messages.OK)
        }

        return future.get(60, TimeUnit.SECONDS)
    }

    private fun formatSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}
