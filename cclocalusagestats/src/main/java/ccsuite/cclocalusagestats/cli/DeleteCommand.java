package ccsuite.cclocalusagestats.cli;

import ccsuite.cclocalusagestats.db.*;
import java.time.*;
import java.util.Scanner;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

@Command(name = "delete", mixinStandardHelpOptions = true,
    description = "Delete ledger rows (never the transcripts). Names what it will remove and asks first. Files already read are remembered, so deleted history is not read in again; --all forgets that too.")
public final class DeleteCommand implements Callable<Integer> {

    @Option(names = "--session", description = "a session id, or its first characters")
    String session;
    @Option(names = "--project", description = "working directories containing this text")
    String project;
    @Option(names = "--before", description = "everything before this day, yyyy-MM-dd, in --zone")
    LocalDate before;
    @Option(names = "--zone", description = "timezone for --before, default: this machine's")
    ZoneId zone = ZoneId.systemDefault();
    @Option(names = "--all", description = "everything: messages, sessions, active time, files read and switch events")
    boolean all;
    @Option(names = "--yes", description = "do not ask")
    boolean yes;

    @Override
    public Integer call() {
        var plan = all ? DeletePlan.everything() : session != null ? DeletePlan.session(session)
            : project != null ? DeletePlan.projectContaining(project) : before != null ? DeletePlan.before(before, zone) : null;
        if (plan == null) {
            System.err.println("name what to delete: --session, --project, --before or --all");
            return 2;
        }
        try (var l = Ledger.open()) {
            var counts = plan.counts(l.db);
            counts.forEach((type, n) -> System.out.println(type + ": " + n + " rows"));
            var total = counts.values().stream().mapToLong(Long::longValue).sum();
            if (total == 0) return 0;
            if (!yes && !confirm(total)) { System.out.println("nothing deleted"); return 1; }
            plan.run(l.db);
            System.out.println("deleted " + total + " rows");
        }
        return 0;
    }

    boolean confirm(long total) {
        System.out.print("Delete " + total + " rows from the ledger? This cannot be undone. [y/N] ");
        var in = new Scanner(System.in);
        return in.hasNextLine() && in.nextLine().trim().equalsIgnoreCase("y");
    }
}
