package ccsuite.ccacc.trim;

import module java.base;

/**
 * One look at the claude.exe processes: remember who was busy, and trim whoever the decision says to. {@code only} narrows which processes are even considered
 * (the tests give it the pids they started themselves; the app gives none). {@code laneBusy} says a call is in flight on a lane: with no pid to session mapping it holds back every trim for that look.
 */
final class Trimmer {

    final Probe probe;
    final Clock clock;
    final BooleanSupplier laneBusy;
    /** The tabs to hold back, asked only when a trim is about to happen. */
    final Supplier<TabsInfo> tabs;

    Trimmer(Probe probe, Clock clock, BooleanSupplier laneBusy, Supplier<TabsInfo> tabs) { this.probe = probe; this.clock = clock; this.laneBusy = laneBusy; this.tabs = tabs; }

    List<Outcome> tick(TrimSettings cfg, TrimState state, Predicate<Proc> only) {
        var now = clock.millis();
        var procs = probe.claudes().stream().filter(only).toList();
        var busy = laneBusy.getAsBoolean();
        state.seen().keySet().retainAll(procs.stream().map(TrimState::key).collect(Collectors.toSet()));
        var free = cfg.onlyBelowFreeGb() > 0 ? probe.freeRam() : Long.MAX_VALUE;
        var done = new ArrayList<Outcome>();
        var asked = new Supplier<TabsInfo>() { TabsInfo v; public TabsInfo get() { return v == null ? v = tabs.get() : v; } };
        for (var p : procs) {
            var seen = TrimPolicy.observe(p, state.seen().get(TrimState.key(p)), cfg, now, busy);
            state.seen().put(TrimState.key(p), seen);
            if (TrimPolicy.decide(p, seen, cfg, now, free) != Verdict.TRIM || !Collections.disjoint(probe.lineage(p), asked.get().hold())) continue;
            trim(p, named(p, asked.get()), seen, state, now).ifPresent(done::add);
        }
        return done;
    }

    /** The manual {@code trim now --pid N}: trims that claude.exe whatever its idle time, but only if it is one of the user's claude.exe. */
    Optional<Outcome> force(long pid, TrimState state) {
        var now = clock.millis();
        return probe.claudes().stream().filter(p -> p.pid() == pid).findFirst().flatMap(p -> {
            var seen = state.seen().computeIfAbsent(TrimState.key(p), k -> new Seen().pid(p.pid()).startMs(p.startMs()).busyAtMs(now).atMs(now).cpuMs(p.cpuMs()).ioOps(p.ioOps()).descendants(p.descendants()));
            return trim(p, named(p, tabs.get()), seen, state, now);
        });
    }

    private Proc named(Proc p, TabsInfo info) { return p.name().isEmpty() ? p.name(info.nameFor(probe.lineage(p))) : p; }

    private Optional<Outcome> trim(Proc p, Proc named, Seen seen, TrimState state, long now) {
        seen.lastTrimMs(now);
        var after = probe.trim(p);
        if (after == null) return Optional.empty();
        state.add(now, (p.workingSet() - after.workingSet()) / 1_048_576.0);
        return Optional.of(new Outcome().pid(p.pid()).name(named.name()).wsBefore(p.workingSet()).wsAfter(after.workingSet())
            .privBefore(p.privateBytes()).privAfter(after.privateBytes()).todayMb(state.today(now)));
    }
}
