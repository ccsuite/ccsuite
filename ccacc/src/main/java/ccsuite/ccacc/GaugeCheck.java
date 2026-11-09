package ccsuite.ccacc;

import module java.base;

/**
 * PRP 87, {@code ccacc tokencheck model gauge}: is the free lane's estimate right? Per account the calls are cut into RUNS (calls in one five-hour window, the gauge never going
 * back); a run's observed cost is the gauge from its first reading to its last, its predicted cost the sum of what the estimate said for those calls. The gauge moves in whole
 * points (about 6,800 input tokens each), so a verdict needs enough points: with 40 the two ends together are off by at most 5%. Predicted and observed are both in points of the
 * five-hour window, so the check tests tokens and rate together; it reads the ledger only and counts nothing with count_tokens. Pure: calls in, text out.
 */
final class GaugeCheck {

    /** One run of calls on one account: the points the estimate predicted and the points the gauge moved. */
    record Run(String account, Instant from, Instant to, int calls, double predicted, double observed, Set<String> sessions, double shadow, int shadowCalls) {
        Run(String account, Instant from, Instant to, int calls, double predicted, double observed, Set<String> sessions) { this(account, from, to, calls, predicted, observed, sessions, predicted, 0); }

        /** PRP 88: the model not in use, on the same calls (its prediction where the ledger has one, else the one in use). */
        double shadowDeviation() { return percentOff(shadow, observed); }
        /** Predicted against observed in percent; 0 when the gauge did not move at all. */
        double deviation() { return percentOff(predicted, observed); }
    }

    record Verdict(String word, double deviation, double points, int runs, int longRuns, int longWithin) {}

    static double percentOff(double predicted, double observed) { return observed <= 0 ? 0 : (predicted - observed) / observed * 100; }

    /** A run being built: the gauge at its start, and the last reading with the index of the call that carried it. */
    private static final class Open {
        final Double start;
        Long reset;
        Double last;
        int lastIndex = -1;
        final List<FreeLedger.Call> calls = new ArrayList<>();
        Open(FreeLedger.Call c) { start = c.before5(); reset = c.reset5Before(); add(c); }

        double floor() { return last != null ? last : start; }

        boolean continuedBy(FreeLedger.Call c) {
            if (c.reset5Before() != null && reset != null && !c.reset5Before().equals(reset)) return false;   // another window
            if (c.reset5After() != null && reset != null && !c.reset5After().equals(reset)) return false;
            if (c.before5() != null && c.before5() < floor() - 1e-9) return false;   // the gauge fell: a window ended unseen
            return c.after5() == null || c.after5() >= floor() - 1e-9;
        }

        void add(FreeLedger.Call c) {
            calls.add(c);
            if (c.reset5After() != null) reset = c.reset5After();
            if (c.after5() != null) { last = c.after5(); lastIndex = calls.size() - 1; }
        }

