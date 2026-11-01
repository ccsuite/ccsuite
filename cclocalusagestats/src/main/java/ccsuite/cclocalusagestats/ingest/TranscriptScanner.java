package ccsuite.cclocalusagestats.ingest;

import static datapotter.arcadedbhelper.Query.query;

import ccsuite.cclocalusagestats.db.Ledger;
import ccsuite.cclocalusagestats.model.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class TranscriptScanner {

    public static final long IDLE_MS = 10 * 60_000;
    public static final Path DEFAULT_ROOT = Path.of(System.getProperty("user.home"), ".claude");

    final Ledger ledger;
    final AccountResolver accounts;
    final Map<String, Long> stats = new LinkedHashMap<>();

    public TranscriptScanner(Ledger ledger) {
        this.ledger = ledger;
        this.accounts = new AccountResolver(ledger.db);
    }

    /** Reads what is new in every transcript under each root's projects folder; read-only towards the roots. */
    public Map<String, Long> scan(List<Path> roots) throws IOException {
        for (var root : roots) {
            var projects = root.resolve("projects");
            if (!Files.isDirectory(projects)) continue;
            try (var files = Files.find(projects, 8, (p, a) -> a.isRegularFile() && p.toString().endsWith(".jsonl"))) {
                for (var f : (Iterable<Path>) files::iterator) one(f);
            }
        }
        return stats;
    }

    void one(Path f) throws IOException {
        var db = ledger.db;
        var path = f.toAbsolutePath().toString();
        var fs = query(db, FileState.TYPEDEF).eq(FileState_A.$path, path).firstOrNull();
        var size = Files.size(f);
        var start = fs == null || fs.offset() > size ? 0L : fs.offset();
        if (fs != null && start == size) { bump("filesUnchanged", 1); return; }
        var acc = new FileAccumulator(IDLE_MS, start == 0 ? null : fs.lastTs());
        var end = LineReader.read(f, start, acc::line);
        db.transaction(() -> {
            write(acc);
            new FileState().path(path).offset(end).lastTs(acc.lastTs())
                .in(db).whereEq(FileState_A.$path, path).upsert();
        });
        bump("filesRead", 1);
    }

    void write(FileAccumulator acc) {
        var db = ledger.db;
        for (var s : acc.sessions.values()) {
            var old = query(db, SessionInfo.TYPEDEF).eq(SessionInfo_A.$sessionId, s.sessionId()).firstOrNull();
            if (old != null) merge(s, old);
            s.in(db).whereEq(SessionInfo_A.$sessionId, s.sessionId()).upsert();
        }
        for (var r : acc.rows.values()) {
            var s = acc.sessions.get(r.sessionId());
            r.prp(s == null ? null : s.prp()).account(accounts.at(r.ts()));
            var old = query(db, UsageRow.TYPEDEF).eq(UsageRow_A.$key, r.key()).firstOrNull();
            if (old != null && old.total() >= r.total()) continue;
            r.in(db).whereEq(UsageRow_A.$key, r.key()).upsert();
            bump("rowsWritten", 1);
        }
        acc.minutes.forEach((sid, set) -> set.forEach(m -> {
            var key = sid + "|" + m;
            new ActiveMinute().key(key).sessionId(sid).minute(m).in(db).whereEq(ActiveMinute_A.$key, key).upsert();
        }));
    }

    static void merge(SessionInfo s, SessionInfo old) {
        s.firstTs(Math.min(s.firstTs(), old.firstTs())).lastTs(Math.max(s.lastTs(), old.lastTs()));
        if (old.cwd() != null) s.cwd(old.cwd());
        if (old.prp() != null) s.prp(old.prp());
        if (old.entrypoint() != null) s.entrypoint(old.entrypoint());
    }

    void bump(String k, long n) {
        stats.merge(k, n, Long::sum);
    }
}
