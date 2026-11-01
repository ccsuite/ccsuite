package ccsuite.cclocalusagestats.db;

import static datapotter.arcadedbhelper.Query.query;
import static org.junit.jupiter.api.Assertions.*;

import ccsuite.cclocalusagestats.ingest.TranscriptScanner;
import ccsuite.cclocalusagestats.model.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class BackupTest {

    @TempDir
    Path tmp;

    @Test
    void anExportRestoresAnEmptyLedgerExactly() throws Exception {
        var f = tmp.resolve("claude/projects/p1/s1.jsonl");
        Files.createDirectories(f.getParent());
        Files.copy(Path.of(getClass().getResource("/transcript-sample.jsonl").toURI()), f);
        try (var a = new Ledger(tmp.resolve("a"))) {
            new TranscriptScanner(a).scan(List.of(tmp.resolve("claude")));
            var counts = Backup.export(a.db, tmp.resolve("bk"));
            assertEquals(4L, counts.get("UsageRow-2026-10"));
            assertTrue(Backup.lastExport(tmp.resolve("bk")).isPresent());
        }
        try (var b = new Ledger(tmp.resolve("b"))) {
            assertEquals(4L, Backup.importFrom(b.db, tmp.resolve("bk")).get("UsageRow"));
            assertEquals(4, query(b.db, UsageRow.TYPEDEF).count());
            assertEquals(4, query(b.db, ActiveMinute.TYPEDEF).count());
            assertEquals(1, query(b.db, FileState.TYPEDEF).count());
            var row = query(b.db, UsageRow.TYPEDEF).eq(UsageRow_A.$requestId, "req_011CgW4jsP7SwRC2G8bUNiCS").firstOrNull();
            assertEquals(536, row.outputTokens());
            assertEquals("63", row.prp());
            assertTrue(Backup.importFrom(b.db, tmp.resolve("bk")).isEmpty());
        }
    }

    @Test
    void aDeletedMonthDisappearsFromTheNextExport() throws Exception {
        var f = tmp.resolve("claude/projects/p1/s1.jsonl");
        Files.createDirectories(f.getParent());
        Files.copy(Path.of(getClass().getResource("/transcript-sample.jsonl").toURI()), f);
        try (var a = new Ledger(tmp.resolve("a"))) {
            new TranscriptScanner(a).scan(List.of(tmp.resolve("claude")));
            Backup.export(a.db, tmp.resolve("bk"));
            assertTrue(Files.exists(tmp.resolve("bk/UsageRow-2026-10.jsonl")));
            DeletePlan.everything().run(a.db);
            Backup.export(a.db, tmp.resolve("bk"));
            assertFalse(Files.exists(tmp.resolve("bk/UsageRow-2026-10.jsonl")));
        }
    }
}
