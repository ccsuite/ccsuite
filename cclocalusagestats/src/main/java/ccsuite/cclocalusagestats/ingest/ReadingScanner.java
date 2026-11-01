package ccsuite.cclocalusagestats.ingest;

import static datapotter.arcadedbhelper.Query.query;

import ccsuite.cclocalusagestats.db.Ledger;
import ccsuite.cclocalusagestats.model.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Copies the account watcher's reading files (one JSON line per changed reading) into the ledger, reading only what was appended. */
public final class ReadingScanner {

    public static final Path DIR = Path.of(System.getProperty("user.home"), "ccsuite", "ccacc", "readings");
    static final ObjectMapper JSON = new ObjectMapper();

    private ReadingScanner() {}

    public static int scan(Ledger ledger, Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        var db = ledger.db;
        var total = 0;
        try (var files = Files.newDirectoryStream(dir, "*.jsonl")) {
            for (var f : files) {
                var path = f.toAbsolutePath().toString();
                var fs = query(db, FileState.TYPEDEF).eq(FileState_A.$path, path).firstOrNull();
                var size = Files.size(f);
                var start = fs == null || fs.offset() > size ? 0L : fs.offset();
                if (fs != null && start == size) continue;
                var rows = new ArrayList<Reading>();
                var end = LineReader.read(f, start, line -> parse(line, rows));
                db.transaction(() -> {
                    rows.forEach(r -> r.in(db).whereEq(Reading_A.$key, r.key()).upsert());
                    new FileState().path(path).offset(end).in(db).whereEq(FileState_A.$path, path).upsert();
                });
                total += rows.size();
            }
        }
        return total;
    }

    static void parse(String line, List<Reading> out) {
        try {
            var j = JSON.readTree(line);
            var account = j.get("account").asText();
            var ts = Instant.parse(j.get("t").asText()).toEpochMilli();
            out.add(new Reading().key(account + "|" + ts).account(account).ts(ts)
                .u5h(num(j, "u5h")).u7d(num(j, "u7d")).reset5h(instant(j, "reset5h")).reset7d(instant(j, "reset7d"))
                .active(j.path("active").asBoolean()));
        } catch (RuntimeException | IOException e) {
            // a line that is not a reading is skipped
        }
    }

    static Double num(JsonNode j, String k) {
        return j.hasNonNull(k) ? j.get(k).asDouble() : null;
    }

    static Long instant(JsonNode j, String k) {
        return j.hasNonNull(k) ? Instant.parse(j.get(k).asText()).toEpochMilli() : null;
    }
}
