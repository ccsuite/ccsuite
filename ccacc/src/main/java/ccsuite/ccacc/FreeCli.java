package ccsuite.ccacc;

import module java.base;
import ccsuite.base.config.CredentialsManager;
import ccsuite.ccacc.Control.Reply;

/**
 * {@code ccacc free [--overall] [--json] [--refresh] [--probe] [--watch SECONDS]}: the usage of the free accounts, the overall first. With the app running it
 * answers from the app's own readings (no new probes; {@code --refresh} reads every account again first), so asking is free. Without the app, or with
 * {@code --probe}, it probes each account itself (one one-word message each).
 */
final class FreeCli {

    record Options(boolean overall, boolean json, boolean refresh, boolean probe, int watch, boolean help) {
        static Options parse(List<String> args) {
            var watch = 0;
            for (var i = 0; i < args.size(); i++) if (args.get(i).equals("--watch")) watch = i + 1 < args.size() ? Integer.parseInt(args.get(i + 1)) : 300;
            return new Options(args.contains("--overall"), args.contains("--json"), args.contains("--refresh"), args.contains("--probe"), watch, args.contains("-h") || args.contains("--help"));
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
        gauges.refreshAll();
        System.out.println(render(gauges.snapshot(), o, new CcaccSettingsStore().load().lanes(), Instant.now()));
        return 0;
    }

    static String render(List<FreeGauges.Row> rows, Options o, LaneSettings limits, Instant now) {
        return o.json() ? FreeReport.json(rows, limits, now) : String.join("\n", FreeReport.text(rows, o.overall(), limits, now));
    }

    /** The app's side: answers from its own gauge table; {@code --refresh} reads every account again first. */
    static Reply serve(List<String> args, FreeGauges gauges, LaneSettings limits) {
        var o = Options.parse(args.subList(1, args.size()));
        if (o.refresh()) gauges.refreshAll();
        return Reply.ok(render(gauges.snapshot(), o, limits, Instant.now()));
    }

    static final String HELP = """
        ccacc free [--overall] [--json] [--refresh] [--probe] [--watch SECONDS]
          the usage of the free claude.ai accounts (tier 0 with a web session key): first ALL, the average of every free account (five-hour and weekly usage,
          how many have room, about how many five-hour windows are left, the pace of the five-hour windows), then one line per account.
          --overall   only the ALL line
          --json      the same as JSON, for scripts
          --refresh   with the app running: read every account again now (one small message each) before answering
          --probe     do not ask the app: probe every account from this command
          --watch N   repeat every N seconds
          With the app running the answer comes from the app's own readings, so asking costs nothing. A free account has no usage endpoint: each reading is a
          one-word probe, kept in ~/ccsuite/ccacc/free-readings/. The app also shows it as a second tray icon and a small window.""";

    private FreeCli() {}
}
