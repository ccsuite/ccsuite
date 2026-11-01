package ccsuite.ccgate;

import module java.base;

/**
 * Points spent per grant and model family, in all and today (UTC), rebuilt from the ledger at start and kept up to date
 * as calls finish, so the caps survive a restart. Also the per-grant requests-per-minute window.
 */
public final class Spend {

    final Map<String, Double> total = new ConcurrentHashMap<>(), today = new ConcurrentHashMap<>();
    final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();
    volatile String day = LocalDate.now(ZoneOffset.UTC).toString();

    public static Spend from(Ledger ledger, int days) {
        var s = new Spend();
        var now = Instant.now();
        ledger.read(now.minus(Duration.ofDays(days)), now).forEach(s::add);
        return s;
    }

    public void add(LedgerEntry e) {
        // The purpose check is the owner's overhead: on the record under the grant, but not charged to its budget.
        if (e.grant() == null || e.points() == null || e.points() == 0 || "purpose-check".equals(e.outcome())) return;
        var k = e.grant() + "/" + e.family();
        total.merge(k, e.points(), Double::sum);
        rollDay();
        if (e.t() != null && e.t().startsWith(day)) today.merge(k, e.points(), Double::sum);
    }

    public double total(String grant, String family) {
        return total.getOrDefault(grant + "/" + family, 0d);
    }

    public double today(String grant, String family) {
        rollDay();
        return today.getOrDefault(grant + "/" + family, 0d);
    }

    /** Records a request now and says whether the grant is within {@code perMinute} (0 = no limit). */
    public boolean tick(String grant, int perMinute) {
        if (perMinute <= 0) return true;
        var q = recent.computeIfAbsent(grant, g -> new ArrayDeque<>());
        synchronized (q) {
            var now = System.currentTimeMillis();
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
            if (q.size() >= perMinute) return false;
            q.addLast(now);
            return true;
        }
    }

    void rollDay() {
        var d = LocalDate.now(ZoneOffset.UTC).toString();
        if (!d.equals(day)) { day = d; today.clear(); }
    }
}
