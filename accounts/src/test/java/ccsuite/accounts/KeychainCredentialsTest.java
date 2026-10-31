package ccsuite.accounts;

import ccsuite.accounts.KeychainCredentials.Runner;
import ccsuite.accounts.KeychainCredentials.Runner.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class KeychainCredentialsTest {

    static final String JSON = "{\"claudeAiOauth\":{\"accessToken\":\"a\"}}";

    /** A fake `security`: answers from a function and records every command. */
    static final class Fake implements Runner {
        final List<List<String>> calls = new ArrayList<>();
        final Function<List<String>, Result> answer;
        Fake(Function<List<String>, Result> answer) { this.answer = answer; }
        @Override public Result run(List<String> command) { calls.add(command); return answer.apply(command); }
    }

    static Result ok(String out) { return new Result(0, out, ""); }
    static Result missing() { return new Result(44, "", "The specified item could not be found in the keychain."); }

    KeychainCredentials keychain(Fake f, Path file) {
        return new KeychainCredentials(f, "Claude Code-credentials", "someone", new FileCredentials(file));
    }

    @Test void theLoginIsReadFromTheKeychainItemOfThatServiceAndUser(@TempDir Path dir) throws IOException {
        var f = new Fake(c -> ok(JSON + "\n"));
        assertEquals(JSON, keychain(f, dir.resolve(".credentials.json")).read());
        assertEquals(List.of("security", "find-generic-password", "-s", "Claude Code-credentials", "-a", "someone", "-w"), f.calls.get(0));
    }

    @Test void noKeychainItemFallsBackToTheFileWhenThereIsOne(@TempDir Path dir) throws IOException {
        var file = dir.resolve(".credentials.json");
        Files.writeString(file, JSON);
        assertEquals(JSON, keychain(new Fake(c -> missing()), file).read());
    }

    @Test void noKeychainItemAndNoFileMeansNoLogin(@TempDir Path dir) throws IOException {
        assertNull(keychain(new Fake(c -> missing()), dir.resolve(".credentials.json")).read());
    }

    @Test void aRefusedAccessIsAnErrorThatSaysWhatToDo(@TempDir Path dir) {
        var f = new Fake(c -> new Result(51, "", "User interaction is not allowed."));
        var e = assertThrows(IOException.class, () -> keychain(f, dir.resolve(".credentials.json")).read());
        assertTrue(e.getMessage().contains("Always Allow"), e.getMessage());
    }

    @Test void writingUpdatesTheKeychainItemInPlace(@TempDir Path dir) throws IOException {
        var f = new Fake(c -> ok(""));
        keychain(f, dir.resolve(".credentials.json")).write(JSON);
        assertEquals(List.of("security", "add-generic-password", "-U", "-s", "Claude Code-credentials", "-a", "someone", "-w", JSON), f.calls.get(1));
    }

    @Test void whenTheLoginLivesInTheFileTheFileIsWrittenNotTheKeychain(@TempDir Path dir) throws IOException {
        var file = dir.resolve(".credentials.json");
        Files.writeString(file, "{}");
        var f = new Fake(c -> missing());
        keychain(f, file).write(JSON);
        assertEquals(JSON, Files.readString(file));
        assertTrue(f.calls.stream().noneMatch(c -> c.contains("add-generic-password")));
    }

    @Test void aFailedWriteIsReported(@TempDir Path dir) {
        var f = new Fake(c -> c.contains("add-generic-password") ? new Result(1, "", "denied") : ok(""));
        assertThrows(IOException.class, () -> keychain(f, dir.resolve(".credentials.json")).write(JSON));
    }

    @Test void onlyMacWithoutAConfigDirectoryUsesTheKeychain() {
        var os = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Mac OS X");
            assertTrue(ClaudeCodePaths.keychainHere(false));
            assertFalse(ClaudeCodePaths.keychainHere(true));
            System.setProperty("os.name", "Windows 11");
            assertFalse(ClaudeCodePaths.keychainHere(false));
        } finally {
            System.setProperty("os.name", os);
        }
    }
}
