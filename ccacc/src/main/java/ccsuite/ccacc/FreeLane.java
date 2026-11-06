package ccsuite.ccacc;

import module java.base;
import module java.net.http;
import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.UsageData;
import ccsuite.localserver.lanes.Lane;
import ccsuite.localserver.lanes.Result;
import ccsuite.webclient.FreeGauge;
import ccsuite.webproxy.CCAPIProxy;
import ccsuite.webproxy.health.UsageFetcher;
import com.sun.net.httpserver.HttpExchange;

/**
 * The FREE lane: the webproxy code (the private proxy that turns a Messages request into a claude.ai web chat, tools and all) running inside this
 * process on a loopback port of its own, with this class as the way in. The proxy picks the account (gauge-aware, with failover and a slot cap per
 * account, so requests of several sessions or sub-agents spread over the free accounts); this class adds what the routing needs to know: whether the
 * pool has room (from {@link FreeGauges}), and what is in flight. The gauge table is also the proxy's usage
 * source ({@link UsageFetcher#freeSource}), so a probe is sent once and read by both.
 */
final class FreeLane implements Lane {

    static final Set<String> SKIP = Set.of("host", "content-length", "connection", "keep-alive", "transfer-encoding", "accept-encoding", "expect", "upgrade", "authorization", "x-api-key");

    final FreeGauges gauges;
    final Supplier<List<CCAPICredential>> accounts;
    final LaneSettings settings;
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
    final AtomicInteger inFlight = new AtomicInteger();
    volatile List<String> ids = List.of();
    volatile int port;
    volatile String problem;
    /** True while the proxy inside is running (tests set it to try the lane against a fake upstream). */
    volatile boolean up;
    CCAPIProxy proxy;

    FreeLane(FreeGauges gauges, Supplier<List<CCAPICredential>> accounts, LaneSettings settings) {
        this.gauges = gauges; this.accounts = accounts; this.settings = settings;
    }

    /** The free accounts the lane may use: the ones named in the settings, else every active free account that is not monitor-only. */
    List<String> chosen() {
        var free = FreeGauges.free(accounts.get());
        var named = settings.freeAccounts();
        return free.stream().filter(c -> named.isEmpty() ? !c.watchOnly() : named.contains(c.id())).map(CCAPICredential::id).toList();
    }

    /** Starts the proxy inside this process; says why in {@link #problem} when it cannot (no free account, or the proxy refused). */
    synchronized void start() {
        if (proxy != null) return;
        ids = chosen();
        if (ids.isEmpty()) { problem = "no free account to serve from (tier 0, with a web session key, active and not monitor-only)"; return; }
        try {
            System.setProperty("ccapi.chatReuse", settings.chatReuseMode());
            if (System.getProperty("ccapi.perAccountConcurrency") == null) System.setProperty("ccapi.perAccountConcurrency", String.valueOf(settings.perAccountSlots()));
            try (var s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = s.getLocalPort(); }
            UsageFetcher.freeSource = this::usageOf;
            ccsuite.webproxy.CCAPIProxy.gaugeListener = (id, g) -> gauges.observe(id, g.fiveHour(), g.sevenDay(), g.fiveResetsAt());   // the gauge of a served call is a reading: the table moves with the traffic
            proxy = new CCAPIProxy(port, FreeGauges.free(accounts.get()).stream().filter(c -> ids.contains(c.id())).toList());
            proxy.start();
            up = true;
            problem = null;
        } catch (Exception e) {
            problem = "the free lane did not start: " + e.getMessage();
            System.err.println("ccacc: " + problem);
            UsageFetcher.freeSource = null;
            ccsuite.webproxy.CCAPIProxy.gaugeListener = null;
            proxy = null;
        }
    }

    synchronized void stop() {
        if (proxy != null) proxy.stop();
        proxy = null;
        up = false;
        if (UsageFetcher.freeSource != null) UsageFetcher.freeSource = null;
        ccsuite.webproxy.CCAPIProxy.gaugeListener = null;
    }

    // ---- Lane

    @Override public String name() { return "free"; }

    @Override public boolean available() { return up; }

    @Override public boolean hasHeadroom() {
        return FreeHeadroom.any(gauges.snapshot(), ids, settings.freeFiveLimit(), settings.freeWeekLimit(), Instant.now());
    }

    int inFlight() { return inFlight.get(); }

