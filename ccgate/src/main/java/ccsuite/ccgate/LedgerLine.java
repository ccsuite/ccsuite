package ccsuite.ccgate;

import module java.base;

/** One ledger entry as one readable line, for the live tail. */
public final class LedgerLine {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public static String format(LedgerEntry e) {
        return String.join("  ", TIME.format(Instant.parse(e.t())), pad(e.outcome(), 7), pad(String.valueOf(e.status()), 3),
                pad(e.grant(), 12), pad(e.account(), 4), pad(e.model() != null ? e.model() : e.modelAsked(), 20),
                "in " + Totals.n(e.inputTokens()) + " out " + Totals.n(e.outputTokens()) + " cr " + Totals.n(e.cacheReadInputTokens()),
                e.points() == null ? "" : String.format(Locale.ROOT, "%.3f pt", e.points()),
                e.job() == null ? "" : "job " + e.job(), e.reason() == null ? "" : e.reason());
    }

    static String pad(String s, int n) {
        var v = s == null ? "-" : s;
        return v.length() >= n ? v : v + " ".repeat(n - v.length());
    }

    private LedgerLine() { }
}
