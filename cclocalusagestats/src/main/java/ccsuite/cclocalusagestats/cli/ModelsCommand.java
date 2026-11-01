package ccsuite.cclocalusagestats.cli;

import ccsuite.cclocalusagestats.report.Dimension;
import picocli.CommandLine.Command;

@Command(name = "models", mixinStandardHelpOptions = true, description = "Tokens per model; see the filters below.")
public final class ModelsCommand extends ReportBase {

    ModelsCommand() {
        super(Dimension.MODELS);
    }
}
