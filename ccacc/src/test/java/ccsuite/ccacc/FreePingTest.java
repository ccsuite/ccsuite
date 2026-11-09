package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPICredential;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The ping policy is a pure function of (accounts, last readings, now): fresh no ping, stale ping, reset window ping, used-up no ping, no accounts nothing. */
class FreePingTest {

    static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    static final Duration FRESH = Duration.ofMinutes(55);

    static CCAPICredential account(String id) { return new CCAPICredential().id(id).email(id + "@example.org").tier(0).key("k").orgId("o"); }

    static FreeGauges.Row row(String id, Duration age, Long resetsAt, String status, String problem) {
        return new FreeGauges.Row(id, id, 0.02, 0.0, status, resetsAt, NOW.minus(age), "claude-haiku-5-5", problem, 10);
    }

    static Map<String, FreeGauges.Row> table(FreeGauges.Row... rows) {
        var m = new HashMap<String, FreeGauges.Row>();
        for (var r : rows) m.put(r.id(), r);
        return m;
    }

    static Optional<String> why(FreeGauges.Row r) { return FreePing.why(r, NOW, FRESH); }

    static long inHours(int h) { return NOW.getEpochSecond() + h * 3600L; }

    @Test void anAccountNeverReadIsPinged() {
        assertEquals(List.of("A"), FreePing.due(List.of(account("A")), Map.of(), NOW, FRESH).stream().map(d -> d.account().id()).toList());
        assertEquals("never read", why(null).orElseThrow());
    }

    @Test void aYoungReadingWithARunningWindowIsNotPinged() {
        assertTrue(why(row("A", Duration.ofMinutes(10), inHours(4), "within_limit", null)).isEmpty());
        assertTrue(why(row("A", Duration.ofMinutes(54), inHours(1), "within_limit", null)).isEmpty());
    }

    @Test void aStaleReadingIsPinged() {
        assertEquals("the reading is 55 min old", why(row("A", Duration.ofMinutes(55), inHours(2), "within_limit", null)).orElseThrow());
        assertTrue(why(row("A", Duration.ofHours(3), inHours(2), "within_limit", null)).isPresent());
    }

    @Test void aWindowThatHasResetCountsAsUnknownEvenWhenTheReadingIsYoung() {
        var r = row("A", Duration.ofMinutes(5), NOW.getEpochSecond() - 60, "within_limit", null);
        assertEquals("the window has reset", why(r).orElseThrow());
    }

    @Test void aUsedUpAccountWaitsForItsResetThenIsPinged() {
        assertTrue(why(row("A", Duration.ofHours(4), inHours(1), "exceeded_limit", null)).isEmpty(), "stale but used up: nothing to open before the reset");
        assertEquals("the used-up window has reset", why(row("A", Duration.ofMinutes(5), NOW.getEpochSecond() - 1, "exceeded_limit", null)).orElseThrow());
    }

    @Test void anAccountAtOneHundredPercentIsNotPingedBeforeItsResetWhateverTheStatusSays() {
        var spent = new FreeGauges.Row("A", "A", 1.0, 0.02, "within_limit", inHours(1), NOW.minus(Duration.ofHours(3)), "m", null, 1);
        assertTrue(why(spent).isEmpty(), "a served call leaves the status within_limit, the 100% is what counts");
        assertTrue(why(new FreeGauges.Row("A", "A", 1.2, 0.02, "within_limit", inHours(1), NOW, "m", null, 1)).isEmpty());
    }

    @Test void anExhaustedReadingWithNoResetTimeWaitsForTheRecencyLimit() {
        assertTrue(why(new FreeGauges.Row("A", "A", 1.0, null, "exceeded_limit", null, NOW.minus(Duration.ofMinutes(10)), null, null, 1)).isEmpty());
        assertTrue(why(new FreeGauges.Row("A", "A", 1.0, null, "exceeded_limit", null, NOW.minus(Duration.ofMinutes(60)), null, null, 1)).isPresent());
    }

