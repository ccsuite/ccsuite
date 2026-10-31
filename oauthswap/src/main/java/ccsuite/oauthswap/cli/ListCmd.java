package ccsuite.oauthswap.cli;

import picocli.CommandLine.*;
import ccsuite.oauthswap.AccountView;

import java.util.concurrent.Callable;

@Command(name = "list", description = "List eligible accounts with their registration / active state.")
public final class ListCmd implements Callable<Integer> {

    @ParentCommand ClaudeSwapCli parent;

    @Override public Integer call() {
        try {
            var views = parent.service().overview();
            if (views.isEmpty()) {
                System.out.println("No eligible accounts (need active + trackUsage + tier>=1).");
                return 0;
            }
            System.out.println("Claude Code accounts (eligible for rotation):");
            for (AccountView v : views) {
                System.out.printf("%s%-4s %-34s limit=%-5s [%s]%s%n",
                        v.active() ? "* " : "  ",
                        v.id(), v.email(), v.limit(),
                        v.registered() ? "registered" : "NOT registered",
                        v.active() ? "  (active)" : "");
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
