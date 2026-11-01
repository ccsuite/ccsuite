package ccsuite.cclocalusagestats.cli;

import ccsuite.cclocalusagestats.report.Dimension;
import picocli.CommandLine.Command;

@Command(name = "sessions", mixinStandardHelpOptions = true, description = "Tokens per session; see the filters below.")
public final class SessionsCommand extends ReportBase {

    SessionsCommand() {
        super(Dimension.SESSIONS);
    }
}
