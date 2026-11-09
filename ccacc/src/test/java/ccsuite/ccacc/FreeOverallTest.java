package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The overall of the free accounts, its report for the command line, and the tray picture. */
class FreeOverallTest {

    static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    static final long IN_2H30 = NOW.getEpochSecond() + 9000;   // half of a five-hour window gone

    static FreeGauges.Row row(String id, Double five, Double seven, Long resetsAt) {
        return new FreeGauges.Row(id, id + "@example.org", five, seven, "within_limit", resetsAt, NOW, "claude-haiku-5-5", null, 10);
    }

    static final LaneSettings LIMITS = new LaneSettings();

    @Test void theOverallIsTheAverageOfEveryAccount() {
        var o = FreeOverall.of(List.of(row("A", 0.20, 0.10, IN_2H30), row("B", 0.40, 0.30, IN_2H30), row("C", 0.60, 0.20, IN_2H30)), 0.97, 0.98, NOW);
        assertEquals(3, o.accounts());
        assertEquals(0.40, o.five(), 1e-9);
        assertEquals(0.20, o.week(), 1e-9);
        assertEquals(50, o.fiveTime(), 1e-9);
        assertEquals(80, o.fiveRate(), 1e-9, "40% used over 50% of the time gone");
        assertEquals(3, o.withRoom());
        assertEquals(1.8, o.windowsLeft(), 1e-9);
    }

    @Test void aUsedUpAccountCountsInTheAverageButNotAsRoom() {
        var o = FreeOverall.of(List.of(row("A", 1.0, null, IN_2H30), row("B", 0.0, 0.0, IN_2H30)), 0.97, 0.98, NOW);
        assertEquals(0.5, o.five(), 1e-9);
        assertEquals(1, o.withRoom());
        assertEquals(1, o.weekCount(), "only B has a weekly reading");
    }

    @Test void aWindowThatResetSinceTheReadingCountsAsUnused() {
        var o = FreeOverall.of(List.of(row("A", 0.99, 0.1, NOW.getEpochSecond() - 60), row("B", 0.40, 0.1, IN_2H30)), 0.97, 0.98, NOW);
        assertEquals(0.20, o.five(), 1e-9);
        assertEquals(2, o.withRoom());
    }

    @Test void noReadingsGiveNoNumbers() {
        var o = FreeOverall.of(List.of(), 0.97, 0.98, NOW);
        assertNull(o.fiveFraction());
    }

    @Test void theReportHasTheOverallFirstThenEachAccount() {
        var rows = List.of(row("A", 0.20, 0.10, IN_2H30), row("B", 0.40, 0.30, IN_2H30));
        var text = FreeReport.text(rows, false, LIMITS, NOW);
        assertTrue(text.get(0).startsWith("ALL"), text.get(0));
        assertTrue(text.get(0).contains("30%") && text.get(0).contains("2 of 2 with room"), text.get(0));
        assertTrue(text.stream().anyMatch(l -> l.contains("A ") || l.trim().startsWith("A")));
        assertEquals(2, FreeReport.text(rows, true, LIMITS, NOW).size(), "--overall: the ALL line and the pace line only");
    }

    @Test void theJsonCarriesTheOverallAndTheAccounts() throws Exception {
        var json = FreeGauges.JSON.readTree(FreeReport.json(List.of(row("A", 0.20, 0.10, IN_2H30), row("B", 0.40, null, IN_2H30)), LIMITS, NOW));
        assertEquals(2, json.path("overall").path("accounts").asInt());
        assertEquals(0.30, json.path("overall").path("fiveHour").asDouble(), 1e-9);
        assertEquals(2, json.path("accounts").size());
        assertEquals("A", json.path("accounts").get(0).path("id").asText());
        assertTrue(json.path("accounts").get(1).path("week").isNull());
    }

    @Test void theAppAnswersFromItsOwnReadingsWithoutProbing() {
        var probes = new int[1];
        var gauges = new FreeGauges(() -> List.of(), (c, m) -> { probes[0]++; return null; }, Path.of("unused"));
        gauges.rows.put("A", row("A", 0.20, 0.10, IN_2H30));
        var reply = FreeCli.serve(List.of("free", "--overall"), gauges, LIMITS);
        assertTrue(reply.ok());
        assertTrue(reply.message().startsWith("ALL"));
        assertEquals(0, probes[0]);
    }

    @Test void optionsAreParsed() {
        var o = FreeCli.Options.parse(List.of("--overall", "--json", "--watch", "60"));
        assertTrue(o.overall() && o.json() && !o.refresh() && !o.probe());
        assertEquals(60, o.watch());
    }
}
