package ccsuite.ccacc;

import ccsuite.oauthclient.AccountUsage;
import ccsuite.oauthclient.QuotaHealth;
import ccsuite.oauthclient.UsageErrors;
import ccsuite.oauthswap.SwitchResult;
import ccsuite.oauthswap.watch.Decision;
import ccsuite.oauthswap.watch.WatchListener;
import javafx.application.Platform;
import leasegate.tray.FxTray;

import java.util.Map;

import static ccsuite.ccacc.TrayText.*;

/**
 * The watcher's voice in the tray: keeps the last poll for the menu, redraws the icon and tooltip,
 * and raises a toast on a prompt, a switch or an error. Called off the UI thread; the tray hands
 * everything to its own thread.
 */
final class WatchBridge implements WatchListener {

    volatile FxTray tray;
    volatile String activeId;
    volatile Map<String, AccountUsage> usages = Map.of();
    /** The account the watcher recommends moving to, until the next switch. */
    volatile String pendingTarget;
    /** Set by the window: called on the JavaFX thread after each poll so an open window shows the new numbers. */
    volatile Runnable onChange;
    /** Where every changed reading is written down; null in tests. */
    volatile ReadingsLog readings;
    /** Told the account Claude Code is on whenever that changes: a switch here, or one a poll found. */
    volatile java.util.function.Consumer<String> activeChanged = id -> { };

    @Override public void onPoll(String activeId, Map<String, AccountUsage> usages, Decision d) {
        if (!java.util.Objects.equals(activeId, this.activeId)) activeChanged.accept(activeId);
        this.activeId = activeId;
        this.usages = usages;
        var r = readings;
        if (r != null) r.record(activeId, usages);
        var t = tray;
        if (t == null) return;
        var active = activeId == null ? null : usages.get(activeId);
        var line = "ccacc — " + (activeId == null ? "unmanaged" : activeId + " " + util(active));
        t.tooltip(d != null && d.kind() != Decision.Kind.NONE ? line + " — " + d.reason() : line);
        var c = onChange;
        if (c != null) Platform.runLater(c);
    }

    @Override public void onPrompt(String activeId, String targetId, AccountUsage active, AccountUsage target) {
        IdleTrim.touch();
        pendingTarget = targetId;
        var t = tray;
        if (t == null) return;
        var overPace = active != null && active.weeklyHealth() == QuotaHealth.DANGER;
        t.warning(overPace ? "Claude Code over-pace on weekly quota" : "Claude Code near its limit",
                (activeId == null ? "Claude Code" : activeId) + " at " + util(active) + " (weekly avg " + avg(active)
                        + "). Open the ccacc menu to switch to " + targetId + " (" + weekFree(target) + " weekly free).");
    }

    @Override public void onSwitched(SwitchResult r, boolean automatic) { onSwitched(r, automatic, null); }

    @Override public void onSwitched(SwitchResult r, boolean automatic, String reason) {
        IdleTrim.touch();
        pendingTarget = null;
        activeId = r.toId();
        activeChanged.accept(r.toId());
        var t = tray;
        if (t != null) t.message(automatic ? "Auto-switched account" : "Switched account",
                (r.fromId() == null ? "(unmanaged)" : r.fromId()) + " → " + r.toId() + " (" + r.toEmail() + ")"
                        + (reason == null ? "" : "\n" + reason));
    }

    /** Locked, and Claude Code moved off the locked account by itself: say so and offer the way back (the top row of the menu). Never switches by itself. */
    @Override public void onLeftLocked(String lockedId, String nowId) {
        pendingTarget = lockedId;
        var t = tray;
        if (t != null) t.warning("Claude Code left " + lockedId,
                "It is on " + nowId + " now, and ccacc did not do it: a running Claude Code session probably saved its own login back. "
                        + "Locked mode keeps " + lockedId + ": open the ccacc menu to switch back.");
    }

    @Override public void onError(Throwable e, int nextIntervalSec) {
        var t = tray;
        if (t != null) t.tooltip("ccacc — error: " + UsageErrors.terse(e) + (nextIntervalSec > 0 ? " (retry " + nextIntervalSec + "s)" : ""));
    }
}
