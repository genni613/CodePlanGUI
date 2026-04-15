package com.github.codeplangui.action

import com.github.codeplangui.settings.SettingsState

class DiffAnalyzer {

    enum class CompressionLevel {
        FULL,  // diff < threshold: two-stage generation
        STATS  // diff >= threshold: stats-only generation
    }

    data class FileChange(
        val path: String,
        val additions: Int,
        val deletions: Int,
        val changeType: String  // "NEW", "DELETED", "MODIFIED"
    ) {
        val totalLines: Int get() = additions + deletions

        val isCodeFile: Boolean
            get() {
                val lower = path.lowercase()
                return !lower.endsWith(".md") &&
                    !lower.endsWith(".txt") &&
                    !lower.endsWith(".rst") &&
                    !lower.contains("package-lock.json") &&
                    !lower.contains("cargo.lock") &&
                    !lower.contains("pnpm-lock.yaml") &&
                    !lower.contains("yarn.lock") &&
                    !lower.contains(".lock") &&
                    !lower.endsWith(".png") &&
                    !lower.endsWith(".jpg") &&
                    !lower.endsWith(".jpeg") &&
                    !lower.endsWith(".gif") &&
                    !lower.endsWith(".bmp") &&
                    !lower.endsWith(".ico") &&
                    !lower.endsWith(".pdf") &&
                    !lower.endsWith(".class") &&
                    !lower.endsWith(".o") &&
                    !lower.endsWith(".obj") &&
                    !lower.contains(".code/") &&
                    !lower.contains(".idea/")
            }
    }

    data class AnalysisResult(
        val level: CompressionLevel,
        val totalDiffLines: Int,
        val fileChanges: List<FileChange>,
        val codeLineCount: Int,
        val nonCodeLineCount: Int
    )

    fun analyze(files: List<CommitPromptFile>, settings: SettingsState): AnalysisResult {
        val fileChanges = files.map { file ->
            val (additions, deletions) = countChanges(file)
            FileChange(
                path = file.path,
                additions = additions,
                deletions = deletions,
                changeType = file.changeType
            )
        }.sortedByDescending { it.totalLines }

        val totalDiffLines = fileChanges.sumOf { it.totalLines }
        val codeLineCount = fileChanges.filter { it.isCodeFile }.sumOf { it.totalLines }
        val nonCodeLineCount = totalDiffLines - codeLineCount

        val level = if (totalDiffLines < settings.commitDiffLineLimit) {
            CompressionLevel.FULL
        } else {
            CompressionLevel.STATS
        }

        return AnalysisResult(
            level = level,
            totalDiffLines = totalDiffLines,
            fileChanges = fileChanges,
            codeLineCount = codeLineCount,
            nonCodeLineCount = nonCodeLineCount
        )
    }

    fun filterFiles(result: AnalysisResult, settings: SettingsState): List<FileChange> {
        var files = result.fileChanges

        // 50% rule: if code lines > 50% of total, filter out non-code files
        if (result.codeLineCount > 0 &&
            result.codeLineCount > (result.totalDiffLines * 0.5) &&
            result.nonCodeLineCount > 0
        ) {
            files = files.filter { it.isCodeFile }
        }

        // Skip lock files and binary files (already handled in isCodeFile)
        files = files.filter { file ->
            val lower = file.path.lowercase()
            !lower.contains("package-lock.json") &&
                !lower.contains("cargo.lock") &&
                !lower.contains("pnpm-lock.yaml") &&
                !lower.contains("yarn.lock") &&
                !lower.endsWith(".png") &&
                !lower.endsWith(".jpg") &&
                !lower.endsWith(".jpeg") &&
                !lower.endsWith(".gif") &&
                !lower.endsWith(".bmp") &&
                !lower.endsWith(".ico") &&
                !lower.endsWith(".pdf") &&
                !lower.endsWith(".class") &&
                !lower.endsWith(".o") &&
                !lower.endsWith(".obj")
        }

        // Skip .code/ and .idea/ unless those are the only files changed
        val nonIdeaFiles = files.filter {
            !it.path.contains(".code/") && !it.path.contains(".idea/")
        }
        if (nonIdeaFiles.isNotEmpty()) {
            files = nonIdeaFiles
        }

        // Limit to max files
        if (settings.commitMaxFiles > 0 && files.size > settings.commitMaxFiles) {
            files = files.take(settings.commitMaxFiles)
        }

        return files
    }

    fun buildStatsSummary(files: List<FileChange>): String {
        val grouped = files.groupBy { categorizeFile(it.path) }
        return grouped.entries.joinToString(", ") { (category, filesInCategory) ->
            val count = filesInCategory.size
            val additions = filesInCategory.sumOf { it.additions }
            val deletions = filesInCategory.sumOf { it.deletions }
            "$category: $count files (+$additions -$deletions)"
        }
    }

    private fun categorizeFile(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.startsWith("src/") || lower.startsWith("lib/") || lower.startsWith("internal/") -> "core"
            lower.startsWith("test/") || lower.startsWith("tests/") || lower.startsWith("spec/") -> "test"
            lower.startsWith("docs/") || lower.endsWith(".md") -> "docs"
            lower.contains("package-lock") || lower.contains("cargo.lock") || lower.contains("pnpm-lock") -> "lock"
            lower.contains("config") || lower.contains("settings") -> "config"
            lower.endsWith(".json") -> "config"
            lower.contains(".idea/") || lower.contains(".code/") -> "ide"
            else -> "other"
        }
    }

    private fun countNewLines(before: String?, after: String?): Int {
        if (after == null) return 0
        val beforeLines = before?.lines()?.toSet() ?: emptySet()
        return after.lines().count { it !in beforeLines }
    }

    private fun countDeletedLines(before: String?, after: String?): Int {
        if (before == null) return 0
        val afterLines = after?.lines()?.toSet() ?: emptySet()
        return before.lines().count { it !in afterLines }
    }

    private fun countChanges(file: CommitPromptFile): Pair<Int, Int> {
        return when (file.changeType) {
            "NEW" -> {
                val lines = file.afterContent?.lines()?.size ?: 0
                Pair(lines, 0)
            }
            "DELETED" -> {
                val lines = file.beforeContent?.lines()?.size ?: 0
                Pair(0, lines)
            }
            else -> {
                // For MODIFICATION and other types, use line counts as proxy
                val beforeLines = file.beforeContent?.lines()?.size ?: 0
                val afterLines = file.afterContent?.lines()?.size ?: 0
                val added = maxOf(0, afterLines - beforeLines)
                val removed = maxOf(0, beforeLines - afterLines)
                // If both are non-zero but equal, assume some lines changed
                if (added == 0 && removed == 0 && beforeLines > 0 && afterLines > 0) {
                    Pair(afterLines, beforeLines)
                } else {
                    Pair(added, removed)
                }
            }
        }
    }
}
