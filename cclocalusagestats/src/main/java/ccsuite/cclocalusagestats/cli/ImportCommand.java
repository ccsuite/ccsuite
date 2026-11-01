package ccsuite.cclocalusagestats.cli;

import ccsuite.cclocalusagestats.db.*;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

@Command(name = "import", mixinStandardHelpOptions = true,
    description = "Restore rows from an export folder into the ledger. Rows already there are kept (a message is replaced only by a fuller copy), so it is safe to run twice.")
public final class ImportCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "export folder, default: the backup folder inside the ledger home")
    Path from = Backup.DEFAULT_DIR;

    @Override
    public Integer call() throws Exception {
        try (var l = Ledger.open()) {
            var added = Backup.importFrom(l.db, from);
            if (added.isEmpty()) System.out.println("nothing to add");
            added.forEach((t, n) -> System.out.println(t + ": " + n + " added"));
        }
        return 0;
    }
}
