package com.github.codeplangui.execution

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class CommandExecutionService(private val project: Project) {

    suspend fun executeAsync(
        command: String,
        timeoutSeconds: Int
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val basePath = project.basePath
            ?: return@withContext ExecutionResult.Blocked(command, "Project path unavailable")
        val startMs = System.currentTimeMillis()

        val process = ProcessBuilder("sh", "-c", command)
            .directory(File(basePath))
            .redirectErrorStream(false)
            .start()

        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val durationMs = System.currentTimeMillis() - startMs

        if (!finished) {
            process.destroyForcibly()
            ExecutionResult.TimedOut(
                command = command,
                stdout = truncateOutput(stdout, 4000),
                timeoutSeconds = timeoutSeconds
            )
        } else {
            val truncated = stdout.length > 4000 || stderr.length > 2000
            if (process.exitValue() == 0) {
                ExecutionResult.Success(
                    command = command,
                    stdout = truncateOutput(stdout, 4000),
                    stderr = truncateOutput(stderr, 2000),
                    exitCode = 0,
                    durationMs = durationMs,
                    truncated = truncated
                )
            } else {
                ExecutionResult.Failed(
                    command = command,
                    stdout = truncateOutput(stdout, 4000),
                    stderr = truncateOutput(stderr, 2000),
                    exitCode = process.exitValue(),
                    durationMs = durationMs,
                    truncated = truncated
                )
            }
        }
    }

    suspend fun executeAsyncWithStream(
        command: String,
        timeoutSeconds: Int,
        onOutput: (line: String, isError: Boolean) -> Unit
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val basePath = project.basePath
            ?: return@withContext ExecutionResult.Blocked(command, "Project path unavailable")
        val startMs = System.currentTimeMillis()

        val process = ProcessBuilder("sh", "-c", command)
            .directory(File(basePath))
            .redirectErrorStream(false)
            .start()

        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()

        val stdoutThread = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                stdoutBuilder.appendLine(line)
                onOutput(line, false)
            }
        }
        val stderrThread = Thread {
            process.errorStream.bufferedReader().forEachLine { line ->
                stderrBuilder.appendLine(line)
                onOutput(line, true)
            }
        }
        stdoutThread.start()
        stderrThread.start()

        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        stdoutThread.join(1000)
        stderrThread.join(1000)

        val stdout = stdoutBuilder.toString()
        val stderr = stderrBuilder.toString()
        val durationMs = System.currentTimeMillis() - startMs

        if (!finished) {
            process.destroyForcibly()
            ExecutionResult.TimedOut(
                command = command,
                stdout = truncateOutput(stdout, 4000),
                timeoutSeconds = timeoutSeconds
            )
        } else {
            val truncated = stdout.length > 4000 || stderr.length > 2000
            if (process.exitValue() == 0) {
                ExecutionResult.Success(
                    command = command,
                    stdout = truncateOutput(stdout, 4000),
                    stderr = truncateOutput(stderr, 2000),
                    exitCode = 0,
                    durationMs = durationMs,
                    truncated = truncated
                )
            } else {
                ExecutionResult.Failed(
                    command = command,
                    stdout = truncateOutput(stdout, 4000),
                    stderr = truncateOutput(stderr, 2000),
                    exitCode = process.exitValue(),
                    durationMs = durationMs,
                    truncated = truncated
                )
            }
        }
    }

    companion object {
        fun extractBaseCommand(command: String): String {
            val stripped = command.trimStart()
            val base = stripped.split(" ", "|", ";", ">", "<", "&").first().trim()
            return base.substringAfterLast('/')
        }

        fun isWhitelisted(command: String, whitelist: List<String>): Boolean {
            if (whitelist.isEmpty()) return false
            val base = extractBaseCommand(command)
            return whitelist.any { it == base }
        }

        fun hasPathsOutsideWorkspace(command: String, basePath: String): Boolean {
            val tokens = command.split("\\s+".toRegex())
            val home = System.getProperty("user.home") ?: ""
            return tokens.any { token ->
                if (token.startsWith('-')) return@any false
                val expanded = if (token.startsWith("~/")) home + token.drop(1) else token
                if (!expanded.startsWith('/')) return@any false
                !expanded.startsWith(basePath)
            }
        }

        fun truncateOutput(text: String, maxChars: Int): String =
            if (text.length <= maxChars) text else text.take(maxChars)

        fun getInstance(project: Project): CommandExecutionService =
            project.getService(CommandExecutionService::class.java)
    }
}
