package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPICredential;
import ccsuite.webclient.FreeGauge;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The usage of the FREE accounts. A free claude.ai account shows nothing on the usage endpoint, so each is probed: one one-word message in a
 * temporary chat, and the {@code message_limit} event of the reply carries the five-hour and the weekly gauge ({@link FreeGauge}). The last reading of
 * every account is kept here for the free window and the {@code ccacc free} command, and every CHANGED reading is appended to
 * {@code ~/ccsuite/ccacc/free-readings/<UTC day>.jsonl} so the slow weekly gauge (steps of 1%) can be fitted over many readings later.
 */
final class FreeGauges {

    /** One account's last reading; {@code problem} is set (and the numbers null) when the probe failed. */
    record Row(String id, String email, Double five, Double seven, String status, Long resetsAt, Instant read, String model, String problem, long millis) {
        boolean ok() { return problem == null && (five != null || seven != null); }
    }

    /** A probe of one account; the real one is {@link FreeGauge#probe}. */
    interface Probe { FreeGauge.Reading probe(CCAPICredential credential, String model) throws IOException; }

    static final String PROBE_MODEL = "claude-haiku-5-5";
    static final ObjectMapper JSON = new ObjectMapper();

    final Supplier<List<CCAPICredential>> accounts;
    final Probe probe;
    final Path log;
    final Map<String, Row> rows = Collections.synchronizedMap(new LinkedHashMap<>());
    final Map<String, String> lastLogged = new HashMap<>();
    volatile Runnable onChange = () -> { };
    /** Told every usable reading with where it came from: "probe" (the background reader) or "call" (the gauge of a served call). The free lane's estimator listens (PRP 78). */
    volatile BiConsumer<Row, String> onReading = (r, source) -> { };
    ScheduledExecutorService timer;

    FreeGauges(Supplier<List<CCAPICredential>> accounts, Probe probe, Path log) {
        this.accounts = accounts;
        this.probe = probe;
        this.log = log;
    }

    static FreeGauges standard(Supplier<List<CCAPICredential>> accounts) {
        return new FreeGauges(accounts, FreeGauge::probe, Path.of(System.getProperty("user.home"), "ccsuite", "ccacc", "free-readings"));
    }

    /** Free = tier 0 with a web session key and an organisation; monitor-only and inactive ones count too, because the point is to see them. */
    static List<CCAPICredential> free(List<CCAPICredential> all) {
        return all.stream().filter(c -> c.tier() == 0 && c.key() != null && !c.key().isBlank() && c.orgId() != null && !c.orgId().isBlank()).toList();
    }

    List<Row> snapshot() {
        synchronized (rows) { return List.copyOf(rows.values()); }
    }

    /** Probes every free account once, one after the other (gently: a probe is a real chat request), and tells the listener after each. */
    void refreshAll() {
        var current = free(accounts.get());
        synchronized (rows) { rows.keySet().retainAll(current.stream().map(CCAPICredential::id).toList()); }
        for (var credential : current) refresh(credential);
    }

    Row refresh(CCAPICredential c) {
        var t0 = System.nanoTime();
        Row row;
        try {
            var r = probe.probe(c, PROBE_MODEL);
            row = new Row(c.id(), c.email(), r.fiveHour(), r.sevenDay(), r.fiveStatus(), r.fiveResetsAt(), Instant.now(), r.model(),
                r.fiveHour() == null && r.sevenDay() == null ? "the reply carried no gauge" : null, (System.nanoTime() - t0) / 1_000_000);
        } catch (IOException | RuntimeException e) {
            var old = rows.get(c.id());
            row = new Row(c.id(), c.email(), old == null ? null : old.five(), old == null ? null : old.seven(), old == null ? null : old.status(),
                old == null ? null : old.resetsAt(), Instant.now(), old == null ? null : old.model(), why(e), (System.nanoTime() - t0) / 1_000_000);
        }
        rows.put(c.id(), row);
        record(row);
        if (row.ok()) onReading.accept(row, "probe");
        onChange.run();
        return row;
    }

