package ccsuite.ccacc;

import module java.base;

/**
 * What each free account has left (PRP 78). Nothing but ccacc spends these accounts, so its own ledger of calls is the whole truth up to the gauge: per account the
 * estimator keeps the points spent in the current five-hour window and in the week, adds the predicted cost of every served call, and lets every gauge reading
 * overwrite the estimate (the size of the correction goes to the ledger). The cost of a request is predicted from its size and its cache state ("new": the whole
 * conversation is sent; "continued": a chat the lane holds, only what is new is sent) with a rate per 10,000 characters that starts at the setting and is learned from
 * the calls whose gauge movement is known; the error of each prediction is kept. The same state is rebuilt from the ledger after a restart ({@link #replay}).
 * Units: points of a window (0 to 100); the gauge's fractions are converted once, on the way in.
 */
final class FreeEstimator {

    static final double FIVE_HOURS_S = 5 * 3600;
    /** Points of the week per point of the five-hour window, until readings tell better (the weekly gauge moves in steps of one point, so this is learned slowly). */
    static final double SEED_WEEK_RATIO = 0.05;
    static final double MIN_CALL_POINTS = 0.3;
    static final double ALPHA = 0.25;
    static final int KEEP_ERRORS = 20;

    /** The estimator's answer for one account at one moment. {@code now}: room left in the five-hour window; {@code predicted}: that minus the predicted cost of the calls in flight. */
    record Room(String id, boolean known, double usedFive, double now, double predicted, double usedWeek, Long resetsAt, Double lastError, double meanAbsError, int errors, int inFlight) {}

    /** The account chosen for a new conversation: its predicted room, and the score that ranked it (room plus the part of its use that comes back with the reset). */
    record Placement(String account, double room, double score, double need) {}

    /** A call in flight: what was reserved for it, and whether another call overlapped it on the same account (then its gauge movement is not its own cost). */
    static final class Token {
        final String account;
        final double predicted;
        volatile boolean dirty;
        boolean released;
        Token(String account, double predicted) { this.account = account; this.predicted = predicted; }
        boolean alone() { return !dirty; }
    }

    private static final class Acc {
        boolean known;
        double five, seven;
        Long reset5;
        Instant lastReadAt;
        String lastSig;
        double reserved;
        final List<Token> open = new ArrayList<>();
        final ArrayDeque<Double> errors = new ArrayDeque<>();
    }

    final LaneSettings settings;
    private final Map<String, Acc> accs = new HashMap<>();
    private final Map<String, Double> rates = new HashMap<>();
    private double weekRatio = SEED_WEEK_RATIO;

    FreeEstimator(LaneSettings settings) { this.settings = settings; }

    /** The token estimate of a request: characters over four (the user's decision for this version; the tokencounter module is a network service and not wired). */
    static long tokens(long chars) { return chars / 4; }

    private Acc acc(String id) { return accs.computeIfAbsent(id, k -> new Acc()); }

    private static void roll(Acc a, Instant t) {
        if (a.reset5 != null && a.reset5 <= t.getEpochSecond()) { a.five = 0; a.reset5 = null; }
    }

    // ---- prediction

    synchronized double rate(String cache) { return rates.getOrDefault(cache == null ? "new" : cache, settings.pointsPer10kChars()); }

    /** Points of the five-hour window a request of this size is predicted to cost. */
    synchronized double predict(long chars, String cache) { return Math.max(MIN_CALL_POINTS, rate(cache) * chars / 10_000.0); }

    // ---- state

    /** A gauge reading (fractions) moves the estimate to the reading. Returns the correction when it is worth keeping: the account's first reading, or an estimate that was off. */
    synchronized Optional<FreeLedger.Correction> observe(String id, Instant t, Double five, Double seven, Long reset5, String source) {
        if (five == null && seven == null) return Optional.empty();
        var a = acc(id);
        var sig = five + "|" + seven + "|" + reset5 + "|" + t;
        if (sig.equals(a.lastSig)) return Optional.empty();
        a.lastSig = sig;
        if (a.lastReadAt != null && t.isBefore(a.lastReadAt)) return Optional.empty();   // an older reading than the one already applied
        roll(a, t);
        FreeLedger.Correction out = null;
        if (five != null) {
            var reading = five * 100;
            var estimate = a.known ? a.five + a.reserved : reading;
            if (!a.known || Math.abs(reading - estimate) >= 0.01) out = new FreeLedger.Correction(t, id, source, estimate, reading, reading - estimate, seven == null ? null : seven * 100, reset5);
            if (a.known && seven != null && Math.abs(reading - a.five) >= 3 && seven * 100 > a.seven) {   // enough movement to tell a week ratio
                var ratio = (seven * 100 - a.seven) / (reading - a.five);
                if (ratio > 0) weekRatio = Math.max(0.005, Math.min(0.5, weekRatio + 0.1 * (ratio - weekRatio)));
            }
            a.five = reading;
            if (reset5 != null) a.reset5 = reset5;
        }
        if (seven != null) a.seven = seven * 100;
        a.known = true;
        a.lastReadAt = t;
        return Optional.ofNullable(out);
    }

