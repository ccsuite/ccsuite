package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PRP 87: the estimate of the free lane against what the gauge really moved, judged over runs of calls on one account (the gauge moves in whole points). */
class GaugeCheckTest {

    static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");
    static final long RESET = 1_800_000_000L;

    /** A served call: gauge fractions before and after (null: not read), the points the estimate predicted. */
    static FreeLedger.Call call(int minute, String account, Double before, Double after, long reset, double predicted, int status, String session) {
        return new FreeLedger.Call(T0.plusSeconds(minute * 60L), account, "model-a", 1000, 250, "continued", status, 1000, before, 0.0, reset, after, 0.0, after == null ? null : reset,
                predicted, FreeEstimator.observed(before, after, reset, after == null ? null : reset), true, session);
    }

    static FreeLedger.Call call(int minute, String account, double before, double after, double predicted) { return call(minute, account, before, after, RESET, predicted, 200, "s1"); }

    @Test void aRunIsTheGaugeFromItsFirstReadingToItsLastAgainstTheSumOfWhatWasPredicted() {
        var runs = GaugeCheck.runs(List.of(call(0, "AAA", 0.00, 0.40, 45), call(1, "AAA", 0.40, 1.00, 55)));
        assertEquals(1, runs.size());
        var r = runs.get(0);
        assertEquals(2, r.calls());
        assertEquals(100.0, r.predicted(), 1e-9);
        assertEquals(100.0, r.observed(), 1e-9);
        assertEquals(0.0, r.deviation(), 1e-9);
    }

    @Test void deviationIsPredictedAgainstObservedInPercent() {
        var r = GaugeCheck.runs(List.of(call(0, "AAA", 0.00, 0.50, 60), call(1, "AAA", 0.50, 1.00, 60))).get(0);
        assertEquals(20.0, r.deviation(), 1e-9, "120 predicted against 100 moved");
        var under = GaugeCheck.runs(List.of(call(0, "AAA", 0.00, 0.50, 40), call(1, "AAA", 0.50, 1.00, 40))).get(0);
        assertEquals(-20.0, under.deviation(), 1e-9);
    }

    @Test void accountsAreSeparateRunsAndInterleavedCallsDoNotMixThem() {
        var runs = GaugeCheck.runs(List.of(call(0, "AAA", 0.00, 0.10, 10), call(1, "BBB", 0.20, 0.50, 30), call(2, "AAA", 0.10, 0.20, 10), call(3, "BBB", 0.50, 0.60, 10)));
        assertEquals(2, runs.size());
        var a = runs.stream().filter(r -> r.account().equals("AAA")).findFirst().orElseThrow();
        var b = runs.stream().filter(r -> r.account().equals("BBB")).findFirst().orElseThrow();
        assertEquals(20.0, a.observed(), 1e-9);
        assertEquals(40.0, b.observed(), 1e-9);
        assertEquals(40.0, b.predicted(), 1e-9);
    }

    @Test void aNewWindowStartsANewRunAndTheResetNeverCountsAsGauge() {
        var runs = GaugeCheck.runs(List.of(call(0, "AAA", 0.00, 0.80, RESET, 80, 200, "s1"), call(1, "AAA", 0.80, 0.90, RESET, 10, 200, "s1"),
                call(2, "AAA", 0.00, 0.30, RESET + 18_000, 30, 200, "s1")));
        assertEquals(2, runs.size());
        assertEquals(90.0, runs.get(0).observed(), 1e-9);
        assertEquals(30.0, runs.get(1).observed(), 1e-9);
    }

    @Test void aGaugeThatFellWithoutAResetStartsANewRun() {
        var runs = GaugeCheck.runs(List.of(call(0, "AAA", 0.50, 0.60, 10), call(1, "AAA", 0.05, 0.15, 10)));
        assertEquals(2, runs.size());
    }

    @Test void aCallWithoutAnAnswerReadingIsCoveredByTheNextReadingAndOnesAfterTheLastReadingAreLeftOut() {
        var runs = GaugeCheck.runs(List.of(call(0, "AAA", 0.00, 0.10, 10), call(1, "AAA", 0.10, null, RESET, 10, 200, "s1"), call(2, "AAA", null, 0.30, RESET, 10, 200, "s1"),
                call(3, "AAA", null, null, RESET, 99, 200, "s1")));
        assertEquals(1, runs.size());
        assertEquals(3, runs.get(0).calls());
        assertEquals(30.0, runs.get(0).predicted(), 1e-9, "the last call has no reading after it, so it is not yet judged");
        assertEquals(30.0, runs.get(0).observed(), 1e-9);
    }

