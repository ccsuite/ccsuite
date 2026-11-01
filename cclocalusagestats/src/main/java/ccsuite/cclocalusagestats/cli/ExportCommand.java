package ccsuite.cclocalusagestats.cli;

import ccsuite.cclocalusagestats.db.*;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

@Command(name = "export", mixinStandardHelpOptions = true,
    description = "Write the whole ledger as plain JSONL files (one row per line, messages split by month) plus a manifest. Point --to at a folder that is backed up elsewhere.")
public final class ExportCommand implements Callable<Integer> {

    @Option(names = "--to", description = "folder to write, default: the backup folder inside the ledger home")
    Path to = Backup.DEFAULT_DIR;

    @Override
    public Integer call() throws Exception {
        try (var l = Ledger.open()) {
            var counts = Backup.export(l.db, to);
            counts.forEach((f, n) -> System.out.println(f + ".jsonl: " + n));
            System.out.println("written to " + to.toAbsolutePath());
        }
        return 0;
    }
}
