package ccsuite.cclocalusagestats.cli;

import ccsuite.cclocalusagestats.db.Ledger;
import ccsuite.cclocalusagestats.report.*;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

@Command(name = "share", mixinStandardHelpOptions = true,
    description = "How much of each account's 5-hour quota this machine used and how much others did, from the account readings the account watcher records (history starts when it began writing them). Points: 1 point = 1% of one account's 5-hour window.")
public final class ShareCommand implements Callable<Integer> {

    @Mixin
    Filters filters = new Filters();

    @Option(names = "--by", description = "account or day; default account")
    String by = "account";

    @Override
    public Integer call() {
        try (var l = Ledger.open()) {
            Table.print(ShareReport.run(l.db, filters, by.equals("day")), filters.json());
        }
        return 0;
    }
}
