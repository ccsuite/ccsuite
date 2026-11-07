package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** R1 (PRP 78): the ledger of the free lane is one JSON line per call, rotated by day, and read back as it was written. */
class FreeLedgerTest {

    static final Instant T = Instant.parse("2026-11-07T10:00:00Z");

    static FreeLedger.Call call(Instant t, String account, long chars, Double before5, Double after5) {
        return new FreeLedger.Call(t, account, "claude-sonnet-5-5", chars, chars / 4, "new", 200, 4200, before5, 0.02, 1794025200L, after5, after5 == null ? null : 0.03, after5 == null ? null : 1794025200L,
                15.4, before5 == null || after5 == null ? null : (after5 - before5) * 100, true, "ab12cd34");
    }

    @Test void aCallIsOneJsonLineAndComesBackAsWrittenAfterARestart(@TempDir Path dir) throws Exception {
        var written = call(T, "ACC1", 70_000, 0.40, 0.55);
        new FreeLedger(dir).append(written);
        var files = Files.list(dir).toList();
        assertEquals(1, files.size());
        assertEquals("calls-2026-11-07.jsonl", files.get(0).getFileName().toString());
        var lines = Files.readAllLines(files.get(0));
        assertEquals(1, lines.size(), "one line per call");
        var json = FreeLedger.JSON.readTree(lines.get(0));
        assertEquals("ACC1", json.path("account").asText());
        assertEquals(70_000, json.path("chars").asInt());
        assertEquals(17_500, json.path("tokens").asInt(), "characters over four");
        assertEquals(0.40, json.path("before5").asDouble(), 1e-12);
        assertEquals(0.55, json.path("after5").asDouble(), 1e-12);
        assertEquals(1794025200L, json.path("reset5After").asLong());
        assertEquals("new", json.path("cache").asText());
        var reopened = new FreeLedger(dir).calls(null);
        assertEquals(List.of(written), reopened, "the same call, read by a new ledger object as a restart would");
    }

    @Test void thePromptSizeTheProxyReportedIsKept(@TempDir Path dir) {
        var c = new FreeLedger.Call(T, "A", "m", 70_000, 17_500, "new", 200, 1, null, null, null, null, null, null, 15.4, null, true, "s", 61_000L);
        new FreeLedger(dir).append(c);
        assertEquals(61_000L, new FreeLedger(dir).calls(null).get(0).promptChars());
    }

    @Test void aCallWithNoReadingKeepsNullsAndFilesRotateByDay(@TempDir Path dir) {
        var ledger = new FreeLedger(dir);
        ledger.append(call(T, "A", 1_000, null, null));
        ledger.append(call(T.plus(Duration.ofDays(1)), "B", 2_000, 0.1, 0.2));
        assertEquals(List.of("calls-2026-11-07.jsonl", "calls-2026-11-08.jsonl"), Files.exists(dir) ? names(dir) : List.of());
        var all = new FreeLedger(dir).calls(null);
        assertEquals(List.of("A", "B"), all.stream().map(FreeLedger.Call::account).toList());
        assertNull(all.get(0).before5());
        assertNull(all.get(0).observed());
        assertEquals(List.of("B"), new FreeLedger(dir).calls(T.plus(Duration.ofHours(24))).stream().map(FreeLedger.Call::account).toList(), "since: the older day is not even read");
    }

    @Test void aTornLastLineIsSkippedAndCorrectionsHaveTheirOwnFile(@TempDir Path dir) throws Exception {
        var ledger = new FreeLedger(dir);
        ledger.append(call(T, "A", 1_000, 0.1, 0.2));
        Files.writeString(dir.resolve("calls-2026-11-07.jsonl"), "{\"t\":\"2026-11-07T10:", StandardOpenOption.APPEND);
        ledger.append(new FreeLedger.Correction(T, "A", "probe", 54.0, 58.0, 4.0, 3.0, 1794025200L));
        assertEquals(1, new FreeLedger(dir).calls(null).size());
        var c = new FreeLedger(dir).corrections(null);
        assertEquals(1, c.size());
        assertEquals(4.0, c.get(0).correction5(), 1e-12);
        assertTrue(names(dir).contains("corrections-2026-11-07.jsonl"));
    }

    static List<String> names(Path dir) {
        try (var s = Files.list(dir)) { return s.map(p -> p.getFileName().toString()).sorted().toList(); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
