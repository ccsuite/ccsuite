package ccsuite.oauthswap.watch;

import ccsuite.oauthswap.SwitchResult;
import ccsuite.oauthswap.tray.*;
import ccsuite.oauthclient.*;

import java.awt.*;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * System-tray UI (AWT — JavaFX has no native tray). A utilization-colored icon, a switch
 * menu rebuilt each poll, and balloon notifications for soft-limit prompts and switches.
 * Clicking a soft-limit balloon switches to the recommended account.
 */
public final class TrayUi implements WatchListener {

    private final SystemTray tray = SystemTray.getSystemTray();
    private final TrayIcon icon = new TrayIcon(
            TrayIconRenderer.render("?", null, QuotaHealth.UNKNOWN, IconFrame.NONE), "cswap — starting…");
    private final PopupMenu menu = new PopupMenu();
    private final CountDownLatch quit = new CountDownLatch(1);

    private UsageWatcher watcher;
    private volatile String pendingTarget;

    /** Add the icon to the tray and wire actions; returns a latch released when the user quits. */
    public CountDownLatch install(UsageWatcher w) throws AWTException {
        this.watcher = w;
        icon.setImageAutoSize(true);
        icon.setPopupMenu(menu);
        icon.addActionListener(e -> { var t = pendingTarget; if (t != null) watcher.requestSwitch(t); });
        rebuildMenu(null, Map.of());
        tray.add(icon);
        return quit;
    }

    @Override public void onPoll(String activeId, Map<String, AccountUsage> usages, Decision d) {
        EventQueue.invokeLater(() -> {
            var active = activeId == null ? null : usages.get(activeId);
            var health = active == null ? QuotaHealth.UNKNOWN : active.weeklyHealth();
            icon.setImage(TrayIconRenderer.render(monogram(activeId), ratioOf(active), health, IconFrame.NONE));
            icon.setToolTip(tooltip(activeId, active, d));
            rebuildMenu(activeId, usages);
        });
    }

    @Override public void onPrompt(String activeId, String targetId, AccountUsage active, AccountUsage target) {
        pendingTarget = targetId;
        var overPace = active != null && active.weeklyHealth() == QuotaHealth.DANGER;
        icon.displayMessage(
                overPace ? "Claude Code over-pace on weekly quota" : "Claude Code near its limit",
                idOf(activeId) + " at " + util(active) + " (weekly avg " + avg(active)
                        + ") — click to switch to " + targetId
                        + " (" + weekFree(target) + " weekly free)",
                overPace ? TrayIcon.MessageType.ERROR : TrayIcon.MessageType.WARNING);
    }

    @Override public void onSwitched(SwitchResult r, boolean automatic) {
        pendingTarget = null;
        icon.displayMessage(automatic ? "Auto-switched account" : "Switched account",
                (r.fromId() == null ? "(unmanaged)" : r.fromId()) + " → " + r.toId() + " (" + r.toEmail() + ")",
                TrayIcon.MessageType.INFO);
    }

    @Override public void onError(Throwable e, int nextIntervalSec) {
        EventQueue.invokeLater(() -> icon.setToolTip("cswap — error: " + UsageErrors.terse(e)
                + (nextIntervalSec > 0 ? " (retry " + nextIntervalSec + "s)" : "")));
    }

    private void rebuildMenu(String activeId, Map<String, AccountUsage> usages) {
        menu.removeAll();
        var active = activeId == null ? null : usages.get(activeId);
        var status = new MenuItem(activeId == null ? "Active: (unmanaged)"
                : "Active: " + activeId + "  5h " + util(active) + "  wk-avg " + avg(active));
        status.setEnabled(false);
        menu.add(status);
        menu.addSeparator();
        for (var e : usages.entrySet()) {
            if (e.getKey().equals(activeId)) continue;
            var id = e.getKey();
            var mi = new MenuItem("Switch to " + id + "  (" + weekFree(e.getValue())
                    + " weekly free, avg " + avg(e.getValue()) + ")");
            mi.addActionListener(ev -> watcher.requestSwitch(id));
            menu.add(mi);
        }
        menu.addSeparator();
        var refresh = new MenuItem("Refresh now");
        refresh.addActionListener(ev -> watcher.pollNow());
        menu.add(refresh);
        var exit = new MenuItem("Quit");
        exit.addActionListener(ev -> { watcher.stop(); tray.remove(icon); quit.countDown(); });
        menu.add(exit);
    }

    private static String tooltip(String activeId, AccountUsage a, Decision d) {
        var base = "cswap — " + (activeId == null ? "unmanaged" : activeId + " " + util(a));
        return (d != null && d.kind() != Decision.Kind.NONE) ? base + " — " + d.reason() : base;
    }

    private static String idOf(String id) { return id == null ? "Claude Code" : id; }

    /** 2-letter tray-icon monogram (or "?" when unmanaged). */
    private static String monogram(String id) {
        return id == null ? "?" : id.length() > 2 ? id.substring(0, 2).toUpperCase(Locale.ROOT) : id.toUpperCase(Locale.ROOT);
    }

    /** Weekly burn-rate as a 0..1+ fraction for the ring fill, or {@code null} when unknown. */
    private static Double ratioOf(AccountUsage u) {
        var avg = u == null ? null : u.sevenDayAverage();
        return avg == null ? null : avg / 100.0;
    }

    private static String util(AccountUsage u) {
        var v = u == null ? null : u.utilization();
        return v == null ? "?" : String.format(Locale.ROOT, "%.0f%%", v);
    }

    private static String weekFree(AccountUsage u) {
        var v = u == null ? null : u.sevenDayHeadroom();
        return v == null ? "?" : String.format(Locale.ROOT, "%.0f%%", v);
    }

    private static String avg(AccountUsage u) {
        var v = u == null ? null : u.sevenDayAverage();
        return v == null ? "?" : String.format(Locale.ROOT, "%.0f%%", v);
    }
}
