package ccsuite.ccgate;

import module java.base;

/** Totals from the ledger, grouped by one dimension: calls, refusals, retries, tokens, quota points and list-price dollars. */
public final class LedgerReport {

    final Ledger ledger;

    public LedgerReport(Ledger ledger) {
        this.ledger = ledger;
    }

    public String run(Flags f) {
        var until = f.has("until") ? LocalDate.parse(f.get("until", null)).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.now();
        var since = f.has("since") ? LocalDate.parse(f.get("since", null)).atStartOfDay(ZoneOffset.UTC).toInstant()
                : until.minus(Duration.ofDays(1));
        if (f.has("tail")) { tail(); return ""; }
        var job = f.get("job", null);
        var by = f.get("by", job != null ? "model" : "grant");
        var rows = new TreeMap<String, Totals>();
        for (var e : ledger.read(since, until)) {
            if (job != null && !job.equals(e.job())) continue;
            rows.computeIfAbsent(Objects.toString(key(e, by), "-"), k -> new Totals()).add(e);
        }
        if (rows.isEmpty()) return "no ledger entries between " + since + " and " + until;
        var sum = new Totals();
        var sb = new StringBuilder(String.format(Locale.ROOT, "%-24s %s%n", "by " + by, Totals.HEADER));
        rows.forEach((k, t) -> { sb.append(String.format(Locale.ROOT, "%-24s %s%n", cut(k), t.line())); sum.merge(t); });
        sb.append(String.format(Locale.ROOT, "%-24s %s%n", "TOTAL", sum.line()));
        return sb + "points: 1 = 1% of one account's 5-hour window; $ = list-price equivalent, not what is paid";
    }

    static Object key(LedgerEntry e, String by) {
        return switch (by) {
            case "job" -> e.job();
            case "project" -> e.project();
            case "model" -> e.model() != null ? e.model() : e.modelAsked();
            case "account" -> e.account();
            case "day" -> e.t().substring(0, 10);
            case "session" -> e.session();
            default -> e.grant();
        };
    }

    void tail() {
        var seen = Instant.now();
        System.out.println("following the ledger; Ctrl+C stops");
        while (true) {
            var now = Instant.now();
            for (var e : ledger.read(seen, now))
                if (Instant.parse(e.t()).isAfter(seen)) System.out.println(LedgerLine.format(e));
            seen = now;
            try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
        }
    }

    static String cut(String s) {
        return s.length() <= 24 ? s : s.substring(0, 23) + "…";
    }
}
