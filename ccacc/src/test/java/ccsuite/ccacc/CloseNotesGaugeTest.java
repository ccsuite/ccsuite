package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** PRP 86 3b: every warn and close line of the decision log carries the account's gauge reading at that call (five-hour and seven-day, as fractions), so a later check can set the
 *  estimate against the impact the calls really had (the user's plus or minus 5 percent test). Neutral fixtures. */
class CloseNotesGaugeTest {

    @Test void warnAndCloseLinesCarryTheGaugeReading(@TempDir Path dir) throws Exception {
        var log = dir.resolve("decisions.log");
        var n = new CloseNotes(CloseNotesClosedLineTest.small(), log);
        assertNotNull(n.note("s-1", "A", 1_500, 100, 0.42, 0.1));
        assertNotNull(n.note("s-1", "A", 2_500, 100, 0.44, 0.1));
        var lines = Files.readAllLines(log);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains(" warn ") && lines.get(0).contains(" five=0.42 seven=0.1 "), lines.get(0));
        assertTrue(lines.get(1).contains(" close ") && lines.get(1).contains(" five=0.44 seven=0.1 "), lines.get(1));
        assertTrue(lines.get(1).endsWith(" session=s-1"), "the session stays the last field: the closed-line reader depends on it");
    }

    @Test void anUnknownGaugeIsWrittenAsADashNotLeftOut(@TempDir Path dir) throws Exception {
        var log = dir.resolve("decisions.log");
        var n = new CloseNotes(CloseNotesClosedLineTest.small(), log);
        assertNotNull(n.note("s-2", "A", 1_500, 100, null, null));
        assertTrue(Files.readString(log).contains(" five=- seven=- "));
    }

    @Test void theFourArgumentFormStillWorksAndWritesDashes(@TempDir Path dir) throws Exception {
        var log = dir.resolve("decisions.log");
        var n = new CloseNotes(CloseNotesClosedLineTest.small(), log);
        assertNotNull(n.note("s-3", "A", 1_500, 100));
        assertTrue(Files.readString(log).contains(" five=- seven=- "));
    }

    @Test void aGaugeLineStillEndsAnOrderThroughTheClosedLine(@TempDir Path dir) throws Exception {
        var log = dir.resolve("decisions.log");
        var n = new CloseNotes(CloseNotesClosedLineTest.small(), log);
        assertNotNull(n.note("s-4", "A", 2_500, 100, 0.5, 0.2));
        assertTrue(n.orderOpen("s-4"));
        CloseNotesClosedLineTest.append(log, Instant.now() + " closed session=s-4");
        assertFalse(n.orderOpen("s-4"));
    }
}
