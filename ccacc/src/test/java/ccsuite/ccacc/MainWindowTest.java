package ccsuite.ccacc;

import ccsuite.base.model.CCAPICredential;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MainWindowTest {

    @BeforeAll static void toolkit() {
        var up = true;
        try { Platform.startup(() -> {}); }
        catch (IllegalStateException alreadyRunning) { }
        catch (Throwable noDisplay) { up = false; }
        if (up) Platform.setImplicitExit(false);   // hiding the last window of one test must not end the toolkit for the next
        assumeTrue(up, "no JavaFX toolkit here");
    }

    static <T> T onFx(java.util.function.Supplier<T> s) throws Exception {
        var f = new CompletableFuture<T>();
        Platform.runLater(() -> { try { f.complete(s.get()); } catch (Throwable t) { f.completeExceptionally(t); } });
        return f.get();
    }

    static FreePane pane() { return new FreePane(new FreeGauges(() -> java.util.List.of(), (c, m) -> null, Path.of("unused")), LaneSettings::new); }

    static Parts parts(Path dir, WatchBridge bridge) {
        return new Parts(null, new CcaccSettingsStore(dir.resolve("settings.json")), new CcaccSettings(), bridge, new LocalServerService(null, null, null, null, null), new ccsuite.ccgate.GateService(() -> null));
    }

    static MainWindow window(Parts parts, WatchBridge bridge, FreePane free) throws Exception {
        return onFx(() -> new MainWindow(parts, bridge, "#d97757", () -> "status line", free, new TrayItems(parts, bridge, () -> { })));
    }

    @Test void theFreeTabOpensFromTheTableAlreadyHeldAndReadsNoAccount(@TempDir Path dir) throws Exception {
        var probes = new AtomicInteger();
        var account = new CCAPICredential().id("F1").email("f1@example.org").tier(0).key("k").orgId("o");
        var gauges = new FreeGauges(() -> java.util.List.of(account), (c, m) -> { probes.incrementAndGet(); return null; }, dir.resolve("log"));
        var bridge = new WatchBridge();
        var w = window(parts(dir, bridge), bridge, new FreePane(gauges, LaneSettings::new));
        onFx(() -> { w.showFree(); return null; });
        onFx(() -> { w.sync(); return null; });
        assertTrue(onFx(() -> w.freeTab.isSelected()), "the tray's entry lands on the free tab");
        assertTrue(onFx(() -> w.free.active));
        assertEquals(1, onFx(() -> w.free.view.count), "the account is listed with no reading yet");
        assertEquals(0, probes.get(), "showing the tab must not read any account");
        onFx(() -> { w.stage.hide(); return null; });
        assertFalse(onFx(() -> w.free.active), "hidden: no clock running");
    }

    @Test void aTabNotInFrontIsNeverBuiltAndStopsWhenAnotherComesForward(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        var w = window(parts(dir, bridge), bridge, pane());
        onFx(() -> { w.show(); w.sync(); return null; });
        assertTrue(onFx(() -> w.paid.active), "the first tab is the one in front");
        assertFalse(onFx(() -> w.servers.active) || onFx(() -> w.lanes.active) || onFx(() -> w.manage.active) || onFx(() -> w.free.active), "no other tab runs");
        onFx(() -> { w.show(w.serversTab); w.sync(); return null; });
        assertTrue(onFx(() -> w.servers.active));
        assertFalse(onFx(() -> w.paid.active), "the one that went back stops");
        onFx(() -> { w.stage.hide(); return null; });
        assertFalse(onFx(() -> w.servers.active));
    }

    @Test void thePaidTabShowsThePaidAccountsAndAll(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        bridge.activeId = "ann";
        var soon = Instant.now().plusSeconds(3600).toString();
        var u = new LinkedHashMap<String, AccountUsage>();
        u.put("ann", new AccountUsage(40.0, soon, 50.0, soon, 50.0));
        u.put("dan", new AccountUsage(10.0, soon, 20.0, soon, 50.0));
        bridge.usages = u;
        var parts = parts(dir, bridge);
        var w = window(parts, bridge, pane());
        onFx(() -> { w.paid.active = true; w.paid.redraw(); return null; });
        assertEquals(3, onFx(() -> w.paid.view.count), "ALL and the two paid accounts");
        assertNotNull(onFx(() -> w.paid.view.blocks.get("ALL")));
        assertNotNull(onFx(() -> w.paid.view.blocks.get("ann")));
    }

    @Test void aRefreshUpdatesTheBlocksInPlaceInsteadOfBuildingThemAgain(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        var soon = Instant.now().plusSeconds(3600).toString();
        var u = new LinkedHashMap<String, AccountUsage>();
        for (var i = 0; i < 21; i++) u.put("A" + i, new AccountUsage(10.0 + i, soon, 20.0, soon, 50.0));
        bridge.usages = u;
        var w = window(parts(dir, bridge), bridge, pane());
        onFx(() -> { w.paid.active = true; w.paid.redraw(); return null; });
        var first = onFx(() -> w.paid.view.blocks.get("A7"));
        var rows = onFx(() -> w.paid.view.rows.getChildren().size());
        u.put("A7", new AccountUsage(77.0, soon, 20.0, soon, 50.0));
        onFx(() -> { w.paid.redraw(); return null; });
        assertSame(first, onFx(() -> w.paid.view.blocks.get("A7")), "the same block, updated");
        assertEquals(rows, onFx(() -> w.paid.view.rows.getChildren().size()));
        assertEquals("77%", onFx(() -> w.paid.view.blocks.get("A7").five.usePct.getText()).strip());
        assertEquals(22, onFx(() -> w.paid.view.count), "ALL and 21 accounts");
    }

    @Test void theModeOfTheWatcherIsShownAndAChoiceChangesItAndIsSaved(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        var store = new CcaccSettingsStore(dir.resolve("settings.json"));
        var parts = new Parts(null, store, new CcaccSettings(), bridge, new LocalServerService(null, null, null, null, null), new ccsuite.ccgate.GateService(() -> null));
        parts.watcher = new UsageWatcher(null, null, null, null, WatchConfig.defaults(), bridge);
        parts.watcher.mode(WatchMode.LOCKED);
        var w = window(parts, bridge, pane());
        onFx(() -> { w.paid.active = true; w.paid.redraw(); return null; });
        assertEquals(WatchMode.LOCKED, onFx(() -> w.paid.modes.getSelectedToggle().getUserData()));
        onFx(() -> { w.paid.modes.getToggles().stream().filter(t -> t.getUserData() == WatchMode.PAUSED).findFirst().get().setSelected(true); return null; });
        assertEquals(WatchMode.PAUSED, parts.watcher.mode());
        assertEquals(WatchMode.PAUSED, store.load().watchMode());
    }

    @Test void theServersTabShowsEachPartAsASwitch(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        var parts = parts(dir, bridge);
        parts.watcher = new UsageWatcher(null, null, null, null, WatchConfig.defaults(), bridge);
        var w = window(parts, bridge, pane());
        onFx(() -> { w.servers.active = true; w.servers.redraw(); return null; });
        assertTrue(onFx(() -> w.servers.watch.isSelected()));
        assertFalse(onFx(() -> w.servers.widget.isSelected()));
        assertTrue(onFx(() -> w.servers.card.isDisable()), "the card belongs to the widget");
        assertFalse(onFx(() -> w.servers.server.isSelected()));
        assertFalse(onFx(() -> w.servers.gate.isSelected()));
    }

    @Test void theWatchersRecommendationShowsOnThePaidTabWithAButtonAndGoesAway(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        var parts = parts(dir, bridge);
        parts.watcher = new UsageWatcher(null, null, null, null, WatchConfig.defaults(), bridge);
        var w = window(parts, bridge, pane());
        onFx(() -> { w.paid.active = true; w.paid.redraw(); return null; });
        assertFalse(onFx(() -> w.paid.recommend.isVisible()), "nothing recommended: no banner");
        bridge.pendingTarget = "dan";
        onFx(() -> { w.paid.redraw(); return null; });
        assertTrue(onFx(() -> w.paid.recommend.isVisible()));
        assertTrue(onFx(() -> w.paid.recommendText.getText()).contains("dan"));
        assertTrue(onFx(() -> w.paid.recommendButton.getText()).contains("dan"));
        bridge.pendingTarget = null;
        onFx(() -> { w.paid.redraw(); return null; });
        assertFalse(onFx(() -> w.paid.recommend.isVisible()));
    }

    @Test void theLanesSubTabsReadTheirContentWhenTheyComeForward(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        var w = window(parts(dir, bridge), bridge, pane());
        onFx(() -> { w.show(w.lanesTab); w.sync(); return null; });
        assertEquals("", onFx(() -> w.lanes.settings.five.getText()), "the form is not read until its sub-tab is in front");
        assertFalse(onFx(() -> w.lanes.calls.loaded));
        onFx(() -> { w.lanes.sub.getSelectionModel().select(w.lanes.settingsTab); return null; });
        assertEquals("97", onFx(() -> w.lanes.settings.five.getText()));
        onFx(() -> { w.lanes.sub.getSelectionModel().select(w.lanes.callsTab); return null; });
        assertTrue(onFx(() -> w.lanes.calls.loaded), "the report is read once, when its sub-tab first comes forward");
        onFx(() -> { w.stage.hide(); return null; });
    }

    static javafx.scene.input.KeyEvent press(javafx.scene.input.KeyCode code) {
        return new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    @Test void theKeysOfTheTabInFrontAreInTheLegendAndDoWhatTheLegendSays(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        var parts = parts(dir, bridge);
        parts.watcher = new UsageWatcher(null, null, null, null, WatchConfig.defaults(), bridge);
        parts.watcher.mode(WatchMode.AUTO);
        var w = window(parts, bridge, pane());
        onFx(() -> { w.show(); w.sync(); return null; });
        var legend = onFx(() -> w.legend.getChildren().stream().map(n -> ((javafx.scene.control.Button) n).getText()).toList());
        assertTrue(legend.contains("F6 Auto") && legend.contains("F7 Locked") && legend.contains("F8 Paused") && legend.contains("F5 Read now") && legend.contains("Esc Close"), legend.toString());
        onFx(() -> { javafx.event.Event.fireEvent(w.stage.getScene(), press(javafx.scene.input.KeyCode.F7)); return null; });
        assertEquals(WatchMode.LOCKED, parts.watcher.mode(), "F7 is what the legend says it is");
        onFx(() -> { w.legend.getChildren().stream().map(n -> (javafx.scene.control.Button) n).filter(b -> b.getText().equals("F8 Paused")).findFirst().get().fire(); return null; });
        assertEquals(WatchMode.PAUSED, parts.watcher.mode(), "the legend item is a button that does the same thing");
        onFx(() -> { w.show(w.freeTab); w.sync(); return null; });
        var free = onFx(() -> w.legend.getChildren().stream().map(n -> ((javafx.scene.control.Button) n).getText()).toList());
        assertFalse(free.contains("F7 Locked"), "the legend follows the tab in front: " + free);
        assertTrue(free.contains("F5 Read now"));
        onFx(() -> { javafx.event.Event.fireEvent(w.stage.getScene(), press(javafx.scene.input.KeyCode.F7)); return null; });
        assertEquals(WatchMode.PAUSED, parts.watcher.mode(), "F7 does nothing on the free tab");
        onFx(() -> { w.stage.hide(); return null; });
    }

    @Test void theWindowComesBackOnTheTabAndAtTheSizeItWasLeft(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        var parts = parts(dir, bridge);
        var memory = dir.resolve("window.json");
        var first = onFx(() -> new MainWindow(parts, bridge, "#d97757", () -> "", pane(), new TrayItems(parts, bridge, () -> { }), memory));
        onFx(() -> { first.stage.setWidth(1010); first.stage.setHeight(710); first.show(first.lanesTab); return null; });
        onFx(() -> { first.stage.hide(); return null; });
        assertTrue(java.nio.file.Files.exists(memory), "hiding the window writes it down");
        var second = onFx(() -> new MainWindow(parts, bridge, "#d97757", () -> "", pane(), new TrayItems(parts, bridge, () -> { }), memory));
        assertTrue(onFx(() -> second.lanesTab.isSelected()), "the last tab");
        assertEquals(1010, onFx(() -> second.stage.getWidth()), 2);
        assertEquals(710, onFx(() -> second.stage.getHeight()), 2);
    }

    @Test void theFreeTabSaysHowManyAccountsHaveRoom(@TempDir Path dir) throws Exception {
        var a = new CCAPICredential().id("A").email("a@example.org").tier(0).key("k").orgId("o");
        var b = new CCAPICredential().id("B").email("b@example.org").tier(0).key("k").orgId("o");
        var gauges = new FreeGauges(() -> java.util.List.of(a, b), (c, m) -> null, dir.resolve("log"));
        var later = Instant.now().plusSeconds(3600).getEpochSecond();
        gauges.seed("A", 0.20, 0.10, later, Instant.now());
        gauges.seed("B", 1.00, 0.10, later, Instant.now());
        var bridge = new WatchBridge();
        var w = window(parts(dir, bridge), bridge, new FreePane(gauges, LaneSettings::new));
        onFx(() -> { w.free.active = true; w.free.redraw(); return null; });
        assertEquals("1 of 2 free accounts have room", onFx(() -> w.free.room.getText()));
    }

    @Test void aTabIsFoundByTheNameTheCommandLineGivesIt(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        var w = window(parts(dir, bridge), bridge, pane());
        assertSame(w.paidTab, w.tabNamed("paid"));
        assertSame(w.freeTab, w.tabNamed("FREE"));
        assertSame(w.lanesTab, w.tabNamed("lanes"));
        assertSame(w.clientsTab, w.tabNamed("clients"));
        assertSame(w.serversTab, w.tabNamed("servers"));
        assertSame(w.manageTab, w.tabNamed("manage"));
        assertSame(w.paidTab, w.tabNamed(null), "no name: the first tab");
        assertNull(w.tabNamed("nosuch"));
    }

    @Test void aTabClickedWhileTheWindowShowsStartsAtOnceWithNoHelp(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        var w = window(parts(dir, bridge), bridge, pane());
        onFx(() -> { w.show(); return null; });
        onFx(() -> null);   // the window shows first, the tab in front is started right after
        assertTrue(onFx(() -> w.paid.active));
        for (var tab : java.util.List.of(w.freeTab, w.lanesTab, w.serversTab, w.manageTab)) {
            onFx(() -> { w.tabs.getSelectionModel().select(tab); return null; });   // what a click does: no sync() called here
            var page = w.pages.get(tab);
            assertTrue(onFx(() -> activeOf(page)), tab.getText() + " must be running once it is in front");
            assertFalse(onFx(() -> w.paid.active), "and the one that went back stops");
        }
        onFx(() -> { w.stage.hide(); return null; });
    }

    static boolean activeOf(Page p) {
        return switch (p) {
            case FreePane f -> f.active;
            case LanesPane l -> l.active;
            case ServersPane s -> s.active;
            case AccountsPane a -> a.active;
            default -> false;
        };
    }

    @Test void aFreshWindowOpensWhollyOnTheUsableScreen(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        var w = window(parts(dir, bridge), bridge, pane());
        onFx(() -> { w.show(); return null; });
        var usable = onFx(() -> javafx.stage.Screen.getPrimary().getVisualBounds());
        var x = onFx(() -> w.stage.getX()); var y = onFx(() -> w.stage.getY());
        var width = onFx(() -> w.stage.getWidth()); var height = onFx(() -> w.stage.getHeight());
        assertFalse(Double.isNaN(x) || Double.isNaN(y), "the window has a place of its own, not the system's");
        assertTrue(x >= usable.getMinX() - 1 && x + width <= usable.getMaxX() + 1, "inside across: " + x + " + " + width + " in " + usable);
        assertTrue(y >= usable.getMinY() - 1 && y + height <= usable.getMaxY() + 1, "inside down: " + y + " + " + height + " in " + usable);
        onFx(() -> { w.stage.hide(); return null; });
    }

    @Test void theTextThatMustShowInFullKeepsItsFullHeight(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        var w = window(parts(dir, bridge), bridge, pane());
        assertEquals(javafx.scene.layout.Region.USE_PREF_SIZE, onFx(() -> w.lanes.state.getMinHeight()), "the lane state is several lines and a label shrinks to one when space is short");
        assertEquals(javafx.scene.layout.Region.USE_PREF_SIZE, onFx(() -> w.free.summary.getMinHeight()));
    }

    @Test void aFreeAccountIsNotMarkedOffForAFlagThatPlaysNoPartForIt() {
        var free = new CCAPICredential().id("ALF").tier(0).active(false);
        var paid = new CCAPICredential().id("OLD").tier(5).active(false);
        assertFalse(AccountsPane.row(free).contains("(off)"), AccountsPane.row(free));
        assertTrue(AccountsPane.row(free).contains("free"));
        assertTrue(AccountsPane.row(paid).contains("(off)"));
    }

    @Test void theLanesSubTabIsFoundByName(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        var w = window(parts(dir, bridge), bridge, pane());
        assertSame(w.lanes.requestsTab, w.lanes.subNamed("requests"));
        assertSame(w.lanes.settingsTab, w.lanes.subNamed("Settings"));
        assertSame(w.lanes.callsTab, w.lanes.subNamed("calls"));
        assertNull(w.lanes.subNamed("nosuch"));
    }

    @Test void theSettingsFormScrollsSoItsLastRowsAndTheApplyButtonAreAlwaysReachable(@TempDir Path dir) throws Exception {
        var bridge = new WatchBridge();
        var w = window(parts(dir, bridge), bridge, pane());
        var content = onFx(() -> w.lanes.settingsTab.getContent());
        assertTrue(content instanceof javafx.scene.control.ScrollPane, "the form is taller than a small window: " + content);
        assertSame(w.lanes.settings, onFx(() -> ((javafx.scene.control.ScrollPane) content).getContent()));
    }
}
