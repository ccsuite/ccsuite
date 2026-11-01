package ccsuite.cclocalusagestats.cli;

import ccsuite.cclocalusagestats.db.Ledger;
import ccsuite.cclocalusagestats.report.*;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

@Command(name = "time", mixinStandardHelpOptions = true,
    description = "Estimated active time: minutes with Claude Code activity, a gap of up to 10 minutes counted as active.")
public final class TimeCommand implements Callable<Integer> {

    @Mixin
    Filters filters = new Filters();

    @Option(names = "--by", description = "project, prp, session, day or hour (of the day); default project")
    String by = "project";

    @Override
    public Integer call() {
        try (var ledger = Ledger.open()) {
            Table.print(ActivityReport.by(ledger.db, filters, by), filters.json());
        }
        return 0;
    }
}
