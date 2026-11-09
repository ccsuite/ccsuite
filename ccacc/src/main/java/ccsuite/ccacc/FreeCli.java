package ccsuite.ccacc;

import module java.base;
import ccsuite.base.config.CredentialsManager;
import ccsuite.ccacc.Control.Reply;

/**
 * {@code ccacc free [--overall] [--json] [--refresh] [--probe] [--ping ID...] [--watch SECONDS]}: the usage of the free accounts, the overall first. With the app running it
 * answers from the app's own readings (no new probes; {@code --refresh} reads every account again first), so asking is free. Without the app, or with
 * {@code --probe}, it probes each account itself (one one-word message each). {@code --ping ID...} pings only the named accounts, whatever their state, and says what came back.
 */
final class FreeCli {

    record Options(boolean overall, boolean json, boolean refresh, boolean probe, int watch, boolean help, List<String> ping) {
        static Options parse(List<String> args) {
            var watch = 0;
            for (var i = 0; i < args.size(); i++) if (args.get(i).equals("--watch")) watch = i + 1 < args.size() ? Integer.parseInt(args.get(i + 1)) : 300;
            var ping = new ArrayList<String>();
            var at = args.indexOf("--ping");
            if (at >= 0) for (var i = at + 1; i < args.size() && !args.get(i).startsWith("--"); i++) ping.addAll(Arrays.stream(args.get(i).split(",")).map(String::strip).filter(t -> !t.isEmpty()).toList());
            return new Options(args.contains("--overall"), args.contains("--json"), args.contains("--refresh"), args.contains("--probe"), watch, args.contains("-h") || args.contains("--help"), List.copyOf(ping));
        }
    }