    /** The reading of the gauge table's row, as a reading; skipped when it is the one already applied. */
    Optional<FreeLedger.Correction> observe(FreeGauges.Row r, String source) {
        return r.ok() ? observe(r.id(), r.read(), r.five(), r.seven(), r.resetsAt(), source) : Optional.empty();
    }

    /** A call's cost with no reading to go with it: added to the window (a window that had reset starts again from this call). */
    synchronized void spent(String id, Instant t, double points) {
        var a = acc(id);
        roll(a, t);
        a.five += points;
        a.seven += points * weekRatio;
        if (a.reset5 == null) a.reset5 = t.getEpochSecond() + (long) FIVE_HOURS_S;
        a.known = true;
    }

    /** A call is on its way to this account: its predicted cost is held back from the room until it ends. */
    synchronized Token begin(String id, double predicted) {
        var a = acc(id);
        var t = new Token(id, predicted);
        if (!a.open.isEmpty()) { a.open.forEach(o -> o.dirty = true); t.dirty = true; }
        a.open.add(t);
        a.reserved += predicted;
        return t;
    }

    synchronized void release(Token t) {
        if (t == null || t.released) return;
        t.released = true;
        var a = acc(t.account);
        a.reserved = Math.max(0, a.reserved - t.predicted);
        a.open.remove(t);
    }

    /** The call ended and is in the ledger: the hold is released, a call that carried no reading is added to the window, and the call teaches the estimator. */
    synchronized void finish(Token t, FreeLedger.Call c) {
        release(t);
        if (c.status() < 400 && c.after5() == null && c.account() != null) spent(c.account(), c.t(), c.predicted());
        learn(c);
    }

    /** Rebuilds the state from the ledger, in time order: the readings that corrected an estimate (the background probe, the gauge table), and the calls (a call that carried a reading is that reading too). */
    synchronized void replay(List<FreeLedger.Call> calls, List<FreeLedger.Correction> corrections) {
        var events = new ArrayList<Object>();
        corrections.stream().filter(c -> !"call".equals(c.source())).forEach(events::add);   // the readings of served calls are in the calls themselves
        events.addAll(calls);
        events.sort(Comparator.comparing((Object o) -> o instanceof FreeLedger.Call c ? c.t() : ((FreeLedger.Correction) o).t()));
        for (var e : events) {
            if (e instanceof FreeLedger.Correction c) {
                observe(c.account(), c.t(), c.reading5() / 100, c.reading7() == null ? null : c.reading7() / 100, c.reset5(), "replay");
            } else if (e instanceof FreeLedger.Call c && c.account() != null) {
                if (c.status() < 400) {
                    if (c.after5() != null) {
                        var a = acc(c.account());
                        a.reserved += c.predicted();   // as it was while the reading arrived
                        observe(c.account(), c.t(), c.after5(), c.after7(), c.reset5After(), "replay");
                        a.reserved = Math.max(0, a.reserved - c.predicted());
                    } else spent(c.account(), c.t(), c.predicted());
                }
                learn(c);
            }
        }
    }

    /** The points the gauge moved over a call, when that can be told: both readings in the same window. */
    static Double observed(Double before5, Double after5, Long resetBefore, Long resetAfter) {
        if (before5 == null || after5 == null) return null;
        if (resetBefore != null && resetAfter != null && !resetBefore.equals(resetAfter)) return null;   // the window changed in between
        var d = (after5 - before5) * 100;
        return d < 0 ? null : d;
    }

