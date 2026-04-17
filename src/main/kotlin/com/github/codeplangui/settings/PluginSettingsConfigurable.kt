package com.github.codeplangui.settings

import com.github.codeplangui.ChatService
import com.github.codeplangui.api.OkHttpSseClient
import com.github.codeplangui.api.TestResult
import com.intellij.ide.SaveAndSyncHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.SpinnerNumberModel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

class PluginSettingsConfigurable : Configurable {
    private lateinit var panel: JPanel
    private lateinit var tableModel: ProviderTableModel
    private lateinit var activeProviderCombo: JComboBox<ProviderChoice>
    private lateinit var temperatureSpinner: JSpinner
    private lateinit var maxTokensSpinner: JSpinner
    private lateinit var contextInjectionCheckbox: JCheckBox
    private lateinit var contextMaxLinesSpinner: JSpinner
    private lateinit var commitLanguageZh: JRadioButton
    private lateinit var commitLanguageEn: JRadioButton
    private lateinit var commitFormatConventional: JRadioButton
    private lateinit var commitFormatFreeform: JRadioButton
    private lateinit var commitMultiModeMerge: JRadioButton
    private lateinit var commitMultiModeSplit: JRadioButton
    private lateinit var commitMaxFilesSpinner: JSpinner
    private lateinit var commitDiffLineLimitSpinner: JSpinner
    private lateinit var memoryTextArea: JTextArea
    private lateinit var commandExecutionCheckbox: JCheckBox
    private lateinit var commandTimeoutSpinner: JSpinner
    private lateinit var commandWhitelistModel: DefaultListModel<String>
    private lateinit var commandWhitelistList: JList<String>
    private lateinit var sessionTtlDaysSpinner: JSpinner
    private val client = OkHttpSseClient()
    private val pendingApiKeyUpdates = linkedMapOf<String, String?>()

    override fun getDisplayName(): String = "CodePlanGUI"

    override fun createComponent(): JComponent {
        val settings = SettingsFormState.fromSettingsState(PluginSettings.getInstance().getState())

        tableModel = ProviderTableModel(settings.providers.toMutableList())
        val table = JBTable(tableModel).apply {
            setDefaultRenderer(String::class.java, object : ColoredTableCellRenderer() {
                override fun customizeCellRenderer(
                    table: JTable,
                    value: Any?,
                    selected: Boolean,
                    hasFocus: Boolean,
                    row: Int,
                    column: Int
                ) {
                    append(value?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            })
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            preferredScrollableViewportSize = Dimension(500, 150)
        }

        activeProviderCombo = JComboBox<ProviderChoice>().apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ) = super.getListCellRendererComponent(list, (value as? ProviderChoice)?.label ?: "未配置 Provider", index, isSelected, cellHasFocus)
            }
        }

