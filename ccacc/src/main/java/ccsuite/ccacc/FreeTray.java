package ccsuite.ccacc;

import module java.base;
import javafx.scene.control.MenuItem;
import leasegate.tray.FxTray;
import leasegate.tray.TrayMenu;

/**
 * A second tray icon for the free accounts only: its picture is one bar per account (five-hour usage), its tooltip the summary line, a click shows or hides
 * the {@link FreeWidget}, and its menu has just two items, so twelve accounts never reach a menu. The first icon (the paid accounts and the app) is unchanged.
 */
final class FreeTray {

    final FreeGauges gauges;
    final FreeWidget widget;
    final LaneSettings limits;
    FxTray tray;

    FreeTray(FreeGauges gauges, FreeWidget widget, LaneSettings limits) {
        this.gauges = gauges; this.widget = widget; this.limits = limits;
    }

    List<MenuItem> items() {
        return List.of(TrayItems.item("Free usage window", widget::toggle), TrayItems.item("Read the free accounts now", gauges::refreshInBackground));
    }

    String status() { return summary(); }

    String summary() { return FreeSummary.line(gauges.snapshot(), limits.freeFiveLimit(), limits.freeWeekLimit(), Instant.now()); }

    void install(String color) {
        var menu = new TrayMenu(FxImages.of(FreeTrayIcon.draw(gauges.snapshot(), 64)), "free accounts", color, this::status, this::items);
        tray = FxTray.install(FreeTrayIcon.draw(gauges.snapshot(), 32), summary(), menu, widget::toggle);
    }

    /** Redraws the picture and the tooltip from the latest readings; any thread. */
    void refresh() {
        var t = tray;
        if (t == null) return;
        try {
            t.image(FreeTrayIcon.draw(gauges.snapshot(), 32));
            t.tooltip(summary());
        } catch (RuntimeException e) {
            System.err.println("ccacc: free tray icon not updated: " + e);
        }
    }

    void remove() { if (tray != null) tray.remove(); tray = null; }
}
