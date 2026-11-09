package ccsuite.usagetracker.ui;

import module java.base;
import ccsuite.base.model.Usage;
import ccsuite.base.usage.Cooldown;
import ccsuite.usagetracker.model.AccountUsage;

/**
 * The details popup of one page (an account, or ALL): five-hour usage, time gone, their ratio and the reset; the weekly usage, time gone, ratio and reset when
 * the window is known; and the cool-down, how long to leave the account unused for its weekly ratio to fall under 100%. One text for every place that shows it:
 * the hover of a row in the usage window, and the hover of the taskbar card, paid and free alike. A free account has no weekly reset, so its weekly time, ratio
 * and cool-down say so instead of showing a made-up number.
 */
public final class UsageDetails {

    static final long FIVE_HOURS = 5 * 3600L, WEEK = 7 * 24 * 3600L;

    /** How the details popup is drawn, the same in the taskbar card and in the usage window: a clear proportional font, a size that reads at a glance. */
    public static final String POPUP_STYLE = "-fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif; -fx-font-size: 12px; -fx-text-fill: #f2f2f2; -fx-padding: 8 10 8 10; -fx-background-color: rgba(34, 34, 34, 0.98); -fx-background-radius: 4; -fx-border-color: #666; -fx-border-radius: 4;";

    public static String text(AccountUsage a) {
        if (a == null) return "no data";
        var c = a.credential();
        var all = "ALL".equals(c.id());
        var sb = new StringBuilder();
        if (all) sb.append(c.name() == null ? "All accounts" : c.name()).append(" (average of ").append(c.tier()).append(")\n");
        else {
            sb.append("Account: ").append(c.id()).append("\n");
            if (c.name() != null) sb.append("Name: ").append(c.name()).append("\n");
            if (c.email() != null) sb.append("Email: ").append(c.email()).append("\n");
        }
        age(sb, a, all);
        var d = a.usageData();
        sb.append("\n5-hour window\n");
        var five = d == null ? null : d.fiveHour();
        if (five == null) sb.append("  no reading\n");
        else {
            var gone = five.resetsAt() == null ? Double.NaN : elapsed(five.resetsAt(), FIVE_HOURS);
            window(sb, five.utilization(), gone, five.resetsAt(), "window has reset, nothing used since");
        }
        sb.append("\nWeekly window\n");
        var week = d == null ? null : d.sevenDay();
        if (week == null) sb.append("  no reading\n");
        else {
            var gone = week.resetsAt() == null ? Double.NaN : elapsed(week.resetsAt(), WEEK);
            window(sb, week.utilization(), gone, week.resetsAt(), c.tier() == 0 ? "not known (a free account has no weekly reset)" : "window has reset, nothing used since");
            sb.append("  Cool-down: ").append(coolDown(a, week)).append("\n");
        }
        if (!all) back(sb, d);
        extra(sb, "7-Day Opus", d == null ? null : d.sevenDayOpus());
        extra(sb, "7-Day OAuth Apps", d == null ? null : d.sevenDayOauthApps());
        return sb.toString().stripTrailing();
    }

    /** How old the reading is, when it is not fresh: a page from the log of an account the endpoint says nothing about, or a stuck reading. */
    static void age(StringBuilder sb, AccountUsage a, boolean all) {
        if (all || a.fetchedAt() == null) return;
        var old = Duration.between(a.fetchedAt(), OffsetDateTime.now());
        if (old.compareTo(Duration.ofMinutes(3)) >= 0) sb.append("Reading: ").append(Cooldown.terse(old)).append(" old\n");
    }

    /** When a used-up window comes back (the latest reset among the windows at 95% or more), or nothing when no window is used up. */
    static void back(StringBuilder sb, ccsuite.base.model.UsageData d) {
        if (d == null) return;
        var spent = Stream.of(d.fiveHour(), d.sevenDay()).filter(u -> u != null && u.utilization() >= 95 && u.resetsAt() != null).map(Usage::resetsAt).max(Comparator.naturalOrder());
        spent.ifPresent(r -> sb.append("\nBack: ").append(reset(r)).append("\n"));
    }

    static void window(StringBuilder sb, double usage, double gone, OffsetDateTime resets, String noReset) {
        sb.append("  Usage: ").append("%.1f%%".formatted(usage)).append("\n");
        sb.append("  Time elapsed: ").append(Double.isNaN(gone) ? "-" : "%.1f%%".formatted(gone)).append("\n");
        var ratio = Cooldown.ratio(usage, gone);
        sb.append("  Ratio: ").append(Double.isNaN(ratio) ? "-" : "%.0f%%".formatted(ratio)).append("\n");
        sb.append("  Resets: ").append(resets == null ? noReset : reset(resets)).append("\n");
    }

    static String coolDown(AccountUsage a, Usage week) {
        if (week.resetsAt() == null) return a.credential().tier() == 0 ? "- (needs the weekly reset, which this account does not show)" : "none (the week has reset)";
        var left = a.getWeeklyCoolDownFormatted();
        return left == null ? "none (the weekly ratio is already under 100%)" : left + " unused, then the weekly ratio is under 100%";
    }

    static void extra(StringBuilder sb, String title, Usage u) {
        if (u == null) return;
        sb.append("\n").append(title).append("\n");
        window(sb, u.utilization(), u.resetsAt() == null ? Double.NaN : elapsed(u.resetsAt(), WEEK), u.resetsAt(), "not known");
    }

    /** The share of a window that has gone, 0 to 100, from the moment it resets. */
    public static double elapsed(OffsetDateTime resets, long windowSecs) {
        var gone = (windowSecs - Duration.between(OffsetDateTime.now(), resets).getSeconds()) * 100.0 / windowSecs;
        return Math.max(0, Math.min(100, gone));
    }

    /** "2h15m (at 14:30)", or "3d4h (at 11/10 08:00)" when more than a day away; local time. */
    public static String reset(OffsetDateTime resets) {
        var left = Duration.between(OffsetDateTime.now(), resets);
        if (left.isNegative() || left.isZero()) return "now";
        var local = resets.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        var at = java.time.format.DateTimeFormatter.ofPattern(left.toDays() > 0 ? "MM/dd HH:mm" : "HH:mm").format(local);
        return Cooldown.terse(left.plusSeconds(59)) + " (at " + at + ")";
    }

    private UsageDetails() {}
}