    private void learn(FreeLedger.Call c) {
        if (c.account() == null || c.status() >= 400 || c.observed() == null || !c.alone()) return;
        var a = acc(c.account());
        a.errors.addLast(c.predicted() - c.observed());
        while (a.errors.size() > KEEP_ERRORS) a.errors.removeFirst();
        var units = c.chars() / 10_000.0;
        if (units < 1.0 || c.observed() < 1.0) return;   // the gauge moves in steps of one point: a smaller movement is noise
        var seed = settings.pointsPer10kChars();
        var cache = c.cache() == null ? "new" : c.cache();
        var fresh = Math.max(seed * 0.2, Math.min(seed * 5, c.observed() / units));
        var old = rates.getOrDefault(cache, seed);
        rates.put(cache, old + ALPHA * (fresh - old));
    }

    // ---- answers

    synchronized Room room(String id, Instant now) {
        var a = accs.get(id);
        if (a == null || !a.known) return new Room(id, false, 0, 100, 100 - (a == null ? 0 : Math.min(100, a.reserved)), 0, null, null, 0, 0, a == null ? 0 : a.open.size());
        var reset = a.reset5 != null && a.reset5 <= now.getEpochSecond();
        var used = reset ? 0 : a.five;
        var nowRoom = Math.max(0, 100 - used);
        Double last = a.errors.peekLast();
        var mean = a.errors.stream().mapToDouble(Math::abs).average().orElse(0);
        return new Room(id, true, used, nowRoom, Math.max(0, nowRoom - a.reserved), a.seven, reset ? null : a.reset5, last, mean, a.errors.size(), a.open.size());
    }

    List<Room> rooms(List<String> ids, Instant now) { return ids.stream().map(id -> room(id, now)).toList(); }

    /**
     * The account for a new conversation: among those whose predicted room still leaves the margin after the call (and whose week has room), the one with the best score,
     * which is its predicted room plus the part of what it has used that comes back at its reset (the nearer the reset, the more: use what is about to refill, keep the
     * rest). Null when no account fits: the only case in which the lane refuses.
     */
    Placement place(List<String> ids, double need, Instant now) {
        Placement best = null;
        for (var id : ids) {
            var r = room(id, now);
            if (r.predicted() - need < settings.guardMarginPoints()) continue;
            if (r.usedWeek() + need * weekRatio() > settings.freeWeekLimit() * 100) continue;
            var toReset = r.resetsAt() == null ? FIVE_HOURS_S : Math.max(0, r.resetsAt() - now.getEpochSecond());
            var score = r.predicted() + r.usedFive() * (1 - Math.min(1, toReset / FIVE_HOURS_S));
            if (best == null || score > best.score()) best = new Placement(id, r.predicted(), score, need);
        }
        return best;
    }

    synchronized double weekRatio() { return weekRatio; }

    /** The most predicted room among these accounts (100 for one never read), -1 when there are none. */
    double bestRoom(List<String> ids, Instant now) { return ids.stream().mapToDouble(id -> room(id, now).predicted()).max().orElse(-1); }

    /** The next five-hour reset among these accounts: when room comes back. */
    Optional<Instant> nextReset(List<String> ids, Instant now) {
        return ids.stream().map(id -> room(id, now).resetsAt()).filter(Objects::nonNull).min(Long::compare).map(Instant::ofEpochSecond);
    }

    /** One line for the status command: room now and predicted, the gauge's own reading, the week, the reset, the error of the last predictions. */
    static String line(Room r, Instant now) {
        if (!r.known()) return String.format("%-5s never read: room 100 assumed%s", r.id(), r.inFlight() > 0 ? ", " + r.inFlight() + " in flight" : "");
        var reset = r.resetsAt() == null ? "-" : FreeGauges.untilReset(r.resetsAt(), now);
        var err = r.errors() == 0 ? "no predictions checked yet" : String.format("last prediction error %+.1f, mean %.1f over %d", r.lastError(), r.meanAbsError(), r.errors());
        return String.format("%-5s room now %3.0f  predicted %3.0f  (5h used %3.0f%%, week %3.0f%%)  resets in %-9s %d in flight; %s", r.id(), r.now(), r.predicted(), r.usedFive(), r.usedWeek(), reset, r.inFlight(), err);
    }

    /** The estimator as the ledger left it. */
    static FreeEstimator fromLedger(FreeLedger ledger, LaneSettings settings, Instant now) {
        var e = new FreeEstimator(settings);
        var since = now.minus(Duration.ofDays(8));   // the week window is the longest that matters
        e.replay(ledger.calls(since), ledger.corrections(since));
        return e;
    }

    /** Accounts the estimator has any state for. */
    synchronized List<String> accounts() { return List.copyOf(accs.keySet()); }
}
