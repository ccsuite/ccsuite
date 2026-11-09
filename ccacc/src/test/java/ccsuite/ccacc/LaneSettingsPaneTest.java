package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** The lane settings form: it shows the settings as they are, applies only what was changed through the same command the line uses, and says why when a value is refused. */
class LaneSettingsPaneTest {

    static <T> T onFx(java.util.function.Supplier<T> s) throws Exception { return MainWindowTest.onFx(s); }

    static LaneSettingsPane pane(Path dir) throws Exception {
        MainWindowTest.toolkit();
        var parts = MainWindowTest.parts(dir, new WatchBridge());
        var p = onFx(() -> new LaneSettingsPane(parts));
        onFx(() -> { p.fill(); return null; });
        return p;
    }

    @Test void itShowsTheDefaultsAsTheyAre(@TempDir Path dir) throws Exception {
        var p = pane(dir);
        assertEquals("97", onFx(() -> p.five.getText()), "the limits are shown as percent");
        assertEquals("98", onFx(() -> p.week.getText()));
        assertEquals("2", onFx(() -> p.slots.getText()));
        assertEquals("8681", onFx(() -> p.pclaudePort.getText()));
        assertEquals("8686", onFx(() -> p.fclaudePort.getText()));
        assertEquals("all", onFx(() -> p.accounts.getText()));
    }

    @Test void aChangedValueIsSavedAndAnUnchangedOneIsLeftAlone(@TempDir Path dir) throws Exception {
        var p = pane(dir);
        onFx(() -> { p.five.setText("95"); p.slots.setText("3"); p.apply(); return null; });
        var l = p.parts.store.load().lanes();
        assertEquals(0.95, l.freeFiveLimit(), 1e-9);
        assertEquals(3, l.perAccountSlots());
        assertEquals(0.98, l.freeWeekLimit(), 1e-9, "not touched");
        assertTrue(onFx(() -> p.note.getText()).contains("freeFiveLimit"), onFx(() -> p.note.getText()));
        assertTrue(onFx(() -> p.note.getText()).contains("next start"), "the slots apply when the lanes next start, and the form says so");
    }

    @Test void aRefusedValueSaysWhyAndEverythingElseStillApplies(@TempDir Path dir) throws Exception {
        var p = pane(dir);
        onFx(() -> { p.five.setText("150"); p.every.setText("30"); p.apply(); return null; });
        var l = p.parts.store.load().lanes();
        assertEquals(0.97, l.freeFiveLimit(), 1e-9, "150 percent is refused");
        assertEquals(30, l.freePingEveryMinutes(), "the valid one was applied");
        assertTrue(onFx(() -> p.note.getText()).contains("freeFiveLimit"));
    }

    @Test void theFreeAccountsAreANamedListOrAll(@TempDir Path dir) throws Exception {
        var p = pane(dir);
        onFx(() -> { p.accounts.setText("F2, F1"); p.apply(); return null; });
        assertEquals(List.of("F2", "F1"), p.parts.store.load().lanes().freeAccounts());
        onFx(() -> { p.fill(); return null; });
        assertEquals("F2,F1", onFx(() -> p.accounts.getText()));
        onFx(() -> { p.accounts.setText("all"); p.apply(); return null; });
        assertTrue(p.parts.store.load().lanes().freeAccounts().isEmpty());
    }
}
