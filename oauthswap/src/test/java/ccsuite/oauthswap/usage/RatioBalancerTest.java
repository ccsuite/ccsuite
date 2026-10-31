package ccsuite.oauthswap.usage;

import ccsuite.oauthclient.*;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RatioBalancerTest {

    private static AccountUsage weekly(double sevenDayPct, double sevenDayElapsedPct) {
        return new AccountUsage(null, null, sevenDayPct, null, sevenDayElapsedPct);
    }

    @Test void normalizedRatioAtExactlyLimitPace() {
        // 45% used at 50% elapsed -> 90% average pace; limit 90% -> ratio 1.0
        assertEquals(1.0, RatioBalancer.normalizedRatio(weekly(45, 50), 90.0), 1e-9);
    }

    @Test void normalizedRatioNullWhenUsageNull() {
        assertNull(RatioBalancer.normalizedRatio(null, 90.0));
    }

    @Test void normalizedRatioNullWhenLimitMissingOrZero() {
        assertNull(RatioBalancer.normalizedRatio(weekly(45, 50), null));
        assertNull(RatioBalancer.normalizedRatio(weekly(45, 50), 0.0));
    }

    @Test void normalizedRatioNullWhenElapsedUnknown() {
        assertNull(RatioBalancer.normalizedRatio(new AccountUsage(null, null, 45.0, null, null), 90.0));
    }

    @Test void rankOrdersAscendingByNormalizedRatio() {
        var usages = Map.of(
                "A", weekly(45, 50),  // avg 90, limit 90 -> ratio 1.0
                "B", weekly(10, 50),  // avg 20, limit 90 -> ratio 0.22
                "C", weekly(80, 50)); // avg 160, limit 90 -> ratio 1.78
        var limits = Map.of("A", 90.0, "B", 90.0, "C", 90.0);
        assertEquals(List.of("B", "A", "C"), RatioBalancer.rank(usages, limits, 5.0));
    }

    @Test void rankExcludesBelowMinHeadroom() {
        var usages = Map.of(
                "A", weekly(97, 50), // headroom 3, below min 5
                "B", weekly(50, 50));
        var limits = Map.of("A", 90.0, "B", 90.0);
        assertEquals(List.of("B"), RatioBalancer.rank(usages, limits, 5.0));
    }

    @Test void rankExcludesUnknownData() {
        var usages = new HashMap<String, AccountUsage>();
        usages.put("A", null);
        usages.put("B", weekly(10, 50));
        var limits = Map.of("B", 90.0);
        assertEquals(List.of("B"), RatioBalancer.rank(usages, limits, 5.0));
    }
}
