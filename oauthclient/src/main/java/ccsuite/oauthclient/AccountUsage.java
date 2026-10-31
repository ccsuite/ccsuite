package ccsuite.oauthclient;

/**
 * Normalized utilization for one Claude Code account: the 5-hour and 7-day rate-limit
 * windows as percentages (0..100) with their reset timestamps, plus how much of the 7-day
 * window has elapsed ({@code sevenDayElapsedPct}, captured at fetch time). The binding
 * window is whichever utilization is higher; {@link #headroom()} is {@code 100 - utilization}.
 * A {@code null} percent means that window's data was unavailable.
 */
public record AccountUsage(
        Double fiveHourPct, String fiveHourResetsAt,
        Double sevenDayPct, String sevenDayResetsAt,
        Double sevenDayElapsedPct) {

    /** Highest of the available window utilizations, or {@code null} if none known. */
    public Double utilization() {
        if (fiveHourPct == null) return sevenDayPct;
        if (sevenDayPct == null) return fiveHourPct;
        return Math.max(fiveHourPct, sevenDayPct);
    }

    /** {@code 100 - utilization}, or {@code null} when utilization is unknown. */
    public Double headroom() {
        var u = utilization();
        return u == null ? null : 100.0 - u;
    }

    /** Weekly quota still available: {@code 100 - sevenDayPct}, or {@code null} if unknown. */
    public Double sevenDayHeadroom() {
        return sevenDayPct == null ? null : 100.0 - sevenDayPct;
    }

    /**
     * Weekly burn-rate "average": {@code sevenDayPct / sevenDayElapsedPct * 100}. 100% is
     * on pace to exhaust the weekly quota exactly at reset; above that is over-pace. {@code null}
     * when either input is unknown or no measurable time has elapsed yet.
     */
    public Double sevenDayAverage() {
        if (sevenDayPct == null || sevenDayElapsedPct == null || sevenDayElapsedPct <= 0) return null;
        return sevenDayPct / sevenDayElapsedPct * 100.0;
    }

    public QuotaHealth weeklyHealth() { return QuotaHealth.ofWeekly(sevenDayAverage(), sevenDayPct); }

    /** Reset timestamp of the binding (higher-utilization) window. */
    public String bindingResetsAt() {
        if (fiveHourPct == null) return sevenDayResetsAt;
        if (sevenDayPct == null) return fiveHourResetsAt;
        return fiveHourPct >= sevenDayPct ? fiveHourResetsAt : sevenDayResetsAt;
    }
}
