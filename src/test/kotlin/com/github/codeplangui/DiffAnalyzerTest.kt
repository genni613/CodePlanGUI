package com.github.codeplangui

import com.github.codeplangui.action.CommitPromptFile
import com.github.codeplangui.action.DiffAnalyzer
import com.github.codeplangui.settings.SettingsState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiffAnalyzerTest {

    private val defaultSettings = SettingsState()

    @Test
    fun `analyze returns FULL level for small diff`() {
        val files = listOf(
            CommitPromptFile(
                path = "src/App.kt",
                changeType = "MODIFICATION",
                beforeContent = "line1\nline2",
                afterContent = "line1\nline2\nline3"
            )
        )

        val result = DiffAnalyzer().analyze(files, defaultSettings)

        assertEquals(DiffAnalyzer.CompressionLevel.FULL, result.level)
        assertEquals(1, result.totalDiffLines)
    }

    @Test
    fun `analyze returns STATS level for large diff`() {
        val files = listOf(
            CommitPromptFile(
                path = "src/Large.kt",
                changeType = "MODIFICATION",
                beforeContent = "line1\nline2",
                afterContent = (1..600).joinToString("\n") { "line$it" }
            )
        )

        val result = DiffAnalyzer().analyze(files, defaultSettings)

        assertEquals(DiffAnalyzer.CompressionLevel.STATS, result.level)
    }

    @Test
    fun `filterFiles respects 50 percent rule`() {
        // Create multi-line content for code file (200 lines vs 1 line for docs)
        val codeBefore = (1..100).joinToString("\n") { "line$it" }
        val codeAfter = (1..200).joinToString("\n") { "line$it" }

        val files = listOf(
            CommitPromptFile(
                path = "src/Code.kt",
                changeType = "MODIFICATION",
                beforeContent = codeBefore,
                afterContent = codeAfter
            ),
            CommitPromptFile(
                path = "docs/README.md",
                changeType = "MODIFICATION",
                beforeContent = "old",
                afterContent = "new"
            )
        )

        val result = DiffAnalyzer().analyze(files, defaultSettings)
        val filtered = DiffAnalyzer().filterFiles(result, defaultSettings)

        // Code file has 200 lines changed (added 100 new lines), docs has 1 line, total 201
        // Code lines (200) > 50% of total (100.5), so docs should be filtered
        assertEquals(1, filtered.size)
        assertEquals("src/Code.kt", filtered[0].path)
    }

    @Test
    fun `filterFiles skips lock files`() {
        val files = listOf(
            CommitPromptFile(
                path = "src/Code.kt",
                changeType = "MODIFICATION",
                beforeContent = "a",
                afterContent = "b"
            ),
            CommitPromptFile(
                path = "package-lock.json",
                changeType = "MODIFICATION",
                beforeContent = "old",
                afterContent = "new"
            )
        )

        val result = DiffAnalyzer().analyze(files, defaultSettings)
        val filtered = DiffAnalyzer().filterFiles(result, defaultSettings)

        assertFalse(filtered.any { it.path.contains("package-lock") })
    }

    @Test
    fun `filterFiles skips binary files`() {
        val files = listOf(
            CommitPromptFile(
                path = "src/Code.kt",
                changeType = "MODIFICATION",
                beforeContent = "a",
                afterContent = "b"
            ),
            CommitPromptFile(
                path = "image.png",
                changeType = "MODIFICATION",
                beforeContent = null,
                afterContent = null
            )
        )

        val result = DiffAnalyzer().analyze(files, defaultSettings)
        val filtered = DiffAnalyzer().filterFiles(result, defaultSettings)

        assertFalse(filtered.any { it.path.endsWith(".png") })
    }

    @Test
    fun `filterFiles limits to max files`() {
        val files = (1..30).map { i ->
            CommitPromptFile(
                path = "src/File$i.kt",
                changeType = "MODIFICATION",
                beforeContent = "old$i",
                afterContent = "new$i"
            )
        }

        val result = DiffAnalyzer().analyze(files, defaultSettings.copy(commitMaxFiles = 10))
        val filtered = DiffAnalyzer().filterFiles(result, defaultSettings.copy(commitMaxFiles = 10))

        assertEquals(10, filtered.size)
    }

    @Test
    fun `FileChange isCodeFile returns false for lock files`() {
        val lockFiles = listOf(
            "package-lock.json",
            "yarn.lock",
            "pnpm-lock.yaml",
            "Cargo.lock"
        )

        lockFiles.forEach { path ->
            val file = DiffAnalyzer.FileChange(path, 10, 5, "MODIFICATION")
            assertFalse(file.isCodeFile, "$path should not be considered code file")
        }
    }

    @Test
    fun `FileChange isCodeFile returns true for source files`() {
        val sourceFiles = listOf(
            "src/App.kt",
            "lib/util.ts",
            "internal/core.go"
        )

        sourceFiles.forEach { path ->
            val file = DiffAnalyzer.FileChange(path, 10, 5, "MODIFICATION")
            assertTrue(file.isCodeFile, "$path should be considered code file")
        }
    }

    @Test
    fun `buildStatsSummary groups files by category`() {
        val files = listOf(
            DiffAnalyzer.FileChange("src/App.kt", 50, 10, "MODIFICATION"),
            DiffAnalyzer.FileChange("src/Util.kt", 30, 5, "MODIFICATION"),
            DiffAnalyzer.FileChange("docs/readme.md", 10, 2, "MODIFICATION"),
            DiffAnalyzer.FileChange("package.json", 5, 3, "MODIFICATION")
        )

        val summary = DiffAnalyzer().buildStatsSummary(files)

        assertTrue(summary.contains("core: 2 files"))
        assertTrue(summary.contains("docs: 1 file"))
        assertTrue(summary.contains("config: 1 file"))
    }
}
