package ccsuite.base.usage;

import java.time.Duration;

/**
 * How long to leave an account (or the pool) unused for its weekly ratio to fall back under 100%, and how that is written. The ratio is weekly usage over the
 * share of the week gone: usage stays where it is while the week passes, so the ratio is back under 100% once the time gone has caught up with the usage,
 * and the wait is the gap between the two, as a share of the seven days. All inputs are percent; NaN means not known.
 */
public final class Cooldown {

    public static final Duration WEEK = Duration.ofDays(7);

    /** Usage over time gone, in percent (100 spends the window exactly as it passes); NaN when either is unknown or no time has gone. */
    public static double ratio(double usagePct, double elapsedPct) {
        return Double.isNaN(usagePct) || Double.isNaN(elapsedPct) || elapsedPct <= 0 ? Double.NaN : usagePct / elapsedPct * 100;
    }

    /** The wait, rounded up to a whole minute; {@link Duration#ZERO} when the weekly ratio is already under 100%; null when the week is not known. */
    public static Duration week(double weekUsagePct, double weekElapsedPct) {
        if (Double.isNaN(weekUsagePct) || Double.isNaN(weekElapsedPct)) return null;
        var gap = weekUsagePct - weekElapsedPct;
        if (gap <= 0) return Duration.ZERO;
        return Duration.ofMinutes((long) Math.ceil(WEEK.toMinutes() * Math.min(gap, 100) / 100));
    }

    /** Days, hours and minutes, the two largest units that are not zero: 1d5h, 2d, 5h, 5h30m, 2m; 0m when there is nothing to wait for, - when not known. */
    public static String terse(Duration d) {
        if (d == null) return "-";
        var minutes = d.toMinutes();
        if (minutes <= 0) return "0m";
        var days = minutes / 1440;
        var hours = minutes % 1440 / 60;
        var mins = minutes % 60;
        if (days > 0) return days + "d" + (hours > 0 ? hours + "h" : "");
        if (hours > 0) return hours + "h" + (mins > 0 ? mins + "m" : "");
        return mins + "m";
    }

    private Cooldown() {}
}
