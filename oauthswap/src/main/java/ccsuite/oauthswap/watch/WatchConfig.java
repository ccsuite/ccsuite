package ccsuite.oauthswap.watch;

/**
 * Watch-loop tuning. Polls every {@code baseIntervalSec}; on consecutive errors the
 * interval backs off (doubling) up to {@code maxBackoffSec}. {@code depletedAtPct} is the
 * utilization at which an active account is considered exhausted (auto-switch). A target
 * must keep at least {@code minTargetHeadroom} free to be chosen. {@code softLimitOverride}
 * (&gt; 0) replaces each account's claudecodelimit. {@code autoSwitch} enables auto-switch on
 * depletion; {@code repromptGapSec} throttles repeat soft-limit prompts for the same account.
 * {@code rebalance} enables proactively switching toward the best-normalized-pace account when
 * the reactive planner has nothing urgent to do; {@code rebalanceMinImprovement} is the minimum
 * normalized-ratio improvement required before bothering to switch (see {@link RebalancePlanner}).
 */
public record WatchConfig(
        int baseIntervalSec, int maxBackoffSec,
        double depletedAtPct, double minTargetHeadroom,
        double softLimitOverride, boolean autoSwitch, int repromptGapSec,
        boolean rebalance, double rebalanceMinImprovement) {

    public static WatchConfig defaults() {
        return new WatchConfig(60, 300, 100.0, 5.0, -1, true, 600, true, 0.01);
    }
}
