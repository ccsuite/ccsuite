package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Whether the free pool has room, from fake gauge rows. */
class FreeHeadroomTest {

    static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    static FreeGauges.Row row(String id, Double five, Double seven, Long resetsAt, String problem) {
        return new FreeGauges.Row(id, id + "@example.org", five, seven, "within_limit", resetsAt, NOW, "claude-haiku-5-5", problem, 10);
    }

    static boolean room(List<FreeGauges.Row> rows, String... ids) {
        return FreeHeadroom.any(rows, List.of(ids), 0.97, 0.98, NOW);
    }

    @Test void anAccountWithRoomOpensThePool() {
        assertTrue(room(List.of(row("A", 0.40, 0.10, NOW.getEpochSecond() + 3600, null)), "A"));
    }

    @Test void aFullFiveHourWindowClosesTheAccountUntilItResets() {
        var full = row("A", 0.99, 0.10, NOW.getEpochSecond() + 3600, null);
        assertFalse(room(List.of(full), "A"));
        var reset = row("A", 0.99, 0.10, NOW.getEpochSecond() - 5, null);
        assertTrue(room(List.of(reset), "A"), "the reading is from before the reset");
    }

    @Test void aFullWeekClosesTheAccount() {
        assertFalse(room(List.of(row("A", 0.10, 0.99, NOW.getEpochSecond() + 3600, null)), "A"));
    }

    @Test void oneOpenAccountIsEnough() {
        var rows = List.of(row("A", 1.0, 0.5, NOW.getEpochSecond() + 3600, null), row("B", 0.30, 0.2, NOW.getEpochSecond() + 3600, null));
        assertTrue(room(rows, "A", "B"));
        assertFalse(room(rows, "A"));
    }

    @Test void anAccountNotReadYetOrReadWithoutNumbersCountsAsOpen() {
        assertTrue(room(List.of(), "A"));
        assertTrue(room(List.of(row("A", null, null, null, "the reply carried no gauge")), "A"));
    }

    @Test void noAccountsMeansNoRoom() {
        assertFalse(room(List.of()));
    }

    @Test void aUsedUpReadingFromTheLimitReplyClosesTheAccount() {
        var spent = new FreeGauges.Row("A", "a@example.org", 1.0, null, "exceeded_limit", NOW.getEpochSecond() + 7200, NOW, null, null, 5);
        assertFalse(room(List.of(spent), "A"));
    }
}
