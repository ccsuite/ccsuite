package ccsuite.cclocalusagestats.cli;

import static datapotter.arcadedbhelper.Query.query;

import ccsuite.cclocalusagestats.db.*;
import ccsuite.cclocalusagestats.model.*;
import java.time.*;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(name = "status", mixinStandardHelpOptions = true, description = "What the ledger holds.")
public final class StatusCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        try (var l = Ledger.open()) {
            var db = l.db;
            var first = query(db, UsageRow.TYPEDEF).orderByAsc(UsageRow_A.$ts).limit(1).firstOrNull();
            var last = query(db, UsageRow.TYPEDEF).orderByDesc(UsageRow_A.$ts).limit(1).firstOrNull();
            System.out.println("ledger: " + Ledger.HOME);
            System.out.println("messages: " + query(db, UsageRow.TYPEDEF).count()
                + "   without account: " + query(db, UsageRow.TYPEDEF).isNull(UsageRow_A.$account).count());
            System.out.println("sessions: " + query(db, SessionInfo.TYPEDEF).count()
                + "   active minutes: " + query(db, ActiveMinute.TYPEDEF).count()
                + "   files read: " + query(db, FileState.TYPEDEF).count()
                + "   switch events: " + query(db, SwitchEvent.TYPEDEF).count());
            if (first != null) System.out.println("from " + Instant.ofEpochMilli(first.ts()) + " to " + Instant.ofEpochMilli(last.ts()));
            System.out.println(Backup.lastExport(Backup.DEFAULT_DIR)
                .map(t -> "last export: " + t + " (" + Duration.between(t, Instant.now()).toDays() + " days ago)")
                .orElse("NEVER EXPORTED: the ledger will outlive the transcripts, so run: cclocalusagestats export --to <a folder that is backed up>"));
        }
        return 0;
    }
}
