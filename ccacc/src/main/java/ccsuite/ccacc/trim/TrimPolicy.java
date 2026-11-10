package ccsuite.ccacc.trim;

/** The idle decision, pure: the same inputs always give the same verdict, so it is tested with fake clocks and fake readings. */
final class TrimPolicy {

    static long idleMinutes(Seen s, long now) { return Math.max(0, now - s.busyAtMs()) / 60_000; }

    static Verdict decide(Proc p, Seen s, TrimSettings cfg, long now, long freeRam) {
        if (!cfg.enabled()) return Verdict.DISABLED;
        if (now - s.busyAtMs() < cfg.idleMinutes() * 60_000L) return Verdict.NOT_IDLE;
        if (p.workingSet() < cfg.minWorkingSetMb() * 1_048_576L) return Verdict.SMALL;
        if (s.lastTrimMs() != 0 && now - s.lastTrimMs() < cfg.minGapMinutes() * 60_000L) return Verdict.GAP;
        if (cfg.onlyBelowFreeGb() > 0 && freeRam >= cfg.onlyBelowFreeGb() * 1_073_741_824.0) return Verdict.RAM_PLENTY;
        return Verdict.TRIM;
    }

    /**
     * Updates what is remembered about a process: it counts as busy now when a call is in flight on a lane, when its reads or writes moved, its CPU (with its children's) is above the limit over the interval
     * since the last look, when the number of its children changed, or when the last look is too old to know anything (the app was off). A process seen for the first time counts as busy now.
     */
    static Seen observe(Proc p, Seen old, TrimSettings cfg, long now, boolean laneBusy) {
        var s = old != null ? old : new Seen().pid(p.pid()).startMs(p.startMs()).busyAtMs(now).atMs(now).cpuMs(p.cpuMs()).ioOps(p.ioOps()).descendants(p.descendants());
        var dt = now - s.atMs();
        var dCpu = p.cpuMs() - s.cpuMs();
        var stale = dt > Math.max(cfg.checkSeconds() * 3000L, 90_000L);
        var rate = dt > 0 ? dCpu * 100.0 / dt : 0;
        var io = p.ioOps() != s.ioOps();
        if (laneBusy || stale || io || dCpu < 0 || rate > cfg.busyCpuPercent() || p.descendants() != s.descendants()) s.busyAtMs(now);
        return s.atMs(now).cpuMs(p.cpuMs()).ioOps(p.ioOps()).descendants(p.descendants());
    }

    private TrimPolicy() {}
}
