package ccsuite.usagetracker.taskbar;

import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.Usage;
import ccsuite.base.model.UsageData;
import ccsuite.base.usage.Consolidated;
import ccsuite.usagetracker.model.AccountUsage;

import java.time.OffsetDateTime;
import java.util.List;

/** All the accounts as one more page of the card: the averages drawn like an account, named ALL, its "tier" the number of accounts. */
public final class AllPage {

    static final long FIVE_HOURS = 5 * 3600L, WEEK = 7 * 24 * 3600L;

    public static Consolidated total(List<AccountUsage> list) {
        return Consolidated.of(list.stream().map(AllPage::row).toList());
    }

    /** {5h usage, 5h time gone, weekly usage, weekly time gone} of one account, NaN where it has none. */
    static double[] row(AccountUsage a) {
        var d = a.usageData();
        var five = d == null ? null : d.fiveHour();
        var week = d == null ? null : d.sevenDay();
        return new double[]{
            five == null ? Double.NaN : five.utilization(),
            five == null || five.resetsAt() == null ? Double.NaN : CardView.elapsed(five.resetsAt(), FIVE_HOURS),
            week == null ? Double.NaN : week.utilization(),
            week == null || week.resetsAt() == null ? Double.NaN : CardView.elapsed(week.resetsAt(), WEEK)};
    }

    /** The page, or null with fewer than two accounts or no data to average. */
    public static AccountUsage page(Consolidated c, int accounts) {
        if (accounts < 2 || !(c.hasFiveHour() || c.hasWeek())) return null;
        var data = new UsageData();
        if (c.hasFiveHour()) data.fiveHour(window(c.fiveHourUsage(), c.fiveHourTime(), FIVE_HOURS));
        if (c.hasWeek()) data.sevenDay(window(c.weekUsage(), c.weekTime(), WEEK));
        return new AccountUsage(new CCAPICredential().id("ALL").name("All accounts").active(true).tier(accounts), data);
    }

    /** A window with this usage whose reset lies so far ahead that this share of the window has gone, which is what the card draws as time. */
    static Usage window(double usage, double timeGone, long windowSecs) {
        return new Usage().utilization(usage).resetsAt(OffsetDateTime.now().plusSeconds(Math.round((100 - timeGone) / 100 * windowSecs)));
    }

    private AllPage() {}
}