    @Test void aFailedPingLeavesTheAccountDueAtTheNextRound() {
        var r = row("A", Duration.ofMinutes(1), inHours(3), "within_limit", "rate limited (HTTP 429), will retry");
        assertTrue(why(r).orElseThrow().startsWith("the last ping failed"));
    }

    @Test void onlyFreeAccountsThatAreNotMonitorOnlyAreEligibleWhateverTheirFlag() {
        var all = List.of(account("A"), account("B").active(false), account("C").watchOnly(true), account("D").tier(1), account("E").key(""), account("F"));
        assertEquals(List.of("A", "B", "F"), FreePing.eligible(all, List.of()).stream().map(CCAPICredential::id).toList());
        assertEquals(List.of("B", "F"), FreePing.eligible(all, List.of("F", "B", "D")).stream().map(CCAPICredential::id).toList(), "named accounts: eligible ones only");
    }

    // (a) flag off, 100%, restore time in the past: eligible and due
    @Test void anAccountWithTheFlagOffAtOneHundredPercentWithAPassedRestoreTimeIsPinged() {
        var off = account("A").active(false);
        var r = new FreeGauges.Row("A", "A", 1.0, 0.02, "exceeded_limit", NOW.getEpochSecond() - 600, NOW.minus(Duration.ofMinutes(20)), "m", null, 1);
        var due = FreePing.due(FreePing.eligible(List.of(off), List.of()), table(r), NOW, FRESH);
        assertEquals(List.of("A"), due.stream().map(d -> d.account().id()).toList());
        assertEquals("the used-up window has reset", due.get(0).why());
    }

    // (b) restore time not known: probed when the last probe is at least 60 minutes old, not before; never read is due now
    @Test void anAccountWithAnUnknownRestoreTimeIsProbedHourlyAfterTheFirstPing() {
        assertTrue(why(new FreeGauges.Row("A", "A", 1.0, null, "exceeded_limit", null, NOW.minus(Duration.ofMinutes(61)), null, null, 1)).isPresent());
        assertTrue(why(new FreeGauges.Row("A", "A", 1.0, null, "exceeded_limit", null, NOW.minus(Duration.ofMinutes(30)), null, null, 1)).isEmpty());
        assertTrue(why(new FreeGauges.Row("A", "A", null, null, null, null, NOW.minus(Duration.ofMinutes(5)), null, "HTTP 500", 1)).isPresent(), "an error, not a refusal: tried again at the next round");
        assertTrue(why(null).isPresent(), "never read: the ping starts the account");
    }

    // (c) 100% with a restore time in the future: not pinged, flag on or off
    @Test void anAccountAtOneHundredPercentWithAFutureRestoreTimeIsNotPingedWhateverItsFlag() {
        var r = new FreeGauges.Row("A", "A", 1.0, 0.02, "exceeded_limit", inHours(2), NOW.minus(Duration.ofHours(3)), "m", null, 1);
        assertTrue(FreePing.due(FreePing.eligible(List.of(account("A").active(false), account("B")), List.of()), table(r, new FreeGauges.Row("B", "B", 1.0, 0.02, "exceeded_limit", inHours(2), NOW.minus(Duration.ofHours(3)), "m", null, 1)), NOW, FRESH).isEmpty());
    }

    @Test void noAccountsMeansNothingDue() {
        assertTrue(FreePing.due(List.of(), Map.of(), NOW, FRESH).isEmpty());
        assertTrue(FreePing.due(FreePing.eligible(List.of(), List.of()), table(row("A", Duration.ofHours(9), null, "within_limit", null)), NOW, FRESH).isEmpty());
    }

    @Test void dueSaysWhyPerAccount() {
        var due = FreePing.due(List.of(account("A"), account("B"), account("C")), table(row("A", Duration.ofMinutes(5), inHours(3), "within_limit", null), row("B", Duration.ofHours(2), inHours(3), "within_limit", null)), NOW, FRESH);
        assertEquals(List.of("B", "C"), due.stream().map(d -> d.account().id()).toList());
    }
}
