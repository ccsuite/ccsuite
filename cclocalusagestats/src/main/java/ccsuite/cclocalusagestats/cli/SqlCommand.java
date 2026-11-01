package ccsuite.cclocalusagestats.cli;

import ccsuite.cclocalusagestats.db.Ledger;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

@Command(name = "sql", mixinStandardHelpOptions = true,
    description = "Run a read-only SQL query on the ledger and print JSON. Types: UsageRow, SessionInfo, ActiveMinute, SwitchEvent, FileState.")
public final class SqlCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "a SELECT statement")
    String query;

    @Override
    public Integer call() {
        if (!query.stripLeading().toLowerCase().startsWith("select")) {
            System.err.println("only SELECT is accepted here");
            return 2;
        }
        try (var l = Ledger.open(); var rs = l.db.query("sql", query)) {
            while (rs.hasNext()) System.out.println(rs.next().toJSON());
        }
        return 0;
    }
}
