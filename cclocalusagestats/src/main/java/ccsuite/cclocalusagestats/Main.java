package ccsuite.cclocalusagestats;

import ccsuite.cclocalusagestats.cli.*;
import ccsuite.cclocalusagestats.db.LedgerBusyException;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "cclocalusagestats", mixinStandardHelpOptions = true, version = "0.1.0",
    description = "Usage statistics of this machine's own Claude Code sessions, kept in a durable local ledger. With no command it opens the window.",
    subcommands = {ScanCommand.class, DailyCommand.class, ProjectsCommand.class, ModelsCommand.class, AccountsCommand.class,
        SessionsCommand.class, PrpsCommand.class, TimeCommand.class, StatusCommand.class, SqlCommand.class, DeleteCommand.class, ExportCommand.class, ImportCommand.class, ShareCommand.class, UiCommand.class})
public final class Main implements Runnable {

    public static void main(String[] args) {
        var cmd = new CommandLine(new Main());
        cmd.setExecutionExceptionHandler((ex, c, parse) -> {
            if (!(ex instanceof LedgerBusyException)) throw ex;
            System.err.println(ex.getMessage());
            return 3;
        });
        System.exit(cmd.execute(args));
    }

    @Override
    public void run() {
        new UiCommand().call();
    }
}
