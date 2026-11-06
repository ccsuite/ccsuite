package ccsuite.localserver.lanes;

import ccsuite.accounts.AccountStore;
import ccsuite.accounts.ClaudeLiveStore;
import ccsuite.localserver.server.AccountRouter;
import ccsuite.localserver.server.PaidLane;
import ccsuite.oauthclient.OAuthUsageClient;
import ccsuite.oauthswap.SwapService;
import ccsuite.oauthswap.usage.UsageService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Supplier;

/**
 * The two ports (pclaude: paid, fclaude: free) as something a host can switch on and off, like {@code LocalServerService} is for the classic proxy: one
 * {@link AccountRouter} and one {@link PaidLane}, an optional free lane supplied by the host, and one {@link LaneFront} per port. A port that cannot start
 * (usually its port is taken) is listed in {@link #problems()} and does not stop the other. The usage service is the host's own, so the router's polls share
 * one per-account cooldown with the rest of the process.
 */
public final class LaneService {

    static final int KEEP = 100;

    final SwapService swap;
    final AccountStore store;
    final ClaudeLiveStore live;
    final UsageService usage;
    final OAuthUsageClient oauth;

    AccountRouter router;
    PaidLane paid;
    Lane free;
    final Map<String, LaneFront> fronts = new LinkedHashMap<>();
    final Map<String, String> problems = new LinkedHashMap<>();
    final ConcurrentLinkedDeque<Routed> recent = new ConcurrentLinkedDeque<>();
    /** Told after every request (the window redraws); set by the host. */
    public volatile Runnable onRouted = () -> { };
    /** The one paid account the paid lane may use (null: the usual ranking); read on every request. Set by the host before {@link #start}. */
    public volatile Supplier<String> paidPin = () -> null;
    /** Where the calls the paid lane served are appended as JSON lines (account, model, job, status, ms), so a run can be read back; null: not written. Set by the host. */
    public volatile Path callLog;

    public LaneService(SwapService swap, AccountStore store, ClaudeLiveStore live, UsageService usage, OAuthUsageClient oauth) {
        this.swap = swap; this.store = store; this.live = live; this.usage = usage; this.oauth = oauth;
    }

    /** Starts every port in {@code ports}; does nothing when already running. {@code free} may be null (the free port then answers 503). */
    public synchronized void start(List<PortSpec> ports, Lane free, int refreshSec) {
        if (router != null) return;
        router = new AccountRouter(swap, store, live, usage, oauth);
        paid = new PaidLane(router).pin(() -> paidPin.get());
        this.free = free;
        problems.clear();
        for (var p : ports) {
            var paidPort = p.kind() == LaneKind.PAID;
            var front = new LaneFront(p.name(), p.port(), paidPort ? paid : free == null ? new Unavailable() : free, !paidPort, this::routed);
            try {
                front.start();
                fronts.put(p.name(), front);
            } catch (IOException e) {
                problems.put(p.name(), p.port() + ": " + e.getMessage());
                System.err.println("ccacc: " + p.name() + " did not start on port " + p.port() + ": " + e);
            }
        }
        router.start(refreshSec);
    }

    public synchronized void stop() {
        fronts.values().forEach(LaneFront::stop);
        fronts.clear();
        if (router != null) router.stop();
        router = null; paid = null; free = null;
    }

    public synchronized boolean running() { return router != null; }

    public synchronized List<LaneFront> fronts() { return List.copyOf(fronts.values()); }

    public synchronized Map<String, String> problems() { return Map.copyOf(problems); }

    /** The newest requests first. */
    public List<Routed> recent() { return new ArrayList<>(recent); }

    public synchronized Lane paid() { return paid; }

    public synchronized Lane free() { return free; }

    static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** A call the paid lane served, as one JSON line in the shape of the free lane's call log (mode "paid"), so `ccacc calls` reads both. */
    void appendCall(Routed r) {
        var file = callLog;
        if (file == null || !"paid".equals(r.lane())) return;
        var line = JSON.createObjectNode().put("ts", r.at().toString()).put("job", r.job()).put("account", r.account()).put("model", r.model()).put("mode", "paid")
                .put("port", r.port()).put("status", r.status()).put("ms", r.millis()).put("ok", r.status() < 400);
        try {
            if (file.getParent() != null) java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, line + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("ccacc: paid call not logged: " + e.getMessage());
        }
    }

    void routed(Routed r) {
        appendCall(r);
        recent.addFirst(r);
        while (recent.size() > KEEP) recent.pollLast();
        onRouted.run();
    }

    /** The free lane when the host has none: the port answers 503 instead of not existing. */
    static final class Unavailable implements Lane {
        @Override public String name() { return "free"; }
        @Override public Result send(com.sun.net.httpserver.HttpExchange ex, byte[] body) { return Result.done(503); }
        @Override public boolean available() { return false; }
        @Override public boolean hasHeadroom() { return false; }
    }
}
