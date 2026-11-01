package ccsuite.cclocalusagestats.cli;

import ccsuite.cclocalusagestats.db.Ledger;
import ccsuite.cclocalusagestats.report.*;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

public abstract class ReportBase implements Callable<Integer> {

    @Mixin
    Filters filters = new Filters();

    final Dimension dimension;

    ReportBase(Dimension dimension) {
        this.dimension = dimension;
    }

    @Override
    public Integer call() {
        try (var ledger = Ledger.open()) {
            Table.print(dimension.rows(ledger.db, filters), filters.json());
        }
        return 0;
    }
}