    /** A reading that came with real traffic (the reply of a served call carries the gauge): the same as a probe, without the probe. Keeps what the last reading had for the other window. */
    void observe(String id, Double five, Double seven, Long resetsAt) {
        var old = rows.get(id);
        var email = old != null ? old.email() : free(accounts.get()).stream().filter(c -> c.id().equals(id)).map(CCAPICredential::email).findFirst().orElse("");
        var row = new Row(id, email, five != null ? five : old == null ? null : old.five(), seven != null ? seven : old == null ? null : old.seven(), "within_limit",
            resetsAt != null ? resetsAt : old == null ? null : old.resetsAt(), Instant.now(), old == null ? null : old.model(), null, 0);
        rows.put(id, row);
        record(row);
        if (row.ok()) onReading.accept(row, "call");
        onChange.run();
    }

    static String why(Exception e) {
        var m = String.valueOf(e.getMessage());
        if (m.contains("429")) return "rate limited (HTTP 429), will retry";
        if (m.contains("401") || m.contains("403")) return "the session key was refused (HTTP " + (m.contains("401") ? "401" : "403") + "): sign in again";
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    synchronized void record(Row r) {
        if (!r.ok()) return;
        var sig = r.five() + "|" + r.seven() + "|" + r.resetsAt() + "|" + r.status();
        if (sig.equals(lastLogged.put(r.id(), sig))) return;
        var line = new LinkedHashMap<String, Object>();
        line.put("t", r.read().toString());
        line.put("account", r.id());
        line.put("u5h", r.five());
        line.put("u7d", r.seven());
        line.put("status", r.status());
        line.put("reset5h", r.resetsAt());
        line.put("model", r.model());
        try {
            Files.createDirectories(log);
            Files.writeString(log.resolve(r.read().atZone(ZoneOffset.UTC).toLocalDate() + ".jsonl"), JSON.writeValueAsString(line) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("ccacc: free reading not written: " + e.getMessage());
        }
    }

    /** Polls in the background: now, then every {@code every}. */
    synchronized void start(Duration every) {
        stop();
        timer = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "free-gauges"); t.setDaemon(true); return t; });
        timer.scheduleWithFixedDelay(() -> { try { refreshAll(); } catch (RuntimeException e) { System.err.println("ccacc: free gauges: " + e); } }, 0, every.toSeconds(), TimeUnit.SECONDS);
    }

    synchronized void stop() {
        if (timer != null) timer.shutdownNow();
        timer = null;
    }

    void refreshInBackground() {
        var t = new Thread(this::refreshAll, "free-gauges-now");
        t.setDaemon(true);
        t.start();
    }

    // ---- text, shared by the window and the command ----

    static String bar(Double fraction) {
        if (fraction == null) return "[----------]";
        var n = (int) Math.round(Math.max(0, Math.min(1, fraction)) * 10);
        return "[" + "#".repeat(n) + "-".repeat(10 - n) + "]";
    }

    static String percent(Double fraction) {
        return fraction == null ? "  -" : String.format("%3d%%", Math.round(fraction * 100));
    }

    static String untilReset(Long epochSeconds, Instant now) {
        if (epochSeconds == null) return "-";
        var s = epochSeconds - now.getEpochSecond();
        if (s <= 0) return "now";
        return s >= 3600 ? (s / 3600) + " h " + (s % 3600 / 60) + " min" : (s / 60) + " min";
    }

    static String ago(Instant read, Instant now) {
        var s = Duration.between(read, now).toSeconds();
        return s < 90 ? s + " s ago" : s < 5400 ? (s / 60) + " min ago" : (s / 3600) + " h ago";
    }

    static String line(Row r, Instant now) {
        var note = r.problem() != null ? "  ! " + r.problem() : "";
        return String.format("%-5s 5h %s %s   week %s   %-14s resets in %-10s read %-11s %s%s", r.id(), bar(r.five()), percent(r.five()), percent(r.seven()),
            r.status() == null ? "-" : r.status(), untilReset(r.resetsAt(), now), ago(r.read(), now), r.model() == null ? "" : r.model(), note);
    }
}
