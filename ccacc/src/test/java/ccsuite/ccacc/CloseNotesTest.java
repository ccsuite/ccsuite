package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** PRP 86 R2: the warn and close lines the free lane adds to a request, once per cycle, never inside the close turns, and the decision log. Neutral fixtures. */
class CloseNotesTest {

    private final LaneSettings s = new LaneSettings();

    @Test void workZoneGetsNoNote(@TempDir Path dir) {
        var n = new CloseNotes(s, dir.resolve("decisions.log"));
        assertNull(n.note("sess-1", "AAA", 150_000, 100));
    }

    @Test void warnZoneGetsOneLineOnceAndNamesTheNumbers(@TempDir Path dir) {
        var n = new CloseNotes(s, dir.resolve("decisions.log"));
        var first = n.note("sess-1", "AAA", 210_000, 100);
        assertNotNull(first);
        assertTrue(first.contains("210") && first.contains("250"), first);
        assertFalse(first.contains("\n\n"), "one line");
        assertNull(n.note("sess-1", "AAA", 215_000, 100), "never twice for the same cycle");
    }

    @Test void closeZoneGetsTheOrderNamingTheThreeSteps(@TempDir Path dir) {
        var n = new CloseNotes(s, dir.resolve("decisions.log"));
        var order = n.note("sess-1", "AAA", 260_000, 100);
        assertNotNull(order);
        assertTrue(order.contains("proj prp logs") && order.contains("proj prp instructions") && order.toLowerCase().contains("status"), order);
    }

    @Test void theCloseTurnsThemselvesGetNothing(@TempDir Path dir) {
        var n = new CloseNotes(s, dir.resolve("decisions.log"));
        assertNotNull(n.note("sess-1", "AAA", 260_000, 100));
        assertNull(n.note("sess-1", "AAA", 262_000, 100), "the order is open: no second order");
        assertNull(n.note("sess-1", "AAA", 300_000, 100), "hard zone too");
        assertTrue(n.orderOpen("sess-1"));
    }

    @Test void aWarnedSessionStillGetsTheOrderLater(@TempDir Path dir) {
        var n = new CloseNotes(s, dir.resolve("decisions.log"));
        assertNotNull(n.note("sess-1", "AAA", 205_000, 100));
        assertNotNull(n.note("sess-1", "AAA", 255_000, 100));
    }

    @Test void theZonesFollowTheServingAccountsRoom(@TempDir Path dir) {
        var n = new CloseNotes(s, dir.resolve("decisions.log"));
        assertNull(n.note("sess-1", "AAA", 100_000, 100), "100K is work zone with full room");
        var half = new CloseNotes(s, dir.resolve("half.log"));
        assertNotNull(half.note("sess-2", "BBB", 135_000, 50), "half room: close at 130K");
        assertTrue(half.orderOpen("sess-2"));
    }

    @Test void sessionsAreIndependent(@TempDir Path dir) {
        var n = new CloseNotes(s, dir.resolve("decisions.log"));
        assertNotNull(n.note("sess-1", "AAA", 260_000, 100));
        assertNotNull(n.note("sess-2", "AAA", 260_000, 100));
    }

    @Test void closingTheCycleReopensNothingUntilANewCycle(@TempDir Path dir) {
        var n = new CloseNotes(s, dir.resolve("decisions.log"));
        n.note("sess-1", "AAA", 260_000, 100);
        n.markClosed("sess-1");
        assertFalse(n.orderOpen("sess-1"));
        assertNull(n.note("sess-1", "AAA", 265_000, 100), "closed cycle: no new order until the size falls back");
        assertNull(n.note("sess-1", "AAA", 20_000, 100));
        assertNotNull(n.note("sess-1", "AAA", 260_000, 100), "a new cycle (size fell back under work) can warn and close again");
    }

    @Test void everyDecisionIsLoggedWithTheEstimateTheZoneAndTheRoom(@TempDir Path dir) throws Exception {
        var log = dir.resolve("decisions.log");
        var n = new CloseNotes(s, log);
        n.note("sess-1", "AAA", 210_000, 100);
        n.note("sess-1", "AAA", 260_000, 100);
        var lines = Files.readAllLines(log);
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("warn") && lines.get(0).contains("210000") && lines.get(0).contains("room=100") && lines.get(0).contains("AAA"), lines.get(0));
        assertTrue(lines.get(1).contains("close") && lines.get(1).contains("260000"), lines.get(1));
    }

    @Test void aBrokenLogNeverBreaksTheCall(@TempDir Path dir) throws Exception {
        var blocker = dir.resolve("file");
        Files.writeString(blocker, "x");
        var n = new CloseNotes(s, blocker.resolve("decisions.log"));   // the parent is a file: writing fails
        assertNotNull(n.note("sess-1", "AAA", 260_000, 100), "fail open: the note is still given");
    }

    @Test void aMissingSessionIdIsNeverNoted(@TempDir Path dir) {
        var n = new CloseNotes(s, dir.resolve("decisions.log"));
        assertNull(n.note(null, "AAA", 260_000, 100));
        assertNull(n.note("", "AAA", 260_000, 100));
    }
}
