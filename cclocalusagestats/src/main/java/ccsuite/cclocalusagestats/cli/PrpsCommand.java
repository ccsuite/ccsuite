package ccsuite.cclocalusagestats.cli;

import ccsuite.cclocalusagestats.report.Dimension;
import picocli.CommandLine.Command;

@Command(name = "prps", mixinStandardHelpOptions = true, description = "Tokens per prp; see the filters below.")
public final class PrpsCommand extends ReportBase {

    PrpsCommand() {
        super(Dimension.PRPS);
    }
}
