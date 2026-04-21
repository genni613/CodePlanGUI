package com.github.codeplangui.execution.executors

import com.github.codeplangui.execution.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Precise text replacement in files.
 * Shows IDE-native DiffDialog for review (when not trusted).
 */
class EditFileExecutor(
    private val fileChangeReview: FileChangeReview
) : ToolExecutor {

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val path = input["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "Missing required parameter: path")
        val search = input["search"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "Missing required parameter: search")
        val replace = input["replace"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "Missing required parameter: replace")
        val replaceAll = input["replaceAll"]?.jsonPrimitive?.booleanOrNull ?: false
        val lineNumber = input["line_number"]?.jsonPrimitive?.intOrNull

        val resolvedPath = ReadFileExecutor.resolveToolPath(path, context.cwd)
            ?: return ToolResult(ok = false, output = "Path resolves outside workspace: $path")

        return withContext(Dispatchers.IO) {
            val file = File(resolvedPath)
            if (!file.exists()) {
                return@withContext ToolResult(ok = false, output = "File not found: $path")
            }

            val originalContent = file.readText()
            if (!originalContent.contains(search)) {
                return@withContext ToolResult(ok = false, output = "Search text not found in $path")
            }

            // Count matches and find line numbers
            val matchLines = originalContent.lines().mapIndexedNotNull { idx, line ->
                if (line.contains(search)) idx + 1 else null
            }
            val matchCount = matchLines.size

            if (!replaceAll && matchCount > 1) {
                if (lineNumber == null) {
                    // Multiple matches without line_number — return match info for AI to disambiguate
                    return@withContext ToolResult(
                        ok = false,
                        output = "Found $matchCount matches for the search text in $path. " +
                            "Matching lines: ${matchLines.joinToString(", ")}. " +
                            "Provide 'line_number' parameter to specify which match to replace."
                    )
                }
                // Use line_number to target a specific match
                val targetLine = lineNumber
                if (targetLine !in matchLines) {
                    return@withContext ToolResult(
                        ok = false,
                        output = "No match found at line $targetLine. Matching lines: ${matchLines.joinToString(", ")}"
                    )
                }
            }

            // Generate new content
            val newContent = if (replaceAll) {
                // Use split/join instead of regex to avoid special char issues
                originalContent.split(search).joinToString(replace)
            } else if (lineNumber != null && matchCount > 1) {
                // Replace at specific line
                replaceAtLine(originalContent, search, replace, lineNumber)
            } else {
                // Single match or first occurrence
                originalContent.replaceFirst(search, replace)
            }

            if (newContent == originalContent) {
                return@withContext ToolResult(ok = false, output = "No changes made (replacement text same as search text)")
            }

            // Review via FileChangeReview (DiffDialog or trust mode)
            val approved = fileChangeReview.reviewFileChange(
                project = context.project,
                path = path,
                oldContent = originalContent,
                newContent = newContent,
                settings = context.settings
            )
            if (!approved) {
                return@withContext ToolResult(ok = false, output = "User rejected the change")
            }

            // Write the file
            writeFileContent(context.project, resolvedPath, newContent)

            // Compute diff stats
            val oldLines = originalContent.lines().size
            val newLines = newContent.lines().size
            val diffLines = Math.abs(newLines - oldLines)
            val changeType = if (newLines > oldLines) "+$diffLines" else "-$diffLines"

            // Run Post-Edit pipeline
            val postEditResult = runPostEdit(context.project, resolvedPath)

            val output = buildString {
                append("File edited successfully: $path ($changeType lines)")
                if (postEditResult != null) {
                    append("\n\n")
                    append(postEditResult)
                }
            }

            ToolResult(ok = true, output = output)
        }
    }

    private fun replaceAtLine(content: String, search: String, replace: String, targetLine: Int): String {
        val lines = content.lines().toMutableList()
        if (targetLine < 1 || targetLine > lines.size) return content
        val idx = targetLine - 1
        if (lines[idx].contains(search)) {
            lines[idx] = lines[idx].replaceFirst(search, replace)
        }
        return lines.joinToString("\n")
    }

    private fun writeFileContent(project: Project, path: String, content: String) {
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val file = File(path)
                file.writeText(content)
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                vf?.refresh(false, false)
            }
        }
    }

    private fun runPostEdit(project: Project, path: String): String? {
        return try {
            val vf = LocalFileSystem.getInstance().findFileByIoFile(File(path)) ?: return null
            PostEditPipeline(project).runAfterWriteSync(vf)
        } catch (_: Exception) {
            null
        }
    }
}
