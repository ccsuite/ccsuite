package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** PRP 86 R4 and R7: the free lane writes one small file per Claude Code session (account, estimated input tokens, the zones, the room); the status line only reads it. Neutral fixtures. */
class SessionStatusTest {

    static final Instant T = Instant.parse("2026-01-02T03:04:05Z");

    @Test void theFileHoldsTheAccountTheTokensAndTheZones(@TempDir Path dir) throws Exception {
        SessionStatus.write(dir, "s-1", "AAA", 132_000, new Zones(200_000, 250_000, 340_000, 63), T);
        var text = Files.readString(dir.resolve("s-1.txt"));
        assertTrue(text.contains("account=AAA"), text);
        assertTrue(text.contains("tokens=132000") && text.contains("warn=200000") && text.contains("close=250000") && text.contains("hard=340000"), text);
        assertTrue(text.contains("room=63") && text.contains("at=" + T), text);
    }

    @Test void aLaterCallRewritesTheFileSoTheAccountFollowsTheLane(@TempDir Path dir) throws Exception {
        SessionStatus.write(dir, "s-2", "AAA", 1_000, new Zones(1, 2, 3, 100), T);
        SessionStatus.write(dir, "s-2", "BBB", 2_000, new Zones(1, 2, 3, 100), T.plusSeconds(5));
        var text = Files.readString(dir.resolve("s-2.txt"));
        assertTrue(text.contains("account=BBB") && !text.contains("account=AAA"), text);
        try (var files = Files.list(dir)) { assertEquals(1, files.count(), "no temporary file is left behind"); }
    }

    @Test void aSessionIdThatIsNotPlainNeverWritesOutsideTheFolder(@TempDir Path dir) throws Exception {
        var inner = Files.createDirectory(dir.resolve("inner"));
        SessionStatus.write(inner, "../escape", "AAA", 1, new Zones(1, 2, 3, 100), T);
        SessionStatus.write(inner, "a/b", "AAA", 1, new Zones(1, 2, 3, 100), T);
        try (var files = Files.walk(dir)) { assertEquals(0, files.filter(Files::isRegularFile).count(), "nothing was written, inside or outside the folder"); }
    }

    @Test void aFolderThatCannotBeWrittenNeverThrows(@TempDir Path dir) throws Exception {
        var blocker = Files.writeString(dir.resolve("blocker"), "x");
        assertDoesNotThrow(() -> SessionStatus.write(blocker.resolve("sub"), "s-3", "AAA", 1, new Zones(1, 2, 3, 100), T));
    }

    @Test void aMissingAccountIsWrittenAsNone(@TempDir Path dir) throws Exception {
        SessionStatus.write(dir, "s-4", null, 1, new Zones(1, 2, 3, 100), T);
        assertTrue(Files.readString(dir.resolve("s-4.txt")).contains("account=-"));
    }
}
