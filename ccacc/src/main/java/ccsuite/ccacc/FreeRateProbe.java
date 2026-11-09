package ccsuite.ccacc;

import module java.base;
import module java.net.http;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * PRP 88, the hourly rate probe (user 2026-11-09: "every hour choose randomly 2 accounts to test the current hourly rate as a default system"). The free price changes with the hour
 * (about 3 times dearer from 13:00 UTC on 2026-11-09), so the lane measures it instead of predicting it: each round sends {@code rateProbeAccounts} new chats of
 * {@code rateProbeChars} characters of Java source (a unique first line, so no cache can make them cheap), each pinned to a random free account with room and no session on it,
 * and reads the points the gauge moved (a one-word call first, so the reading before is fresh). Points per 10,000 characters over the off-peak rate of the same content
 * ({@link LaneSettings#termsFreshPer10k}) is the RATE FACTOR; the estimator multiplies every prediction by it while it is fresh. Each probe is a line in
 * {@code rateprobes-<day>.jsonl} of the free ledger, and a restart takes the last factor back from there.
 */
final class FreeRateProbe {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String PREFIX = "rateprobes-", JOB = "rateprobe";

    /** One probe: the points the gauge moved for {@code chars} characters on one account (null with a problem when it cannot be told). */
    record Result(Instant t, String account, long chars, Double points, String problem) {
        Double per10k() { return points == null ? null : points / chars * 1e4; }
        Map<String, Object> toMap(double factor) {
            var m = new LinkedHashMap<String, Object>();
            m.put("t", t.toString()); m.put("account", account); m.put("chars", chars); m.put("points", points); m.put("per10k", per10k()); m.put("factor", factor); m.put("problem", problem);
            return m;
        }
    }

    /** The rate in force: when it was measured, the mean points per 10,000 characters, the factor over the off-peak rate, and the probes behind it. */
    record Rate(Instant t, double per10k, double factor, List<String> accounts) {}

    /** Accounts to probe: chosen at random among the free accounts with enough room and no session on them. */
    static List<String> pick(List<String> ids, Map<String, Double> room, Set<String> inUse, int k, int minRoom, Random random) {
        var c = new ArrayList<>(ids.stream().filter(id -> room.getOrDefault(id, 0.0) >= minRoom && !inUse.contains(id)).toList());
        Collections.shuffle(c, random);
        return c.subList(0, Math.min(k, c.size()));
    }

    /** The rate of a round: the mean per 10,000 characters of the probes that could be told; null when none could. */
    static Rate rate(List<Result> results, double offPeakPer10k, Instant t) {
        var good = results.stream().filter(r -> r.points() != null && r.points() > 0).toList();
        if (good.isEmpty() || offPeakPer10k <= 0) return null;
        var per10k = good.stream().mapToDouble(Result::per10k).average().orElseThrow();
        return new Rate(t, per10k, per10k / offPeakPer10k, good.stream().map(Result::account).toList());
    }

    /** The probe text: a unique first line, then {@code chars} characters of Java source from a random place. */
    static String text(String corpus, int chars, Random random) {
        var head = "Probe " + UUID.randomUUID() + ". The text below is test material.\n";
        var n = Math.max(0, chars - head.length());
        var sb = new StringBuilder(head);
        var at = corpus.isEmpty() ? 0 : random.nextInt(corpus.length());
        while (sb.length() < chars && !corpus.isEmpty()) { var take = Math.min(corpus.length() - at, chars - sb.length()); sb.append(corpus, at, at + take); at = 0; }
        return sb.append("\n\nDo not use any tool. Reply with just the word OK.").toString();
    }

    final FreeLane lane;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    final Random random = new Random();
    volatile Rate current;
    private ScheduledExecutorService timer;
    private String corpus;

    FreeRateProbe(FreeLane lane) { this.lane = lane; }

    /** The factor to multiply predictions by: the last probed one while it is fresh, else 1. */
    double factor(Instant now) {
        var r = current;
        var s = lane.settings;
        return r == null || !s.rateProbe() || Duration.between(r.t(), now).toMinutes() > s.rateProbeMaxAgeMinutes() ? 1.0 : r.factor();
    }

    /** The last factor the ledger holds (a restart keeps it while it is fresh). */
    void restore() {
        if (lane.ledger == null) return;
        var lines = lane.ledger.lines(PREFIX, Instant.now().minus(Duration.ofDays(1)));
        for (var i = lines.size() - 1; i >= 0; i--) {
            try {
                var j = JSON.readTree(lines.get(i));
                if (j.hasNonNull("factor") && j.path("factor").asDouble() > 0 && j.hasNonNull("points")) {
                    current = new Rate(Instant.parse(j.path("t").asText()), j.path("per10k").asDouble(), j.path("factor").asDouble(), List.of(j.path("account").asText()));
                    return;
                }
            } catch (IOException | RuntimeException e) { /* a torn line */ }
        }
    }

    synchronized void start() {
        stop();
        restore();
        timer = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "free-rate-probe"); t.setDaemon(true); return t; });
        timer.schedule(this::tick, 3, TimeUnit.MINUTES);
    }

    synchronized void stop() {
        if (timer != null) timer.shutdownNow();
        timer = null;
    }

    private void tick() {
        try { if (lane.settings.rateProbe() && lane.available()) round(); } catch (RuntimeException e) { System.err.println("ccacc: rate probe: " + e); }
        synchronized (this) { if (timer != null) timer.schedule(this::tick, Math.max(10, lane.settings.rateProbeEveryMinutes()), TimeUnit.MINUTES); }
    }

    /** One round: picks the accounts, probes each, and puts the new rate in force. */
    Rate round() {
        var s = lane.settings;
        var now = Instant.now();
        var ids = lane.chosen();
        var room = new HashMap<String, Double>();
        ids.forEach(id -> room.put(id, lane.estimator.room(id, now).predicted()));
        var inUse = new HashSet<String>();
        lane.chats.calledSince(now.minus(Duration.ofMinutes(30))).forEach(k -> { var a = lane.sessionAccount.get(k); if (a != null) inUse.add(a); });
        ids.forEach(id -> { if (lane.estimator.room(id, now).inFlight() > 0) inUse.add(id); });
        var results = new ArrayList<Result>();
        for (var acc : pick(ids, room, inUse, s.rateProbeAccounts(), s.rateProbeMinRoom(), random)) results.add(probe(acc, s.rateProbeChars()));
        var r = rate(results, s.termsFreshPer10k(), Instant.now());
        if (r != null) current = r;
        var f = r == null ? factor(Instant.now()) : r.factor();
        if (lane.ledger != null) results.forEach(x -> lane.ledger.write(PREFIX, x.t(), x.toMap(f)));
        System.out.println("[LANE] rate probe: " + (r == null ? "no rate (" + results.stream().map(x -> x.account() + ": " + x.problem()).toList() + ")" : "factor %.2f, %.2f points per 10k chars on %s".formatted(r.factor(), r.per10k(), r.accounts())));
        return r;
    }

    /** One probe on one account: a one-word call for a fresh reading, then the probe; the points are the gauge's move between the two. */
    Result probe(String acc, int chars) {
        var t = Instant.now();
        try {
            var before = call(acc, "Reply with just the word OK.");
            var after = call(acc, text(corpus(), chars, random));
            if (before == null || after == null) return new Result(t, acc, chars, null, "no gauge reading");
            if (!Objects.equals(before.resetsAt(), after.resetsAt()) || after.five() < before.five()) return new Result(t, acc, chars, null, "the window reset between the two readings");
            return new Result(t, acc, chars, (after.five() - before.five()) * 100, null);
        } catch (Exception e) {
            return new Result(t, acc, chars, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** One call through the proxy inside ccacc, pinned to the account; returns the gauge reading it carried (null when none arrived). */
    private FreeGauges.Row call(String acc, String text) throws Exception {
        var b = JSON.createObjectNode().put("model", FreeEstimator.SONNET).put("max_tokens", 64).put("stream", false);
        b.putObject("metadata").put("user_id", JSON.createObjectNode().put("session_id", UUID.randomUUID().toString()).toString());
        b.putArray("tools").addObject().put("name", "noop").put("description", "Never call this tool.").putObject("input_schema").put("type", "object").putObject("properties");
        b.putArray("messages").addObject().put("role", "user").put("content", text);
        var start = Instant.now();
        var res = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + lane.port + "/v1/messages")).timeout(Duration.ofMinutes(5))
                .header("content-type", "application/json").header("x-api-key", "ccacc-local").header("anthropic-version", "2023-06-01")
                .header(FreeLane.PLACE_HEADER, acc).header("x-ccacc-job", JOB).POST(HttpRequest.BodyPublishers.ofString(b.toString())).build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) throw new IOException("status " + res.statusCode());
        var served = res.headers().firstValue(FreeLane.ACCOUNT_HEADER).orElse(acc);
        if (!acc.equals(served)) throw new IOException("served by " + served);
        for (var i = 0; i < 20; i++) {   // the reading arrives with the answer, through the gauge listener
            var row = lane.gauges.rows.get(acc);
            if (row != null && row.ok() && !row.read().isBefore(start)) return row;
            Thread.sleep(250);
        }
        return null;
    }

    private String corpus() throws IOException {
        if (corpus == null) try (var in = FreeRateProbe.class.getResourceAsStream("rateprobe-corpus.txt")) { corpus = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        return corpus;
    }
}
