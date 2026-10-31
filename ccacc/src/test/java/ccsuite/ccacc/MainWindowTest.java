package ccsuite.ccacc;

import ccsuite.localserver.server.LocalServerService;
import ccsuite.oauthclient.AccountUsage;
import ccsuite.oauthswap.watch.UsageWatcher;
import ccsuite.oauthswap.watch.WatchConfig;
import ccsuite.oauthswap.watch.WatchMode;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MainWindowTest {

    @BeforeAll static void toolkit() {
        var up = true;
        try { Platform.startup(() -> {}); }
        catch (IllegalStateException alreadyRunning) { }
        catch (Throwable noDisplay) { up = false; }
        assumeTrue(up, "no JavaFX toolkit here");
    }

    static <T> T onFx(java.util.function.Supplier<T> s) throws Exception {
        var f = new CompletableFuture<T>();
        Platform.runLater(() -> { try { f.complete(s.get()); } catch (Throwable t) { f.completeExceptionally(t); } });
        return f.get();
    }

    @Test void theControlsShowWhatThePartsAre(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        bridge.activeId = "ann";
        var u = new LinkedHashMap<String, AccountUsage>();
        u.put("ann", new AccountUsage(40.0, null, 50.0, null, 50.0));
        u.put("dan", new AccountUsage(10.0, null, 20.0, null, 50.0));
        bridge.usages = u;
        var store = new CcaccSettingsStore(dir.resolve("settings.json"));
        var parts = new Parts(null, store, new CcaccSettings(), bridge, new LocalServerService(null, null, null, null, null));
        parts.watcher = new UsageWatcher(null, null, null, null, WatchConfig.defaults(), bridge);
        parts.watcher.mode(WatchMode.LOCKED);
        var w = onFx(() -> new MainWindow(parts, bridge, "#d97757", () -> "status line"));
        onFx(() -> { w.redraw(); return null; });
        assertEquals(2, onFx(() -> w.accounts.getItems().size()));
        assertTrue(onFx(() -> w.accounts.getItems().get(0).startsWith("●")));
        assertTrue(onFx(() -> w.accounts.getItems().get(1).contains("dan")));
        assertEquals(WatchMode.LOCKED, onFx(() -> w.modes.getSelectedToggle().getUserData()));
        assertTrue(onFx(() -> w.watch.isSelected()));
        assertFalse(onFx(() -> w.widget.isSelected()));
        assertTrue(onFx(() -> w.card.isDisable()));
        assertFalse(onFx(() -> w.server.isSelected()));
        assertEquals("status line", onFx(() -> w.status.getText()));
    }

    @Test void choosingAModeInTheWindowChangesTheWatcherAndIsSaved(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        var store = new CcaccSettingsStore(dir.resolve("settings.json"));
        var parts = new Parts(null, store, new CcaccSettings(), bridge, new LocalServerService(null, null, null, null, null));
        parts.watcher = new UsageWatcher(null, null, null, null, WatchConfig.defaults(), bridge);
        var w = onFx(() -> new MainWindow(parts, bridge, "#d97757", () -> ""));
        onFx(() -> { w.redraw(); return null; });
        onFx(() -> { w.modes.getToggles().stream().filter(t -> t.getUserData() == WatchMode.PAUSED).findFirst().get().setSelected(true); return null; });
        assertEquals(WatchMode.PAUSED, parts.watcher.mode());
        assertEquals(WatchMode.PAUSED, store.load().watchMode());
    }

    @Test void switchingWithNothingChosenOrNoWatcherSaysWhy(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        var parts = new Parts(null, new CcaccSettingsStore(dir.resolve("s.json")), new CcaccSettings(), bridge, new LocalServerService(null, null, null, null, null));
        var w = onFx(() -> new MainWindow(parts, bridge, "#d97757", () -> ""));
        onFx(() -> { w.switchToChosen(); return null; });
        assertTrue(onFx(() -> w.note.getText()).contains("Choose an account"));
    }
}
