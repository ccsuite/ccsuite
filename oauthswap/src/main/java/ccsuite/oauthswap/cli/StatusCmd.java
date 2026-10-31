package ccsuite.oauthswap.cli;

import picocli.CommandLine.*;
import ccsuite.oauthswap.AccountView;

import java.util.concurrent.Callable;

@Command(name = "status", description = "Show which account Claude Code is currently logged into.")
public final class StatusCmd implements Callable<Integer> {

    @ParentCommand ClaudeSwapCli parent;

    @Override public Integer call() {
        try {
            var svc = parent.service();
            var activeId = svc.detectActiveId();
            if (activeId == null) {
                System.out.println("Active account: (not managed — logged-in account is not eligible)");
                return 0;
            }
            var v = svc.overview().stream().filter(AccountView::active).findFirst().orElse(null);
            if (v != null)
                System.out.println("Active account: " + v.id() + " (" + v.email() + "), limit " + v.limit());
            else
                System.out.println("Active account id: " + activeId);
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
