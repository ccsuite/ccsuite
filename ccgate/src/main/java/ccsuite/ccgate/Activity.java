package ccsuite.ccgate;

import module java.base;

/**
 * What each grant is doing right now, in memory: calls in the last minute, calls in all since the gate started,
 * refusals, calls in flight, when it was last seen and whether through the relay. The Clients tab reads it; nothing
 * here is stored (the ledger is the record).
 */
public final class Activity {

    /** One grant's counters. */
    static final class State {
        final Deque<Long> recent = new ArrayDeque<>();
        final AtomicLong calls = new AtomicLong(), refused = new AtomicLong();
        final AtomicInteger inFlight = new AtomicInteger();
        volatile long lastSeen;
        volatile boolean viaRelay;
        volatile String lastModel;
    }

    /** A read-only picture of one grant's counters. */
    public record Snapshot(long calls, long refused, int inFlight, int lastMinute, Instant lastSeen, boolean viaRelay, String lastModel) {
        static final Snapshot NONE = new Snapshot(0, 0, 0, 0, null, false, null);
    }

    final Map<String, State> states = new ConcurrentHashMap<>();

    State of(String grant) {
        return states.computeIfAbsent(grant, g -> new State());
    }

    /** A call under a known grant begins (after admission, before the policy decides). */
    public void begin(Call c) {
        var s = of(c.grant().id());
        var now = System.currentTimeMillis();
        synchronized (s.recent) { s.recent.addLast(now); trim(s, now); }
        s.calls.incrementAndGet();
        s.inFlight.incrementAndGet();
        s.lastSeen = now;
        s.viaRelay = c.remoteUser() != null;
        if (c.modelAsked() != null) s.lastModel = c.modelAsked();
    }

    public void end(Call c) {
        of(c.grant().id()).inFlight.decrementAndGet();
    }

    public void refused(Call c) {
        if (c.grant() != null) of(c.grant().id()).refused.incrementAndGet();
    }

    public Snapshot snapshot(String grant) {
        var s = states.get(grant);
        if (s == null) return Snapshot.NONE;
        int last;
        synchronized (s.recent) { trim(s, System.currentTimeMillis()); last = s.recent.size(); }
        return new Snapshot(s.calls.get(), s.refused.get(), s.inFlight.get(), last,
                s.lastSeen == 0 ? null : Instant.ofEpochMilli(s.lastSeen), s.viaRelay, s.lastModel);
    }

    static void trim(State s, long now) {
        while (!s.recent.isEmpty() && now - s.recent.peekFirst() > 60_000) s.recent.pollFirst();
    }
}