    @Test void failedCallsAndCallsOfAnAccountNeverReadAreSkipped() {
        var runs = GaugeCheck.runs(List.of(call(0, "AAA", 0.00, 0.10, RESET, 10, 429, "s1"), call(1, null, 0.0, 0.1, RESET, 10, 200, "s1"), call(2, "AAA", 0.00, 0.20, 20)));
        assertEquals(1, runs.size());
        assertEquals(20.0, runs.get(0).predicted(), 1e-9);
    }

    @Test void aRunRemembersTheSessionsThatMadeIt() {
        var r = GaugeCheck.runs(List.of(call(0, "AAA", 0.00, 0.10, RESET, 10, 200, "one"), call(1, "AAA", 0.10, 0.20, RESET, 10, 200, "two"))).get(0);
        assertEquals(new TreeSet<>(List.of("one", "two")), new TreeSet<>(r.sessions()));
    }

    @Test void theVerdictIsWithinOutsideOrTooFewPointsAndTheGranularityOfTheGaugeSetsHowManyPointsCount() {
        var within = GaugeCheck.runs(List.of(call(0, "AAA", 0.00, 0.50, 51), call(1, "AAA", 0.50, 1.00, 51)));
        var v = GaugeCheck.verdict(within, 5.0, 40.0);
        assertEquals("within", v.word());
        assertEquals(2.0, v.deviation(), 1e-9);
        assertEquals(100.0, v.points(), 1e-9);

        var outside = GaugeCheck.runs(List.of(call(0, "AAA", 0.00, 0.50, 58), call(1, "AAA", 0.50, 1.00, 58)));
        assertEquals("outside", GaugeCheck.verdict(outside, 5.0, 40.0).word());

        var few = GaugeCheck.runs(List.of(call(0, "AAA", 0.00, 0.10, 40)));
        assertEquals("too-few-points", GaugeCheck.verdict(few, 5.0, 40.0).word(), "10 points cannot tell 5%: one point is 10%");
        assertEquals("too-few-points", GaugeCheck.verdict(List.of(), 5.0, 40.0).word());
    }

    @Test void shortRunsAddUpInTheVerdictButOnlyLongRunsAreFlaggedOnTheirOwn() {
        var runs = new ArrayList<FreeLedger.Call>();
        for (var i = 0; i < 6; i++) runs.add(call(i * 10, "AAA", 0.0, 0.10, RESET + i, 10.0, 200, "s" + i));   // six windows of 10 points, each predicted exactly
        var rs = GaugeCheck.runs(runs);
        assertEquals(6, rs.size());
        var v = GaugeCheck.verdict(rs, 5.0, 40.0);
        assertEquals(60.0, v.points(), 1e-9);
        assertEquals("within", v.word());
        var text = String.join("\n", GaugeCheck.report(rs, 5.0, 40.0));
        assertFalse(text.contains("OUTSIDE"), text);
        assertTrue(text.contains("short"), text);
    }

    @Test void theReportFlagsALongRunOutsideTheToleranceAndSaysWhichSessionsToCount() {
        var rs = GaugeCheck.runs(List.of(call(0, "AAA", 0.00, 0.50, RESET, 70, 200, "sess-x"), call(1, "AAA", 0.50, 1.00, RESET, 70, 200, "sess-x")));
        var text = String.join("\n", GaugeCheck.report(rs, 5.0, 40.0));
        assertTrue(text.contains("OUTSIDE"), text);
        assertTrue(text.contains("+40.0%"), text);
        assertTrue(text.contains("tokencheck model bodies --session sess-x"), text);
        assertTrue(text.contains("2026-11-05"), "per day and per run: " + text);
    }

    @Test void theVerdictLineIsOneLineOfKeyValuePairs() {
        var rs = GaugeCheck.runs(List.of(call(0, "AAA", 0.00, 0.50, 51), call(1, "AAA", 0.50, 1.00, 51)));
        assertEquals("gauge verdict: within deviation=+2.0% points=100 runs=1 long=1 long-within=1 tolerance=5%", GaugeCheck.verdictLine(GaugeCheck.verdict(rs, 5.0, 40.0), 5.0));
        assertEquals("gauge verdict: too-few-points deviation=+0.0% points=0 runs=0 long=0 long-within=0 tolerance=5%", GaugeCheck.verdictLine(GaugeCheck.verdict(List.of(), 5.0, 40.0), 5.0));
    }

    @Test void theReportOnAnEmptyLedgerSaysSo() {
        assertEquals(List.of("no calls with a gauge reading in the ledger for that selection"), GaugeCheck.report(List.of(), 5.0, 40.0));
    }
}
