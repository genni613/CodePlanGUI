package com.github.codeplangui.execution

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class CommandExecutionServiceTest {

    @Test
    fun `extractBaseCommand returns first word for simple command`() {
        assertEquals("cargo", CommandExecutionService.extractBaseCommand("cargo test --workspace"))
    }

    @Test
    fun `extractBaseCommand strips path prefix`() {
        assertEquals("cargo", CommandExecutionService.extractBaseCommand("/usr/local/bin/cargo test"))
    }

    @Test
    fun `extractBaseCommand returns first word before pipe`() {
        assertEquals("ls", CommandExecutionService.extractBaseCommand("ls src/ | grep kt"))
    }

    @Test
    fun `isWhitelisted returns true when base command matches whitelist entry`() {
        val whitelist = listOf("cargo", "git", "ls")
        assertTrue(CommandExecutionService.isWhitelisted("cargo test --workspace", whitelist))
    }

    @Test
    fun `isWhitelisted returns false when base command is not in whitelist`() {
        val whitelist = listOf("cargo", "git")
        assertFalse(CommandExecutionService.isWhitelisted("rm -rf dist", whitelist))
    }

    @Test
    fun `isWhitelisted returns false for empty whitelist`() {
        assertFalse(CommandExecutionService.isWhitelisted("ls", emptyList()))
    }

    @Test
    fun `hasPathsOutsideWorkspace returns false for relative paths`() {
        assertFalse(CommandExecutionService.hasPathsOutsideWorkspace("ls src/main", "/home/user/project"))
    }

    @Test
    fun `hasPathsOutsideWorkspace returns true for absolute path outside project`() {
        assertTrue(CommandExecutionService.hasPathsOutsideWorkspace("cat /etc/passwd", "/home/user/project"))
    }

    @Test
    fun `hasPathsOutsideWorkspace returns false for absolute path inside project`() {
        assertFalse(CommandExecutionService.hasPathsOutsideWorkspace(
            "cat /home/user/project/src/main.kt",
            "/home/user/project"
        ))
    }

    @Test
    fun `truncateOutput trims output exceeding max chars`() {
        val long = "a".repeat(5000)
        val result = CommandExecutionService.truncateOutput(long, 4000)
        assertEquals(4000, result.length)
    }

    @Test
    fun `truncateOutput returns original when within limit`() {
        val short = "hello"
        assertEquals(short, CommandExecutionService.truncateOutput(short, 4000))
    }

    @Test
    fun `executeAsyncWithStream captures stdout lines`() = runBlocking {
        val service = serviceWithTempProject()
        val lines = mutableListOf<Pair<String, Boolean>>()
        val result = service.executeAsyncWithStream("echo hello && echo world", 5) { line, isError ->
            lines += Pair(line, isError)
        }
        assertInstanceOf(ExecutionResult.Success::class.java, result)
        val success = result as ExecutionResult.Success
        assertEquals(0, success.exitCode)
        assertTrue(lines.any { it.first == "hello" && !it.second })
        assertTrue(lines.any { it.first == "world" && !it.second })
    }

    @Test
    fun `executeAsyncWithStream captures stderr lines`() = runBlocking {
        val service = serviceWithTempProject()
        val lines = mutableListOf<Pair<String, Boolean>>()
        val result = service.executeAsyncWithStream("echo err >&2", 5) { line, isError ->
            lines += Pair(line, isError)
        }
        assertInstanceOf(ExecutionResult.Success::class.java, result)
        assertTrue(lines.any { it.first == "err" && it.second })
    }

    @Test
    fun `executeAsyncWithStream returns Failed for non-zero exit`() = runBlocking {
        val service = serviceWithTempProject()
        val result = service.executeAsyncWithStream("exit 42", 5) { _, _ -> }
        assertInstanceOf(ExecutionResult.Failed::class.java, result)
        assertEquals(42, (result as ExecutionResult.Failed).exitCode)
    }

    @Test
    fun `executeAsyncWithStream returns TimedOut for slow command`() = runBlocking {
        val service = serviceWithTempProject()
        val result = service.executeAsyncWithStream("sleep 30", 1) { _, _ -> }
        assertInstanceOf(ExecutionResult.TimedOut::class.java, result)
    }

    @Test
    fun `executeAsyncWithStream returns Blocked when project basePath is null`() = runBlocking {
        val project = mockProject(null)
        val service = CommandExecutionService(project)
        val result = service.executeAsyncWithStream("echo hi", 5) { _, _ -> }
        assertInstanceOf(ExecutionResult.Blocked::class.java, result)
    }

    private fun serviceWithTempProject(): CommandExecutionService {
        val tempDir = Files.createTempDirectory("ces-test").toFile()
        tempDir.deleteOnExit()
        return CommandExecutionService(mockProject(tempDir.absolutePath))
    }

    private fun mockProject(basePath: String?): Project {
        val project = mockk<Project>()
        every { project.basePath } returns basePath
        return project
    }
}