        /** The run up to its last reading; null when no call of it carried one (nothing to judge). */
        Run close(String account) {
            if (lastIndex < 0) return null;
            var used = calls.subList(0, lastIndex + 1);   // calls after the last reading are not yet seen by the gauge
            return new Run(account, used.getFirst().t(), used.getLast().t(), used.size(), used.stream().mapToDouble(FreeLedger.Call::predicted).sum(),
                    Math.max(0, (last - start) * 100), used.stream().map(FreeLedger.Call::session).filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(TreeSet::new)),
                    used.stream().mapToDouble(c -> c.predictedOther() != null ? c.predictedOther() : c.predicted()).sum(), (int) used.stream().filter(c -> c.predictedOther() != null).count());
        }
    }

    /** The runs of these calls, in time order: failed calls and calls of no account are left out, and a run starts at a call that carries the gauge it began from. */
    static List<Run> runs(List<FreeLedger.Call> calls) {
        var byAccount = new TreeMap<String, List<FreeLedger.Call>>();
        calls.stream().filter(c -> c.status() < 400 && c.account() != null).sorted(Comparator.comparing(FreeLedger.Call::t)).forEach(c -> byAccount.computeIfAbsent(c.account(), k -> new ArrayList<>()).add(c));
        var out = new ArrayList<Run>();
        byAccount.forEach((account, list) -> {
            Open open = null;
            for (var c : list) {
                if (open != null && !open.continuedBy(c)) { var r = open.close(account); if (r != null) out.add(r); open = null; }
                if (open != null) open.add(c);
                else if (c.before5() != null) open = new Open(c);
            }
            if (open != null) { var r = open.close(account); if (r != null) out.add(r); }
        });
        out.sort(Comparator.comparing(Run::from));
        return out;
    }

    /** All runs together: predicted against moved, in percent; "too-few-points" when the gauge moved less than {@code minPoints} in all. */
    static Verdict verdict(List<Run> runs, double tolerance, double minPoints) {
        var predicted = runs.stream().mapToDouble(Run::predicted).sum();
        var observed = runs.stream().mapToDouble(Run::observed).sum();
        var dev = percentOff(predicted, observed);
        var word = observed < minPoints ? "too-few-points" : Math.abs(dev) <= tolerance ? "within" : "outside";
        var longRuns = runs.stream().filter(r -> r.observed() >= minPoints).toList();
        return new Verdict(word, dev, observed, runs.size(), longRuns.size(), (int) longRuns.stream().filter(r -> Math.abs(r.deviation()) <= tolerance).count());
    }

    static String percent(double v) { return v == Math.rint(v) ? "%.0f%%".formatted(v) : "%s%%".formatted(v); }

    /** The one line another program reads: {@code gauge verdict: within deviation=+2.0% points=100 runs=1 tolerance=5%}. */
    static String verdictLine(Verdict v, double tolerance) {
        return "gauge verdict: %s deviation=%+.1f%% points=%.0f runs=%d long=%d long-within=%d tolerance=%s".formatted(v.word(), v.deviation(), v.points(), v.runs(), v.longRuns(), v.longWithin(), percent(tolerance));
    }

    static List<String> report(List<Run> runs, double tolerance, double minPoints) {
        if (runs.isEmpty()) return List.of("no calls with a gauge reading in the ledger for that selection");
        var out = new ArrayList<String>();
        var days = new TreeMap<String, List<Run>>();
        runs.forEach(r -> days.computeIfAbsent(r.from().atZone(ZoneOffset.UTC).toLocalDate().toString(), k -> new ArrayList<>()).add(r));
        days.forEach((day, list) -> {
            var p = list.stream().mapToDouble(Run::predicted).sum();
            var o = list.stream().mapToDouble(Run::observed).sum();
            out.add("%s (UTC)  %d runs  predicted %.1f  moved %.1f points  deviation %+.1f%%".formatted(day, list.size(), p, o, percentOff(p, o)));
            for (var r : list) {
                var judged = r.observed() >= minPoints;
                var flag = !judged ? "short (under %.0f points: one point is more than %s of it)".formatted(minPoints, percent(Math.ceil(100 / minPoints)))
                        : Math.abs(r.deviation()) > tolerance ? "OUTSIDE" : "within";
                out.add("  %s  %s-%s  %d calls  predicted %.1f  moved %.1f points  deviation %+.1f%%  %s".formatted(r.account(), r.from().toString().substring(11, 16), r.to().toString().substring(11, 16),
                        r.calls(), r.predicted(), r.observed(), r.deviation(), flag)
                        + (r.shadowCalls() > 0 ? "  (other model %.1f, %+.1f%%, %d of %d calls)".formatted(r.shadow(), r.shadowDeviation(), r.shadowCalls(), r.calls()) : ""));
                if (judged && Math.abs(r.deviation()) > tolerance)
                    out.add("    to settle whether the estimate or the rate is off, count its bodies: " + r.sessions().stream().limit(5).map(s -> "ccacc tokencheck model bodies --session " + s).collect(java.util.stream.Collectors.joining("; ")));
            }
        });
        out.add("The gauge moves in whole points, so judge runs of tens of points; the estimate is tokens times rate, so a deviation does not say which of the two is off.");
        var shadowed = runs.stream().filter(r -> r.shadowCalls() > 0 && r.observed() >= minPoints).toList();
        if (!shadowed.isEmpty()) out.add("PRP 88, the two cost models on the same long runs (%d): in use %.1f%% mean deviation, %d within %s; the other %.1f%%, %d within".formatted(shadowed.size(),
                shadowed.stream().mapToDouble(r -> Math.abs(r.deviation())).average().orElse(0), shadowed.stream().filter(r -> Math.abs(r.deviation()) <= tolerance).count(), percent(tolerance),
                shadowed.stream().mapToDouble(r -> Math.abs(r.shadowDeviation())).average().orElse(0), shadowed.stream().filter(r -> Math.abs(r.shadowDeviation()) <= tolerance).count()));
        out.add(verdictLine(verdict(runs, tolerance, minPoints), tolerance));
        return out;
    }

    private GaugeCheck() { }
}
