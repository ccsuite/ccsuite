package ccsuite.base.usage;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Collection;

/**
 * All accounts as one: the average 5-hour and weekly usage, the average share of each window already gone,
 * and the rate (usage over time gone: 100% spends the window exactly as it passes, above is faster).
 * Every account counts the same, since the credentials say nothing about the size of an account's quota.
 * An account without data for a window is left out of that window's averages only.
 */
@Data
@Accessors(fluent = true, chain = true)
public class Consolidated {

    /** How many accounts had data for the 5-hour and for the weekly window. */
    int fiveHourAccounts, weekAccounts;
    /** Averages in percent, 0 when no account had data. */
    double fiveHourUsage, fiveHourTime, weekUsage, weekTime;

    /** One row per account: {5h usage, 5h time gone, weekly usage, weekly time gone} in percent, NaN where unknown. */
    public static Consolidated of(Collection<double[]> rows) {
        var c = new Consolidated();
        double u5 = 0, t5 = 0, uw = 0, tw = 0;
        for (var r : rows) {
            if (!Double.isNaN(r[0]) && !Double.isNaN(r[1])) { u5 += r[0]; t5 += r[1]; c.fiveHourAccounts++; }
            if (!Double.isNaN(r[2]) && !Double.isNaN(r[3])) { uw += r[2]; tw += r[3]; c.weekAccounts++; }
        }
        if (c.fiveHourAccounts > 0) { c.fiveHourUsage = u5 / c.fiveHourAccounts; c.fiveHourTime = t5 / c.fiveHourAccounts; }
        if (c.weekAccounts > 0) { c.weekUsage = uw / c.weekAccounts; c.weekTime = tw / c.weekAccounts; }
        return c;
    }

    public double fiveHourRate() { return fiveHourTime > 0 ? fiveHourUsage / fiveHourTime * 100 : 0; }

    public double weekRate() { return weekTime > 0 ? weekUsage / weekTime * 100 : 0; }

    public boolean hasWeek() { return weekAccounts > 0; }

    public boolean hasFiveHour() { return fiveHourAccounts > 0; }
}
