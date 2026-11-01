package ccsuite.cclocalusagestats.cli;

import ccsuite.cclocalusagestats.db.Ledger;
import ccsuite.cclocalusagestats.ingest.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

@Command(name = "scan", mixinStandardHelpOptions = true,
    description = "Read what is new in the transcripts (and the account switch log) into the ledger.")
public final class ScanCommand implements Callable<Integer> {

    @Option(names = "--root", description = "Claude config folder to read (repeatable); default CLAUDE_CONFIG_DIR or ~/.claude")
    List<Path> roots;

    @Override
    public Integer call() throws Exception {
        try (var ledger = Ledger.open()) {
            var switches = SwitchLogScanner.scan(ledger, SwitchLogScanner.LOGS);
            var readings = ReadingScanner.scan(ledger, ReadingScanner.DIR);
            var stats = new TranscriptScanner(ledger).scan(Roots.resolve(roots));
            System.out.println("switch events seen: " + switches + ", new account readings: " + readings);
            stats.forEach((k, v) -> System.out.println(k + ": " + v));
        }
        return 0;
    }
}
