package com.github.codeplangui.execution.dialogs

import com.github.codeplangui.settings.PluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextPane
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.StyleConstants
import javax.swing.text.StyleContext

/**
 * IntelliJ-native DiffDialog for reviewing file modifications.
 *
 * Embeds a side-by-side diff view with LCS-based line-level coloring
 * directly inside the modal confirmation dialog, so the user sees
 * content and colour differentiation without a separate frame.
 *
 * Accept / Reject buttons + optional trust-session checkbox.
 */
class DiffReviewDialog(
    private val project: Project?,
    private val path: String,
    private val oldContent: String,
    private val newContent: String
) {
    data class Result(
        val accepted: Boolean,
        val trustSession: Boolean
    )

    companion object {
        private val REMOVED_BG = JBColor(Color(0xFF, 0xDD, 0xDD), Color(0x60, 0x2B, 0x2B))
        private val ADDED_BG = JBColor(Color(0xDD, 0xFF, 0xDD), Color(0x2B, 0x50, 0x2B))
    }

    fun show(): Result {
        var result = Result(accepted = false, trustSession = false)
        val settings = PluginSettings.getInstance().getState()

        ApplicationManager.getApplication().invokeAndWait {
            val trustCheckbox: JBCheckBox? =
                if (settings.allowSessionFileTrust) {
                    JBCheckBox("Trust this session — auto-apply all file changes").also {
                        it.border = JBUI.Borders.empty(4, 0)
                    }
                } else null

            val dialog = object : DialogWrapper(project, true) {
                init {
                    title = "File Change Review — $path"
                    setOKButtonText("Accept")
                    setCancelButtonText("Reject")
                    init()
                }

                override fun createCenterPanel(): JComponent {
                    val panel = JPanel(BorderLayout())

                    val added = countAddedLines()
                    val removed = countRemovedLines()
                    val statsLabel = JBLabel(
                        "<html><b>$path</b>&nbsp;&nbsp;&nbsp;+$added / -$removed lines changed</html>"
                    )
                    statsLabel.border = JBUI.Borders.empty(0, 0, 8, 0)
                    panel.add(statsLabel, BorderLayout.NORTH)

                    panel.add(buildDiffPanel(), BorderLayout.CENTER)

                    trustCheckbox?.let {
                        val bottom = JPanel(BorderLayout())
                        bottom.add(it, BorderLayout.WEST)
                        bottom.border = JBUI.Borders.empty(8, 0, 0, 0)
                        panel.add(bottom, BorderLayout.SOUTH)
                    }

                    panel.preferredSize = Dimension(960, 520)
                    return panel
                }

                override fun getDimensionServiceKey(): String = "CodePlanGUI.DiffReviewDialog"

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

    // ── Side-by-side diff panel ────────────────────────────────────────

    private fun buildDiffPanel(): JComponent {
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        split.resizeWeight = 0.5
        split.border = BorderFactory.createEmptyBorder()

        val diff = computeLineDiff(oldContent.lines(), newContent.lines())

        val leftPane = buildDiffTextPane(diff, isOld = true)
        val rightPane = buildDiffTextPane(diff, isOld = false)

        val leftScroll = JScrollPane(leftPane)
        val rightScroll = JScrollPane(rightPane)

        // Synchronise vertical scrolling between both sides
        var syncing = false
        leftScroll.verticalScrollBar.addAdjustmentListener { e ->
            if (!syncing) {
                syncing = true
                rightScroll.verticalScrollBar.value = e.value
                syncing = false
            }
        }
        rightScroll.verticalScrollBar.addAdjustmentListener { e ->
            if (!syncing) {
                syncing = true
                leftScroll.verticalScrollBar.value = e.value
                syncing = false
            }
        }

        val leftPanel = JPanel(BorderLayout()).apply {
            add(
                JBLabel("  Original").apply { border = JBUI.Borders.empty(4, 0) },
                BorderLayout.NORTH
            )
            add(leftScroll, BorderLayout.CENTER)
        }
        val rightPanel = JPanel(BorderLayout()).apply {
            add(
                JBLabel("  Modified").apply { border = JBUI.Borders.empty(4, 0) },
                BorderLayout.NORTH
            )
            add(rightScroll, BorderLayout.CENTER)
        }

        split.leftComponent = leftPanel
        split.rightComponent = rightPanel
        return split
    }

    private fun buildDiffTextPane(diff: List<DiffLine>, isOld: Boolean): JTextPane {
        val doc = DefaultStyledDocument()
        val ctx = StyleContext.getDefaultStyleContext()

        val regular = ctx.addStyle("diffRegular", null).apply {
            StyleConstants.setFontFamily(this, "Monospaced")
            StyleConstants.setFontSize(this, 13)
        }
        val removed = ctx.addStyle("diffRemoved", null).apply {
            StyleConstants.setFontFamily(this, "Monospaced")
            StyleConstants.setFontSize(this, 13)
            StyleConstants.setBackground(this, REMOVED_BG)
        }
        val added = ctx.addStyle("diffAdded", null).apply {
            StyleConstants.setFontFamily(this, "Monospaced")
            StyleConstants.setFontSize(this, 13)
            StyleConstants.setBackground(this, ADDED_BG)
        }

        for (line in diff) {
            val text: String
            val style: javax.swing.text.Style
            when (line.type) {
                DiffType.UNCHANGED -> {
                    text = if (isOld) line.oldLine ?: "" else line.newLine ?: ""
                    style = regular
                }
                DiffType.REMOVED -> {
                    text = if (isOld) line.oldLine ?: "" else ""
                    style = removed
                }
                DiffType.ADDED -> {
                    text = if (isOld) "" else line.newLine ?: ""
                    style = added
                }
            }
            doc.insertString(doc.length, text + "\n", style)
        }

        return JTextPane(doc).apply { isEditable = false }
    }

    // ── LCS-based line diff ────────────────────────────────────────────

    private enum class DiffType { UNCHANGED, REMOVED, ADDED }

    private data class DiffLine(
        val type: DiffType,
        val oldLine: String?,
        val newLine: String?
    )

    private fun computeLineDiff(oldLines: List<String>, newLines: List<String>): List<DiffLine> {
        val m = oldLines.size
        val n = newLines.size

        // Build LCS DP table
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (oldLines[i - 1] == newLines[j - 1]) dp[i - 1][j - 1] + 1
            else maxOf(dp[i - 1][j], dp[i][j - 1])
        }

        // Back-track to recover diff
        val result = mutableListOf<DiffLine>()
        var i = m
        var j = n
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1]) {
                result += DiffLine(DiffType.UNCHANGED, oldLines[i - 1], newLines[j - 1])
                i--; j--
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                result += DiffLine(DiffType.ADDED, null, newLines[j - 1])
                j--
            } else {
                result += DiffLine(DiffType.REMOVED, oldLines[i - 1], null)
                i--
            }
        }
        return result.reversed()
    }

    // ── Stats ──────────────────────────────────────────────────────────

    private fun countAddedLines(): Int {
        val oldLines = oldContent.lines().toSet()
        return newContent.lines().count { it !in oldLines }
    }

    private fun countRemovedLines(): Int {
        val newLines = newContent.lines().toSet()
        return oldContent.lines().count { it !in newLines }
    }
}
