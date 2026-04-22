package com.github.codeplangui.execution

import com.github.codeplangui.execution.dialogs.DiffReviewDialog
import com.github.codeplangui.execution.dialogs.NewFileConfirmDialog
import com.github.codeplangui.settings.SettingsState
import com.intellij.openapi.project.Project

/**
 * Manages file change review via IDE-native dialogs.
 * Supports session-level trust mode to reduce approval fatigue.
 *
 * §8.2 — Existing file modifications use DiffReviewDialog (side-by-side syntax-highlighted diff)
 * §8.3 — New file creation uses NewFileConfirmDialog (EditorTextField with full content preview)
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
     * Review a file modification via IDE-native DiffDialog.
     * In trust mode, skips the dialog and returns true directly.
     *
     * @return true if the change is approved
     */
    @Suppress("UNUSED_PARAMETER")
    fun reviewFileChange(
        project: Project,
        path: String,
        oldContent: String,
        newContent: String,
        settings: SettingsState
    ): Boolean {
        if (sessionFileWriteTrusted) return true

        val result = DiffReviewDialog(project, path, oldContent, newContent).show()

        if (result.trustSession && result.accepted) {
            sessionFileWriteTrusted = true
        }

        return result.accepted
    }

    /**
     * Review a new file creation via IDE-native ConfirmDialog with content preview.
     * In trust mode, skips the dialog and returns true directly.
     *
     * @return true if creation is approved
     */
    @Suppress("UNUSED_PARAMETER")
    fun reviewNewFile(
        project: Project,
        path: String,
        content: String,
        settings: SettingsState
    ): Boolean {
        if (sessionFileWriteTrusted) return true

        val result = NewFileConfirmDialog(project, path, content).show()

        if (result.trustSession && result.accepted) {
            sessionFileWriteTrusted = true
        }

        return result.accepted
    }
}
