package ccsuite.oauthswap.cli;

import picocli.CommandLine.*;
import ccsuite.oauthclient.*;
import ccsuite.oauthswap.usage.*;

import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "switch",
         description = "Switch Claude Code to a registered account; with no ID, rotates to the next one.")
public final class SwitchCmd implements Callable<Integer> {

    @ParentCommand ClaudeSwapCli parent;

    @Parameters(index = "0", arity = "0..1", paramLabel = "ID",
            description = "Account id to switch to; omit to rotate to the next registered account.")
    String id;

    @Override public Integer call() {
        try {
            var svc = parent.service();
            var r = (id == null || id.isBlank()) ? svc.switchNext() : svc.switchTo(id);
            if (r.changed())
                System.out.println("Switched " + (r.fromId() == null ? "(unmanaged)" : r.fromId())
                        + " -> " + r.toId() + " (" + r.toEmail()
                        + "). Claude Code will pick it up on your next message.");
            else
                System.out.println("Already on " + r.toId() + " (" + r.toEmail() + ").");
            warnWeekly(parent.usageService(svc), r.toId());
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /** Best-effort weekly burn-rate check on the account we just landed on. */
    private static void warnWeekly(UsageService usage, String activeId) {
        AccountUsage u;
        try { u = usage.accountUsage(activeId, true); }
        catch (Exception e) { return; }
        var avg = u == null ? null : u.sevenDayAverage();
        if (avg == null) return;
        var msg = String.format(Locale.ROOT,
                "  weekly burn-rate %.0f%% (used %.0f%%, %.0f%% weekly quota left)",
                avg, u.sevenDayPct(), u.sevenDayHeadroom());
        switch (u.weeklyHealth()) {
            case DANGER -> System.out.println(QuotaColors.danger(
                    "  ⚠ " + activeId + " is OVER-PACE —" + msg + ". Consider stopping until the weekly window resets."));
            case WARN -> System.out.println(QuotaColors.warn("  ⚠ " + activeId + " is near pace —" + msg + "."));
            default -> {
                var earlyWeek = u.sevenDayPct() < QuotaHealth.LOW_USAGE_GRACE && avg >= QuotaHealth.WARN_AT;
                System.out.println(QuotaColors.ok("  ✓ " + activeId + " —" + msg
                        + (earlyWeek ? " (early-week spike — averages out, fine to use)" : "") + "."));
            }
        }
    }
}
