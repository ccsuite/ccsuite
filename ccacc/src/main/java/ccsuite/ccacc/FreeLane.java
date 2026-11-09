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

    /** PRP 86: the request header that carries the warn or close line to the proxy, which appends it to the prompt it builds (never to the recorded history or the session key, so chat reuse survives). */
    static final String NOTE_HEADER = "x-ccacc-note";

    /** The warn and close decisions (PRP 86); null: no note is ever added (tests, and a lane started without a decision log). */
    volatile CloseNotes notes;

    /** PRP 86 R4/R7: the folder of the per-session status files the status line reads; null: none is written (tests, and a lane started without one). */
    volatile Path statusDir;

    /** The line to add to this call, or null: from the request's whole size (what Claude Code sent), the Claude Code session id and the room of the account serving it. One ASCII line; fails open. */
    String noteFor(byte[] body, Call call) {
        var n = notes;
        var dir = statusDir;
        if (n == null && dir == null) return null;
        try {
            var session = ccsuite.webproxy.ChatSessions.sessionIdOf(JSON.readTree(body).path("metadata").path("user_id").asText(""));
            if (session.isEmpty()) return null;
            var room = call.account == null ? 100 : estimator.room(call.account, Instant.now()).predicted();
            var tokens = ccsuite.webproxy.RequestTokens.ofBody(body);
            if (dir != null) SessionStatus.write(dir, session, call.account, tokens, Zones.free(settings, room), Instant.now());   // PRP 86 R4/R7: what the status line shows; fails open
            if (n == null) return null;
            var row = call.account == null ? null : gauges.table().get(call.account);   // PRP 86 3b: the gauge of the serving account as the table has it now, written with the decision
            var line = n.note(session, call.account, tokens, room, row == null ? null : row.five(), row == null ? null : row.seven());
            return line == null ? null : line.replaceAll("[^\\x20-\\x7E]", " ");
        } catch (Exception e) {
            return null;
        }
    }

    /** PRP 87: where every request is kept (incremental jsonl); null: off. */
    volatile BodyLog bodyLog;

    /** Appends this call to the request log, after the response has been sent; fails open. */
    void logBody(byte[] body, Call call, String note) {
        var l = bodyLog;
        if (l == null || call == null) return;
        try {
            var room = call.account == null ? 100 : estimator.room(call.account, Instant.now()).predicted();
            var tokens = ccsuite.webproxy.RequestTokens.ofBody(body);
            l.record(body, call.start, call.account, tokens, Zones.free(settings, room).judge(tokens), note);
        } catch (Exception e) {
            System.err.println("ccacc: body log: " + e);   // fail open: the call is never affected
        }
    }

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

    /** The free accounts the lane may use: the ones named in the settings, else every free account that is not monitor-only (the store flag active plays no part). */
    List<String> chosen() {
        var free = FreeGauges.free(accounts.get());
        var named = settings.freeAccounts();
        return free.stream().filter(c -> named.isEmpty() ? !c.watchOnly() : named.contains(c.id())).map(CCAPICredential::id).toList();
    }

    /** Starts the proxy inside this process; says why in {@link #problem} when it cannot (no free account, or the proxy refused). */
    synchronized void start() {
        if (proxy != null) return;
        ids = chosen();
        if (ids.isEmpty()) { problem = "no free account to serve from (tier 0, with a web session key and not monitor-only)"; return; }
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
            ex.getRequestHeaders().forEach((k, vs) -> { if (!SKIP.contains(k.toLowerCase()) && !k.equalsIgnoreCase(PLACE_HEADER) && !k.equalsIgnoreCase(NOTE_HEADER)) vs.forEach(v -> req.header(k, v)); });
            if (call != null && call.place != null) req.header(PLACE_HEADER, call.place);
            var note = call == null ? null : noteFor(body, call);
            if (note != null) req.header(NOTE_HEADER, note);
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
                for (var n = in.read(buf); n >= 0; n = in.read(buf)) { out.write(buf, 0, n); out.flush(); if (call != null) call.meter.feed(buf, 0, n); }
            }
            logBody(body, call, note);
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
        /** PRP 88: the full session key, what the terms model was told, the cost the model not in use predicted, and the size of the answer as it streams. */
        String key;
        FreeCostModel.Input input;
        Double shadow;
        final ReplyMeter meter = new ReplyMeter();
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
        var session = key == null ? "-" : key.substring(0, 8);
        var input = chats.input(key, last == null ? null : sessionAccount.get(key), session, model, cache, chars, estimator.promptPerBody(), Instant.now());
        var both = estimator.predictBoth(chars, cache, model, input);
        var call = new Call(model, session, cache, chars, both[0]);
        call.key = key;
        call.input = input;
        call.shadow = input == null ? null : both[1];
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
            var now = Instant.now();
            var reply = c.meter.chars();
            var held = c.input == null ? null : (long) c.input.heldChars();
            var entry = new FreeLedger.Call(now, c.account, c.model, c.chars, FreeEstimator.tokens(c.chars), c.cache, c.status, (System.nanoTime() - c.nanos) / 1_000_000,
                    c.before5, c.before7, c.reset5Before, after5, after7, reset5After, c.predicted, observed, c.token == null || c.token.alone(), c.session, c.promptChars,
                    c.status < 400 ? reply : null, "new".equals(c.cache) ? Long.valueOf(0) : held, c.input == null ? null : c.input.gapS(), c.shadow);
            if (c.status < 400) chats.served(c.key, c.cache, c.promptChars != null ? c.promptChars : c.input != null ? c.input.promptChars() : c.chars, reply, now);
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
            var raw = ccsuite.webproxy.ChatSessions.sessionIdOf(j.path("metadata").path("user_id").asText("")) + "|" + j.path("model").asText() + "|" + j.path("system") + "|" + j.path("messages").path(0);   // Claude Code's session id too (PRP 85): two sessions that open with the same words stay two
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

    /** PRP 88: each chat's held size, last call and last reply, for the terms cost model. */
    final LaneChats chats = new LaneChats();

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
     * A refusal (the API's error JSON) when this request would START a conversation the free lane should not carry (PRP 84): its estimated input tokens (characters over the proxy's one ratio)
     * above the safe start (340,000 by default), or above the server's context limit; or, within the safe start, when the allocation ({@link FreeEstimator#place}) finds no account whose predicted room
     * survives the first call with the margin. No summary reserve is counted on the points: the safe start already leaves the room to summarise.
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
        var tokens = ccsuite.webproxy.RequestTokens.ofBody(body);   // PRP 85: from the content (system, tools, messages), the one ratio only as the fallback
        var need = estimator.predictStart(body.length, model);
        var text = tokens > estimator.actualMaxInputTokens() || tokens > settings.contextLimitTokens()   // the decision is the estimated input against the safe start (PRP 84)
                ? beyondAnyAccount(body.length, tokens, model)
                : estimator.place(ids, need, now) != null ? null : waitForRoom(tokens, need, estimator.bestRoom(ids, now), ids, now);
        if (text == null) return null;
        return JSON.createObjectNode().put("type", "error").set("error", JSON.createObjectNode().put("type", "invalid_request_error").put("message", text)).toString();
    }

    static String modelOf(byte[] body) {
        try { return JSON.readTree(body).path("model").asText(); } catch (Exception e) { return FreeEstimator.SONNET; }
    }

    /** The estimated input is above the safe start (or the server's context limit): waiting for a reset cannot help. Say the estimate and the limit it was compared with, and that compacting (or Haiku, when it would fit a Haiku start) is the way. */
    private String beyondAnyAccount(long chars, long tokens, String model) {
        var overContext = tokens > settings.contextLimitTokens();
        var limit = overContext ? Math.round(settings.contextLimitTokens()) : estimator.actualMaxInputTokens();
        var haikuNeed = estimator.predictStart(chars, "claude-haiku-5-5");
        var haiku = FreeEstimator.family(model).equals("sonnet") && !overContext && haikuNeed <= 100 - settings.guardMarginPoints() ? " It would fit a Haiku start: start fclaude with --model haiku." : "";
        return String.format("fclaude: this conversation is too long to start on the free lane: its estimated input is ~%,d tokens (%,d characters), above the %s of %,d tokens. "
                + "A conversation may start up to %,d tokens and is worked on up to %,d. Compact it first in claude or pclaude (/compact) and continue here, or start a new session in fclaude; waiting for a reset does not help.%s",
                tokens, chars, overContext ? "limit the server itself enforces" : "safe start", limit, estimator.actualMaxInputTokens(), estimator.workZoneTokens(), haiku);
    }

    /** The estimate is within the safe start but no free account has the room now: say which one gets it back first, and when. */
    private String waitForRoom(long tokens, double need, double room, List<String> ids, Instant now) {
        var back = estimator.nextReset(ids, now).map(r -> "room comes back with the next window reset, in about " + FreeGauges.untilReset(r.at().getEpochSecond(), now) + ", on " + r.account()).orElse("no window reset time is known yet");
        return String.format("fclaude: this conversation cannot start on the free lane now: its estimated input is ~%,d tokens, within the safe start of %,d tokens, but it needs roughly %d points of a five-hour window and the ledger predicts the free account with the most room has about %d points left (%s). "
                + "Wait for that, or start a new session in fclaude. Compact it first in claude or pclaude (/compact) to continue this one here.", tokens, estimator.actualMaxInputTokens(), Math.round(need), Math.round(room), back);
    }

    /** The free lane's account lines for the status command: room now and predicted (the ledger's estimate), the last gauge read, the week, the reset, the error of the last predictions. */
    List<String> roomLines() {
        sync();
        var now = Instant.now();
        var chosen = chosen();
        var rooms = new HashMap<String, FreeEstimator.Room>();
        estimator.rooms(chosen, now).forEach(r -> rooms.put(r.id(), r));
        var pending = chosen.stream().filter(id -> !ids.contains(id)).toList();
        return FreeStates.entries(accounts.get(), gauges.table(), pending, now, ZoneId.systemDefault()).stream().map(e -> {
            var room = rooms.get(e.id());
            var by = e.row() == null || e.row().five() == null ? "" : " by " + gauges.sources.getOrDefault(e.id(), "?");
            var estimate = room == null || e.kind() != FreeStates.Kind.AVAILABLE || !room.known() ? "" : "\n        " + estimator.line(room, now).substring(e.id().length()).strip();
            return "  " + FreeStates.line(e, now) + by + estimate;
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
