package ccsuite.cclocalusagestats.cli;

import ccsuite.cclocalusagestats.ui.StatsApp;
import java.util.concurrent.Callable;
import javafx.application.Application;
import picocli.CommandLine.Command;

@Command(name = "ui", mixinStandardHelpOptions = true, description = "Open the window (also what running with no command does).")
public final class UiCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        Application.launch(StatsApp.class);
        return 0;
    }
}
