package com.github.codeplangui.settings

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

class ProviderDialog(
    private val existing: ProviderConfig? = null
) : DialogWrapper(true) {

    private val nameField = JBTextField(existing?.name ?: "")
    private val endpointField = JBTextField(existing?.endpoint ?: "")
    private val keyField = JBPasswordField().apply {
        if (existing != null) {
            val loaded = ApiKeyStore.load(existing.id)
            if (!loaded.isNullOrBlank()) {
                text = loaded
            }
        }
    }
    private val modelField = JBTextField(existing?.model ?: "")

    init {
        title = if (existing == null) "添加 Provider" else "编辑 Provider"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("名称:", nameField)
            .addLabeledComponent("Endpoint:", endpointField)
            .addTooltip("例: https://api.openai.com/v1  或  https://dashscope.aliyuncs.com/compatible-mode/v1")
            .addLabeledComponent("API Key:", keyField)
            .addLabeledComponent("模型名称:", modelField)
            .addTooltip("例: gpt-4o  或  qwen-max  或  deepseek-chat")
            .panel
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo("名称不能为空", nameField)
        if (endpointField.text.isBlank()) return ValidationInfo("Endpoint 不能为空", endpointField)
        val endpoint = endpointField.text.trim()
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            return ValidationInfo("Endpoint 必须以 http:// 或 https:// 开头", endpointField)
        }
        if (existing == null && normalizeApiKeyInput(String(keyField.password)) == null) {
            return ValidationInfo("API Key 不能为空", keyField)
        }
        if (modelField.text.isBlank()) return ValidationInfo("模型名称不能为空", modelField)
        return null
    }

    fun getConfig(): ProviderConfig = ProviderConfig(
        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
        name = nameField.text.trim(),
        endpoint = endpointField.text.trim().trimEnd('/'),
        model = modelField.text.trim()
    )

    fun getApiKeyOrNull(): String? = normalizeApiKeyInput(String(keyField.password))

    companion object {
        internal fun normalizeApiKeyInput(rawInput: String): String? =
            rawInput.trim().ifBlank { null }
    }
}
