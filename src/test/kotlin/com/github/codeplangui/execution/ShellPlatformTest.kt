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

    @Test
    fun `Unix hasPathsOutside returns false when basePath has trailing slash`() {
        assertFalse(ShellPlatform.Unix.hasPathsOutsideWorkspace(
            "find /home/user/project -name \"*.kt\"", "/home/user/project/"
        ))
    }

    @Test
    fun `Unix hasPathsOutside returns false for dev null redirect token`() {
        assertFalse(ShellPlatform.Unix.hasPathsOutsideWorkspace(
            "find /home/user/project -name \"*.java\" 2>/dev/null", "/home/user/project"
        ))
    }

    @Test
    fun `Unix hasPathsOutside returns false for spaced dev null redirect`() {
        assertFalse(ShellPlatform.Unix.hasPathsOutsideWorkspace(
            "find /home/user/project -name \"*.java\" 2> /dev/null | head -20", "/home/user/project"
        ))
    }

    @Test
    fun `Unix hasPathsOutside returns true for dev path traversal`() {
        assertTrue(ShellPlatform.Unix.hasPathsOutsideWorkspace(
            "cat /dev/../../etc/passwd", "/home/user/project"
        ))
    }

    @Test
    fun `Unix hasPathsOutside returns true for path that shares prefix with project`() {
        assertTrue(ShellPlatform.Unix.hasPathsOutsideWorkspace(
            "cat /home/user/project-evil/secret.txt", "/home/user/project"
        ))
    }

    @Test
    fun `Unix hasPathsOutside returns false for exact project root path`() {
        assertFalse(ShellPlatform.Unix.hasPathsOutsideWorkspace(
            "ls /home/user/project", "/home/user/project"
        ))
    }

    // ── Unix.hasPathsOutsideWorkspace: quoted / heredoc stripping ────

    @Test
    fun `Unix hasPathsOutside ignores javadoc inside single quotes`() {
        assertFalse(ShellPlatform.Unix.hasPathsOutsideWorkspace(
            "echo '/** comment */' > ./src/Foo.java", "/home/user/project"
        ))
    }

    @Test
    fun `Unix hasPathsOutside ignores javadoc inside double quotes`() {
        assertFalse(ShellPlatform.Unix.hasPathsOutsideWorkspace(
            "echo \"/** comment */\" > ./src/Foo.java", "/home/user/project"
        ))
    }

    @Test
    fun `Unix hasPathsOutside ignores heredoc body with quoted delimiter`() {
        val cmd = "cat > ./src/Foo.java << 'EOF'\n/**\n * javadoc\n */\npublic class Foo {}\nEOF\n"
        assertFalse(ShellPlatform.Unix.hasPathsOutsideWorkspace(cmd, "/home/user/project"))
    }

    @Test
    fun `Unix hasPathsOutside ignores heredoc body with unquoted delimiter`() {
        val cmd = "cat > ./src/Foo.java << EOF\n/etc/passwd is just text here\nEOF\n"
        assertFalse(ShellPlatform.Unix.hasPathsOutsideWorkspace(cmd, "/home/user/project"))
    }

    @Test
    fun `Unix hasPathsOutside ignores tab-stripped heredoc body`() {
        val cmd = "cat <<-EOF\n\t/** stuff */\n\tEOF\n"
        assertFalse(ShellPlatform.Unix.hasPathsOutsideWorkspace(cmd, "/home/user/project"))
    }

    @Test
    fun `Unix hasPathsOutside still catches absolute path after heredoc`() {
        val cmd = "cat << 'EOF'\n/** safe body */\nEOF\ncat /etc/passwd"
        assertTrue(ShellPlatform.Unix.hasPathsOutsideWorkspace(cmd, "/home/user/project"))
    }

    @Test
    fun `Unix hasPathsOutside still catches absolute path between quoted segments`() {
        assertTrue(ShellPlatform.Unix.hasPathsOutsideWorkspace(
            "echo 'hello' /etc/passwd \"world\"", "/home/user/project"
        ))
    }

    @Test
    fun `Unix hasPathsOutside handles malformed unclosed quote without crashing`() {
        assertFalse(ShellPlatform.Unix.hasPathsOutsideWorkspace(
            "echo 'dangling /** quote", "/home/user/project"
        ))
    }

    @Test
    fun `Unix hasPathsOutside treats here-string as regular quoted arg`() {
        assertFalse(ShellPlatform.Unix.hasPathsOutsideWorkspace(
            "grep foo <<< '/** not a path */'", "/home/user/project"
        ))
    }

    @Test
    fun `Unix hasPathsOutside does not treat here-string body as heredoc`() {
        assertTrue(ShellPlatform.Unix.hasPathsOutsideWorkspace(
            "grep foo <<< /etc/passwd", "/home/user/project"
        ))
    }

    @Test
    fun `Unix hasPathsOutside handles python inline script with javadoc string`() {
        val cmd = "python3 -c \"open('./src/Foo.java','w').write('/** javadoc */')\""
        assertFalse(ShellPlatform.Unix.hasPathsOutsideWorkspace(cmd, "/home/user/project"))
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

    @Test
    fun `Windows hasPathsOutside returns true for path that shares prefix with project`() {
        assertTrue(ShellPlatform.Windows.hasPathsOutsideWorkspace(
            "Get-Content C:\\Users\\user\\project-evil\\secret.txt",
            "C:\\Users\\user\\project"
        ))
    }

    @Test
    fun `Windows hasPathsOutside returns false for exact project root path`() {
        assertFalse(ShellPlatform.Windows.hasPathsOutsideWorkspace(
            "Get-ChildItem C:\\Users\\user\\project",
            "C:\\Users\\user\\project"
        ))
    }

    @Test
    fun `Windows hasPathsOutside ignores drive path inside single quotes`() {
        assertFalse(ShellPlatform.Windows.hasPathsOutsideWorkspace(
            "Write-Output 'C:\\Windows\\System32 is just text'",
            "C:\\Users\\user\\project"
        ))
    }

    @Test
    fun `Windows hasPathsOutside ignores drive path inside double quotes`() {
        assertFalse(ShellPlatform.Windows.hasPathsOutsideWorkspace(
            "Write-Output \"C:\\Windows\\System32 is just text\"",
            "C:\\Users\\user\\project"
        ))
    }

    @Test
    fun `Windows hasPathsOutside still catches drive path between quoted segments`() {
        assertTrue(ShellPlatform.Windows.hasPathsOutsideWorkspace(
            "Write-Output 'safe' C:\\Windows\\System32\\hosts \"also safe\"",
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
