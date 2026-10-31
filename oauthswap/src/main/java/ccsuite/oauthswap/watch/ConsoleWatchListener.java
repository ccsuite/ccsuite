package ccsuite.oauthswap.watch;

import ccsuite.oauthswap.SwitchResult;
import ccsuite.oauthclient.*;
import ccsuite.oauthswap.usage.*;

import java.util.Locale;
import java.util.Map;

/** Headless listener: prints poll results and decisions to stdout. Used by {@code --no-tray}/{@code --once}. */
public final class ConsoleWatchListener implements WatchListener {

    @Override public void onPoll(String activeId, Map<String, AccountUsage> usages, Decision d) {
        for (var e : usages.entrySet()) {
            var u = e.getValue();
            System.out.printf(Locale.ROOT, "  %s%-5s util=%-5s wk=%-5s wk-avail=%-5s wk-avg=%s%s%n",
                    e.getKey().equals(activeId) ? "* " : "  ", e.getKey(),
                    pct(u == null ? null : u.utilization()),
                    pct(u == null ? null : u.sevenDayPct()),
                    pct(u == null ? null : u.sevenDayHeadroom()),
                    weeklyAvg(u), resets(u));
        }
        System.out.println("  -> " + d.kind() + (d.targetId() != null ? " " + d.targetId() : "") + " : " + d.reason());
    }

    @Override public void onPrompt(String activeId, String targetId, AccountUsage a, AccountUsage t) {
        var avg = a == null ? null : a.sevenDayAverage();
        var weekly = avg == null ? "" : " (weekly burn-rate " + pct(avg) + ")";
        System.out.println("PROMPT: " + activeId + " near limit" + weekly
                + " — recommend switch to " + targetId);
    }

    private static String weeklyAvg(AccountUsage u) {
        var avg = u == null ? null : u.sevenDayAverage();
        return QuotaColors.paint(u == null ? QuotaHealth.UNKNOWN : u.weeklyHealth(), pct(avg));
    }

    @Override public void onSwitched(SwitchResult r, boolean automatic) {
        System.out.println((automatic ? "AUTO-SWITCHED " : "SWITCHED ")
                + (r.fromId() == null ? "(unmanaged)" : r.fromId()) + " -> " + r.toId());
    }

    @Override public void onError(Throwable e, int nextIntervalSec) {
        System.out.println("ERROR: " + e.getMessage() + (nextIntervalSec > 0 ? " (retry " + nextIntervalSec + "s)" : ""));
    }

    private static String pct(Double v) { return v == null ? "?" : String.format(Locale.ROOT, "%.0f%%", v); }

    private static String resets(AccountUsage u) {
        var r = u == null ? null : u.bindingResetsAt();
        return r == null ? "" : "  resets=" + r;
    }
}
