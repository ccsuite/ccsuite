package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPICredential;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The free pane and the taskbar card list every free account, with the state text of the widget, whether or not it has a live reading. */
class FreePaneTest {

    static final Instant NOW = Instant.parse("2026-11-07T04:20:00Z");
    static final ZoneId UTC = ZoneOffset.UTC;

    static CCAPICredential account(String id, boolean active) { return new CCAPICredential().id(id).email(id + "@example.org").tier(0).key("k").orgId("o").active(active); }

    static FreeGauges gauges(CCAPICredential... accounts) {
        var list = List.of(accounts);
        return new FreeGauges(() -> list, (c, m) -> null, Path.of("unused"));
    }

    static long at(int minutes) { return NOW.getEpochSecond() + minutes * 60L; }

    @Test void anAccountWithTheFlagOffAndOnlyALedgerReadingAndAPastResetIsListedWithItsResetTime() {
        var g = gauges(account("LIVE", true), account("ADA", false));
        g.seed("ADA", 0.80, 0.10, at(-100), NOW.minus(Duration.ofHours(5)));
        var lines = FreePane.entries(g, NOW, UTC).stream().map(e -> FreeStates.line(e, NOW)).toList();
        assertEquals(2, lines.size(), "every free account is a row");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("ADA") && l.contains("available, window reset at 02:40")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("LIVE") && l.contains("restore time not known")), lines.toString());
    }

    @Test void theRowsFollowTheOrderOfTheWidget() {
        var g = gauges(account("OFF", false), account("NEW", true), account("FULL", true), account("ROOM", true));
        g.seed("FULL", 1.0, 0.1, at(60), NOW.minusSeconds(60));
        g.seed("ROOM", 0.2, 0.1, at(60), NOW.minusSeconds(60));
        var ids = FreePane.entries(g, NOW, UTC).stream().map(FreeStates.Entry::id).toList();
        assertEquals(List.of("ROOM", "FULL", "NEW", "OFF"), ids);
        assertEquals(FreeStates.entries(g.accounts.get(), g.table(), List.of(), NOW, UTC).stream().map(FreeStates.Entry::id).toList(), ids);
    }

    @Test void theTotalCountsLiveReadingsOnlyAndSaysSo() {
        var g = gauges(account("LIVE", true), account("OLD", true), account("ADA", false));
        g.seed("OLD", 0.50, 0.1, at(60), NOW.minusSeconds(60));
        g.seed("ADA", 0.80, 0.1, at(-100), NOW.minus(Duration.ofHours(5)));
        var none = FreePane.summaryText(g, FreePane.entries(g, NOW, UTC));
        assertTrue(none.startsWith("3 free accounts."), none);
        assertTrue(none.contains("No live reading yet"), "readings from the ledger are not live: " + none);
        g.rows.put("LIVE", new FreeGauges.Row("LIVE", "", 0.25, 0.1, "within_limit", at(60), NOW, "claude-haiku-5-5", null, 10));
        g.sources.put("LIVE", "probe");
        var some = FreePane.summaryText(g, FreePane.entries(g, NOW, UTC));
        assertTrue(some.contains("About 75% of one five-hour window is left across the 1 accounts with a live reading"), some);
        assertTrue(some.contains("not counted"), some);
    }

    @Test void theCardHasAPageForEveryAccountInTheSameOrderAndNoCross() {
        var g = gauges(account("LIVE", true), account("ADA", false));
        g.seed("ADA", 0.80, 0.10, at(-100), NOW.minus(Duration.ofHours(5)));
        var pages = FreeCard.accounts(FreePane.entries(g, NOW, UTC), NOW);
        assertEquals(List.of("ADA", "LIVE"), pages.stream().map(p -> p.credential().id()).toList());
        assertNull(pages.get(1).usageData(), "never read: the card says no data");
        assertTrue(pages.get(0).credential().active(), "available: no cross");
        assertEquals(0.0, pages.get(0).getFiveHourUsagePercent(), 1e-9, "its window has reset");
    }
}
