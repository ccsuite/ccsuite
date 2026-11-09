package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPICredential;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The explicit state of every free account and the one order every view shares. */
class FreeStatesTest {

    static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    static final ZoneId UTC = ZoneOffset.UTC;

    static CCAPICredential account(String id) { return new CCAPICredential().id(id).email(id + "@example.org").tier(0).key("k").orgId("o"); }

    static long at(int minutes) { return NOW.getEpochSecond() + minutes * 60L; }

    static FreeGauges.Row row(String id, Double five, Long reset, String status) {
        return new FreeGauges.Row(id, id, five, 0.02, status, reset, NOW.minus(Duration.ofMinutes(3)), "claude-haiku-5-5", null, 10);
    }

    static Map<String, FreeGauges.Row> table(FreeGauges.Row... rows) {
        var m = new HashMap<String, FreeGauges.Row>();
        for (var r : rows) m.put(r.id(), r);
        return m;
    }

    static FreeStates.Entry one(CCAPICredential c, FreeGauges.Row r) { return FreeStates.entry(c, r, false, NOW, UTC); }

    @Test void anAvailableAccountSaysHowManyPointsAreLeftAndWhenItResets() {
        var e = one(account("A"), row("A", 0.41, at(125), "within_limit"));
        assertEquals(FreeStates.Kind.AVAILABLE, e.kind());
        assertEquals("59 points left, resets at 14:05 (in 2 h 5 min)", e.text());
    }

    @Test void anExhaustedAccountSaysWhenItResets() {
        assertEquals("exhausted, resets at 13:52 (in 1 h 52 min)", one(account("A"), row("A", 1.0, at(112), "exceeded_limit")).text());
        assertEquals(FreeStates.Kind.EXHAUSTED, one(account("A"), row("A", 1.17, at(30), "within_limit")).kind(), "over 100% counts, whatever the status word says");
        assertEquals("restore time not known yet, probing hourly, last probe 11:57", one(account("A"), row("A", 1.0, null, "exceeded_limit")).text());
    }

    @Test void anExhaustedAccountWhoseResetHasPassedIsAvailableAgainAfterTheNextReading() {
        var e = one(account("A"), row("A", 1.0, at(-10), "exceeded_limit"));
        assertEquals(FreeStates.Kind.AVAILABLE, e.kind());
        assertEquals("available, window reset at 11:50", e.text());
    }

    @Test void anAccountNeverReadSaysSo() {
        assertEquals("restore time not known yet, probing hourly, not probed yet", one(account("A"), null).text());
        assertEquals(FreeStates.Kind.RESTORE_UNKNOWN, one(account("A"), null).kind());
    }

    // (d) the store flag does not decide the state: the restore time against the current time does
    @Test void anAccountWithTheFlagOffAndAFutureRestoreTimeIsExhaustedWithItsTimeNotInactive() {
        var e = one(account("A").active(false), row("A", 1.0, at(112), "exceeded_limit"));
        assertEquals(FreeStates.Kind.EXHAUSTED, e.kind());
        assertEquals("exhausted, resets at 13:52 (in 1 h 52 min)", e.text());
    }

    // (a) the state half: flag off, 100%, restore time already passed
    @Test void anAccountWithTheFlagOffAndAPassedRestoreTimeIsAvailableWithItsLastBars() {
        var e = one(account("A").active(false), row("A", 1.0, at(-10), "exceeded_limit"));
        assertEquals(FreeStates.Kind.AVAILABLE, e.kind());
        assertEquals("available, window reset at 11:50", e.text());
        assertTrue(FreeStates.line(e, NOW).contains("[##########] 100%"), "the last reading's bars stay");
    }

    @Test void anAccountWithTheFlagOffAndRoomLeftIsAvailableLikeAnyOther() {
        assertEquals("70 points left, resets at 13:00 (in 1 h 0 min)", one(account("A").active(false), row("A", 0.3, at(60), "within_limit")).text());
    }

    @Test void anAccountWithNoRestoreTimeIsNotDeadWhateverTheFlagSays() {
        var e = one(account("A").active(false), null);
        assertEquals(FreeStates.Kind.RESTORE_UNKNOWN, e.kind());
        assertEquals("restore time not known yet, probing hourly, not probed yet", e.text());
        assertFalse(e.text().contains("inactive"));
    }

