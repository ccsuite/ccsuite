package ccsuite.oauthswap.cli;

import picocli.CommandLine.*;

import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "register",
         description = "Snapshot the current Claude Code login and tie its OAuth token to an account id.")
public final class RegisterCmd implements Callable<Integer> {

    @ParentCommand ClaudeSwapCli parent;

    @Option(names = "--id", paramLabel = "ID",
            description = "Credential id to tie this login to (auto-detected from the logged-in email if omitted).")
    String id;

    @Override public Integer call() {
        try {
            var cred = parent.service().register(Optional.ofNullable(id));
            System.out.println("Registered " + cred.id() + " (" + cred.email() + ")");
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
