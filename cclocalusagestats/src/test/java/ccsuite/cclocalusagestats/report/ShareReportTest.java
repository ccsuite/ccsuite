package ccsuite.cclocalusagestats.report;

import static org.junit.jupiter.api.Assertions.*;

import ccsuite.cclocalusagestats.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ShareReportTest {

    static final long T0 = Instant.parse("2026-11-01T06:00:00Z").toEpochMilli();
    static final long MIN = 60_000;

    static Reading reading(String account, int minute, double u5h, int resetMinute) {
        return new Reading().key(account + "|" + minute).account(account).ts(T0 + minute * MIN).u5h(u5h).reset5h(T0 + resetMinute * MIN);
    }

    static UsageRow points(String account, int minute, int pts) {
        return new UsageRow().key("k" + account + minute).account(account).ts(T0 + minute * MIN).model("claude-sonnet-5-5").inputTokens(pts * 116_400L);
    }

    @Test
    void onePointIsOnePercentOfFreshSonnetInput() {
        assertEquals(1.0, Quota.points(points("A", 0, 1)), 1e-9);
    }

    @Test
    void othersIsWhatTheAccountConsumedBeyondMyMessages() {
        var readings = List.of(reading("A", 0, 10, 300), reading("A", 30, 20, 300), reading("A", 60, 30, 300));
        var rows = List.of(points("A", 10, 8), points("A", 40, 7), points("B", 40, 50));
        var out = ShareReport.compute(readings, rows, ZoneOffset.UTC, false);
        var a = out.get(0);
        assertEquals("A", a.get("key"));
        assertEquals(20.0, a.get("consumed"));
        assertEquals(15.0, a.get("mine"));
        assertEquals(5.0, a.get("others"));
        assertEquals("75%", a.get("mineShare"));
    }

    @Test
    void aResetStartsANewWindowInsteadOfANegativeConsumption() {
        var readings = List.of(reading("A", 0, 80, 300), reading("A", 30, 90, 300), reading("A", 400, 2, 700), reading("A", 430, 12, 700));
        var out = ShareReport.compute(readings, List.of(), ZoneOffset.UTC, false);
        assertEquals(2L, out.get(0).get("windows"));
        assertEquals(20.0, out.get(0).get("consumed"));
    }

    @Test
    void mineNeverMakesOthersNegative() {
        var readings = List.of(reading("A", 0, 10, 300), reading("A", 30, 12, 300));
        var out = ShareReport.compute(readings, List.of(points("A", 10, 9)), ZoneOffset.UTC, false);
        assertEquals(0.0, out.get(0).get("others"));
    }
}
