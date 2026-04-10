package com.github.codeplangui.action

object CommitPromptBuilder {

    private const val MAX_DIFF_CHARS = 5000

    fun buildSystemPrompt(language: String, format: String = "conventional"): String {
        val langInstruction = if (language == "zh") "中文" else "English"
        val formatInstruction = if (format == "freeform") {
            "- 格式：自由组织 subject 和 body，但保持简洁、可读、便于团队理解"
        } else {
            """
- 格式：<type>(<scope>): <subject>
- type 从以下选择：feat / fix / refactor / docs / test / chore / style / perf
            """.trimIndent()
        }
        return """
你是一个 git commit message 生成助手。
根据提供的 git diff，生成一条符合规范的 commit message。

要求：
$formatInstruction
- subject 使用${langInstruction}，不超过 72 字符
- 如需补充说明，在空行后加 body（bullet points）
- 只输出 commit message 本身，不要任何解释或额外文字
        """.trimIndent()
    }

    fun buildUserMessage(diff: String, language: String): String {
        val safeDiff = if (diff.length > MAX_DIFF_CHARS) {
            diff.take(MAX_DIFF_CHARS) + "\n... [diff truncated]"
        } else {
            diff
        }
        val languageName = if (language == "zh") "中文" else "English"
        return "请根据以下 git diff 生成 $languageName commit message：\n\n$safeDiff"
    }
}
