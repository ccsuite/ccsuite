package ccsuite.ccacc;

import module java.base;
import ccsuite.localserver.lanes.LaneService;

/** The lanes inside the app: pclaude (paid) and fclaude (free) on their own ports, switched on and off like the other parts. */
final class LaneHost {

    final CcaccSettingsStore store;
    final CcaccSettings settings;
    final FreeGauges gauges;
    final Supplier<List<ccsuite.base.model.CCAPICredential>> accounts;
    final LaneService service;
    FreeLane free;
    String problem;

    LaneHost(Core core, CcaccSettingsStore store, CcaccSettings settings, FreeGauges gauges) {
        this.store = store; this.settings = settings; this.gauges = gauges;
        this.accounts = () -> core.creds.credentials();
        this.service = new LaneService(core.swap, core.store, core.live, core.usage, core.oauth);
        this.service.paidPin = () -> settings.lanes().paidPinOrNull();
        this.service.callLog = Path.of(System.getProperty("user.home"), "ccsuite", "ccacc", "lane-calls.jsonl");
    }

    LaneSettings lanes() { return settings.lanes(); }

    boolean running() { return service.running(); }

    synchronized void start() {
        if (service.running()) return;
        var l = new FreeLane(gauges, accounts, lanes());
        l.start();
        free = l;
        service.start(lanes().specs(), l.available() ? l : null, 60);
        problem = service.problems().isEmpty() ? null : String.join("; ", service.problems().values());
    }

    synchronized void stop() {
        service.stop();
        if (free != null) free.stop();
        free = null;
        problem = null;
    }

    void enable(boolean on) {
        lanes().enabled(on);
        store.save(settings);
        if (on) start(); else stop();
    }

    /** The state as text, for the command and the window. */
    List<String> report() {
        var out = new ArrayList<String>();
        var l = lanes();
        out.add("lanes: " + (running() ? "on" : "off") + (problem == null ? "" : "   ! " + problem));
        out.add(String.format("  pclaude   127.0.0.1:%-5d the paid accounts, every model", l.pclaudePort()));
        out.add(String.format("  fclaude   127.0.0.1:%-5d the free accounts, haiku and sonnet", l.fclaudePort()));
        var f = free;
        if (f == null) out.add("free lane: not running" + (running() ? "" : " (lanes are off)"));
        else out.add("free lane: " + (f.available() ? f.ids.size() + " accounts " + f.ids + ", " + f.inFlight() + " in flight, room " + (f.hasHeadroom() ? "yes" : "no") + ", chat reuse " + l.chatReuseMode()
                : "not started: " + f.problem));
        var paid = service.paid();
        if (l.paidPinOrNull() != null) out.add("paid pin: " + l.paidPinOrNull() + "  (the paid lane uses only this account and never moves to another)");
        if (paid != null) out.add("paid lane: weekly pace " + paid.weeklyPace().stream().mapToObj(p -> Math.round(p) + "%").findFirst().orElse("unknown") + ", room " + (paid.hasHeadroom() ? "yes" : "no"));
        return out;
    }
}
