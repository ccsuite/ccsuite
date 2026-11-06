package ccsuite.localserver.lanes;

import ccsuite.localserver.server.PaidLaneAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The pin of the paid lane: only that account, never another; and the paid calls written to the run log. */
class PaidPinTest {

    @Test void withoutAPinTheRankingStands() {
        assertEquals(List.of("A", "B"), PaidLaneAccess.candidates(List.of("A", "B"), null));
        assertEquals(List.of("A", "B"), PaidLaneAccess.candidates(List.of("A", "B"), " "));
    }

    @Test void withAPinOnlyThatAccountIsTried() {
        assertEquals(List.of("B"), PaidLaneAccess.candidates(List.of("A", "B", "C"), "B"));
    }

    @Test void aPinnedAccountWithoutHeadroomIsRefusedNotReplaced() {
        assertEquals(List.of(), PaidLaneAccess.candidates(List.of("A", "C"), "B"), "B is not among the accounts with headroom: nothing, not A");
    }

    @Test void aPaidCallIsAppendedWithItsAccountAndJob(@TempDir Path dir) throws Exception {
        var service = new LaneService(null, null, null, null, null);
        service.callLog = dir.resolve("lane-calls.jsonl");
        service.routed(new Routed(Instant.parse("2026-11-06T12:00:00Z"), "pclaude:8681", "claude-haiku-5-5", "paid", "", 200, 850, "P1", "paid-C-1"));
        service.routed(new Routed(Instant.parse("2026-11-06T12:00:01Z"), "fclaude:8686", "claude-sonnet-5-5", "free", "", 200, 9000, null, "paid-C-1"));
        var lines = Files.readAllLines(service.callLog);
        assertEquals(1, lines.size(), "only the paid lane's calls: the free lane has its own log");
        assertTrue(lines.get(0).contains("\"account\":\"P1\"") && lines.get(0).contains("\"job\":\"paid-C-1\"") && lines.get(0).contains("\"mode\":\"paid\"") && lines.get(0).contains("\"ok\":true"), lines.get(0));
        assertEquals(2, service.recent().size());
        assertTrue(service.recent().get(1).line().contains("[P1]"));
    }
}
