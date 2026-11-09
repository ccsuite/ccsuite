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

    @Test void theFreeWindowIsTheSharedWindowFedWithTheFreePagesAndNeverShowsByItself(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        MainWindowTest.toolkit();
        var gauges = new FreeGauges(() -> List.of(account("F1", 0, "k"), account("F2", 0, "k")), (c, m) -> null, dir.resolve("log"));
        var ui = onFx(() -> new FreeUsageUi(gauges, new LaneSettings(), MainWindowTest.pane(), false));
        ui.windowSettings = dir.resolve("free-window.json");
        onFx(() -> { ui.sync(); return null; });
        assertTrue(ui.shown);
        assertNotNull(ui.widget, "the free window exists as soon as a free account does");
        assertFalse(onFx(() -> ui.widget.floatingOn()), "and is not shown by itself");
        onFx(() -> { ui.toggleDialog(); return null; });
        assertTrue(onFx(() -> ui.widget.floatingOn()), "it opens on request");
        onFx(() -> { ui.push(); return null; });
        onFx(() -> { ui.toggleDialog(); return null; });
        assertFalse(onFx(() -> ui.widget.floatingOn()));
        onFx(() -> { ui.hide(); return null; });
        assertNull(ui.widget);
    }

    static <T> T onFx(java.util.function.Supplier<T> s) throws Exception { return MainWindowTest.onFx(s); }
}
