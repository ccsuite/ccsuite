package ccsuite.ccacc;

import ccsuite.oauthswap.watch.WatchMode;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import java.awt.Desktop;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static ccsuite.ccacc.TrayText.*;

/** What the tray menu lists each time it opens, from the state of the parts. Items are plain text rows; a switch shows as a marker in front. */
final class TrayItems {

    final Parts parts;
    final WatchBridge bridge;
    final Runnable quit;
    /** Opens the simple window; set by the app once the window exists. */
    Runnable openWindow = () -> {};
    /** Whether any free account is saved; the free usage window row is left out when none is. Set by the app. */
    java.util.function.BooleanSupplier hasFree = () -> true;
    /** Shows or hides the floating usage windows (paid, free), which are never shown by themselves; set by the app. */
    Runnable openPaidDialog = () -> {}, openFreeDialog = () -> {};
    /** Opens the lanes window; set by the app. */
    Runnable openLanes = () -> {};
    /** Opens the window with the account switches, the watcher mode, the parts and the servers; set by the app. */
    Runnable openParts = () -> {};

    TrayItems(Parts parts, WatchBridge bridge, Runnable quit) {
        this.parts = parts; this.bridge = bridge; this.quit = quit;
    }

    String status() {
        var w = parts.watcher;
        if (w == null) return "account watcher is off";
        var id = bridge.activeId;
        return TrayText.status(id, id == null ? null : bridge.usages.get(id), w.mode());
    }

    /** The tray menu, kept short: the things used every day, and the watcher's mode (an important setting) flat. Everything long (switching to an account, the parts and servers, the lanes) is behind one window. */
    List<MenuItem> build() {
        IdleTrim.touch();
        var l = new ArrayList<MenuItem>();
        l.add(item("Open ccacc window", () -> openWindow.run()));
        l.add(item("Usage window (paid accounts)", () -> openPaidDialog.run()));
        if (hasFree.getAsBoolean()) l.add(item("Usage window (free accounts)", () -> openFreeDialog.run()));
        l.add(item("Lanes…", () -> openLanes.run()));
        l.add(new SeparatorMenuItem());
        var w = parts.watcher;
        if (w != null) {
            var target = bridge.pendingTarget;
            if (target != null) l.add(item("⚠ Switch to " + target + " now (recommended)", () -> w.requestSwitch(target)));
            l.add(new SeparatorMenuItem());
            l.addAll(modeItems());   // Auto, Locked, Paused with the current one marked, and Refresh now: an important setting, so flat in the menu
            l.add(new SeparatorMenuItem());
        }
        l.add(item("Servers and accounts…", () -> openParts.run()));
        l.add(new SeparatorMenuItem());
        l.add(item("Open ccacc settings", () -> open(parts.store.file().toFile())));
        l.add(item("Quit ccacc", quit));
        return l;
    }

    /** What the watcher may do on its own. */
    List<MenuItem> modeItems() {
        var l = new ArrayList<MenuItem>();
        var w = parts.watcher;
        if (w == null) return l;
        l.add(item(mark(w.mode() == WatchMode.AUTO) + " Auto: switch and rebalance on its own", () -> parts.mode(WatchMode.AUTO)));
        l.add(item(mark(w.mode() == WatchMode.LOCKED) + " Locked: stay on this account, still warn", () -> parts.mode(WatchMode.LOCKED)));
        l.add(item(mark(w.mode() == WatchMode.PAUSED) + " Paused: do not poll at all", () -> parts.mode(WatchMode.PAUSED)));
        l.add(item("Refresh now", w::pollNow));
        return l;
    }

    String gateNote() {
        if (parts.gate.running()) return "  :" + parts.gate.port();
        return parts.gateProblem == null ? "" : "  (could not start: " + parts.gateProblem + ")";
    }

    String relayNote() {
        var r = parts.relay;
        if (r != null) return r.problem() == null ? "  (connected)" : "  (cannot reach: " + r.problem() + ")";
        return parts.relayProblem == null ? "" : "  (could not start: " + parts.relayProblem + ")";
    }

    String serverNote() {
        if (parts.server.running()) return "  :" + parts.server.port();
        return parts.serverProblem == null ? "" : "  (could not start: " + parts.serverProblem + ")";
    }

    static String mark(boolean on) { return on ? "●" : "○"; }

    static String onOff(boolean on) { return on ? "✓" : "✗"; }

    static MenuItem item(String text, Runnable action) {
        var m = new MenuItem(text);
        m.setOnAction(e -> action.run());
        return m;
    }

    static void open(java.io.File f) {
        try {
            if (!f.exists()) { java.nio.file.Files.createDirectories(f.toPath().getParent()); }
            Desktop.getDesktop().open(f);
        } catch (IOException | RuntimeException e) {
            System.err.println("ccacc: cannot open " + f + ": " + e);
        }
    }
}
