package ccsuite.base.usage;

/**
 * When an account is being spent too fast, in percent of a quota window. One definition for everything that reacts to
 * it: the red beat of the widget's glow, and the watcher's 5-hour guard, which moves Claude Code off such an account.
 * The rate is usage over the share of the window that has passed: 100 means the quota would run out exactly when the
 * window resets.
 */
public final class Pace {

    /** The 5-hour window counts as spent too fast only after more than this much of it is used. */
    public static final double FIVE_HOUR_HALF = 50;

    /** More than {@code threshold} percent of the 5-hour quota used, and used faster than the window passes. A time of 0 has no rate. */
    public static boolean fiveHourTooFast(double usedPct, double elapsedPct, double threshold) {
        return usedPct > threshold && elapsedPct > 0 && usedPct / elapsedPct * 100 > 100;
    }

    public static boolean fiveHourTooFast(double usedPct, double elapsedPct) {
        return fiveHourTooFast(usedPct, elapsedPct, FIVE_HOUR_HALF);
    }

    /** The weekly quota used faster than the week passes, once more than half of the week has gone. */
    public static boolean weekTooFast(double usedPct, double elapsedPct) {
        return elapsedPct > 50 && usedPct / elapsedPct * 100 > 100;
    }

    private Pace() {}
}
