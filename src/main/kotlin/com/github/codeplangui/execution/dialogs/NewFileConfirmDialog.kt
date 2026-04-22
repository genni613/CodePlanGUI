package com.github.codeplangui.execution.dialogs

import com.github.codeplangui.settings.PluginSettings
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * IDE-native confirmation dialog for new file creation.
 * Uses EditorTextField for syntax-highlighted content preview.
 */
class NewFileConfirmDialog(
    private val project: Project?,
    private val path: String,
    private val content: String
) {
    data class Result(
        val accepted: Boolean,
        val trustSession: Boolean
    )

    fun show(): Result {
        var result = Result(accepted = false, trustSession = false)
        val settings = PluginSettings.getInstance().getState()
        val fileType = detectFileType(path)
        val lineCount = content.lines().size
        val sizeBytes = content.toByteArray().size

        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
            val trustCheckbox: JBCheckBox? =
                if (settings.allowSessionFileTrust) {
                    JBCheckBox("Trust this session — auto-apply all file changes").also {
                        it.border = JBUI.Borders.empty(4, 0)
                    }
                } else null

            val dialog = object : DialogWrapper(project, true) {
                init {
                    title = "Create New File"
                    setOKButtonText("Create")
                    setCancelButtonText("Cancel")
                    init()
                }

                override fun createCenterPanel(): JComponent {
                    val panel = JPanel(BorderLayout())

                    // Header with file info
                    val headerLabel = JBLabel(
                        "<html><b>$path</b><br/>" +
                            "${formatSize(sizeBytes)} / $lineCount lines</html>"
                    )
                    headerLabel.border = JBUI.Borders.empty(0, 0, 8, 0)
                    panel.add(headerLabel, BorderLayout.NORTH)

                    // Content preview with syntax highlighting
                    val document = com.intellij.openapi.editor.EditorFactory.getInstance().createDocument(content)
                    val editorTextField = EditorTextField(
                        document,
                        project,
                        fileType,
                        false,  // not writable
                        false   // not viewer
                    )
                    editorTextField.setOneLineMode(false)
                    editorTextField.setPreferredSize(Dimension(700, 500))

                    panel.add(JBScrollPane(editorTextField), BorderLayout.CENTER)

                    // Trust checkbox at bottom
                    trustCheckbox?.let {
                        val bottomPanel = JPanel(BorderLayout())
                        bottomPanel.add(it, BorderLayout.WEST)
                        bottomPanel.border = JBUI.Borders.empty(8, 0, 0, 0)
                        panel.add(bottomPanel, BorderLayout.SOUTH)
                    }

                    panel.preferredSize = Dimension(750, 600)
                    return panel
                }

                override fun getDimensionServiceKey(): String = "CodePlanGUI.NewFileConfirmDialog"

                override fun doOKAction() {
                    result = Result(
                        accepted = true,
                        trustSession = trustCheckbox?.isSelected ?: false
                    )
                    super.doOKAction()
                }

                override fun doCancelAction() {
                    result = Result(accepted = false, trustSession = false)
                    super.doCancelAction()
                }
            }

            dialog.show()
        }

        return result
    }

    private fun detectFileType(path: String): FileType {
        val ext = path.substringAfterLast('.', "")
        return FileTypeManager.getInstance().getFileTypeByExtension(ext)
    }

    private fun formatSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}
