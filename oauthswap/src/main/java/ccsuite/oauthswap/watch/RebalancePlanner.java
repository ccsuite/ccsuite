package ccsuite.oauthswap.watch;

import ccsuite.oauthclient.*;
import ccsuite.oauthswap.usage.*;

import java.util.*;

/**
 * Pure policy (no I/O, unit-testable), lower priority than {@link SwitchPlanner}: proactively
 * rebalances the active account toward whichever eligible account currently has the lowest
 * normalized weekly pace ({@link RatioBalancer}), even when nothing is near its limit. Only
 * recommends a switch when it improves the normalized ratio by at least {@code minImprovement}
 * — small differences aren't worth the churn of switching.
 */
public final class RebalancePlanner {

    private final double minHeadroom;
    private final double minImprovement;

    public RebalancePlanner(double minHeadroom, double minImprovement) {
        this.minHeadroom = minHeadroom;
        this.minImprovement = minImprovement;
    }

    public Optional<String> bestTarget(String activeId, Map<String, AccountUsage> usages, Map<String, Double> limitPercents) {
        if (activeId == null) return Optional.empty();
        var activeRatio = RatioBalancer.normalizedRatio(usages.get(activeId), limitPercents.get(activeId));
        if (activeRatio == null) return Optional.empty();

        var ranked = RatioBalancer.rank(usages, limitPercents, minHeadroom);
        var targetId = ranked.stream().filter(id -> !id.equals(activeId)).findFirst();
        if (targetId.isEmpty()) return Optional.empty();

        var targetRatio = RatioBalancer.normalizedRatio(usages.get(targetId.get()), limitPercents.get(targetId.get()));
        if (targetRatio == null || activeRatio - targetRatio < minImprovement) return Optional.empty();
        return targetId;
    }
}
