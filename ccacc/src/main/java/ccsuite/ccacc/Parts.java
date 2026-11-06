package ccsuite.ccacc;

import ccsuite.localserver.config.SettingsManager;
import ccsuite.ccgate.GateService;
import ccsuite.localserver.server.LocalServerService;
import ccsuite.oauthswap.watch.*;
import ccsuite.usagetracker.ui.UsageWidget;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * The three parts of the app and their switches: the account watcher (cswap), the usage widget with its
 * taskbar card (ccu) and the local proxy (ccls). A part is on or off; what is on is remembered in the
 * settings. Everything here runs on the JavaFX thread except the watcher's own.
 */
final class Parts {

    final Core core;
    final CcaccSettingsStore store;
    final CcaccSettings settings;
    final WatchBridge bridge;
    final LocalServerService server;
    final GateService gate;
    UsageWatcher watcher;
    UsageWidget widget;
    ccsuite.ccrelay.RelayAgent relay;
    /** The ports with modes over the paid and the free lane; set by the app once the gauges exist, null in tests. */
    LaneHost lanes;
    String serverProblem, gateProblem, relayProblem;

    Parts(Core core, CcaccSettingsStore store, CcaccSettings settings, WatchBridge bridge, LocalServerService server, GateService gate) {
        this.core = core; this.store = store; this.settings = settings; this.bridge = bridge; this.server = server; this.gate = gate;
        bridge.activeChanged = id -> { var w = widget; if (w != null) w.activeChanged(id); };
    }

    // ---- watcher

    void startWatch() {
        if (watcher != null) return;
        var cfg = WatchConfig.defaults();
        watcher = new UsageWatcher(core.usage, core.swap, new SwitchPlanner(cfg.depletedAtPct(), cfg.minTargetHeadroom()),
                BalancePlanner.of(cfg), cfg, bridge);
        watcher.mode(settings.watchMode());
        watcher.start();
    }

    void stopWatch() {
        if (watcher == null) return;
        watcher.stop();
        watcher = null;
    }

    void watch(boolean on) {
        settings.watch(on);
        store.save(settings);
        if (on) startWatch(); else stopWatch();
    }

    void mode(WatchMode m) {
        settings.watchMode(m);
        store.save(settings);
        if (watcher != null) watcher.mode(m);
    }

    // ---- widget and taskbar card

    /** Who Claude Code is logged into right now; the widget puts that account first whatever the file says. */
    String liveActiveId() {
        try { return core.swap.detectActiveId(); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    void startWidget() {
        if (widget != null) return;
        widget = new UsageWidget().hostedBy(core.usageSource, this::liveActiveId, () -> TrayText.autoOrOff(watcher == null ? null : watcher.mode()));
        widget.start(new Stage());
    }

    void stopWidget() {
        if (widget == null) return;
        widget.shutdown();
        widget = null;
    }

    void widget(boolean on) {
        settings.widget(on);
        store.save(settings);
        if (on) startWidget(); else stopWidget();
    }

    // ---- local proxy

    /** Starts the proxy on the port of its own settings; says why when it cannot (usually the port is taken). */
    void startServer() {
        try {
            var s = new SettingsManager().load();
            server.start(s.port(), s.usageRefreshIntervalSecs());
            serverProblem = null;
        } catch (IOException | jakarta.xml.bind.JAXBException e) {
            serverProblem = e.getMessage();
            System.err.println("ccacc: local server did not start: " + e);
        }
    }

    void stopServer() {
        server.stop();
        serverProblem = null;
    }

    void server(boolean on) {
        settings.server(on);
        store.save(settings);
        if (on) startServer(); else stopServer();
    }

    // ---- gate

    void startGate() {
        try {
            gate.start(settings.gatePort(), GateService.RETENTION_DAYS);
            gateProblem = null;
        } catch (IOException e) {
            gateProblem = e.getMessage();
            System.err.println("ccacc: gate did not start: " + e);
        }
    }

    void stopGate() {
        gate.stop();
        gateProblem = null;
    }

    void gate(boolean on) {
        settings.gate(on);
        store.save(settings);
        if (on) startGate(); else stopGate();
    }

    // ---- relay (phase 2): the outward link that brings remote callers to the gate

    void startRelay() {
        if (relay != null) return;
        try {
            var cfg = RelayConfig.standard();
            var url = cfg.url().orElseThrow(() -> new IllegalStateException("no relay address: ccacc gate relay url <address>"));
            var secret = cfg.secret().orElseThrow(() -> new IllegalStateException("no provider secret: ccacc gate relay secret"));
            relay = new ccsuite.ccrelay.RelayAgent(java.net.URI.create(url), secret,
                    java.net.URI.create("http://127.0.0.1:" + settings.gatePort()), Math.max(1, settings.relayWorkers())).start();
            relayProblem = null;
        } catch (Exception e) {
            relayProblem = e.getMessage();
            System.err.println("ccacc: relay did not start: " + e.getMessage());
        }
    }

    void stopRelay() {
        if (relay != null) relay.close();
        relay = null;
        relayProblem = null;
    }

    void relay(boolean on) {
        settings.relay(on);
        store.save(settings);
        if (on) startRelay(); else stopRelay();
    }

    /** Starts what the settings say, minus what this run was told to leave out (--no-watch, --no-widget, --no-server). */
    void startConfigured(java.util.List<String> args) {
        if (settings.watch() && !args.contains("--no-watch")) startWatch();
        if (settings.widget() && !args.contains("--no-widget")) startWidget();
        if (settings.server() && !args.contains("--no-server")) startServer();
        if (settings.gate() && !args.contains("--no-gate")) startGate();
        if (settings.relay() && !args.contains("--no-relay")) startRelay();
        if (settings.lanes().enabled() && !args.contains("--no-lanes") && lanes != null) lanes.start();
    }

    void stopAll() {
        stopWatch();
        stopWidget();
        stopServer();
        if (lanes != null) lanes.stop();
        stopRelay();
        stopGate();
    }
}
