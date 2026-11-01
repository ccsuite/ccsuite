package ccsuite.quotaweights;

import static ccsuite.quotaweights.QuotaWeights.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class QuotaWeightsTest {

    final QuotaWeights w = QuotaWeights.standard();

    @Test
    void measuredTokensPerPoint() {
        assertEquals(116_400, w.tokensPerPoint("claude-sonnet-5-5", INPUT), 116_400 * 0.01);
        assertEquals(25_000, w.tokensPerPoint("claude-sonnet-5-5", OUTPUT), 250);
        assertEquals(4_615_000, w.tokensPerPoint("claude-sonnet-5-5", CACHE_READ), 46_150);
        assertEquals(89_900, w.tokensPerPoint("claude-opus-5-5", INPUT), 899);
        assertEquals(16_250, w.tokensPerPoint("claude-opus-5-5", OUTPUT), 160);
        assertEquals(1_876_000, w.tokensPerPoint("claude-haiku-5-5", INPUT), 1_876_000 * 0.02);
    }

    @Test
    void oneMeasuredPointCostsOneMeasuredPoint() {
        var c = w.cost("claude-sonnet-5-5", 116_450, 0, 0, 0, 0);
        assertEquals(1.0, c.points(), 0.01);
        assertTrue(c.fullyMeasured());
        assertEquals("sonnet", c.family());
        assertEquals(116_450 * 2.0 / 1e6, c.dollars(), 1e-9);
    }

    @Test
    void cacheWritesAreAssumedAndWeighedAsInputTimesTheListMultiple() {
        var in = w.cost("claude-sonnet-5-5", 1000, 0, 0, 0, 0);
        var w5 = w.cost("claude-sonnet-5-5", 0, 0, 0, 1000, 0);
        var w1 = w.cost("claude-sonnet-5-5", 0, 0, 0, 0, 1000);
        assertEquals(in.points() * 1.25, w5.points(), 1e-12);
        assertEquals(in.points() * 2, w1.points(), 1e-12);
        assertEquals(w5.points(), w5.assumedPoints(), 1e-12);
        assertEquals(0, in.assumedPoints());
    }

    @Test
    void assumedPartIsOnlyTheAssumedKinds() {
        var c = w.cost("claude-opus-5-5", 89_900, 0, 3_000_000, 0, 0);
        assertEquals(c.points() - 1.0, c.assumedPoints(), 0.01);
        assertFalse(c.fullyMeasured());
        var haiku = w.cost("claude-haiku-5-5", 1_876_000, 0, 0, 0, 0);
        assertTrue(haiku.fullyMeasured());
        assertFalse(w.cost("claude-haiku-5-5", 0, 100, 0, 0, 0).fullyMeasured());
    }

    @Test
    void versionsPriceByTheirOwnList() {
        var old = w.cost("claude-sonnet-4-5", 1_000_000, 0, 0, 0, 0);
        var now = w.cost("claude-sonnet-5-5", 1_000_000, 0, 0, 0, 0);
        assertEquals(3.0, old.dollars(), 1e-9);
        assertEquals(2.0, now.dollars(), 1e-9);
        assertEquals(1.5, old.points() / now.points(), 1e-9);
    }

    @Test
    void unknownModelIsPricedDearAndWhollyAssumed() {
        var c = w.cost("some-future-model", 1000, 1000, 1000, 0, 0);
        assertEquals("unknown", c.family());
        assertTrue(c.points() > 0);
        assertEquals(c.points(), c.assumedPoints(), 1e-12);
        assertEquals("unknown", w.family(null));
        assertEquals(0, w.cost(null, 0, 0, 0, 0, 0).points());
    }
}
