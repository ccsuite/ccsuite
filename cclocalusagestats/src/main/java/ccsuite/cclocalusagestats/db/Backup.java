package ccsuite.cclocalusagestats.db;

import com.arcadedb.database.Database;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

/** The ledger as plain JSONL files, one row per line, so it survives anything that happens to the database folder. */
public final class Backup {

    public static final Path DEFAULT_DIR = Ledger.HOME.resolve("backup");
    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String, String> KEYS = Map.of("UsageRow", "key", "SessionInfo", "sessionId", "ActiveMinute", "key", "FileState", "path", "SwitchEvent", "key", "Reading", "key");

    private Backup() {}

    /** Writes every row; UsageRow is split by month (UTC) so old months stay small. Returns the row count per file. Files of a deleted month are removed. */
    public static Map<String, Long> export(Database db, Path dir) throws IOException {
        Files.createDirectories(dir);
        var counts = new TreeMap<String, Long>();
        var writers = new HashMap<String, BufferedWriter>();
        try {
            for (var type : KEYS.keySet()) {
                try (var rs = db.query("sql", "SELECT FROM " + type)) {
                    while (rs.hasNext()) {
                        var r = rs.next();
                        var row = new LinkedHashMap<String, Object>();
                        for (var p : new TreeSet<>(r.getPropertyNames())) if (!p.startsWith("@")) row.put(p, r.getProperty(p));
                        var file = type.equals("UsageRow") ? "UsageRow-" + YearMonth.from(Instant.ofEpochMilli((Long) row.get("ts")).atZone(ZoneOffset.UTC)) : type;
                        var w = writers.computeIfAbsent(file, f -> open(dir.resolve(f + ".jsonl.tmp")));
                        w.write(JSON.writeValueAsString(row));
                        w.newLine();
                        counts.merge(file, 1L, Long::sum);
                    }
                }
            }
        } finally {
            for (var w : writers.values()) w.close();
        }
        for (var f : counts.keySet()) Files.move(dir.resolve(f + ".jsonl.tmp"), dir.resolve(f + ".jsonl"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        try (var old = Files.list(dir)) {
            for (var p : old.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList())
                if (!counts.containsKey(p.getFileName().toString().replace(".jsonl", ""))) Files.delete(p);
        }
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("exportedAt", Instant.now().toString());
        manifest.put("files", counts);
        Files.writeString(dir.resolve("manifest.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        return counts;
    }

    /** Adds what the ledger lacks; a row already there is kept, except a message the backup holds a fuller copy of. Returns rows added per type. */
    public static Map<String, Long> importFrom(Database db, Path dir) throws IOException {
        var added = new TreeMap<String, Long>();
        try (var files = Files.list(dir)) {
            for (var f : files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().toList()) {
                var type = f.getFileName().toString().replaceAll("[-.].*", "");
                if (!KEYS.containsKey(type)) continue;
                try (var lines = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    var batch = new ArrayList<Map<String, Object>>();
                    for (var line = lines.readLine(); line != null; line = lines.readLine()) {
                        if (!line.isBlank()) batch.add(JSON.readValue(line, new TypeReference<Map<String, Object>>() {}));
                        if (batch.size() >= 5000) flush(db, type, batch, added);
                    }
                    flush(db, type, batch, added);
                }
            }
        }
        return added;
    }

    static BufferedWriter open(Path p) {
        try {
            return Files.newBufferedWriter(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void flush(Database db, String type, List<Map<String, Object>> batch, Map<String, Long> added) {
        db.transaction(() -> batch.forEach(m -> put(db, type, m, added)));
        batch.clear();
    }

    static void put(Database db, String type, Map<String, Object> m, Map<String, Long> added) {
        var keyField = KEYS.get(type);
        try (var rs = db.query("sql", "SELECT FROM " + type + " WHERE " + keyField + " = ?", m.get(keyField))) {
            if (!rs.hasNext()) {
                var d = db.newDocument(type);
                m.forEach(d::set);
                d.save();
                added.merge(type, 1L, Long::sum);
                return;
            }
            var old = rs.next();
            if (type.equals("UsageRow") && total(m) > total(old.toMap())) {
                var d = old.getElement().orElseThrow().asDocument().modify();
                m.forEach(d::set);
                d.save();
            }
        }
    }

    static long total(Map<String, Object> m) {
        return Stream.of("inputTokens", "outputTokens", "cacheWriteTotal", "cacheRead").mapToLong(k -> m.get(k) instanceof Number n ? n.longValue() : 0).sum();
    }

    public static Optional<Instant> lastExport(Path dir) {
        try {
            var m = JSON.readValue(Files.readString(dir.resolve("manifest.json")), new TypeReference<Map<String, Object>>() {});
            return Optional.of(Instant.parse((String) m.get("exportedAt")));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
