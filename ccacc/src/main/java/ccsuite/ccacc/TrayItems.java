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

    TrayItems(Parts parts, WatchBridge bridge, Runnable quit) {
        this.parts = parts; this.bridge = bridge; this.quit = quit;
    }

    String status() {
        var w = parts.watcher;
        if (w == null) return "account watcher is off";
        var id = bridge.activeId;
        return TrayText.status(id, id == null ? null : bridge.usages.get(id), w.mode());
    }

    List<MenuItem> build() {
        IdleTrim.touch();
        var l = new ArrayList<MenuItem>();
        l.add(item("Open ccacc window", () -> openWindow.run()));
        l.add(new SeparatorMenuItem());
        var w = parts.watcher;
        if (w != null) {
            var target = bridge.pendingTarget;
            if (target != null) l.add(item("⚠ Switch to " + target + " now (recommended)", () -> w.requestSwitch(target)));
            bridge.usages.forEach((id, u) -> {
                if (!id.equals(bridge.activeId))
                    l.add(item("Switch to " + id + "   " + weekFree(u) + " weekly free, avg " + avg(u), () -> w.requestSwitch(id)));
            });
            l.add(new SeparatorMenuItem());
            l.add(item(mark(w.mode() == WatchMode.AUTO) + " Auto: switch and rebalance on its own", () -> parts.mode(WatchMode.AUTO)));
            l.add(item(mark(w.mode() == WatchMode.LOCKED) + " Locked: stay on this account, still warn", () -> parts.mode(WatchMode.LOCKED)));
            l.add(item(mark(w.mode() == WatchMode.PAUSED) + " Paused: do not poll at all", () -> parts.mode(WatchMode.PAUSED)));
            l.add(item("Refresh now", w::pollNow));
        }
        l.add(new SeparatorMenuItem());
        l.add(item(onOff(w != null) + " Account watcher", () -> parts.watch(w == null)));
        l.add(item(onOff(parts.widget != null) + " Usage widget", () -> parts.widget(parts.widget == null)));
        var widget = parts.widget;
        if (widget != null) {
            l.add(item("      " + onOff(widget.taskbarOn()) + " Card in the taskbar", () -> widget.taskbar(!widget.taskbarOn())));
            l.add(item("      " + onOff(widget.floatingOn()) + " Floating window", () -> widget.floating(!widget.floatingOn())));
        }
        l.add(item(onOff(parts.server.running()) + " Local server" + serverNote(), () -> parts.server(!parts.server.running())));
        l.add(item(onOff(parts.gate.running()) + " Gate" + gateNote(), () -> parts.gate(!parts.gate.running())));
        l.add(item(onOff(parts.relay != null) + " Relay" + relayNote(), () -> parts.relay(parts.relay == null)));
        l.add(new SeparatorMenuItem());
        l.add(item("Open ccacc settings", () -> open(parts.store.file().toFile())));
        l.add(item("Quit ccacc", quit));
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