    static int run(String[] args) {
        var list = List.of(args);
        Options o;
        try { o = Options.parse(list); } catch (NumberFormatException e) { System.err.println("Error: --watch needs a number of seconds"); return 1; }
        if (o.help()) { System.out.println(HELP); return 0; }
        try {
            do {
                var reply = o.probe() ? Optional.<Reply>empty() : ControlClient.send(Control.defaultFile(), Stream.concat(Stream.of("free"), list.stream().filter(a -> !a.equals("--watch") && !a.matches("\\d+"))).toList());
                if (reply.isPresent()) {
                    if (!reply.get().ok()) { System.err.println("Error: " + reply.get().message()); return 1; }
                    System.out.println(reply.get().message());
                } else if (probeHere(o) != 0) return 1;
                if (o.watch() > 0) Thread.sleep(o.watch() * 1000L);
            } while (o.watch() > 0);
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /** No app answering (or --probe): read every free account with a probe of our own and print it. */
    static int probeHere(Options o) throws Exception {
        var manager = new CredentialsManager();
        if (!manager.credentialsFileExists()) { System.err.println("Error: no accounts file yet"); return 1; }
        var creds = manager.load();
        if (FreeGauges.free(creds.credentials()).isEmpty()) { System.out.println("No free accounts (tier 0 with a web session key and an organisation id) are saved."); return 0; }
        var gauges = FreeGauges.standard(() -> creds.credentials());
        if (!o.ping().isEmpty()) { var report = ping(gauges, o.ping(), FreeLedger.standard(), Instant.now(), ZoneId.systemDefault()); System.out.println(String.join("\n", report.lines())); return report.ok() ? 0 : 1; }
        gauges.refreshAll();
        System.out.println(render(gauges, o, new CcaccSettingsStore().load().lanes(), Instant.now()));
        return 0;
    }

    /** The gauge table as text: every free account in the shared order, with its explicit state ({@link FreeStates}). */
    static String render(FreeGauges gauges, Options o, LaneSettings limits, Instant now) {
        if (o.json()) return render(gauges.snapshot(), o, limits, now);
        var states = FreeStates.entries(gauges.accounts.get(), gauges.table(), List.of(), now, ZoneId.systemDefault());
        return String.join("\n", FreeReport.text(gauges.snapshot(), o.overall(), limits, now, states));
    }

    static String render(List<FreeGauges.Row> rows, Options o, LaneSettings limits, Instant now) {
        return o.json() ? FreeReport.json(rows, limits, now) : String.join("\n", FreeReport.text(rows, o.overall(), limits, now));
    }

    /** The app's side: answers from its own gauge table; {@code --refresh} reads every account again first. */
    static Reply serve(List<String> args, FreeGauges gauges, LaneSettings limits) { return serve(args, gauges, limits, FreeLedger.standard()); }

    static Reply serve(List<String> args, FreeGauges gauges, LaneSettings limits, FreeLedger ledger) {
        var o = Options.parse(args.subList(1, args.size()));
        if (!o.ping().isEmpty()) {
            var report = ping(gauges, o.ping(), ledger, Instant.now(), ZoneId.systemDefault());
            var text = String.join("\n", report.lines());
            return report.ok() ? Reply.ok(text) : Reply.error(text);
        }
        if (o.refresh()) gauges.refreshAll();
        return Reply.ok(render(gauges, o, limits, Instant.now()));
    }

    record PingReport(boolean ok, List<String> lines) { }

    /**
     * A forced ping of the named free accounts only: one small message each, whatever the account's state or flag, one line in the ping ledger each, and a line saying what came back:
     * the answer (status, both windows, restore time), or the failure in words (an invalid session key says so). An unknown id is an error that names the free accounts there are.
     */
    static PingReport ping(FreeGauges gauges, List<String> ids, FreeLedger ledger, Instant now, ZoneId zone) {
        var free = FreeGauges.free(gauges.accounts.get());
        var unknown = ids.stream().filter(id -> free.stream().noneMatch(c -> c.id().equalsIgnoreCase(id))).toList();
        if (!unknown.isEmpty()) return new PingReport(false, List.of("no free account named " + String.join(", ", unknown) + "; the free accounts are " + free.stream().map(ccsuite.base.model.CCAPICredential::id).collect(Collectors.joining(", "))));
        var lines = new ArrayList<String>();
        var ok = true;
        for (var id : ids) {
            var c = free.stream().filter(a -> a.id().equalsIgnoreCase(id)).findFirst().orElseThrow();
            var before = FreeStates.entry(c, gauges.table().get(c.id()), false, now, zone);
            var row = gauges.refresh(c, "ping");
            var ping = new FreeLedger.Ping(row.read(), row.id(), "forced from the command line", row.ok() ? row.five() : null, row.ok() ? row.seven() : null, row.ok() ? row.resetsAt() : null, row.ok() ? row.status() : null, row.problem());
            ledger.append(ping);
            var after = FreeStates.entry(c, row, false, row.read(), zone);
            lines.add("pinged " + c.id() + " at " + FreeStates.time(row.read().getEpochSecond(), zone) + " (was: " + before.text() + ")");
            lines.add(row.problem() != null ? "  FAILED: " + row.problem() : "  answered: status " + row.status() + (row.resetsAt() == null ? ", no restore time in the answer" : ""));
            lines.add("  " + FreeStates.line(after, row.read()));
            ok &= row.problem() == null;
        }
        return new PingReport(ok, lines);
    }

    static final String HELP = """
        ccacc free [--overall] [--json] [--refresh] [--probe] [--ping ID[,ID...]] [--watch SECONDS]
          the usage of the free claude.ai accounts (tier 0 with a web session key): first ALL, the average of every free account (five-hour and weekly usage,
          how many have room, about how many five-hour windows are left, the pace of the five-hour windows), then one line per account.
          --overall   only the ALL line
          --json      the same as JSON, for scripts
          --refresh   with the app running: read every account again now (one small message each) before answering
          --probe     do not ask the app: probe every account from this command
          --ping IDs  ping only the named accounts now, whatever their state (one small message each), and say what came back: the status, both windows and the
                      restore time, or why it failed (a session key the server no longer accepts says so). Each ping is also a line in the ping ledger.
          --watch N   repeat every N seconds
          With the app running the answer comes from the app's own readings, so asking costs nothing. A free account has no usage endpoint: each reading is a
          one-word probe, kept in ~/ccsuite/ccacc/free-readings/. The app also shows it as a second tray icon and a small window.""";

    private FreeCli() {}
}
