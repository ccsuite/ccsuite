package ccsuite.oauthclient;

/**
 * Severity of a weekly burn-rate "average" (usage% ÷ time-elapsed%, see
 * {@link AccountUsage#sevenDayAverage()}). {@code 100%} means on track to spend the
 * weekly quota exactly at week's end; above that is over-pace. Thresholds: ≥100 danger,
 * ≥95 warn, else ok — {@code null} average is {@link #UNKNOWN}.
 *
 * <p>Early-week grace: a tiny absolute usage makes the average spike (e.g. 5% used after a
 * few hours reads as hundreds-of-percent pace) even though almost the whole week is free, and
 * a normal sleep/work rhythm averages it back down. So below {@link #LOW_USAGE_GRACE}% weekly
 * usage we stay {@link #OK} regardless of the average — see {@link #ofWeekly}.
 */
public enum QuotaHealth {
    OK, WARN, DANGER, UNKNOWN;

    public static final double WARN_AT = 95.0;
    public static final double DANGER_AT = 100.0;
    public static final double LOW_USAGE_GRACE = 20.0;

    public static QuotaHealth ofAverage(Double averagePct) {
        if (averagePct == null) return UNKNOWN;
        if (averagePct >= DANGER_AT) return DANGER;
        if (averagePct >= WARN_AT) return WARN;
        return OK;
    }

    /** Burn-rate severity, but suppressed to {@link #OK} while weekly usage is still low. */
    public static QuotaHealth ofWeekly(Double averagePct, Double weeklyUsagePct) {
        if (weeklyUsagePct != null && weeklyUsagePct < LOW_USAGE_GRACE) return OK;
        return ofAverage(averagePct);
    }

    public boolean isElevated() { return this == WARN || this == DANGER; }
}
