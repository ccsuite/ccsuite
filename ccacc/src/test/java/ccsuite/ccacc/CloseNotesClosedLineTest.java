package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** PRP 86: the lane learns that a cycle is closed from the closed line the Stop hook appends to the decision log (CloseNotes.markClosed was never called). Neutral fixtures. */
class CloseNotesClosedLineTest {

    static LaneSettings small() {
        var s = new LaneSettings();
        s.startCostTokens = 0; s.workMarginTokens = 0;
        s.freeWarnTokens = 1_000; s.freeCloseTokens = 2_000;
        return s;
    }

    static void append(Path log, String line) throws Exception { Files.writeString(log, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }

    @Test void aClosedLineAfterTheCloseEndsTheOpenOrder(@TempDir Path dir) throws Exception {
        var log = dir.resolve("decisions.log");
        var n = new CloseNotes(small(), log);
        assertNotNull(n.note("s-1", "A", 2_500, 100));
        assertTrue(n.orderOpen("s-1"));
        append(log, Instant.now() + " closed session=s-1");
        assertFalse(n.orderOpen("s-1"), "the Stop hook wrote closed: the order is no longer open");
        assertNull(n.note("s-1", "A", 2_600, 100), "and no new order is given while the size stays above close");
    }

    @Test void aClosedLineOfAnotherSessionChangesNothing(@TempDir Path dir) throws Exception {
        var log = dir.resolve("decisions.log");
        var n = new CloseNotes(small(), log);
        n.note("s-2", "A", 2_500, 100);
        append(log, Instant.now() + " closed session=s-other");
        assertTrue(n.orderOpen("s-2"));
    }

    @Test void aClosedLineFromAnEarlierCycleDoesNotCloseTheNewOrder(@TempDir Path dir) throws Exception {
        var log = dir.resolve("decisions.log");
        var n = new CloseNotes(small(), log);
        n.note("s-3", "A", 2_500, 100);
        append(log, Instant.now() + " closed session=s-3");
        assertFalse(n.orderOpen("s-3"));
        n.note("s-3", "A", 100, 100);                      // back under warn: a new cycle
        assertNotNull(n.note("s-3", "A", 2_500, 100));     // a second close order, logged after the old closed line
        assertTrue(n.orderOpen("s-3"), "the old closed line lies before the new close");
    }

    @Test void aMissingOrUnreadableLogNeverStopsTheCall(@TempDir Path dir) throws Exception {
        var n = new CloseNotes(small(), dir.resolve("nowhere").resolve("decisions.log"));
        assertDoesNotThrow(() -> n.orderOpen("s-4"));
        assertNotNull(n.note("s-4", "A", 2_500, 100));
        assertTrue(n.orderOpen("s-4"));
    }
}
