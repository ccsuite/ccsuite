package ccsuite.ccacc;

import module java.base;
import ccsuite.usagetracker.ui.Palette;
import ccsuite.usagetracker.ui.UsageWidget;
import javafx.stage.Stage;

/**
 * Everything that shows the free accounts' usage outside the main window (the free usage window, the taskbar card) as one unit that exists only while at least one
 * free account is saved: with none, nothing of it is visible, and it appears by itself when the first one is added ({@link #sync}, asked every half minute on the FX
 * thread). The free usage window is the paid one ({@link UsageWidget}) fed with the free pages and painted teal; it shows by itself unless the taskbar card is on (PRP 93), and opens on request otherwise
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
        this.cardWanted = () -> windows && UsageWidget.cardSetting();
    }

    boolean hasFree() { return !FreeGauges.free(gauges.accounts.get()).isEmpty(); }

    /** Shows or removes the whole thing to match whether a free account exists, and keeps the times in the window current. FX thread. */
    void sync() {
        var has = hasFree();
        if (has && !shown) show();
        else if (!has && shown) hide();
        else if (shown) {
            var cardOn = cardWanted.getAsBoolean();   // the card was switched on or off since the last look
            if (cardOn && card == null) startCard();
            else if (!cardOn && card != null) stopCard();
            push();
        }
    }

    /** Whether the taskbar card is wanted (PRP 93: opt-in, the {@code taskbar} setting of the usage widget; only on Windows). Without it the free window is the display and shows by itself. */
    BooleanSupplier cardWanted;

    void show() {
        shown = true;
        var cardOn = cardWanted.getAsBoolean();
        widget = UsageWidget.feed("Free accounts", Palette.FREE, windowSettings, page -> FreePages.reread(gauges, page)).cardInTaskbar(cardOn);
        widget.start(new Stage());
        push();
        if (cardOn) startCard();
    }

    void startCard() {
        try {
            card = new FreeCard(gauges, limits);
            card.start();
        } catch (Throwable e) {
            card = null;
            System.err.println("ccacc: no taskbar card for the free accounts (" + e + ")");
        }
    }

    void stopCard() {
        if (card == null) return;   // never started (the card is off): nothing was written, nothing to undo
        card.stop();
        card = null;
        if (windows) ccsuite.usagetracker.taskbar.SlotLayout.freeCard(false);   // the band is one card long again the next time the taskbar loads it
    }

    void hide() {
        shown = false;
        if (widget != null) widget.shutdown();
        stopCard();
        widget = null;
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
