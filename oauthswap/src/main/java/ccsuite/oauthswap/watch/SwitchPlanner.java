package ccsuite.oauthswap.watch;

import ccsuite.oauthclient.AccountUsage;

import java.util.*;

/**
 * Pure policy (no I/O, unit-testable): given the active account's utilization and the
 * candidates' headroom, decide to do nothing, prompt a switch (soft limit reached) or
 * auto-switch (active account depleted). The target is the registered account with the
 * most headroom above {@code minTargetHeadroom}.
 */
public final class SwitchPlanner {

    private final double depletedAtPct;
    private final double minTargetHeadroom;

    public SwitchPlanner(double depletedAtPct, double minTargetHeadroom) {
        this.depletedAtPct = depletedAtPct;
        this.minTargetHeadroom = minTargetHeadroom;
    }

    public Decision decide(String activeId, double softLimitPct, Map<String, AccountUsage> usages) {
        var active = activeId == null ? null : usages.get(activeId);
        var util = active == null ? null : active.utilization();
        if (util == null) return Decision.none("active utilization unknown");

        var target = bestTarget(activeId, usages);
        if (util >= depletedAtPct)
            return target == null
                    ? Decision.none("active depleted (" + pct(util) + ") but no account has headroom")
                    : Decision.auto(target, "active depleted (" + pct(util) + ")");
        if (util >= softLimitPct)
            return target == null
                    ? Decision.none("active at " + pct(util) + " (limit " + pct(softLimitPct) + "), no better account")
                    : Decision.prompt(target, "active at " + pct(util) + " ≥ limit " + pct(softLimitPct));
        return Decision.none("active at " + pct(util));
    }

    /**
     * Registered candidate (≠ active) that best preserves weekly quota: the most weekly
     * headroom (overall headroom breaks ties), among those still usable now (overall headroom
     * ≥ {@code minTargetHeadroom}). Returns null when no candidate is usable.
     */
    String bestTarget(String activeId, Map<String, AccountUsage> usages) {
        String best = null;
        var bestWeekly = -1.0;
        var bestOverall = -1.0;
        for (var e : usages.entrySet()) {
            if (e.getKey().equals(activeId)) continue;
            var u = e.getValue();
            if (u == null) continue;
            var overall = u.headroom();
            if (overall == null || overall < minTargetHeadroom) continue;
            var weekly = u.sevenDayHeadroom();
            var weeklyRank = weekly != null ? weekly : overall;
            if (weeklyRank > bestWeekly || (weeklyRank == bestWeekly && overall > bestOverall)) {
                bestWeekly = weeklyRank; bestOverall = overall; best = e.getKey();
            }
        }
        return best;
    }

    private static String pct(double v) { return String.format(Locale.ROOT, "%.0f%%", v); }
}
