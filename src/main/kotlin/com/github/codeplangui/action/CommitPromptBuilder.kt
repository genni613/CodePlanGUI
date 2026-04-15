package com.github.codeplangui.action

data class CommitPromptFile(
    val path: String,
    val changeType: String,
    val beforeContent: String?,
    val afterContent: String?
)

object CommitPromptBuilder {

    private const val MAX_DIFF_CHARS = 5000
    private const val MAX_CHANGED_LINES = 40
    private const val MAX_NEW_FILE_CHARS = 1200

    /**
     * Strips AI thinking/process tags from generated content.
     * Some AI providers (like DeepSeek) include <think>...</think> tags in their responses.
     */
    fun stripThinkContent(content: String): String {
        return content
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            .trim()
    }

    fun buildStage1Prompt(): String = """
You are an expert developer specialist in creating git commits.
Provide a concise one sentence summary for each changed file, describing the main change made.
Each line must follow this format: {FILE: CHANGES: (CHANGED_LINES_COUNT)}

Rules:
- Output ONLY the lines of summaries, NO explanations, NO markdown, NO code blocks
- Each file change gets exactly one line
- Do not use general terms like "update" or "change", be specific
- Use present tense, active voice, and imperative mood ("Fix" not "Fixed")
- Skip lock files: package-lock.json, Cargo.lock, pnpm-lock.yaml, yarn.lock
- Skip binary files diff content
- Ignore files under .code folder or .idea folder, unless there aren't other files changed
- Avoid phrases like "The main goal is to..." or "Based on...", state the change directly
    """.trimIndent()

    fun buildStage2Prompt(language: String): String {
        val langInstruction = if (language == "zh") "中文" else "English"
        return """
You are an expert developer specialist in creating git commit messages.
Based on the provided file changes, generate ONE commit message following Conventional Commits.

Rules:
- Format: <type>(<scope>): <subject>
- Type must be one of: feat / fix / docs / style / refactor / perf / test / chore / revert / build / ci
  - feat: Only when adding a new feature
  - fix: When fixing a bug
  - docs: When updating documentation
  - style: Formatting without changing code logic
  - refactor: Restructuring code without changing external behavior
  - perf: Improving performance
  - test: Adding or updating tests
  - chore: Build process or auxiliary tools changes
  - revert: Undoing a previous commit
  - build / ci: Build system or CI changes
- Scope: derived from the most significant changed directory; omit scope if changes span multiple unrelated directories
- Subject: use imperative mood, keep under 72 characters
- Add body with bullet points ONLY when changes are complex enough to need explanation
- If this is a breaking change, append "!" after type (e.g., "feat(auth)!:")
- Output ONLY the commit message, no explanation or formatting
- Use ${langInstruction} for the commit message
        """.trimIndent()
    }

    fun buildStage1UserMessage(summaries: List<String>, language: String): String {
        val langInstruction = if (language == "zh") "中文" else "English"
        return "Below are the file change summaries. Generate a commit message based on this information.\n\n${summaries.joinToString("\n")}"
    }

    fun buildStatsUserMessage(
        files: List<DiffAnalyzer.FileChange>,
        language: String
    ): String {
        val langInstruction = if (language == "zh") "中文" else "English"
        val summary = files.joinToString("\n") { file ->
            "${file.path}: (${file.additions} additions, ${file.deletions} deletions)"
        }
        return """
Below is a summary of ${files.size} changed files. Generate an appropriate commit message based on this summary.
$summary

Generate the commit message in ${langInstruction}, following Conventional Commits format.
        """.trimIndent()
    }

    /**
     * Builds a user message for single-stage generation (fallback path).
     * Uses the same style as two-stage generation for consistent output.
     */
    fun buildSingleStageUserMessage(diff: String, language: String): String {
        val langInstruction = if (language == "zh") "中文" else "English"
        val safeDiff = if (diff.length > MAX_DIFF_CHARS) {
            diff.take(MAX_DIFF_CHARS) + "\n... [diff truncated]"
        } else {
            diff
        }
        return """
Below is the git diff. Generate a commit message based on this information.

$safeDiff

Generate the commit message in ${langInstruction}, following Conventional Commits format.
- Output ONLY the commit message, no explanation
- Keep subject under 72 characters
- Use imperative mood
- Add body with bullet points ONLY when changes are complex enough
        """.trimIndent()
    }

    fun buildSingleFilePrompt(file: DiffAnalyzer.FileChange): String {
        val changeType = file.changeType
        val path = file.path
        val lines = file.additions + file.deletions
        return "File: $path\nChange type: $changeType\nChanged lines: $lines"
    }

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

    fun buildDiffPreview(files: List<CommitPromptFile>): String {
        if (files.isEmpty()) return ""

        val builder = StringBuilder()
        for ((index, file) in files.withIndex()) {
            if (index > 0) builder.append('\n')
            builder.append("=== ${file.changeType}: ${file.path} ===\n")
            builder.append(
                when (file.changeType) {
                    "NEW" -> previewNewFile(file.afterContent)
                    "DELETED" -> "--- file deleted\n"
                    else -> previewModifiedFile(file.beforeContent, file.afterContent)
                }
            )

            if (builder.length > MAX_DIFF_CHARS) {
                return builder.take(MAX_DIFF_CHARS).toString().trimEnd() + "\n... [diff truncated]"
            }
        }
        return builder.toString().trim()
    }

    private fun previewNewFile(content: String?): String {
        if (content.isNullOrBlank()) return "+++ [new file content unavailable]\n"

        val snippet = content.take(MAX_NEW_FILE_CHARS)
        val prefix = if (content.length > MAX_NEW_FILE_CHARS) {
            "+++ [new file truncated]\n"
        } else {
            "+++\n"
        }
        return prefix + snippet.lines().take(MAX_CHANGED_LINES).joinToString("\n") + "\n"
    }

    private fun previewModifiedFile(before: String?, after: String?): String {
        if (before == null || after == null) return "[modified content unavailable]\n"

        val beforeLines = before.lines()
        val afterLines = after.lines()
        val maxLines = maxOf(beforeLines.size, afterLines.size)
        val changed = mutableListOf<String>()

        for (index in 0 until maxLines) {
            if (changed.size >= MAX_CHANGED_LINES) break
            val beforeLine = beforeLines.getOrNull(index)
            val afterLine = afterLines.getOrNull(index)
            if (beforeLine == afterLine) continue

            if (!beforeLine.isNullOrEmpty()) {
                changed += "- $beforeLine"
            }
            if (!afterLine.isNullOrEmpty() && changed.size < MAX_CHANGED_LINES) {
                changed += "+ $afterLine"
            }
        }

        if (changed.isEmpty()) {
            return "[content changed but no compact diff available]\n"
        }
        if (maxLines > MAX_CHANGED_LINES) {
            changed += "... [more changes omitted]"
        }
        return changed.joinToString("\n", postfix = "\n")
    }
}
