package ccsuite.oauthswap.watch;

import ccsuite.base.usage.Pace;
import ccsuite.oauthclient.AccountUsage;
import ccsuite.oauthswap.usage.RatioBalancer;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Pure policy (no I/O, the clock is passed in) for the moves the watcher makes on its own when nothing is urgent, that
 * is when the active account is neither depleted nor at its limit ({@link SwitchPlanner} handles those first). In order:
 * <ol>
 * <li><b>Week-end burn.</b> Quota a week leaves unused is lost at its reset. So the account whose week resets first,
 * within {@code burnWindow}, is used up while it still has quota: a primary account to 100%, any other up to its own
 * limit. Being on it means staying on it, so the pace rebalance does not move away.</li>
 * <li><b>5-hour guard.</b> An account that is not primary may be someone's only account. When it has used more than
 * {@code spikePct} of its 5-hour quota, faster than the window passes ({@link Pace#fiveHourTooFast}), Claude Code
 * moves off it, so that person is not locked out for hours. Such an account is not a target for any move either,
 * until its 5-hour window has room again.</li>
 * <li><b>Pace rebalance</b> ({@link RebalancePlanner}) toward the lowest weekly pace, among the targets the guard
 * allows.</li>
 * </ol>
 */
public final class BalancePlanner {

    /** What the planner needs to know about an account besides its usage. */
    public record Policy(double limitPct, boolean primary) {
        static final Policy DEFAULT = new Policy(95.0, false);
    }

    /** Where Claude Code should be. A target equal to the active account means stay. */
    public record Plan(String targetId, String reason) {
        public boolean moves(String activeId) { return !targetId.equals(activeId); }
    }

    private final double minHeadroom;
    private final double spikePct;
    private final Duration burnWindow;
    private final RebalancePlanner pace;

    public BalancePlanner(double minHeadroom, double rebalanceMinImprovement, double spikePct, Duration burnWindow) {
        this.minHeadroom = minHeadroom;
        this.spikePct = spikePct;
        this.burnWindow = burnWindow;
        this.pace = new RebalancePlanner(minHeadroom, rebalanceMinImprovement);
    }

    public static BalancePlanner of(WatchConfig cfg) {
        return new BalancePlanner(cfg.minTargetHeadroom(), cfg.rebalanceMinImprovement(), cfg.spikeFiveHourPct(),
                Duration.ofHours(cfg.burnWindowHours()));
    }

    /** {@code usages} are the accounts a move may go to, the active one included. */
    public Optional<Plan> plan(String activeId, Map<String, AccountUsage> usages, Map<String, Policy> policies, Instant now) {
        if (activeId == null || usages.get(activeId) == null) return Optional.empty();
        var burn = burn(activeId, usages, policies, now);
        if (burn.isPresent()) return burn;
        var guard = guard(activeId, usages, policies, now);
        if (guard.isPresent()) return guard;
        var limits = new HashMap<String, Double>();
        policies.forEach((id, p) -> limits.put(id, p.limitPct()));
        return pace.bestTarget(activeId, allowed(activeId, usages, policies), limits)
                .map(id -> new Plan(id, "rebalance: " + activeId + " is at a higher weekly pace than " + id));
    }

    /** The account whose week resets first within the window and still has quota to use; empty when there is none. */
    Optional<Plan> burn(String activeId, Map<String, AccountUsage> usages, Map<String, Policy> policies, Instant now) {
        String best = null;
        Duration bestLeft = null;
        for (var e : usages.entrySet()) {
            var id = e.getKey();
            var u = e.getValue();
            if (u == null || u.sevenDayPct() == null) continue;
            var left = u.weekLeft(now);
            if (left == null || left.isNegative() || left.compareTo(burnWindow) > 0) continue;
            if (u.sevenDayPct() >= burnTo(policy(policies, id)) - 1) continue;
            if (!usable(id, activeId, u, policy(policies, id), now)) continue;
            if (bestLeft == null || left.compareTo(bestLeft) < 0) { best = id; bestLeft = left; }
        }
        if (best == null) return Optional.empty();
        var u = usages.get(best);
        return Optional.of(new Plan(best, "week-end burn: %s's week resets in %s with %.0f%% of it unused (using it up to %.0f%%)"
                .formatted(best, hours(bestLeft), 100 - u.sevenDayPct(), burnTo(policy(policies, best)))));
    }

    /** Off the active account when it is spent too fast and someone else may depend on it; empty when it is fine or there is nowhere to go. */
    Optional<Plan> guard(String activeId, Map<String, AccountUsage> usages, Map<String, Policy> policies, Instant now) {
        var active = usages.get(activeId);
        if (policy(policies, activeId).primary() || !spiking(active, now)) return Optional.empty();
        var limits = new HashMap<String, Double>();
        policies.forEach((id, p) -> limits.put(id, p.limitPct()));
        var others = new LinkedHashMap<>(allowed(activeId, usages, policies));
        others.remove(activeId);
        return RatioBalancer.rank(others, limits, minHeadroom).stream().findFirst()
                .map(id -> new Plan(id, "5-hour guard: %s has used %.0f%% of its 5 hours, faster than they pass"
                        .formatted(activeId, active.fiveHourPct())));
    }

    /** The active account, and the others a move may go to: usable now, and not pushed past the guard. */
    private Map<String, AccountUsage> allowed(String activeId, Map<String, AccountUsage> usages, Map<String, Policy> policies) {
        var out = new LinkedHashMap<String, AccountUsage>();
        usages.forEach((id, u) -> { if (id.equals(activeId) || (u != null && fresh(u, policy(policies, id)))) out.put(id, u); });
        return out;
    }

    /**
     * Usable for the burn: its 5-hour window has room (the week is the burn's own measure, up to {@link #burnTo}); then the
     * active account while it is not spent too fast, another one when it is fresh.
     */
    private boolean usable(String id, String activeId, AccountUsage u, Policy p, Instant now) {
        if (u.fiveHourPct() != null && 100 - u.fiveHourPct() < minHeadroom) return false;
        if (p.primary()) return true;
        return id.equals(activeId) ? !spiking(u, now) : fresh(u, p);
    }

    /** A target that a move will not push into a spike: primary, or its 5-hour window still under the guard's line. */
    private boolean fresh(AccountUsage u, Policy p) {
        var headroom = u.headroom();
        if (headroom == null || headroom < minHeadroom) return false;
        return p.primary() || u.fiveHourPct() == null || u.fiveHourPct() < spikePct;
    }

    private boolean spiking(AccountUsage u, Instant now) {
        if (u == null || u.fiveHourPct() == null) return false;
        var elapsed = u.fiveHourElapsedPct(now);
        return elapsed != null && Pace.fiveHourTooFast(u.fiveHourPct(), elapsed, spikePct);
    }

    private static double burnTo(Policy p) { return p.primary() ? 100.0 : p.limitPct(); }

    private static Policy policy(Map<String, Policy> policies, String id) { return policies.getOrDefault(id, Policy.DEFAULT); }

    private static String hours(Duration d) {
        var h = d.toMinutes() / 60.0;
        return h < 1 ? d.toMinutes() + " min" : "%.0f h".formatted(h);
    }
}