    /** Forwards to the proxy inside and streams its answer back as it is (a refusal of the web, a 429 or a 5xx too: there is no other lane to try). */
    @Override public Result send(HttpExchange ex, byte[] body) throws Exception {
        inFlight.incrementAndGet();
        try {
            var refusal = ex.getRequestMethod().equals("POST") ? guard(body) : null;
            if (refusal != null) {
                var b = refusal.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("content-type", "application/json");
                ex.sendResponseHeaders(400, b.length);
                ex.getResponseBody().write(b);
                return Result.done(400);
            }
            var uri = ex.getRequestURI();
            var target = URI.create("http://127.0.0.1:" + port + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
            var req = HttpRequest.newBuilder(target).method(ex.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
            ex.getRequestHeaders().forEach((k, vs) -> { if (!SKIP.contains(k.toLowerCase())) vs.forEach(v -> req.header(k, v)); });
            HttpResponse<InputStream> res;
            try {
                res = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                var b = ("the free lane did not answer: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(502, b.length);
                ex.getResponseBody().write(b);
                return Result.done(502);
            }
            var status = res.statusCode();
            res.headers().map().forEach((k, vs) -> { if (!SKIP.contains(k.toLowerCase())) vs.forEach(v -> ex.getResponseHeaders().add(k, v)); });
            ex.sendResponseHeaders(status, 0);
            try (var in = res.body(); var out = ex.getResponseBody()) {
                var buf = new byte[8192];
                for (var n = in.read(buf); n >= 0; n = in.read(buf)) { out.write(buf, 0, n); out.flush(); }
            }
            if (status < 400) remember(body);
            return Result.done(status);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    // ---- the guard against starting a conversation the free lane cannot carry

    /** Sessions this lane has carried (a hash of model, system prompt and first message): a request of one of them continues a chat the proxy holds, whatever its size. Lost with the lane, as the proxy's chats are. */
    final Set<String> started = Collections.newSetFromMap(Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 500; }
    }));

    static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** The session of a request: what the proxy also recognises it by (model, system prompt, first message), as a hash; null when the body is not a Messages request. */
    static String sessionKey(byte[] body) {
        try {
            var j = JSON.readTree(body);
            if (!j.has("messages")) return null;
            var raw = j.path("model").asText() + "|" + j.path("system") + "|" + j.path("messages").path(0);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    void remember(byte[] body) {
        var k = sessionKey(body);
        if (k != null) started.add(k);
    }

    /** The five-hour room, in points, of the free account with the most of it; a window that has reset counts as empty (100 left), an account never read as unknown (100); no accounts at all: -1. */
    double bestRoomPoints() {
        var byId = new HashMap<String, FreeGauges.Row>();
        gauges.snapshot().forEach(r -> byId.put(r.id(), r));
        var now = Instant.now().getEpochSecond();
        return ids.stream().mapToDouble(id -> {
            var r = byId.get(id);
            if (r == null || r.five() == null) return 100;
            return r.resetsAt() != null && r.resetsAt() <= now ? 100 : Math.max(0, (1 - r.five()) * 100);
        }).max().orElse(-1);
    }

    /**
     * A refusal (the API's error JSON) when this request would START a conversation the free lane cannot carry: one whose first call is so large that, at the measured
     * cost of about 2.2 points of a five-hour window per 10,000 characters, the free account with the most room could not take it (one such call, 475,719 characters,
     * took an account from 61% to 142% on 2026-11-06). A conversation this lane already carries is never refused (its chat is cached, a call sends only what is new),
     * and a small request never is. null: send it.
     */
    String guard(byte[] body) {
        if (body.length < settings.guardMinChars()) return null;
        var key = sessionKey(body);
        if (key == null || started.contains(key)) return null;
        var room = bestRoomPoints();
        if (room < 0) return null;
        var need = body.length / 10_000.0 * settings.pointsPer10kChars();
        if (need <= room - settings.guardMarginPoints()) return null;
        var text = String.format("fclaude: this conversation is too long to start on the free lane now: about %,d characters, roughly %d points of a five-hour window, and the free account with the most room has about %d points left. "
                + "Compact it first in claude or pclaude (/compact) and continue here, or start a new session in fclaude.", body.length, Math.round(need), Math.round(room));
        return JSON.createObjectNode().put("type", "error").set("error", JSON.createObjectNode().put("type", "invalid_request_error").put("message", text)).toString();
    }

    // ---- the gauge table as the proxy's usage source

    /**
     * The usage of a free account for the proxy's selector: the last reading in the table, whatever its age, and NEVER a probe. The selector runs while a request holds
     * the proxy's one lock, so a network call here (a probe takes seconds, 24 s when claude.ai is slow to answer) would stop every other request behind it, for each of
     * twelve stale accounts in turn: that is what made a session hang for minutes (2026-11-06). The table is kept fresh by the background reader ({@link FreeGauges#start})
     * and by the gauge of every served call ({@link FreeGauges#observe}). An account never read gives null (unknown), which the selector copes with.
     */
    UsageData usageOf(CCAPICredential c) {
        var row = gauges.rows.get(c.id());
        if (row == null || (row.five() == null && row.seven() == null)) return null;
        return UsageFetcher.toUsage(new FreeGauge.Reading(row.five(), row.seven(), row.status(), row.resetsAt(), row.model(), 0, 0));
    }
}
