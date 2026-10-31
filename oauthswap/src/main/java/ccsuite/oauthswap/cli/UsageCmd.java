package ccsuite.oauthswap.cli;

import picocli.CommandLine.*;
import ccsuite.oauthclient.*;
import ccsuite.oauthswap.usage.*;

import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "usage", mixinStandardHelpOptions = true,
         description = "Print live quota per account, focused on weekly burn-rate (the limit we must not over-spend).")
public final class UsageCmd implements Callable<Integer> {

    @ParentCommand ClaudeSwapCli parent;

    @Override public Integer call() {
        try {
            var svc = parent.service();
            var usage = parent.usageService(svc);
            var activeId = svc.detectActiveId();

            System.out.println("Claude Code quota — weekly avg = used% ÷ week-elapsed% (>100% = over-pace):");
            System.out.printf(Locale.ROOT, "    %-5s %-7s %-9s %-10s %-9s %s%n",
                    "id", "5h", "week", "wk-avail", "wk-avg", "limit");

            var known = 0;
            var danger = 0;
            var fetched = 0;
            for (var c : svc.eligible()) {
                var id = c.id();
                var active = id.equals(activeId);
                if (!active && !svc.store().isRegistered(id)) {
                    System.out.printf("    %-5s (not registered)%n", id);
                    continue;
                }
                if (fetched++ > 0) sleepQuietly(OAuthUsageClient.FETCH_SPACING_MS); // space out — don't burst the rate limit
                AccountUsage u;
                try { u = usage.accountUsageWithFallback(id, active); }
                catch (Exception e) { System.out.printf("    %-5s error: %s%n", id, terseError(e)); continue; }
                printRow(id, active, u, (int) c.claudeCodeLimitPercent());
                var avg = u == null ? null : u.sevenDayAverage();
                if (avg != null) { known++; if (u.weeklyHealth() == QuotaHealth.DANGER) danger++; }
            }
            banner(known, danger);
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static void printRow(String id, boolean active, AccountUsage u, int limit) {
        var health = u == null ? QuotaHealth.UNKNOWN : u.weeklyHealth();
        var avgCell = QuotaColors.paint(health, pad(pct(u == null ? null : u.sevenDayAverage()), 9));
        System.out.printf(Locale.ROOT, "  %s %-5s %-7s %-9s %-10s %s %-4s%s%n",
                active ? "*" : " ", id,
                pct(u == null ? null : u.fiveHourPct()),
                pct(u == null ? null : u.sevenDayPct()),
                pct(u == null ? null : u.sevenDayHeadroom()),
                avgCell, limit + "%",
                active ? "  (active)" : "");
    }

    private void banner(int known, int danger) {
        if (known == 0) return;
        if (danger == known)
            System.out.println(QuotaColors.danger(
                    "  ⚠ ALL accounts are over-pace on weekly quota (avg ≥ 100%). Stop using Claude Code "
                    + "until the weekly windows reset."));
        else if (danger > 0)
            System.out.println(QuotaColors.warn(
                    "  ⚠ " + danger + " of " + known + " accounts are over-pace (avg ≥ 100%); prefer a greener one."));
    }

    private static String pct(Double v) { return v == null ? "?" : String.format(Locale.ROOT, "%.0f%%", v); }

    private static String pad(String s, int w) { return s.length() >= w ? s : s + " ".repeat(w - s.length()); }

    /** Keep the per-account error to one tidy line (the raw 429 body is multi-line JSON). */
    private static String terseError(Exception e) { return "error: " + UsageErrors.terse(e); }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
