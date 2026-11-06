package ccsuite.ccgate;

import module java.base;

/**
 * The owner's view of the callers and the means to rein them in, for the Clients tab and the command line. Everything
 * here only takes power away (kick, suspend, rate, throttle) or gives it back (lift), so none of it asks for approval;
 * issuing new grants and keys does, and lives in {@link GrantCommands}.
 */
public final class Clients {

    /** One row of the Clients tab. */
    public record Row(String id, String name, String kind, String status, boolean viaRelay, int lastMinute, long calls, long refused,
                      int inFlight, double pointsToday, Instant lastSeen, String limits, String lastModel) { }

    final GrantBook book;
    final Spend spend;
    final Activity activity;

    public Clients(GrantBook book, Spend spend, Activity activity) {
        this.book = book; this.spend = spend; this.activity = activity;
    }

    public List<Row> rows() {
        var now = Instant.now();
        var rows = new ArrayList<Row>();
        for (var g : book.current().grants()) {
            var a = activity.snapshot(g.id());
            rows.add(new Row(g.id(), g.name(), g.kind(), status(g, now), a.viaRelay(), a.lastMinute(), a.calls(), a.refused(), a.inFlight(),
                    spend.todayAll(g.id()), a.lastSeen(), limits(g), a.lastModel()));
        }
        rows.sort(Comparator.comparing((Row r) -> r.lastSeen() == null ? Instant.EPOCH : r.lastSeen()).reversed());
        return rows;
    }

    static String status(Grant g, Instant now) {
        if (g.revoked()) return "revoked";
        if (g.expired(now)) return "expired";
        if (g.suspended(now)) return "suspended";
        if (g.throttleBps() > 0) return "throttled";
        return "ok";
    }

    static String limits(Grant g) {
        var parts = new ArrayList<String>();
        if (g.perMinute() > 0) parts.add(g.perMinute() + "/min" + (g.smooth() ? " (queued)" : ""));
        if (g.throttleBps() > 0) parts.add(speed(g.throttleBps()));
        if (g.suspended(Instant.now())) parts.add("until " + g.bannedUntil().substring(11, 16) + " UTC");
        return String.join(", ", parts);
    }

    public static String speed(int bps) {
        return bps >= 1000 ? String.format(Locale.ROOT, "%.1f KB/s", bps / 1000.0).replace(".0 ", " ") : bps + " B/s";
    }

    // ---- controls

    /** Revokes the grant and every key of it. */
    public synchronized void kick(String id) throws IOException {
        edit(id, g -> { g.revoked(true); g.keys().forEach(k -> k.revoked(true)); });
    }

    public synchronized void suspend(String id, Duration length, String reason) throws IOException {
        edit(id, g -> g.bannedUntil(Instant.now().plus(length).toString()).banReason(reason));
    }

    public synchronized void lift(String id) throws IOException {
        edit(id, g -> g.bannedUntil(null).banReason(null));
    }

    /** Requests a minute (0 = no limit); with {@code smooth} the excess waits instead of being refused. */
    public synchronized void rate(String id, int perMinute, boolean smooth) throws IOException {
        edit(id, g -> g.perMinute(Math.max(0, perMinute)).smooth(smooth));
    }

    /** Response speed in bytes per second (0 = none). */
    public synchronized void throttle(String id, int bytesPerSecond) throws IOException {
        edit(id, g -> g.throttleBps(Math.max(0, bytesPerSecond)));
    }

    void edit(String id, Consumer<Grant> change) throws IOException {
        var grants = book.current();
        change.accept(grants.byId(id).orElseThrow(() -> new IllegalArgumentException("no grant '" + id + "'")));
        book.save(grants);
    }
}
