package ccsuite.cclocalusagestats.cli;

import ccsuite.cclocalusagestats.report.Dimension;
import picocli.CommandLine.Command;

@Command(name = "daily", mixinStandardHelpOptions = true, description = "Tokens per day; see the filters below.")
public final class DailyCommand extends ReportBase {

    DailyCommand() {
        super(Dimension.DAILY);
    }
}
