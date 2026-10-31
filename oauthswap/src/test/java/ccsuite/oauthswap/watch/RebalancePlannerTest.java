package ccsuite.oauthswap.watch;

import org.junit.jupiter.api.Test;
import ccsuite.oauthclient.AccountUsage;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RebalancePlannerTest {

    private final RebalancePlanner planner = new RebalancePlanner(5.0, 0.01);

    private static AccountUsage weekly(double sevenDayPct, double sevenDayElapsedPct) {
        return new AccountUsage(null, null, sevenDayPct, null, sevenDayElapsedPct);
    }

    @Test void switchesToLowerNormalizedRatioWhenImprovementMeetsThreshold() {
        var usages = Map.of(
                "A", weekly(45, 50),  // avg 90, limit 90 -> ratio 1.0
                "B", weekly(10, 50)); // avg 20, limit 90 -> ratio 0.22
        var limits = Map.of("A", 90.0, "B", 90.0);
        assertEquals(Optional.of("B"), planner.bestTarget("A", usages, limits));
    }

    @Test void noneWhenImprovementBelowThreshold() {
        // A: avg 90, ratio 1.0. B: avg 89.9, ratio 0.99889 -> improvement ~0.0011, below the 0.01 threshold.
        var usages = Map.of(
                "A", weekly(45.0, 50),
                "B", weekly(44.95, 50));
        var limits = Map.of("A", 90.0, "B", 90.0);
        assertEquals(Optional.empty(), planner.bestTarget("A", usages, limits));
    }

    @Test void noneWhenActiveIdNull() {
        var usages = Map.of("B", weekly(10, 50));
        assertEquals(Optional.empty(), planner.bestTarget(null, usages, Map.of("B", 90.0)));
    }

    @Test void noneWhenActiveUsageUnknown() {
        var usages = new HashMap<String, AccountUsage>();
        usages.put("A", null);
        usages.put("B", weekly(10, 50));
        assertEquals(Optional.empty(), planner.bestTarget("A", usages, Map.of("A", 90.0, "B", 90.0)));
    }

    @Test void noneWhenNoOtherCandidateHasHeadroom() {
        var usages = Map.of(
                "A", weekly(45, 50),
                "B", weekly(97, 50)); // headroom 3, below min 5
        var limits = Map.of("A", 90.0, "B", 90.0);
        assertEquals(Optional.empty(), planner.bestTarget("A", usages, limits));
    }
}
