package com.github.codeplangui.execution

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ShellPlatformTest {

    // ── Unix.extractBaseCommand ──────────────────────────────────────

    @Test
    fun `Unix extractBaseCommand returns first word`() {
        assertEquals("cargo", ShellPlatform.Unix.extractBaseCommand("cargo test --workspace"))
    }

    @Test
    fun `Unix extractBaseCommand strips unix path prefix`() {
        assertEquals("git", ShellPlatform.Unix.extractBaseCommand("/usr/bin/git status"))
    }

    @Test
    fun `Unix extractBaseCommand returns first word before pipe`() {
        assertEquals("ls", ShellPlatform.Unix.extractBaseCommand("ls src/ | grep kt"))
    }

    // ── Windows.extractBaseCommand ───────────────────────────────────

    @Test
    fun `Windows extractBaseCommand returns cmdlet name`() {
        assertEquals("Get-ChildItem", ShellPlatform.Windows.extractBaseCommand("Get-ChildItem src/"))
    }

    @Test
    fun `Windows extractBaseCommand strips windows path and exe suffix`() {
        assertEquals("git", ShellPlatform.Windows.extractBaseCommand("C:\\Program Files\\Git\\bin\\git.exe status"))
    }

    @Test
    fun `Windows extractBaseCommand strips forward-slash path`() {
        assertEquals("npm", ShellPlatform.Windows.extractBaseCommand("/c/Program Files/nodejs/npm install"))
    }

    // ── Unix.hasPathsOutsideWorkspace ────────────────────────────────

    @Test
    fun `Unix hasPathsOutside returns false for relative paths`() {
        assertFalse(ShellPlatform.Unix.hasPathsOutsideWorkspace("ls src/main", "/home/user/project"))
    }

    @Test
    fun `Unix hasPathsOutside returns true for absolute path outside project`() {
        assertTrue(ShellPlatform.Unix.hasPathsOutsideWorkspace("cat /etc/passwd", "/home/user/project"))
    }

    @Test
    fun `Unix hasPathsOutside returns false for absolute path inside project`() {
        assertFalse(ShellPlatform.Unix.hasPathsOutsideWorkspace(
            "cat /home/user/project/src/main.kt", "/home/user/project"
        ))
    }

    @Test
    fun `Unix hasPathsOutside returns false for flag tokens`() {
        assertFalse(ShellPlatform.Unix.hasPathsOutsideWorkspace("ls -la /home/user/project/src", "/home/user/project"))
    }

    // ── Windows.hasPathsOutsideWorkspace ─────────────────────────────

    @Test
    fun `Windows hasPathsOutside returns false for relative paths`() {
        assertFalse(ShellPlatform.Windows.hasPathsOutsideWorkspace("Get-ChildItem src\\main", "C:\\Users\\user\\project"))
    }

    @Test
    fun `Windows hasPathsOutside returns true for drive path outside project`() {
        assertTrue(ShellPlatform.Windows.hasPathsOutsideWorkspace(
            "Get-Content C:\\Windows\\System32\\drivers\\etc\\hosts",
            "C:\\Users\\user\\project"
        ))
    }

    @Test
    fun `Windows hasPathsOutside returns false for drive path inside project`() {
        assertFalse(ShellPlatform.Windows.hasPathsOutsideWorkspace(
            "Get-Content C:\\Users\\user\\project\\src\\main.kt",
            "C:\\Users\\user\\project"
        ))
    }

    @Test
    fun `Windows hasPathsOutside returns true for UNC path outside project`() {
        assertTrue(ShellPlatform.Windows.hasPathsOutsideWorkspace(
            "Get-Content \\\\server\\share\\secret.txt",
            "C:\\Users\\user\\project"
        ))
    }

    @Test
    fun `Windows hasPathsOutside returns false for flag tokens`() {
        assertFalse(ShellPlatform.Windows.hasPathsOutsideWorkspace(
            "Get-ChildItem -Recurse C:\\Users\\user\\project\\src",
            "C:\\Users\\user\\project"
        ))
    }

    // ── toolName ─────────────────────────────────────────────────────

    @Test
    fun `Unix toolName returns run_command`() {
        assertEquals("run_command", ShellPlatform.Unix.toolName())
    }

    @Test
    fun `Windows toolName returns run_powershell`() {
        assertEquals("run_powershell", ShellPlatform.Windows.toolName())
    }

    // ── shellHint ─────────────────────────────────────────────────────

    @Test
    fun `Unix shellHint returns empty string`() {
        assertEquals("", ShellPlatform.Unix.shellHint())
    }

    @Test
    fun `Windows shellHint returns non-empty string`() {
        assertTrue(ShellPlatform.Windows.shellHint().isNotEmpty())
    }

    // ── current ───────────────────────────────────────────────────────

    @Test
    fun `current returns Windows when os name contains win`() {
        val original = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Windows 11")
            assertSame(ShellPlatform.Windows, ShellPlatform.current())
        } finally {
            System.setProperty("os.name", original)
        }
    }

    @Test
    fun `current returns Unix when os name does not contain win`() {
        val original = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Mac OS X")
            assertSame(ShellPlatform.Unix, ShellPlatform.current())
        } finally {
            System.setProperty("os.name", original)
        }
    }

    // ── defaultWhitelist ─────────────────────────────────────────────

    @Test
    fun `Unix defaultWhitelist contains unix commands`() {
        val list = ShellPlatform.Unix.defaultWhitelist()
        assertTrue(list.containsAll(listOf("git", "ls", "cat", "grep", "find", "echo", "pwd")))
    }

    @Test
    fun `Windows defaultWhitelist contains powershell cmdlets`() {
        val list = ShellPlatform.Windows.defaultWhitelist()
        assertTrue(list.containsAll(listOf("git", "Get-ChildItem", "Get-Content", "Select-String")))
        assertFalse(list.contains("ls"))
        assertFalse(list.contains("cat"))
    }
}
