package ccsuite.ccacc.trim;

import module java.base;

/** A probe over made-up processes and a clock that moves only when told; records every trim it was asked to do. */
final class FakeProbe implements Probe {

    final List<Proc> procs = new ArrayList<>();
    final List<Long> trimmed = new ArrayList<>();
    final Map<Long, Set<Long>> lineage = new HashMap<>();
    long free = Long.MAX_VALUE;

    static final class Clk extends Clock {
        long ms = 1_000_000_000L;
        public ZoneId getZone() { return ZoneId.systemDefault(); }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(ms); }
        public long millis() { return ms; }
        void sec(int s) { ms += s * 1000L; }
    }

    static Proc proc(long pid, int wsMb) { return new Proc().pid(pid).startMs(5).workingSet(wsMb * 1_048_576L).privateBytes(400L * 1_048_576).ioOps(10).name("s" + pid); }

    FakeProbe add(Proc p) { procs.add(p); return this; }

    public List<Proc> claudes() {
        return procs.stream().map(p -> new Proc().pid(p.pid()).startMs(p.startMs()).cpuMs(p.cpuMs()).workingSet(p.workingSet()).privateBytes(p.privateBytes()).ioOps(p.ioOps()).descendants(p.descendants()).name(p.name())).toList();
    }

    public Mem trim(Proc p) {
        trimmed.add(p.pid());
        procs.stream().filter(x -> x.pid() == p.pid()).forEach(x -> x.workingSet(2L * 1_048_576));
        return new Mem().workingSet(2L * 1_048_576).privateBytes(p.privateBytes());
    }

    public Set<Long> lineage(Proc p) { return lineage.getOrDefault(p.pid(), Set.of(p.pid())); }

    public long freeRam() { return free; }
}
