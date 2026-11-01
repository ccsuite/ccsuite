package ccsuite.ccgate;

import module java.base;

/** Running sums for one row of a ledger report. */
public final class Totals {

    static final String HEADER = String.format(Locale.ROOT, "%6s %5s %5s %5s %10s %8s %11s %11s %9s %8s",
            "calls", "refsd", "retry", "flags", "input", "output", "cache-read", "cache-write", "points", "$list");

    long calls, refused, retries, flags, input, output, cacheRead, cacheWrite;
    double points, dollars;

    void add(LedgerEntry e) {
        switch (String.valueOf(e.outcome())) {
            case "refused" -> refused++;
            case "retry" -> retries++;
            default -> calls++;
        }
        if ("no".equals(e.purpose())) flags++;
        input += n(e.inputTokens());
        output += n(e.outputTokens());
        cacheRead += n(e.cacheReadInputTokens());
        cacheWrite += n(e.cacheCreationInputTokens());
        points += e.points() == null ? 0 : e.points();
        dollars += e.dollars() == null ? 0 : e.dollars();
    }

    void merge(Totals t) {
        calls += t.calls; refused += t.refused; retries += t.retries; flags += t.flags; input += t.input; output += t.output;
        cacheRead += t.cacheRead; cacheWrite += t.cacheWrite; points += t.points; dollars += t.dollars;
    }

    String line() {
        return String.format(Locale.ROOT, "%6d %5d %5d %5d %10d %8d %11d %11d %9.2f %8.2f",
                calls, refused, retries, flags, input, output, cacheRead, cacheWrite, points, dollars);
    }

    static long n(Long v) {
        return v == null ? 0 : v;
    }
}
