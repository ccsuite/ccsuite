package ccsuite.ccacc.trim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Claude Code renames its running exe when it updates itself (seen 2026-11-10: every live session turned into claude.exe.old.<timestamp>.<pid>), and the trimmer must still know them. */
class ClaudeExeNameTest {

    @Test void claudeExeAndItsRenamedFormAreClaude() {
        assertTrue(WinProbe.isClaudeExe("C:\\Users\\u\\.local\\bin\\claude.exe"));
        assertTrue(WinProbe.isClaudeExe("C:\\Users\\u\\.local\\bin\\CLAUDE.EXE"));
        assertTrue(WinProbe.isClaudeExe("C:\\Users\\u\\.local\\bin\\claude.exe.old.1794278190966.29880"));
        assertTrue(WinProbe.isClaudeExe("C:\\Users\\u\\.local\\bin\\claude.exe.old.1794278190966"));
    }

    @Test void aRunningClaudeOfTheUserIsOursEvenWhenRenamed() {
        assertTrue(WinProbe.ours("C:\\Users\\u\\.local\\bin\\claude.exe", "PC\\User", "User"));
        assertTrue(WinProbe.ours("C:\\Users\\u\\.local\\bin\\claude.exe.old.1794278190966.29880", "PC\\User", "user"));
    }

    @Test void anotherUsersClaudeAnUnreadableOwnerOrAnotherProgramIsNotOurs() {
        assertFalse(WinProbe.ours("C:\\Users\\u\\.local\\bin\\claude.exe", "PC\\Other", "User"));
        assertFalse(WinProbe.ours("C:\\Users\\u\\.local\\bin\\claude.exe", "", "User"));
        assertFalse(WinProbe.ours("C:\\Windows\\System32\\PING.EXE", "PC\\User", "User"));
        assertFalse(WinProbe.ours("", "PC\\User", "User"));
    }

    @Test void nothingElseIs() {
        assertFalse(WinProbe.isClaudeExe("C:\\Windows\\System32\\PING.EXE"));
        assertFalse(WinProbe.isClaudeExe("C:\\x\\notclaude.exe"));
        assertFalse(WinProbe.isClaudeExe("C:\\x\\claude.exe.bak"));
        assertFalse(WinProbe.isClaudeExe("C:\\x\\claude.exe.old.abc"));
        assertFalse(WinProbe.isClaudeExe("C:\\x\\claude.exe.old.1.2.3"));
        assertFalse(WinProbe.isClaudeExe("C:\\x\\claude.exe\\child.exe"));
        assertFalse(WinProbe.isClaudeExe("claude.exe"));
    }
}
