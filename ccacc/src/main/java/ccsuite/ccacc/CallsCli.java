package ccsuite.ccacc;

import module java.base;
import ccsuite.webproxy.CallLog;

/** {@code ccacc calls [--job TAG] [--since 30m] [--by job|session|account|model] [--json] [--file PATH]}: what the free lane served, and what it cost. */
final class CallsCli {

    static int run(String[] args) { return run(args, CallsCli::openFromApp); }

    /** The calls running now, as the running app lists them; empty when no app answered. Asking changes nothing: it is the read-only {@code lanes open}. */
    static Optional<List<String>> openFromApp() {
        var reply = ControlClient.send(Control.defaultFile(), List.of("lanes", "open"));
        if (reply.isEmpty() || !reply.get().ok()) return Optional.empty();
        return Optional.of(reply.get().message().lines().filter(l -> l.startsWith("open ")).toList());
    }

    static int run(String[] args, Supplier<Optional<List<String>>> openCalls) {
        String job = null, by = "job", file = null;
        var since = (Duration) null;
        var json = false;
        var problems = false;
        var slow = Duration.ofSeconds(60);
        try {
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--job" -> job = args[++i];
                    case "--since" -> since = CallsReport.since(args[++i]);
                    case "--by" -> by = args[++i];
                    case "--file" -> file = args[++i];
                    case "--json" -> json = true;
                    case "--problems" -> problems = true;
                    case "--slow" -> slow = CallsReport.since(args[++i]);
                    case "-h", "--help" -> { System.out.println(HELP); return 0; }
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            if (!List.of("job", "session", "account", "model").contains(by)) throw new IllegalArgumentException("--by takes job, session, account or model");
            var paths = file != null ? List.of(Path.of(file)) : logs();
            var lines = new ArrayList<String>();
            for (var p : paths) if (Files.exists(p)) lines.addAll(Files.readAllLines(p));
            if (lines.isEmpty()) { System.out.println("No calls logged yet (looked in " + paths + ")."); return 0; }
            var calls = CallsReport.filter(CallsReport.sorted(CallsReport.parse(lines)), job, since == null ? null : Instant.now().minus(since));
            var groups = CallsReport.groups(calls, by);
            var attention = CallsReport.attention(calls, slow.toMillis());
            if (problems && json) { System.out.println(CallsReport.attentionJson(attention, openCalls.get())); return 0; }
            System.out.println(json ? CallsReport.json(groups) : String.join("\n", CallsReport.text(groups, by)));
            if (!json && (since != null || problems)) System.out.println(String.join("\n", CallsReport.attentionText(attention, openCalls.get())));
            return 0;
        } catch (ArrayIndexOutOfBoundsException e) {
            System.err.println("Error: an option is missing its value");
            return 1;
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /** The free lane's log (the proxy's) and the paid lane's (ccacc's own), both read, so a run on either lane or across them is one report. */
    static List<Path> logs() {
        var free = CallLog.file();
        var paid = Path.of(System.getProperty("user.home"), "ccsuite", "ccacc", "lane-calls.jsonl");
        return free == null ? List.of(paid) : List.of(free, paid);
    }

    static final String HELP = """
        ccacc calls [--job TAG] [--since 30m] [--by job|session|account|model] [--json] [--file PATH] [--problems] [--slow 60s]
          with --since (or --problems) the text ends with what needs attention: the calls that failed, the slow ones (over --slow, 60 s unless said) and the ones still open (asked of the running app, read-only);
          --problems --json prints that alone as JSON ({failed, slow, open}); nothing here changes any state.
          what the lanes served, read from the free lane's call log (~/ccsuite/webproxy/calls.jsonl) and the paid lane's (~/ccsuite/ccacc/lane-calls.jsonl; mode paid, with the account): per group the calls, ok and failed, how many continued a
          chat against started a new one (or a fresh one), the characters sent and received, the median and total time, and per account the five-hour gauge
          from the first call to the last (the points the run cost; the gauge moves in steps of one point, so judge a run by that span).
          Tag a run by starting Claude Code with ANTHROPIC_CUSTOM_HEADERS="x-ccacc-job: NAME" and read it back with --job NAME. The chat reuse switch is
          'ccacc lanes set chatReuse off|on|fresh' (on by default; fresh: a new chat every call, the control for a measurement).""";

    private CallsCli() {}
}
