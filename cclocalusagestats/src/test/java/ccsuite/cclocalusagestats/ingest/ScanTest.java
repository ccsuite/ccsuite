package ccsuite.cclocalusagestats.ingest;

import static datapotter.arcadedbhelper.Query.query;
import static org.junit.jupiter.api.Assertions.*;

import ccsuite.cclocalusagestats.db.*;
import ccsuite.cclocalusagestats.model.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ScanTest {

    @TempDir
    Path tmp;

    Path transcript() throws Exception {
        var f = tmp.resolve("claude/projects/p1/s1.jsonl");
        Files.createDirectories(f.getParent());
        Files.copy(Path.of(getClass().getResource("/transcript-sample.jsonl").toURI()), f);
        return f;
    }

    @Test
    void finalUsageWinsAndARescanChangesNothing() throws Exception {
        transcript();
        try (var l = new Ledger(tmp.resolve("ledger"))) {
            new TranscriptScanner(l).scan(List.of(tmp.resolve("claude")));
            var first = query(l.db, UsageRow.TYPEDEF).eq(UsageRow_A.$key, "msg_011CgW4jtFxD7dju7dTgyPXR|req_011CgW4jsP7SwRC2G8bUNiCS").firstOrNull();
            assertEquals(536, first.outputTokens());
            assertEquals("63", first.prp());
            assertEquals(4, query(l.db, UsageRow.TYPEDEF).count());
            assertEquals(4, query(l.db, ActiveMinute.TYPEDEF).count());

            var again = new TranscriptScanner(l).scan(List.of(tmp.resolve("claude")));
            assertEquals(1, again.get("filesUnchanged"));
            assertEquals(4, query(l.db, UsageRow.TYPEDEF).count());
        }
    }

    @Test
    void aHalfWrittenLastLineWaitsForItsNewline() throws Exception {
        var f = transcript();
        var extra = "{\"isSidechain\":false,\"message\":{\"model\":\"claude-sonnet-5-5\",\"id\":\"msg_011CgW8ddddddddddddddddddd\",\"usage\":{\"input_tokens\":4,\"output_tokens\":9}},\"requestId\":\"req_d\",\"timestamp\":\"2026-10-29T08:00:00.000Z\",\"sessionId\":\"s1\",\"cwd\":\"C:\\\\proj\\\\demo\"}";
        try (var l = new Ledger(tmp.resolve("ledger"))) {
            new TranscriptScanner(l).scan(List.of(tmp.resolve("claude")));
            Files.writeString(f, extra, StandardOpenOption.APPEND);
            new TranscriptScanner(l).scan(List.of(tmp.resolve("claude")));
            assertEquals(4, query(l.db, UsageRow.TYPEDEF).count());
            Files.writeString(f, "\n", StandardOpenOption.APPEND);
            new TranscriptScanner(l).scan(List.of(tmp.resolve("claude")));
            assertEquals(5, query(l.db, UsageRow.TYPEDEF).count());
        }
    }

    @Test
    void rowsGetTheAccountInForceAtTheirTime() throws Exception {
        transcript();
        try (var l = new Ledger(tmp.resolve("ledger"))) {
            var t = java.time.Instant.parse("2026-10-29T06:00:00Z").toEpochMilli();
            l.db.transaction(() -> new SwitchEvent().key(t + "|AAA").ts(t).fromAccount("ZZZ").toAccount("AAA")
                .in(l.db).whereEq(SwitchEvent_A.$key, t + "|AAA").upsert());
            new TranscriptScanner(l).scan(List.of(tmp.resolve("claude")));
            assertEquals(4, query(l.db, UsageRow.TYPEDEF).eq(UsageRow_A.$account, "AAA").count());
        }
    }
}
