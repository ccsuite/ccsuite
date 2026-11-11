package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPICredential;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The free usage tracker exists only while a free account does, and is teal where the paid one is orange. */
class FreeUsageUiTest {

    static CCAPICredential account(String id, Integer tier, String key) {
        return new CCAPICredential().id(id).email(id + "@example.org").tier(tier).key(key).orgId("o");
    }

    static FreeUsageUi ui(List<CCAPICredential> accounts) {
        return new FreeUsageUi(new FreeGauges(() -> accounts, (c, m) -> null, Path.of("unused")), new LaneSettings(), null, false);
    }

    @Test void withNoFreeAccountThereIsNothingToShow() {
        assertFalse(ui(List.of()).hasFree());
        assertFalse(ui(List.of(account("P", 1, "k"), account("Q", null, "k"))).hasFree(), "paid accounts do not count");
        assertFalse(ui(List.of(account("F", 0, ""))).hasFree(), "a free account without a session key cannot be read");
    }

    @Test void oneFreeAccountIsEnough() {
        assertTrue(ui(List.of(account("P", 1, "k"), account("F", 0, "k"))).hasFree());
    }

    @Test void theFreeWindowHasItsOwnColours() {
        assertNotEquals(ccsuite.usagetracker.ui.Palette.PAID.bgActive(), ccsuite.usagetracker.ui.Palette.FREE.bgActive(), "teal ground, so it is never mistaken for the paid window");
        assertNotNull(ccsuite.usagetracker.ui.Palette.FREE.timeBar());
        assertNull(ccsuite.usagetracker.ui.Palette.PAID.timeBar(), "the paid window keeps the colours of its own rows");
    }

    /** PRP 93: with no taskbar card the free window is the display, so it shows by itself; hiding it is remembered and it comes back on request. */
    @Test void withoutATaskbarCardTheFreeWindowIsTheSharedWindowFedWithTheFreePagesAndShowsByItself(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        MainWindowTest.toolkit();
        var gauges = new FreeGauges(() -> List.of(account("F1", 0, "k"), account("F2", 0, "k")), (c, m) -> null, dir.resolve("log"));
        var ui = onFx(() -> new FreeUsageUi(gauges, new LaneSettings(), MainWindowTest.pane(), false));
        ui.windowSettings = dir.resolve("free-window.json");
        onFx(() -> { ui.sync(); return null; });
        assertTrue(ui.shown);
        assertNotNull(ui.widget, "the free window exists as soon as a free account does");
        assertNull(ui.card, "no card was asked for, so nothing was put into the taskbar");
        assertTrue(onFx(() -> ui.widget.floatingOn()), "and with no card it is the display, shown by itself");
        onFx(() -> { ui.toggleDialog(); return null; });
        assertFalse(onFx(() -> ui.widget.floatingOn()), "it hides on request");
        onFx(() -> { ui.push(); return null; });
        onFx(() -> { ui.toggleDialog(); return null; });
        assertTrue(onFx(() -> ui.widget.floatingOn()), "and opens again");
        onFx(() -> { ui.hide(); return null; });
        assertNull(ui.widget);
    }

    /** The window the user hid stays hidden at the next start: the setting is written to the window's own file. */
    @Test void aHiddenFreeWindowStaysHiddenTheNextTime(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        MainWindowTest.toolkit();
        var gauges = new FreeGauges(() -> List.of(account("F1", 0, "k")), (c, m) -> null, dir.resolve("log"));
        var file = dir.resolve("free-window.json");
        var first = onFx(() -> new FreeUsageUi(gauges, new LaneSettings(), MainWindowTest.pane(), false));
        first.windowSettings = file;
        onFx(() -> { first.sync(); first.toggleDialog(); return null; });
        assertFalse(onFx(() -> first.widget.floatingOn()));
        onFx(() -> { first.hide(); return null; });
        var second = onFx(() -> new FreeUsageUi(gauges, new LaneSettings(), MainWindowTest.pane(), false));
        second.windowSettings = file;
        onFx(() -> { second.sync(); return null; });
        assertFalse(onFx(() -> second.widget.floatingOn()), "the user hid it, so it waits to be asked for");
        onFx(() -> { second.hide(); return null; });
    }

    /** With the card as the display (Windows, the setting on) the window waits to be asked for, as it always did on the machine that has the card. */
    @Test void whenTheTaskbarCardIsTheDisplayTheWindowWaitsToBeAskedFor(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(ccsuite.usagetracker.ui.UsageWidget.WINDOWS, "the card exists on Windows only");
        MainWindowTest.toolkit();
        var w = onFx(() -> ccsuite.usagetracker.ui.UsageWidget.feed("Free accounts", ccsuite.usagetracker.ui.Palette.FREE, dir.resolve("w.json"), page -> null).cardInTaskbar(true));
        onFx(() -> { w.start(new javafx.stage.Stage()); return null; });
        assertFalse(onFx(() -> w.floatingOn()), "the card shows these pages; the window opens only on request");
        onFx(() -> { w.floating(true); return null; });
        assertTrue(onFx(() -> w.floatingOn()));
        onFx(() -> { w.shutdown(); return null; });
    }

    static <T> T onFx(java.util.function.Supplier<T> s) throws Exception { return MainWindowTest.onFx(s); }
}
