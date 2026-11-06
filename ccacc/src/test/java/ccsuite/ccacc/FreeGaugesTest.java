package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPICredential;
import ccsuite.webclient.FreeGauge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class FreeGaugesTest {

    static CCAPICredential account(String id, Integer tier, String key, String org) {
        return new CCAPICredential().id(id).email(id + "@example.org").tier(tier).key(key).orgId(org);
    }

    static FreeGauge.Reading reading(double five, double seven) {
        return new FreeGauge.Reading(five, seven, "within_limit", 1793937600L, "claude-haiku-5-5", 100, 200);
    }

    @Test
    void onlyFreeAccountsWithAKeyAndAnOrganisationAreListed() {
        var all = List.of(account("A", 0, "k", "o"), account("B", 1, "k", "o"), account("C", null, "k", "o"), account("D", 0, "", "o"), account("E", 0, "k", null), account("F", 0, "k", "o"));
        assertEquals(List.of("A", "F"), FreeGauges.free(all).stream().map(CCAPICredential::id).toList());
    }

    @Test
    void aReadingIsKeptAndLoggedOnlyWhenItChanges(@TempDir Path dir) throws Exception {
        var next = new double[] {0.41, 0.01};
        var gauges = new FreeGauges(() -> List.of(account("F2", 0, "k", "o")), (c, m) -> reading(next[0], next[1]), dir);
        gauges.refreshAll();
        gauges.refreshAll();
        next[0] = 0.43;
        gauges.refreshAll();
        var row = gauges.snapshot().get(0);
        assertEquals(0.43, row.five());
        assertTrue(row.ok());
        var lines = Files.readAllLines(Files.list(dir).findFirst().orElseThrow());
        assertEquals(2, lines.size(), "the unchanged second reading is not written again");
        assertTrue(lines.get(0).contains("\"account\":\"F2\"") && lines.get(0).contains("\"u5h\":0.41"));
    }

    @Test
    void aFailedProbeKeepsTheLastNumbersAndSaysWhy(@TempDir Path dir) {
        var fail = new boolean[1];
        FreeGauges.Probe probe = (c, m) -> { if (fail[0]) throw new IOException("Failed to send message: HTTP 429 - rate limited"); return reading(0.5, 0.02); };
        var gauges = new FreeGauges(() -> List.of(account("F1", 0, "k", "o")), probe, dir);
        gauges.refreshAll();
        fail[0] = true;
        gauges.refreshAll();
        var row = gauges.snapshot().get(0);
        assertFalse(row.ok());
        assertEquals(0.5, row.five());
        assertTrue(row.problem().contains("429"));
    }

    @Test
    void aUsedUpWindowIsAReadingNotAFailure() {
        var body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"{\\\"type\\\":\\\"exceeded_limit\\\",\\\"resetsAt\\\":1793937600,\\\"remaining\\\":null,\\\"representativeClaim\\\":\\\"five_hour\\\"}\"}}";
        var r = FreeGauge.exceeded(429, body);
        assertEquals(1.0, r.fiveHour());
        assertEquals(1793937600L, r.fiveResetsAt());
        assertEquals("exceeded_limit", r.fiveStatus());
        assertNull(FreeGauge.exceeded(429, "{\"error\":{\"message\":\"Rate limited. Please try again later.\"}}"));
        assertNull(FreeGauge.exceeded(500, body));
    }

    @Test
    void textFormsAreReadable() {
        var now = Instant.ofEpochSecond(1793937600L - 7500);
        assertEquals("2 h 5 min", FreeGauges.untilReset(1793937600L, now));
        assertEquals("[####------]", FreeGauges.bar(0.41));
        assertEquals(" 41%", FreeGauges.percent(0.41));
        assertEquals("now", FreeGauges.untilReset(1793937600L, Instant.ofEpochSecond(1793937700L)));
    }
}
