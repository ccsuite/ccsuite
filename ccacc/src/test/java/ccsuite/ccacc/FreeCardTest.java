package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** What the free accounts' taskbar card is given: one page per account, and ALL built from their average. */
class FreeCardTest {

    static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    static final long IN_2H30 = NOW.getEpochSecond() + 9000;

    static FreeGauges.Row row(String id, Double five, Double seven, Long resetsAt) {
        return new FreeGauges.Row(id, id + "@example.org", five, seven, "within_limit", resetsAt, NOW, "claude-haiku-5-5", null, 10);
    }

    static List<FreeStates.Entry> entries(FreeGauges.Row... rows) {
        return Arrays.stream(rows).map(r -> FreeStates.entry(new ccsuite.base.model.CCAPICredential().id(r.id()).tier(0).key("k").orgId("o"), r.five() == null ? null : r, false, NOW, java.time.ZoneOffset.UTC)).toList();
    }

    @Test void eachFreeAccountIsAPageWithItsFiveHourResetAndNoWeeklyReset() {
        var pages = FreeCard.accounts(entries(row("A", 0.20, 0.10, IN_2H30), row("B", null, null, null)), NOW);
        assertEquals(2, pages.size());
        assertEquals(0, pages.get(0).credential().tier());
        assertEquals(20.0, pages.get(0).getFiveHourUsagePercent(), 1e-9);
        assertEquals(10.0, pages.get(0).usageData().sevenDay().utilization(), 1e-9);
        assertNull(pages.get(0).usageData().sevenDay().resetsAt(), "a free account reports no weekly reset");
        assertNull(pages.get(1).usageData(), "no reading: the card says no data");
    }

    @Test void aWindowThatResetSinceTheReadingShowsAsUnused() {
        var page = FreeCard.accounts(entries(row("A", 0.99, 0.10, NOW.getEpochSecond() - 30)), NOW).get(0);
        assertEquals(0.0, page.getFiveHourUsagePercent(), 1e-9);
    }

    @Test void theOverallPageIsTheAverageAndCountsTheAccounts() {
        var all = FreeCard.overall(List.of(row("A", 0.20, 0.10, IN_2H30), row("B", 0.40, 0.30, IN_2H30)), 0.97, 0.98, NOW);
        assertEquals("ALL", all.credential().id());
        assertEquals(2, all.credential().tier(), "the number of accounts, as the paid ALL page does");
        assertEquals(30.0, all.getFiveHourUsagePercent(), 1e-9);
        assertEquals(20.0, all.usageData().sevenDay().utilization(), 1e-9);
        assertEquals(50.0, all.usageData().fiveHour().resetsAt().toInstant().until(NOW.plusSeconds(18000), java.time.temporal.ChronoUnit.SECONDS) * 100.0 / 18000, 1.0, "half of the five-hour window gone on average");
    }
}
