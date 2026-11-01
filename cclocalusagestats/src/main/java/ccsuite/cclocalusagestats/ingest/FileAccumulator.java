package ccsuite.cclocalusagestats.ingest;

import ccsuite.cclocalusagestats.model.*;
import java.util.*;

/** What one pass over one transcript file collected, before it is written in a single transaction. */
public final class FileAccumulator {

    final long idleMs;
    final Map<String, UsageRow> rows = new LinkedHashMap<>();
    final Map<String, SessionInfo> sessions = new HashMap<>();
    final Map<String, Set<Long>> minutes = new HashMap<>();
    Long lastTs;

    public FileAccumulator(long idleMs, Long lastTs) {
        this.idleMs = idleMs;
        this.lastTs = lastTs;
    }

    public Long lastTs() {
        return lastTs;
    }

    public void line(String text) {
        var r = LineParser.parse(text);
        if (r == null) return;
        var sid = r.sessionId();
        if (sid != null) {
            session(sid, r);
            if (!Boolean.TRUE.equals(r.sidechain())) mark(sid, r.ts());
        }
        if (r.key() != null) rows.merge(r.key(), r, (a, b) -> b.total() > a.total() ? b : a);
    }

    void session(String sid, UsageRow r) {
        var s = sessions.computeIfAbsent(sid, k -> new SessionInfo().sessionId(k).firstTs(r.ts()).lastTs(r.ts()));
        s.firstTs(Math.min(s.firstTs(), r.ts())).lastTs(Math.max(s.lastTs(), r.ts()));
        if (s.cwd() == null) s.cwd(r.cwd());
        if (s.entrypoint() == null) s.entrypoint(r.entrypoint());
        if (s.prp() == null) s.prp(r.prp());
    }

    void mark(String sid, long ts) {
        var set = minutes.computeIfAbsent(sid, k -> new HashSet<>());
        var from = lastTs != null && ts > lastTs && ts - lastTs <= idleMs ? lastTs : ts;
        for (var m = from / 60_000; m <= ts / 60_000; m++) set.add(m);
        lastTs = lastTs == null ? ts : Math.max(lastTs, ts);
    }
}
