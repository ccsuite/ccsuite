package ccsuite.cclocalusagestats.cli;

import ccsuite.cclocalusagestats.report.Dimension;
import picocli.CommandLine.Command;

@Command(name = "accounts", mixinStandardHelpOptions = true, description = "Tokens per account; see the filters below.")
public final class AccountsCommand extends ReportBase {

    AccountsCommand() {
        super(Dimension.ACCOUNTS);
    }
}
