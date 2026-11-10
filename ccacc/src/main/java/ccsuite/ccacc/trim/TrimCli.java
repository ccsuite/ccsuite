package ccsuite.ccacc.trim;

import module java.base;

/**
 * {@code ccacc trim status | now [--pid N] | on | off | set <name> <value>}. {@code laneBusy} asks the running app whether a call is in flight on a lane:
 * empty means no app answered, true means a call is open.
 */
public final class TrimCli {

    public static int run(String[] args, Supplier<Optional<Boolean>> laneBusy) {
        try {
            var sub = args.length > 0 ? args[0] : "status";
            return switch (sub) {
                case "status" -> status(laneBusy.get().isPresent());
                case "now" -> now(args, laneBusy);
                case "on", "off" -> onOff(sub.equals("on"), laneBusy.get().isPresent());
                case "set" -> set(args);
                case "-h", "--help", "help" -> out(HELP);
                default -> err("unknown trim command " + sub + "\n" + HELP);
            };
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    private static int status(boolean appRunning) {
        var cfg = TrimSettingsStore.load();
        var state = TrimState.load(TrimState.file());
        var now = System.currentTimeMillis();
        var probe = new WinProbe();
        var procs = probe.claudes();
        var tabs = procs.isEmpty() ? new TabsInfo() : Tabs.fetch();
        System.out.println("trim: " + (cfg.enabled() ? "on" : "off") + "   idle " + cfg.idleMinutes() + " min, only above " + cfg.minWorkingSetMb() + " MB, gap " + cfg.minGapMinutes() + " min, "
            + (cfg.onlyBelowFreeGb() > 0 ? "only when free RAM is under " + cfg.onlyBelowFreeGb() + " GB" : "free RAM: always"));
        System.out.println("ccacc app: " + (appRunning ? "running (it does the looking and the trimming)" : "not running (nothing is watched or trimmed until it runs; idle times below are as it last saw them)"));
        System.out.printf(Locale.ROOT, "given back today: %.1f MB   claude.exe of this user: %d%n", state.today(now), procs.size());
        if (procs.isEmpty()) return 0;
        System.out.printf("  %-7s %-24s %12s %12s %8s  %s%n", "pid", "session", "working set", "private", "idle", "last trim / next");
        for (var p : procs) {
            var s = state.seen().get(TrimState.key(p));
            var idle = s == null ? "-" : TrimPolicy.idleMinutes(s, now) + " min";
            var last = s == null || s.lastTrimMs() == 0 ? "never" : ((now - s.lastTrimMs()) / 60_000) + " min ago";
            var next = s == null ? "not seen by the app yet" : TrimPolicy.decide(p, s, cfg.enabled(true), now, Long.MAX_VALUE).text;
            System.out.printf(Locale.ROOT, "  %-7d %-24s %9.1f MB %9.1f MB %8s  %s; %s%n", p.pid(), nameOrDash(tabs.nameFor(probe.lineage(p))), TrimLog.mb(p.workingSet()), TrimLog.mb(p.privateBytes()), idle, last, next);
        }
        return 0;
    }

    private static int now(String[] args, Supplier<Optional<Boolean>> laneBusy) {
        var state = TrimState.load(TrimState.file());
        var trimmer = new Trimmer(new WinProbe(), Clock.systemDefaultZone(), () -> laneBusy.get().orElse(false), Tabs::fetch);
        var done = args.length >= 3 && args[1].equals("--pid") ? trimmer.force(parsePid(args[2]), state).stream().toList() : trimmer.tick(TrimSettingsStore.load().enabled(true), state, p -> true);
        done.forEach(o -> { TrimLog.append(TrimLog.file(), o, Instant.now()); System.out.println(TrimLog.line(o, Instant.now())); });
        state.save(TrimState.file());
        if (done.isEmpty()) System.out.println(args.length >= 3 ? "nothing done: that pid is not a claude.exe of yours, or it could not be trimmed" : "nothing to trim: no claude.exe is idle long enough (ccacc status shows why)");
        return 0;
    }

    private static int onOff(boolean on, boolean appRunning) {
        TrimSettingsStore.save(TrimSettingsStore.load().enabled(on));
        System.out.println("trim " + (on ? "on" : "off") + (appRunning ? "; the running ccacc takes it up within a minute" : "; ccacc is not running, so nothing trims until it runs"));
        return 0;
    }

    private static int set(String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("trim set <name> <value>");
        TrimSettingsStore.save(TrimSettingsStore.set(TrimSettingsStore.load(), args[1], args[2]));
        System.out.println(args[1] + " = " + args[2]);
        return 0;
    }

    private static long parsePid(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--pid takes a process number, not " + s);
        }
    }

    private static String nameOrDash(String name) { return name.isEmpty() ? "-" : name; }

    private static int out(String text) { System.out.println(text); return 0; }

    private static int err(String text) { System.err.println("Error: " + text); return 1; }

    static final String HELP = """
        ccacc trim status                  each claude.exe of this user: working set, private bytes, idle minutes, last trim, and what the next look would do; the total given back today
        ccacc trim now [--pid N]           trim the idle ones now (as the app would); with --pid, that claude.exe whatever its idle time (never a process that is not the user's claude.exe)
        ccacc trim on | off                switch the idle trimmer on or off (shipped off)
        ccacc trim set <name> <value>      idleMinutes (5), minWorkingSetMb (80), minGapMinutes (10), checkSeconds (30), onlyBelowFreeGb (0 = always), busyCpuPercent (5)
          Trims the working set (Windows EmptyWorkingSet) of claude.exe processes idle for idleMinutes: physical RAM is given back, private bytes do not change,
          the pages come back when the session is used again (about a second on the next turn). Settings: ~/ccsuite/ccacc/trim-settings.json, one line per trim: ~/ccsuite/ccacc/trim.log.""";

    private TrimCli() {}
}
