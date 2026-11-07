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
    /** What each free account has left, from the ledger and corrected by every gauge reading (PRP 78). */
    final FreeEstimator estimator;
    /** Where every served call is recorded; null: nothing is kept (tests that do not look at it). */
    final FreeLedger ledger;
    /** While true a new request is turned away with a 503: the restart script waits for the running ones to end ({@link #drainVerdict}). Lost with the process. */
    volatile boolean hold;
    final Map<Long, Call> open = new ConcurrentHashMap<>();
    final AtomicLong callSeq = new AtomicLong();

    /** The header a request carries to the proxy to say where a new conversation should go (the account with the most predicted room), and the one the proxy answers with to say where it went. */
    static final String PLACE_HEADER = "x-ccacc-place", ACCOUNT_HEADER = "x-ccacc-account", PROMPT_HEADER = "x-ccacc-prompt-chars";

    FreeLane(FreeGauges gauges, Supplier<List<CCAPICredential>> accounts, LaneSettings settings) { this(gauges, accounts, settings, null); }

    /** The estimator is rebuilt from the ledger (the last eight days: the week window is the longest that matters) and then listens to every reading of the gauge table. */
    FreeLane(FreeGauges gauges, Supplier<List<CCAPICredential>> accounts, LaneSettings settings, FreeLedger ledger) {
        this.gauges = gauges; this.accounts = accounts; this.settings = settings; this.ledger = ledger;
        this.estimator = ledger == null ? new FreeEstimator(settings) : FreeEstimator.fromLedger(ledger, settings, Instant.now());
        gauges.onReading = (row, source) -> estimator.observe(row, source).ifPresent(this::keep);
    }

    private void keep(FreeLedger.Correction c) { if (ledger != null) ledger.append(c); }

    /** Brings in the gauge table's readings the estimator has not seen (a reading put into the table without passing the listener). */
    void sync() { gauges.snapshot().forEach(r -> estimator.observe(r, "table").ifPresent(this::keep)); }

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
        Call call = null;
        try {
            var post = ex.getRequestMethod().equals("POST");
            if (post && hold) {
                var b = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"fclaude: the free lane is restarting, try again in a minute.\"}}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("content-type", "application/json");
                ex.getResponseHeaders().set("retry-after", "30");
                ex.sendResponseHeaders(503, b.length);
                ex.getResponseBody().write(b);
                return Result.done(503);
            }
            var refusal = post ? guard(body) : null;
            if (refusal != null) {
                var b = refusal.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("content-type", "application/json");
                ex.sendResponseHeaders(400, b.length);
                ex.getResponseBody().write(b);
                return Result.done(400);
            }
            if (post && ex.getRequestURI().getPath().endsWith("/v1/messages")) call = begin(body);
            var uri = ex.getRequestURI();
            var target = URI.create("http://127.0.0.1:" + port + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
            var req = HttpRequest.newBuilder(target).method(ex.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
            ex.getRequestHeaders().forEach((k, vs) -> { if (!SKIP.contains(k.toLowerCase()) && !k.equalsIgnoreCase(PLACE_HEADER)) vs.forEach(v -> req.header(k, v)); });
            if (call != null && call.place != null) req.header(PLACE_HEADER, call.place);
            HttpResponse<InputStream> res;
            try {
                res = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                var b = ("the free lane did not answer: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
                if (call != null) call.status = 502;
                ex.sendResponseHeaders(502, b.length);
                ex.getResponseBody().write(b);
                return Result.done(502);
            }
            var status = res.statusCode();
            if (call != null) call.reached(res.headers().firstValue(ACCOUNT_HEADER).orElse(null), status, res.headers().firstValue(PROMPT_HEADER).orElse(null));
            res.headers().map().forEach((k, vs) -> { if (!SKIP.contains(k.toLowerCase()) && !k.equalsIgnoreCase(ACCOUNT_HEADER) && !k.equalsIgnoreCase(PROMPT_HEADER)) vs.forEach(v -> ex.getResponseHeaders().add(k, v)); });
            ex.sendResponseHeaders(status, 0);
            try (var in = res.body(); var out = ex.getResponseBody()) {
                var buf = new byte[8192];
                for (var n = in.read(buf); n >= 0; n = in.read(buf)) { out.write(buf, 0, n); out.flush(); }
            }
            if (status < 400) remember(body, call == null ? null : call.account);
            return Result.done(status);
        } finally {
            if (call != null) end(call);
            inFlight.decrementAndGet();
        }
    }

    // ---- the calls: what is in flight, and what each one cost (the ledger)

    /** One call to the lane: its size and cache state before it starts, the account it ended on, and the gauge of that account before and after. */
    final class Call {
        final long id = callSeq.incrementAndGet();
        final Instant start = Instant.now();
        final long nanos = System.nanoTime();
        final String model, session, cache;
        final long chars;
        final double predicted;
        String place;
        volatile String account;
        volatile int status;
        volatile Long promptChars;
        FreeEstimator.Token token;
        Double before5, before7;
        Long reset5Before;

        Call(String model, String session, String cache, long chars, double predicted) { this.model = model; this.session = session; this.cache = cache; this.chars = chars; this.predicted = predicted; }

        /** The proxy answered: which account it used (its response header) and with what status. The account's gauge as the table has it now is the "before" reading. */
        void reached(String served, int status, String prompt) {
            this.status = status;
            try { if (prompt != null) promptChars = Long.valueOf(prompt.trim()); } catch (NumberFormatException e) { /* not told */ }
            if (served == null) return;
            if (!served.equals(account)) expect(served);   // somewhere else than expected: held back and read there now
        }

        /** This call is expected on this account (where it was placed, or where its chat is): its predicted cost is held back there, and the account's gauge as the table has it now is the "before" reading. */
        void expect(String id) {
            estimator.release(token);
            token = estimator.begin(id, predicted);
            account = id;
            var row = gauges.rows.get(id);
            before5 = before7 = null;
            reset5Before = null;
            if (row != null && row.ok()) { before5 = row.five(); before7 = row.seven(); reset5Before = row.resetsAt(); }
        }
    }

    /** A call is starting: its size, whether it continues a chat the lane holds, and, for a new conversation, the account with the most predicted room (named to the proxy, and held back from the estimator until the call ends). */
    Call begin(byte[] body) {
        sync();
        String model = null, key = null;
        try {
            var j = JSON.readTree(body);
            if (!j.has("messages")) return null;
            model = j.path("model").asText();
            key = sessionKey(body);
        } catch (Exception e) {
            return null;
        }
        var last = key == null ? null : started.get(key);
        var chars = last == null ? body.length : Math.max(0, body.length - last);   // a chat the lane holds is sent only what is new
        var cache = last == null ? "new" : "continued";
        var call = new Call(model, key == null ? "-" : key.substring(0, 8), cache, chars, estimator.predict(chars, cache, model));
        if (last == null) {
            var placed = estimator.place(chosen(), estimator.predictStart(chars, model), Instant.now());
            if (placed != null) { call.place = placed.account(); call.expect(placed.account()); }
        } else {
            var where = sessionAccount.get(key);   // a chat the lane holds is on the account that served its last call
            if (where != null) call.expect(where);
        }
        open.put(call.id, call);
        return call;
    }

    /** The call is over: it goes into the ledger with the gauge before and after, and the estimator learns from it. */
    void end(Call c) {
        open.remove(c.id);
        try {
            Double after5 = null, after7 = null;
            Long reset5After = null;
            var row = c.account == null ? null : gauges.rows.get(c.account);
            if (c.status < 400 && row != null && row.ok() && row.read().isAfter(c.start)) { after5 = row.five(); after7 = row.seven(); reset5After = row.resetsAt(); }   // the reply carried a gauge (or a probe read one meanwhile)
            var observed = FreeEstimator.observed(c.before5, after5, c.reset5Before, reset5After);
            var entry = new FreeLedger.Call(Instant.now(), c.account, c.model, c.chars, FreeEstimator.tokens(c.chars), c.cache, c.status, (System.nanoTime() - c.nanos) / 1_000_000,
                    c.before5, c.before7, c.reset5Before, after5, after7, reset5After, c.predicted, observed, c.token == null || c.token.alone(), c.session, c.promptChars);
            estimator.finish(c.token, entry);
            if (ledger != null) ledger.append(entry);
        } catch (RuntimeException e) {
            estimator.release(c.token);
            System.err.println("ccacc: free ledger: " + e);
        }
    }

    /** A call that is running now, for {@code ccacc lanes open} and the restart script. */
    record OpenCall(long id, Instant start, String account, String model, long chars, String cache) {}

    List<OpenCall> openCalls() {
        return open.values().stream().map(c -> new OpenCall(c.id, c.start, c.account, c.model, c.chars, c.cache)).sorted(Comparator.comparing(OpenCall::start)).toList();
    }

    void hold(boolean on) { hold = on; }

    /**
     * Whether the lane may be restarted: "idle" (nothing running), "busy N, oldest S s" (wait), or "stuck N, oldest S s" (every call still running has been running over {@code stuck}:
     * the restart may go, and says which calls it drops). A call stuck over the limit never holds the restart while a younger one is running.
     */
    static String drainVerdict(List<OpenCall> open, Instant now, Duration stuck) {
        if (open.isEmpty()) return "idle";
        var young = open.stream().filter(o -> Duration.between(o.start(), now).compareTo(stuck) <= 0).toList();
        var list = young.isEmpty() ? open : young;
        var oldest = list.stream().mapToLong(o -> Duration.between(o.start(), now).toSeconds()).max().orElse(0);
        return (young.isEmpty() ? "stuck " : "busy ") + list.size() + ", oldest " + oldest + " s" + (young.isEmpty() ? " (over " + stuck.toMinutes() + " min each: these would be dropped)" : "");
    }

    // ---- the guard against starting a conversation the free lane cannot carry

    /** Sessions this lane has carried (a hash of model, system prompt and first message), each with the size of its last request: a request of one of them continues a chat the proxy holds, whatever its size, and costs what has grown. Lost with the lane, as the proxy's chats are. */
    final Map<String, Integer> started = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> e) { return size() > 500; }
    });

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

    void remember(byte[] body) { remember(body, null); }

    void remember(byte[] body, String account) {
        var k = sessionKey(body);
        if (k == null) return;
        started.put(k, body.length);
        if (account != null) sessionAccount.put(k, account);
    }

    /** The account that served each session's last call: where its chat is. */
    final Map<String, String> sessionAccount = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 500; }
    });

    /** The predicted five-hour room, in points, of the free account with the most of it among {@link #chosen()} now: the ledger's estimate, corrected by every reading, minus the calls in flight; a window that has reset counts as empty (100 left), an account never read as unknown (100); no accounts at all: -1. */
    double bestRoomPoints() {
        sync();
        return estimator.bestRoom(chosen(), Instant.now());   // the accounts the lane could use NOW, not the ones it started with (added, reactivated or newly eligible ones count)
    }

    /**
     * A refusal (the API's error JSON) when this request would START a conversation no free account can carry: the estimator's allocation ({@link FreeEstimator#place}) found
     * none whose predicted room, with the ledger's spending and the calls in flight counted, survives the first call with the margin (at the cost of about 2.2 points of a
     * five-hour window per 10,000 characters until the ledger has taught a better rate; one such call, 475,719 characters, took an account from 61% to 142% on 2026-11-06).
     * A conversation this lane already carries is never refused (its chat is cached, a call sends only what is new), and a small request never is. null: send it.
     */
    String guard(byte[] body) {
        if (body.length < settings.guardMinChars()) return null;
        var key = sessionKey(body);
        if (key == null || started.containsKey(key)) return null;
        sync();
        var ids = chosen();
        if (ids.isEmpty()) return null;
        var now = Instant.now();
        var model = modelOf(body);
        var need = estimator.predictStart(body.length, model);   // with the room to summarise it afterwards
        if (estimator.place(ids, need, now) != null) return null;
        var room = estimator.bestRoom(ids, now);
        var tokens = estimator.claudeCodeTokens(body.length);
        var text = tokens > settings.contextLimitTokens() || need > 100 - settings.guardMarginPoints()
                ? beyondAnyAccount(body.length, tokens, need, model)
                : waitForRoom(body.length, tokens, need, room, ids, now);
        return JSON.createObjectNode().put("type", "error").set("error", JSON.createObjectNode().put("type", "invalid_request_error").put("message", text)).toString();
    }

    static String modelOf(byte[] body) {
        try { return JSON.readTree(body).path("model").asText(); } catch (Exception e) { return FreeEstimator.SONNET; }
    }

    /** No free account could take this first call even when completely unloaded: say the limit, and that a compaction (or Haiku, when it would fit) is the way. */
    private String beyondAnyAccount(long chars, long tokens, double need, String model) {
        var limit = estimator.ceilingChars();
        var haikuNeed = estimator.predictStart(chars, "claude-haiku-5-5");
        var haiku = FreeEstimator.family(model).equals("sonnet") && tokens <= settings.contextLimitTokens() && haikuNeed <= 100 - settings.guardMarginPoints() ? " It would fit on Haiku: start fclaude with --model haiku." : "";
        return String.format("fclaude: this conversation is too long to start on the free lane, on any free account: about %,d characters (~%,d tokens), roughly %d points of the 100 a completely unloaded account has, counting the room to summarise it afterwards. "
                + "The most the free lane can safely start is about %,d characters (~%,d tokens) on Sonnet 5.5, and the server itself refuses a request over %,d tokens. Compact it first in claude or pclaude (/compact) and continue here, or start a new session in fclaude.%s",
                chars, tokens, Math.round(need), Math.round(limit), estimator.claudeCodeTokens(limit), Math.round(settings.contextLimitTokens()), haiku);
    }

    /** It fits an unloaded account but none has the room now: say which one gets it back first, and when. */
    private String waitForRoom(long chars, long tokens, double need, double room, List<String> ids, Instant now) {
        var back = estimator.nextReset(ids, now).map(r -> "room comes back with the next window reset, in about " + FreeGauges.untilReset(r.at().getEpochSecond(), now) + ", on " + r.account()).orElse("no window reset time is known yet");
        return String.format("fclaude: this conversation is too long to start on the free lane now: about %,d characters (~%,d tokens), roughly %d points of a five-hour window, and the ledger predicts the free account with the most room has about %d points left (%s). "
                + "Wait for that, or start a new session in fclaude. Compact it first in claude or pclaude (/compact) to continue this one here.", chars, tokens, Math.round(need), Math.round(room), back);
    }

    /** The free lane's account lines for the status command: room now and predicted (the ledger's estimate), the last gauge read, the week, the reset, the error of the last predictions. */
    List<String> roomLines() {
        sync();
        var now = Instant.now();
        var byId = new HashMap<String, FreeGauges.Row>();
        gauges.snapshot().forEach(r -> byId.put(r.id(), r));
        return estimator.rooms(chosen(), now).stream().map(r -> {
            var row = byId.get(r.id());
            return "  " + estimator.line(r, now) + (row == null ? "" : "; gauge read " + FreeGauges.percent(row.five()).strip() + ", " + FreeGauges.ago(row.read(), now));
        }).toList();
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
