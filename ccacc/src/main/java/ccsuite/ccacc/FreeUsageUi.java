package ccsuite.ccacc;

import module java.base;
import ccsuite.usagetracker.ui.Palette;
import ccsuite.usagetracker.ui.UsageWidget;
import javafx.stage.Stage;

/**
 * Everything that shows the free accounts' usage outside the main window (the free usage window, the taskbar card) as one unit that exists only while at least one
 * free account is saved: with none, nothing of it is visible, and it appears by itself when the first one is added ({@link #sync}, asked every half minute on the FX
 * thread). The free usage window is the paid one ({@link UsageWidget}) fed with the free pages and painted teal; it is never shown by itself, only when asked
 * ({@link #toggleDialog}). There is no tray icon of its own: the one ccacc icon is enough.
 */
final class FreeUsageUi {

    final FreeGauges gauges;
    final LaneSettings limits;
    final FreePane pane;
    final boolean windows;
    UsageWidget widget;
    FreeCard card;
    boolean shown;
    /** Where the free usage window keeps its position; a test points it elsewhere. */
    Path windowSettings = Path.of(System.getProperty("user.home"), "ccsuite", "ccacc", "free-widget-settings.json");

    FreeUsageUi(FreeGauges gauges, LaneSettings limits, FreePane pane, boolean windows) {
        this.gauges = gauges; this.limits = limits; this.pane = pane; this.windows = windows;
    }

    boolean hasFree() { return !FreeGauges.free(gauges.accounts.get()).isEmpty(); }

    /** Shows or removes the whole thing to match whether a free account exists, and keeps the times in the window current. FX thread. */
    void sync() {
        var has = hasFree();
        if (has && !shown) show();
        else if (!has && shown) hide();
        else if (shown) push();
    }

    void show() {
        shown = true;
        widget = UsageWidget.feed("Free accounts", Palette.FREE, windowSettings, page -> FreePages.reread(gauges, page));
        widget.start(new Stage());
        push();
        if (windows) {
            try {
                card = new FreeCard(gauges, limits);
                card.start();
            } catch (Throwable e) {
                card = null;
                System.err.println("ccacc: no taskbar card for the free accounts (" + e + ")");
            }
        }
    }

    void hide() {
        shown = false;
        if (widget != null) widget.shutdown();
        if (card != null) card.stop();
        if (windows) ccsuite.usagetracker.taskbar.SlotLayout.freeCard(false);   // the band is one card long again the next time the taskbar loads it
        widget = null; card = null;
    }

    /** The free usage window, shown or hidden on request; does nothing while there is no free account. FX thread. */
    void toggleDialog() {
        var w = widget;
        if (w != null) w.floating(!w.floatingOn());
    }

    /** The window gets the pages as they are now. Any thread. */
    void push() {
        var w = widget;
        if (w == null) return;
        var now = Instant.now();
        var pages = FreePages.pages(gauges, now);
        w.feed(pages, FreePages.all(gauges, limits, pages, now));
    }

    /** The gauges changed: redraw what is showing. Any thread. */
    void changed() {
        pane.redrawLater();
        if (!shown) return;
        push();
        var c = card;
        if (c != null) c.push();
    }
}
