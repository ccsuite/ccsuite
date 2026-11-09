package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PRP 88: the hourly rate probe (which accounts, what rate, what factor) and the factor applied to every prediction. */
class FreeRateProbeTest {

    static final Instant T = Instant.parse("2026-11-09T13:05:00Z");

    @Test void onlyAccountsWithRoomAndNoSessionArePickedAtRandom() {
        var ids = List.of("A", "B", "C", "D", "E");
        var room = Map.of("A", 100.0, "B", 30.0, "C", 90.0, "D", 80.0, "E", 100.0);
        var seen = new HashSet<String>();
        for (var seed = 0; seed < 50; seed++) {
            var p = FreeRateProbe.pick(ids, room, Set.of("E"), 2, 40, new Random(seed));
            assertEquals(2, p.size());
            assertFalse(p.contains("B"), "under the minimum room");
            assertFalse(p.contains("E"), "a session is on it");
            seen.addAll(p);
        }
        assertEquals(Set.of("A", "C", "D"), seen, "random: every eligible account comes up");
        assertEquals(List.of(), FreeRateProbe.pick(ids, room, Set.of(), 2, 101, new Random(1)), "nothing with room: no probe");
    }

    @Test void theRateIsTheMeanOfTheProbesThatCouldBeToldAndTheFactorIsOverTheOffPeakRate() {
        var r = FreeRateProbe.rate(List.of(
                new FreeRateProbe.Result(T, "A", 150_000, 27.0, null),
                new FreeRateProbe.Result(T, "B", 150_000, 25.0, null),
                new FreeRateProbe.Result(T, "C", 150_000, null, "no gauge reading")), 0.6, T);
        assertEquals((1.8 + 25.0 / 15) / 2, r.per10k(), 1e-9);
        assertEquals(r.per10k() / 0.6, r.factor(), 1e-9);
        assertEquals(List.of("A", "B"), r.accounts());
        assertNull(FreeRateProbe.rate(List.of(new FreeRateProbe.Result(T, "A", 150_000, null, "x")), 0.6, T), "nothing could be told: no rate");
    }

    @Test void theProbeTextIsAsLongAsAskedAndUnique() {
        var corpus = "class X { int y; }\n".repeat(1000);
        var a = FreeRateProbe.text(corpus, 50_000, new Random(1));
        var b = FreeRateProbe.text(corpus, 50_000, new Random(1));
        assertTrue(a.length() >= 50_000 && a.length() < 50_200, "length " + a.length());
        assertNotEquals(a.lines().findFirst(), b.lines().findFirst(), "a unique first line: no cache can make a probe cheap");
        assertTrue(a.endsWith("Reply with just the word OK."));
    }

    @Test void theFactorMultipliesBothModelsAndShrinksWhatFits() {
        var s = new LaneSettings();
        var e = new FreeEstimator(s);
        var in = new FreeCostModel.Input(FreeEstimator.SONNET, "new", 300_000, 0, null, null);
        var rate1 = e.predict(300_000, "new", FreeEstimator.SONNET);
        var terms1 = e.terms.predict(in);
        var fits1 = e.maxChars(100, FreeEstimator.SONNET);
        e.rateFactor = () -> 3.0;
        assertEquals(rate1 * 3, e.predict(300_000, "new", FreeEstimator.SONNET), 1e-9);
        assertEquals(terms1 * 3, e.terms.predict(in), 1e-9);
        assertTrue(e.maxChars(100, FreeEstimator.SONNET) < fits1 / 2.5, "at three times the price, about a third fits");
    }

    @Test void theRateLearnedFromAPeakCallIsTheOffPeakRate() {
        var s = new LaneSettings();
        var off = new FreeEstimator(s);
        var peak = new FreeEstimator(s);
        peak.rateFactor = () -> 3.0;
        for (var i = 0; i < 20; i++) {
            var t = T.plusSeconds(60L * i);
            off.replay(List.of(call(t, 12.0)), List.of());
            peak.replay(List.of(call(t, 36.0)), List.of());
        }
        assertEquals(off.rate(FreeEstimator.SONNET), peak.rate(FreeEstimator.SONNET), 1e-6, "a call three times dearer at a factor of three teaches the same rate");
    }

    static FreeLedger.Call call(Instant t, double observed) {
        return new FreeLedger.Call(t, "A", FreeEstimator.SONNET, 200_000, 50_000, "new", 200, 1000, 0.0, 0.0, 9L, observed / 100, 0.0, 9L, 10.0, observed, true, "s", 200_000L);
    }

    @Test void aProbeLedgerLineGivesTheFactorBackAfterARestart(@org.junit.jupiter.api.io.TempDir Path dir) {
        var ledger = new FreeLedger(dir);
        var now = Instant.now();
        ledger.write(FreeRateProbe.PREFIX, now, new FreeRateProbe.Result(now, "A", 150_000, 27.0, null).toMap(3.0));
        var lane = new FreeLane(new FreeGauges(List::of, (c, m) -> { throw new IllegalStateException("no probe in this test"); }, dir.resolve("readings")), List::of, new LaneSettings(), ledger);
        assertEquals(1.0, lane.rateProbe.factor(now), 1e-9, "not yet restored");
        lane.rateProbe.restore();
        assertEquals(3.0, lane.rateProbe.factor(now), 1e-9);
        assertEquals(3.0, lane.estimator.rateFactor(), 1e-9, "the estimator reads it");
        assertEquals(1.0, lane.rateProbe.factor(now.plus(Duration.ofHours(3))), 1e-9, "too old: off-peak again");
    }
}
