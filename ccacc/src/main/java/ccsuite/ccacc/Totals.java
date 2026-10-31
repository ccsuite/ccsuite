package ccsuite.ccacc;

import ccsuite.base.usage.Consolidated;
import ccsuite.oauthclient.AccountUsage;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

/** All accounts as one, from what the watcher last polled: the same averages the taskbar card shows on its ALL page. */
final class Totals {

    static final long FIVE_HOURS = 5 * 3600L;

    static Consolidated of(Map<String, AccountUsage> usages) {
        return Consolidated.of(usages.values().stream().filter(u -> u != null).map(Totals::row).toList());
    }

    /** {5h usage, 5h time gone, weekly usage, weekly time gone} in percent, NaN where unknown. */
    static double[] row(AccountUsage u) {
        return new double[]{
            nan(u.fiveHourPct()),
            u.fiveHourPct() == null ? Double.NaN : fiveHourTimeGone(u.fiveHourResetsAt()),
            nan(u.sevenDayPct()),
            nan(u.sevenDayElapsedPct())};
    }

    static double nan(Double v) { return v == null ? Double.NaN : v; }

    /** The share of the 5-hour window that has passed, from the moment it resets (NaN when that is not known). */
    static double fiveHourTimeGone(String resetsAt) {
        if (resetsAt == null) return Double.NaN;
        try {
            var left = Duration.between(OffsetDateTime.now(), OffsetDateTime.parse(resetsAt)).getSeconds();
            return Math.max(0, Math.min(100, (FIVE_HOURS - left) * 100.0 / FIVE_HOURS));
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private Totals() {}
}
