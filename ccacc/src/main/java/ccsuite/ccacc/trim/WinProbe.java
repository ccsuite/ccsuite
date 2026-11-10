package ccsuite.ccacc.trim;

import module java.base;

/**
 * The real {@link Probe}. A process is a claude.exe of ours when its executable is named claude.exe and the system reports it as owned by the current user (a process whose owner
 * cannot be read is not ours); nothing else is ever listed, so nothing else can be trimmed. The CPU counts the process and its children together, so a session waiting on a long tool run is not idle.
 */
final class WinProbe implements Probe {

    public List<Proc> claudes() {
        if (!Win.available()) return List.of();
        return ProcessHandle.allProcesses().filter(WinProbe::isClaude).map(WinProbe::proc).filter(Objects::nonNull).toList();
    }

    public Mem trim(Proc p) {
        var h = ProcessHandle.of(p.pid());
        if (h.isEmpty() || !isClaude(h.get()) || h.get().info().startInstant().map(Instant::toEpochMilli).orElse(-1L) != p.startMs()) return null;
        return Win.trim(p.pid());
    }

    public long freeRam() { return Win.freeRam(); }

    public Set<Long> lineage(Proc p) {
        var out = new HashSet<Long>();
        for (var h = ProcessHandle.of(p.pid()); h.isPresent() && out.size() < 8; h = h.get().parent()) out.add(h.get().pid());
        return out;
    }

    static boolean isClaude(ProcessHandle h) {
        var info = h.info();
        return info.command().map(c -> c.toLowerCase().endsWith("\\claude.exe")).orElse(false)
            && info.user().map(u -> u.substring(u.lastIndexOf('\\') + 1).equalsIgnoreCase(System.getProperty("user.name"))).orElse(false);
    }

    static Proc proc(ProcessHandle h) {
        var mem = Win.mem(h.pid());
        if (mem == null) return null;
        var kids = h.descendants().toList();
        var cpu = Stream.concat(Stream.of(h), kids.stream()).mapToLong(x -> x.info().totalCpuDuration().map(Duration::toMillis).orElse(0L)).sum();
        return new Proc().pid(h.pid()).startMs(h.info().startInstant().map(Instant::toEpochMilli).orElse(0L)).cpuMs(cpu).descendants(kids.size())
            .ioOps(Win.ioOps(h.pid())).workingSet(mem.workingSet()).privateBytes(mem.privateBytes());
    }

}