    @Test void accountsWithUnknownRestoreTimeAreOrderedByOldestProbeFirstAndNeverProbedBeforeAll() {
        var all = List.of(account("U3"), account("U1"), account("U2"), account("U0"));
        var t = table(old("U3", 10), old("U1", 200), old("U2", 90));
        var ids = FreeStates.entries(all, t, List.of(), NOW, UTC).stream().map(FreeStates.Entry::id).toList();
        assertEquals(List.of("U0", "U1", "U2", "U3"), ids);
    }

    static FreeGauges.Row old(String id, int minutesAgo) {
        return new FreeGauges.Row(id, id, 1.0, 0.02, "exceeded_limit", null, NOW.minus(Duration.ofMinutes(minutesAgo)), "m", null, 10);
    }

    @Test void theCommandLineShowsTheBarsOfAnAccountWithTheFlagOffThatHasAReading() {
        var line = FreeStates.line(one(account("ADA").active(false), row("ADA", 1.0, at(112), "exceeded_limit")), NOW);
        assertTrue(line.startsWith("ADA   exhausted, resets at 13:52"), line);
        assertTrue(line.contains("[##########] 100%") && line.contains("read 3 min ago"), line);
        assertEquals("NTA   restore time not known yet, probing hourly, not probed yet", FreeStates.line(one(account("NTA").active(false), null), NOW));
    }

    @Test void theOrderIsAvailableThenExhaustedBySoonestResetThenRestoreTimeUnknown() {
        var all = List.of(account("I1").active(false), account("N1"), account("X2"), account("A1"), account("X1"), account("A2"), account("N0"));
        var t = table(row("A1", 0.80, at(100), "within_limit"), row("A2", 0.10, at(200), "within_limit"), row("X1", 1.0, at(20), "exceeded_limit"), row("X2", 1.0, at(90), "exceeded_limit"));
        var ids = FreeStates.entries(all, t, List.of(), NOW, UTC).stream().map(FreeStates.Entry::id).toList();
        assertEquals(List.of("A2", "A1", "X1", "X2", "I1", "N0", "N1"), ids, "available (most room first), exhausted (soonest reset first), restore time unknown (never probed first, by id)");
    }

    @Test void everyFreeAccountIsListedEvenWithoutAReading() {
        var all = List.of(account("A"), account("B").watchOnly(true), account("C").tier(1), account("D").key(""), account("E").active(false));
        assertEquals(List.of("A", "B", "E"), FreeStates.entries(all, Map.of(), List.of(), NOW, UTC).stream().map(FreeStates.Entry::id).sorted().toList());
    }

    @Test void anAccountAddedAfterTheLaneStartedIsSaidToBeServedAfterTheNextRestart() {
        var e = FreeStates.entries(List.of(account("NEW")), Map.of(), List.of("NEW"), NOW, UTC).get(0);
        assertEquals("restore time not known yet, probing hourly, not probed yet; added, served after the next restart", e.describe());
        assertFalse(FreeStates.entries(List.of(account("OLD")), Map.of(), List.of("NEW"), NOW, UTC).get(0).pending());
    }

    @Test void theCommandLineShowsTheStateFirstThenTheBars() {
        var line = FreeStates.line(one(account("IKO"), row("IKO", 1.0, at(112), "exceeded_limit")), NOW);
        assertTrue(line.startsWith("IKO   exhausted, resets at 13:52"), line);
        assertTrue(line.contains("[##########] 100%") && line.contains("read 3 min ago"), line);
        assertEquals("NEW   restore time not known yet, probing hourly, not probed yet", FreeStates.line(one(account("NEW"), null), NOW));
    }

    @Test void theFreeCommandListsEveryAccountInTheSharedOrder() {
        var all = List.of(account("OFF").active(false), account("NEW"), account("IKO"), account("ALF"));
        var gauges = new FreeGauges(() -> all, (c, m) -> null, Path.of("unused"));
        gauges.rows.put("IKO", row("IKO", 1.0, at(112), "exceeded_limit"));
        gauges.rows.put("ALF", row("ALF", 0.30, at(112), "within_limit"));
        var lines = FreeCli.render(gauges, FreeCli.Options.parse(List.of()), new LaneSettings(), NOW).lines().filter(l -> l.startsWith("  ") && !l.startsWith("   ")).toList();
        assertEquals(List.of("ALF", "IKO", "NEW", "OFF"), lines.stream().map(l -> l.strip().substring(0, 3)).toList());
        assertTrue(lines.get(2).contains("restore time not known") && lines.get(3).contains("restore time not known") && lines.stream().noneMatch(l -> l.contains("inactive")));
    }
}
