package ccsuite.ccacc;

import ccsuite.localserver.server.LocalServerService;
import ccsuite.oauthclient.AccountUsage;
import ccsuite.oauthswap.watch.UsageWatcher;
import ccsuite.oauthswap.watch.WatchConfig;
import ccsuite.oauthswap.watch.WatchMode;
import javafx.application.Platform;
import javafx.scene.control.MenuItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TrayItemsTest {

    /** The menu rows are JavaFX controls, which need the toolkit; without a display the class is skipped. */
    @BeforeAll static void toolkit() {
        var up = true;
        try { Platform.startup(() -> {}); }
        catch (IllegalStateException alreadyRunning) { }
        catch (Throwable noDisplay) { up = false; }
        assumeTrue(up, "no JavaFX toolkit here");
    }

    static TrayItems items(Path dir, WatchBridge bridge, boolean withWatcher) {
        var store = new CcaccSettingsStore(dir.resolve("settings.json"));
        var parts = new Parts(null, store, new CcaccSettings(), bridge, new LocalServerService(null, null, null, null, null));
        if (withWatcher) parts.watcher = new UsageWatcher(null, null, null, null, WatchConfig.defaults(), bridge);
        return new TrayItems(parts, bridge, () -> {});
    }

    static List<String> texts(List<MenuItem> l) { return l.stream().map(m -> m.getText() == null ? "---" : m.getText()).toList(); }

    @Test void withoutAWatcherTheMenuOffersToStartItAndNoSwitches(@TempDir Path dir) {
        var i = items(dir, new WatchBridge(), false);
        var t = texts(i.build());
        assertEquals("account watcher is off", i.status());
        assertTrue(t.contains("✗ Account watcher"));
        assertTrue(t.stream().noneMatch(s -> s.startsWith("Switch to")));
        assertTrue(t.contains("Quit ccacc"));
    }

    @Test void withAWatcherEveryOtherAccountCanBeSwitchedToAndTheModeIsMarked(@TempDir Path dir) {
        var bridge = new WatchBridge();
        bridge.activeId = "ann";
        var u = new LinkedHashMap<String, AccountUsage>();
        u.put("ann", new AccountUsage(40.0, null, 50.0, null, 50.0));
        u.put("dan", new AccountUsage(10.0, null, 20.0, null, 50.0));
        bridge.usages = u;
        bridge.pendingTarget = "dan";
        var i = items(dir, bridge, true);
        var t = texts(i.build());
        assertEquals("ann  5h 40%  week 50% (avg 100%)  ·  auto", i.status());
        assertEquals("Open ccacc window", t.getFirst());
        assertEquals("⚠ Switch to dan now (recommended)", t.get(2));
        assertTrue(t.contains("Switch to dan   80% weekly free, avg 40%"));
        assertTrue(t.stream().noneMatch(s -> s.startsWith("Switch to ann")));
        assertTrue(t.contains("● Auto: switch and rebalance on its own"));
        assertTrue(t.contains("○ Locked: stay on this account, still warn"));
    }

    @Test void choosingAModeIsRememberedInTheSettingsFile(@TempDir Path dir) {
        var i = items(dir, new WatchBridge(), true);
        i.parts.mode(WatchMode.LOCKED);
        assertEquals(WatchMode.LOCKED, i.parts.watcher.mode());
        assertEquals(WatchMode.LOCKED, i.parts.store.load().watchMode());
        assertTrue(texts(i.build()).contains("● Locked: stay on this account, still warn"));
    }
}
