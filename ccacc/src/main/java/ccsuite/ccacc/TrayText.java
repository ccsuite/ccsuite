package ccsuite.ccacc;

import ccsuite.oauthclient.AccountUsage;
import ccsuite.oauthswap.watch.WatchMode;

import java.util.Locale;

/** The words and numbers the tray shows; pure, so they can be tested without a screen. */
final class TrayText {

    static String util(AccountUsage u) { return pct(u == null ? null : u.utilization()); }

    static String fiveHour(AccountUsage u) { return pct(u == null ? null : u.fiveHourPct()); }

    static String week(AccountUsage u) { return pct(u == null ? null : u.sevenDayPct()); }

    static String weekFree(AccountUsage u) { return pct(u == null ? null : u.sevenDayHeadroom()); }

    static String avg(AccountUsage u) { return pct(u == null ? null : u.sevenDayAverage()); }

    static String pct(Double v) { return v == null ? "?" : String.format(Locale.ROOT, "%.0f%%", v); }

    /** The 2-letter icon monogram, {@code ?} when Claude Code is on no managed account. */
    static String monogram(String id) {
        return id == null ? "?" : id.substring(0, Math.min(2, id.length())).toUpperCase(Locale.ROOT);
    }

    /** The weekly burn rate as the 0..1+ ring fill, or null when unknown. */
    static Double ratioOf(AccountUsage u) {
        var avg = u == null ? null : u.sevenDayAverage();
        return avg == null ? null : avg / 100.0;
    }

    static String status(String activeId, AccountUsage active, WatchMode mode) {
        var who = activeId == null ? "Claude Code: no managed account" : activeId + "  5h " + fiveHour(active) + "  week " + week(active) + " (avg " + avg(active) + ")";
        return who + "  ·  " + label(mode);
    }

    /** The word on the card: the watcher switches on its own ("auto"), or it does not and Claude Code stays on one account ("off": locked, paused or no watcher). */
    static String autoOrOff(WatchMode m) { return m == WatchMode.AUTO ? "auto" : "off"; }

    static String label(WatchMode m) {
        return switch (m) {
            case AUTO -> "auto";
            case LOCKED -> "locked";
            case PAUSED -> "paused";
        };
    }

    private TrayText() {}
}
