package ccsuite.ccacc;

import module java.base;

/** The calls report as the window shows it: the groups, then what failed or ran slow. The log files are read here and the lines come from {@link CallsReport}, the same code the command line prints with. */
final class CallsView {

    /** The report of the calls in {@code paths}, narrowed to a job and to the last {@code since} (either may be null), grouped {@code by} job, session, account or model. */
    static List<String> lines(List<Path> paths, String job, Duration since, String by, Duration slow, Optional<List<String>> open) throws IOException {
        var raw = new ArrayList<String>();
        for (var p : paths) if (Files.exists(p)) raw.addAll(Files.readAllLines(p));
        if (raw.isEmpty()) return List.of("No calls logged yet (looked in " + paths + ").");
        var calls = CallsReport.filter(CallsReport.sorted(CallsReport.parse(raw)), job, since == null ? null : Instant.now().minus(since));
        var out = new ArrayList<>(CallsReport.text(CallsReport.groups(calls, by), by));
        out.addAll(CallsReport.attentionText(CallsReport.attention(calls, slow.toMillis()), open));
        return out;
    }

    private CallsView() {}
}
