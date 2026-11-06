package ccsuite.ccacc;

import module java.base;

/**
 * Everything that shows the free accounts' usage (the second tray icon, the small window, the taskbar card) as one unit that exists only while at least one free
 * account is saved: with none, nothing of it is visible, and it appears by itself when the first one is added ({@link #sync}, asked every half minute on the FX thread).
 */
final class FreeUsageUi {

    final FreeGauges gauges;
    final LaneSettings limits;
    final FreeWindow window;
    final boolean windows;
    FreeWidget widget;
    FreeTray tray;
    FreeCard card;
    boolean shown;

    FreeUsageUi(FreeGauges gauges, LaneSettings limits, FreeWindow window, boolean windows) {
        this.gauges = gauges; this.limits = limits; this.window = window; this.windows = windows;
    }

    boolean hasFree() { return !FreeGauges.free(gauges.accounts.get()).isEmpty(); }

    /** Shows or removes the whole thing to match whether a free account exists. FX thread. */
    void sync() {
        var has = hasFree();
        if (has && !shown) show();
        else if (!has && shown) hide();
    }

    void show() {
        shown = true;
        widget = new FreeWidget(gauges, limits, FreeSummary.COLOR, Path.of(System.getProperty("user.home"), "ccsuite", "ccacc", "free-widget.json"));
        try {
            tray = new FreeTray(gauges, widget, limits);
            tray.install(FreeSummary.COLOR);
        } catch (Throwable noTray) {
            tray = null;
            System.err.println("ccacc: no second tray icon for the free accounts (" + noTray + ")");
        }
        if (windows) {
            try {
                card = new FreeCard(gauges, limits);
                card.start();
            } catch (Throwable e) {
                card = null;
                System.err.println("ccacc: no taskbar card for the free accounts (" + e + ")");
            }
        }
        if (widget.wasShowing()) widget.show();
    }

    void hide() {
        shown = false;
        if (widget != null) widget.hideQuietly();
        if (tray != null) tray.remove();
        if (card != null) card.stop();
        if (windows) ccsuite.usagetracker.taskbar.SlotLayout.freeCard(false);   // the band is one card long again the next time the taskbar loads it
        widget = null; tray = null; card = null;
    }

    /** The gauges changed: redraw what is showing. Any thread. */
    void changed() {
        window.redrawLater();
        if (!shown) return;
        var w = widget;
        if (w != null) w.redrawLater();
        var t = tray;
        if (t != null) t.refresh();
        var c = card;
        if (c != null) c.push();
    }
}
