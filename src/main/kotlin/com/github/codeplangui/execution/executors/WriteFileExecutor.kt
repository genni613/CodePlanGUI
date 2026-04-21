package com.github.codeplangui.execution.executors

import com.github.codeplangui.execution.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Whole-file write (create or overwrite).
 * Shows IDE-native DiffDialog for existing files, ConfirmDialog for new files.
 */
class WriteFileExecutor(
    private val fileChangeReview: FileChangeReview
) : ToolExecutor {

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val path = input["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "Missing required parameter: path")
        val content = input["content"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "Missing required parameter: content")

        val resolvedPath = ReadFileExecutor.resolveToolPath(path, context.cwd)
            ?: return ToolResult(ok = false, output = "Path resolves outside workspace: $path")

        return withContext(Dispatchers.IO) {
            val file = File(resolvedPath)
            val isNewFile = !file.exists()

            if (isNewFile) {
                // New file: confirm via IDE dialog
                val confirmed = fileChangeReview.reviewNewFile(
                    project = context.project,
                    path = path,
                    content = content,
                    settings = context.settings
                )
                if (!confirmed) {
                    return@withContext ToolResult(ok = false, output = "User rejected file creation")
                }

                // Ensure parent dirs exist
                file.parentFile?.mkdirs()
                writeFileContent(context.project, resolvedPath, content)
            } else {
                // Existing file: diff review
                val originalContent = file.readText()
                if (originalContent == content) {
                    return@withContext ToolResult(ok = true, output = "File unchanged: $path")
                }

                val approved = fileChangeReview.reviewFileChange(
                    project = context.project,
                    path = path,
                    oldContent = originalContent,
                    newContent = content,
                    settings = context.settings
                )
                if (!approved) {
                    return@withContext ToolResult(ok = false, output = "User rejected the change")
                }

                writeFileContent(context.project, resolvedPath, content)
            }

            // Post-Edit pipeline
            val postEditResult = runPostEdit(context.project, resolvedPath)

            val verb = if (isNewFile) "created" else "written"
            val lineCount = content.lines().size
            val sizeBytes = content.toByteArray().size
            val output = buildString {
                append("File $verb: $path ($lineCount lines, ${formatSize(sizeBytes)})")
                if (postEditResult != null) {
                    append("\n\n")
                    append(postEditResult)
                }
            }

            ToolResult(ok = true, output = output)
        }
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

    private fun formatSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}