        val testBtn = JButton("Test Connection").apply {
            addActionListener {
                val row = table.selectedRow
                if (row < 0) {
                    Messages.showInfoMessage("请先选择一个 Provider", "CodePlanGUI")
                    return@addActionListener
                }
                val config = tableModel.providers[row]
                val key = effectiveApiKey(config.id).orEmpty()
                if (key.isBlank()) {
                    Messages.showInfoMessage("当前 Provider 尚未填写 API Key", "CodePlanGUI")
                    return@addActionListener
                }
                ProgressManager.getInstance().run(object : Task.Backgroundable(null, "Testing connection...") {
                    override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                        val result = client.testConnection(config, key)
                        SwingUtilities.invokeLater {
                            when (result) {
                                is TestResult.Success -> Messages.showInfoMessage("✓ 连接成功", "CodePlanGUI")
                                is TestResult.Failure -> Messages.showErrorDialog(result.message, "连接失败")
                            }
                        }
                    }
                })
            }
        }

        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction {
                val dialog = ProviderDialog()
                if (dialog.showAndGet()) {
                    val config = dialog.getConfig()
                    pendingApiKeyUpdates[config.id] = dialog.getApiKeyOrNull()
                    tableModel.providers.add(config)
                    tableModel.fireTableDataChanged()
                    refreshActiveProviderChoices(config.id)
                }
            }
            .setEditAction {
                val row = table.selectedRow
                if (row < 0) return@setEditAction
                val existing = tableModel.providers[row]
                val dialog = ProviderDialog(
                    existing = existing,
                    initialApiKey = effectiveApiKey(existing.id)
                )
                if (dialog.showAndGet()) {
                    val config = dialog.getConfig()
                    tableModel.providers[row] = config
                    pendingApiKeyUpdates[config.id] = dialog.getApiKeyOrNull()
                    tableModel.fireTableDataChanged()
                    refreshActiveProviderChoices(selectedProviderId())
                }
            }
            .setRemoveAction {
                val row = table.selectedRow
                if (row < 0) return@setRemoveAction
                val config = tableModel.providers[row]
                tableModel.providers.removeAt(row)
                pendingApiKeyUpdates[config.id] = null
                tableModel.fireTableDataChanged()
                refreshActiveProviderChoices(selectedProviderId().takeIf { it != config.id })
            }
            .createPanel()

        val providersPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Active Provider:"), activeProviderCombo)
            .addLabeledComponent(
                JBLabel("API Providers:"),
                JPanel(BorderLayout()).apply {
                    add(decorator, BorderLayout.CENTER)
                    add(testBtn, BorderLayout.SOUTH)
                }
            )
            .panel

        temperatureSpinner = JSpinner(SpinnerNumberModel(settings.chatTemperature, 0.0, 2.0, 0.1))
        maxTokensSpinner = JSpinner(SpinnerNumberModel(settings.chatMaxTokens, 100, 8192, 100))
        contextInjectionCheckbox = JCheckBox("启用当前文件上下文注入", settings.contextInjectionEnabled)
        contextMaxLinesSpinner = JSpinner(SpinnerNumberModel(settings.contextMaxLines, 20, 2000, 20))

        commitLanguageZh = JRadioButton("中文", settings.commitLanguage == "zh")
        commitLanguageEn = JRadioButton("English", settings.commitLanguage == "en")
        ButtonGroup().apply {
            add(commitLanguageZh)
            add(commitLanguageEn)
        }

        commitFormatConventional = JRadioButton("Conventional Commits", settings.commitFormat == "conventional")
        commitFormatFreeform = JRadioButton("自由格式", settings.commitFormat == "freeform")
        ButtonGroup().apply {
            add(commitFormatConventional)
            add(commitFormatFreeform)
        }

        commitMultiModeMerge = JRadioButton("合并为一条", settings.commitMultiMode == "merge")
        commitMultiModeSplit = JRadioButton("拆分为多条（开发中）", settings.commitMultiMode == "split")
        ButtonGroup().apply {
            add(commitMultiModeMerge)
            add(commitMultiModeSplit)
        }

        commitMaxFilesSpinner = JSpinner(SpinnerNumberModel(settings.commitMaxFiles, 5, 100, 5))
        commitDiffLineLimitSpinner = JSpinner(SpinnerNumberModel(settings.commitDiffLineLimit, 100, 2000, 100))

        memoryTextArea = JTextArea(settings.memoryText, 5, 40).apply {
            lineWrap = true
            wrapStyleWord = true
        }

        sessionTtlDaysSpinner = JSpinner(SpinnerNumberModel(settings.sessionTtlDays, 1, 365, 1))

        val chatCommitPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Temperature:", temperatureSpinner)
            .addLabeledComponent("Max Tokens:", maxTokensSpinner)
            .addComponent(contextInjectionCheckbox)
            .addLabeledComponent("Context Max Lines:", contextMaxLinesSpinner)
            .addLabeledComponent(
                "Commit Language:",
                JPanel().apply {
                    add(commitLanguageZh)
                    add(commitLanguageEn)
                }
            )
            .addLabeledComponent(
                "Commit Format:",
                JPanel().apply {
                    add(commitFormatConventional)
                    add(commitFormatFreeform)
                }
            )
            .addLabeledComponent(
                "Multi-file Strategy:",
                JPanel().apply {
                    add(commitMultiModeMerge)
                    add(commitMultiModeSplit)
                }
            )
            .addLabeledComponent("Max Files:", commitMaxFilesSpinner)
            .addLabeledComponent("Diff Line Limit:", commitDiffLineLimitSpinner)
            .addLabeledComponent(
                JBLabel("AI 记忆（注入所有对话的系统提示词）:"),
                JScrollPane(memoryTextArea)
            )
            .addLabeledComponent(
                "Session 过期天数 (0 = 永不过期):",
                sessionTtlDaysSpinner
            )
            .panel

        val execSettings = SettingsFormState.fromSettingsState(PluginSettings.getInstance().getState())

        commandExecutionCheckbox = JCheckBox("Enable AI command execution", execSettings.commandExecutionEnabled)
        commandTimeoutSpinner = JSpinner(SpinnerNumberModel(execSettings.commandTimeoutSeconds, 5, 300, 5))
        commandWhitelistModel = DefaultListModel<String>().also { model ->
            execSettings.commandWhitelist.forEach { model.addElement(it) }
        }
        commandWhitelistList = JList(commandWhitelistModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 6
        }

        val whitelistPanel = JPanel(BorderLayout()).apply {
            add(JScrollPane(commandWhitelistList), BorderLayout.CENTER)
            val btnPanel = JPanel()
            val addBtn = JButton("+ Add").apply {
                addActionListener {
                    val cmd = Messages.showInputDialog(
                        "Command name (e.g. cargo):", "Add Command", null
                    ) ?: return@addActionListener
                    if (cmd.isNotBlank() && !commandWhitelistModel.contains(cmd.trim())) {
                        commandWhitelistModel.addElement(cmd.trim())
                    }
                }
            }
            val removeBtn = JButton("Remove").apply {
                addActionListener {
                    val idx = commandWhitelistList.selectedIndex
                    if (idx >= 0) commandWhitelistModel.remove(idx)
                }
            }
            btnPanel.add(addBtn)
            btnPanel.add(removeBtn)
            add(btnPanel, BorderLayout.SOUTH)
        }

        val executionPanel = FormBuilder.createFormBuilder()
            .addComponent(commandExecutionCheckbox)
            .addLabeledComponent("Execution timeout (s):", commandTimeoutSpinner)
            .addLabeledComponent(JBLabel("Allowed commands:"), whitelistPanel)
            .addComponent(
                JBLabel("<html><small>&#x26A0; Commands in this list run without approval.<br>" +
                        "Commands not in this list will prompt for your approval.</small></html>")
            )
            .panel

        val tabs = JBTabbedPane().apply {
            addTab("Providers", providersPanel)
            addTab("Chat / Commit", chatCommitPanel)
            addTab("Execution", executionPanel)
        }

        panel = JPanel(BorderLayout()).apply {
            add(tabs, BorderLayout.CENTER)
        }
        refreshActiveProviderChoices(settings.activeProviderId)

        return panel
    }

    override fun isModified(): Boolean {
        return currentFormState() != SettingsFormState.fromSettingsState(PluginSettings.getInstance().getState()) ||
            pendingApiKeyUpdates.isNotEmpty()
    }

    override fun apply() {
        val settings = currentFormState().toSettingsState()
        PluginSettings.getInstance().loadState(settings)

        pendingApiKeyUpdates.forEach { (providerId, value) ->
            if (value == null) {
                ApiKeyStore.delete(providerId)
            } else {
                ApiKeyStore.save(providerId, value)
            }
        }
        pendingApiKeyUpdates.clear()
        SaveAndSyncHandler.getInstance().saveSettingsUnderModalProgress(ApplicationManager.getApplication())
        ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .forEach { project ->
                ChatService.getInstance(project).refreshBridgeStatus()
            }
    }

    override fun reset() {
        val settings = SettingsFormState.fromSettingsState(PluginSettings.getInstance().getState())
        tableModel.providers = settings.providers.toMutableList()
        tableModel.fireTableDataChanged()
        refreshActiveProviderChoices(settings.activeProviderId)
        temperatureSpinner.value = settings.chatTemperature
        maxTokensSpinner.value = settings.chatMaxTokens
        contextInjectionCheckbox.isSelected = settings.contextInjectionEnabled
        contextMaxLinesSpinner.value = settings.contextMaxLines
        commitLanguageZh.isSelected = settings.commitLanguage == "zh"
        commitLanguageEn.isSelected = settings.commitLanguage == "en"
        commitFormatConventional.isSelected = settings.commitFormat == "conventional"
        commitFormatFreeform.isSelected = settings.commitFormat == "freeform"
        commitMultiModeMerge.isSelected = settings.commitMultiMode == "merge"
        commitMultiModeSplit.isSelected = settings.commitMultiMode == "split"
        commitMaxFilesSpinner.value = settings.commitMaxFiles
        commitDiffLineLimitSpinner.value = settings.commitDiffLineLimit
        memoryTextArea.text = settings.memoryText
        sessionTtlDaysSpinner.value = settings.sessionTtlDays
        val execState = SettingsFormState.fromSettingsState(PluginSettings.getInstance().getState())
        commandExecutionCheckbox.isSelected = execState.commandExecutionEnabled
        commandTimeoutSpinner.value = execState.commandTimeoutSeconds
        commandWhitelistModel.clear()
        execState.commandWhitelist.forEach { commandWhitelistModel.addElement(it) }
        pendingApiKeyUpdates.clear()
    }

    override fun disposeUIResources() {
        pendingApiKeyUpdates.clear()
    }

    private fun currentFormState(): SettingsFormState = SettingsFormState(
        providers = tableModel.providers.toMutableList(),
        activeProviderId = selectedProviderId(),
        chatTemperature = (temperatureSpinner.value as Number).toDouble(),
        chatMaxTokens = (maxTokensSpinner.value as Number).toInt(),
        commitLanguage = if (commitLanguageEn.isSelected) "en" else "zh",
        commitFormat = if (commitFormatFreeform.isSelected) "freeform" else "conventional",
        commitMultiMode = if (commitMultiModeSplit.isSelected) "split" else "merge",
        commitMaxFiles = (commitMaxFilesSpinner.value as Number).toInt(),
        commitDiffLineLimit = (commitDiffLineLimitSpinner.value as Number).toInt(),
        contextInjectionEnabled = contextInjectionCheckbox.isSelected,
        contextMaxLines = (contextMaxLinesSpinner.value as Number).toInt(),
        memoryText = memoryTextArea.text,
        commandExecutionEnabled = commandExecutionCheckbox.isSelected,
        commandWhitelist = (0 until commandWhitelistModel.size()).map {
            commandWhitelistModel.getElementAt(it)
        }.toMutableList(),
        commandTimeoutSeconds = (commandTimeoutSpinner.value as Number).toInt(),
        sessionTtlDays = (sessionTtlDaysSpinner.value as Number).toInt(),
    )

    private fun selectedProviderId(): String? =
        (activeProviderCombo.selectedItem as? ProviderChoice)?.id

    private fun refreshActiveProviderChoices(preferredId: String?) {
        val choices = tableModel.providers.map {
            ProviderChoice(id = it.id, label = "${it.name} (${it.model})")
        }
        activeProviderCombo.model = DefaultComboBoxModel(choices.toTypedArray())
        activeProviderCombo.isEnabled = choices.isNotEmpty()
        activeProviderCombo.selectedItem = choices.firstOrNull { it.id == preferredId } ?: choices.firstOrNull()
    }

    private fun effectiveApiKey(providerId: String): String? =
        resolveInitialApiKeyValue(providerId, pendingApiKeyUpdates, ApiKeyStore.load(providerId))

    private data class ProviderChoice(val id: String, val label: String)

    private class ProviderTableModel(var providers: MutableList<ProviderConfig>) : AbstractTableModel() {
        private val columns = listOf("名称", "Endpoint", "模型")

        override fun getRowCount(): Int = providers.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
            0 -> providers[rowIndex].name
            1 -> providers[rowIndex].endpoint
            2 -> providers[rowIndex].model
            else -> ""
        }
    }
}
