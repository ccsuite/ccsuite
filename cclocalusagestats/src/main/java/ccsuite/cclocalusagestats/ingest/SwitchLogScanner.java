package ccsuite.cclocalusagestats.ingest;

import ccsuite.cclocalusagestats.db.Ledger;
import ccsuite.cclocalusagestats.model.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

public final class SwitchLogScanner {

    public static final Path LOGS = Path.of(System.getProperty("user.home"), "xyz-jphil", "ccapis", "logs");
    static final Pattern P = Pattern.compile(
        "^(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d(?:\\.\\d+)?).*?Switched Claude Code from ([A-Z]{3}) to ([A-Z]{3})");

    private SwitchLogScanner() {}

    /** Copies every "Switched Claude Code from X to Y" line of the cswap logs (local time) into the ledger; returns how many events were seen. */
    public static int scan(Ledger ledger, Path dir) throws IOException {
        var events = new ArrayList<SwitchEvent>();
        if (!Files.isDirectory(dir)) return 0;
        try (var files = Files.newDirectoryStream(dir, "claudeswap*.log")) {
            for (var f : files) new String(Files.readAllBytes(f), StandardCharsets.UTF_8).lines().forEach(l -> parse(l, events));
        }
        ledger.db.transaction(() -> events.forEach(e ->
            e.in(ledger.db).whereEq(SwitchEvent_A.$key, e.key()).upsert()));
        return events.size();
    }

    static void parse(String line, List<SwitchEvent> out) {
        var m = P.matcher(line);
        if (!m.find()) return;
        var ts = LocalDateTime.parse(m.group(1).replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        out.add(new SwitchEvent().key(ts + "|" + m.group(3)).ts(ts).fromAccount(m.group(2)).toAccount(m.group(3)));
    }
}
