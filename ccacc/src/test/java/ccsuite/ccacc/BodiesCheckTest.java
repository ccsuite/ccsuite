package ccsuite.ccacc;

import module java.base;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** PRP 87 R4: the comparison of the estimate with the real count, per call, from the request log. Neutral fixtures, a fake counter. */
class BodiesCheckTest {

    static final Instant T = Instant.parse("2026-11-08T10:00:00Z");

    static void fill(Path dir) throws Exception {
        var log = new BodyLog(dir, 14);
        for (var s : List.of("s1", "s2")) for (var n = 1; n <= 5; n += 2) log.record(BodyLogTest.body(s, "sys", 2, BodyLogTest.thread(n)), T.plusSeconds(n), "A", 100 * n, "work", null);
    }

    @Test void everyCallOfTheSessionIsCountedAndPrintedWithEstimateRealAndError(@TempDir Path dir) throws Exception {
        fill(dir);
        var asked = new ArrayList<ObjectNode>();
        var lines = BodiesCheck.report(dir, "s1", body -> { asked.add(body); return 1000; });
        assertEquals(3, asked.size());
        assertTrue(lines.stream().filter(l -> l.contains("real 1000")).count() == 3, lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("estimate") && l.contains("%")), lines.toString());
        assertTrue(lines.get(lines.size() - 1).contains("3 calls"), lines.toString());
    }

    @Test void theRequestSentToTheCounterHoldsOnlyWhatCountTokensTakes(@TempDir Path dir) throws Exception {
        fill(dir);
        var asked = new ArrayList<ObjectNode>();
        BodiesCheck.report(dir, "s1", body -> { asked.add(body); return 1; });
        var b = asked.get(2);
        assertTrue(b.has("model") && b.has("messages") && b.has("system") && b.has("tools"));
        assertFalse(b.has("max_tokens") || b.has("stream") || b.has("metadata"), b.fieldNames().toString());
        assertEquals(5, b.path("messages").size());
    }

    /** A real request (2026-11-08): Claude Code puts {@code output_config} on a message, and count_tokens answers 400 "messages.1.output_config: Extra inputs are not permitted". */
    @Test void aFieldCountTokensRefusesOnAMessageIsLeftOutAndTheLoggedRequestIsNotChanged() throws Exception {
        var request = BodyLog.J.readTree("""
            {"model":"m","system":"s","messages":[{"role":"user","content":"a"},{"role":"system","content":[{"type":"text","text":"b"}],"output_config":{"effort":"high"}}]}""");
        var sent = BodiesCheck.countable(request);
        assertFalse(sent.path("messages").get(1).has("output_config"), sent.toString());
        assertEquals("b", sent.path("messages").get(1).path("content").get(0).path("text").asText());
        assertEquals("system", sent.path("messages").get(1).path("role").asText());
        assertTrue(request.path("messages").get(1).has("output_config"), "the logged request itself is left as it was");
    }

    @Test void withoutASessionEveryLoggedSessionIsChecked(@TempDir Path dir) throws Exception {
        fill(dir);
        var count = new int[1];
        var lines = BodiesCheck.report(dir, null, body -> { count[0]++; return 500; });
        assertEquals(6, count[0]);
        assertTrue(lines.stream().anyMatch(l -> l.contains("session s1")) && lines.stream().anyMatch(l -> l.contains("session s2")), lines.toString());
    }

    @Test void aCallThatCannotBeCountedIsReportedAndTheRestGoOn(@TempDir Path dir) throws Exception {
        fill(dir);
        var n = new int[1];
        var lines = BodiesCheck.report(dir, "s1", body -> { if (n[0]++ == 1) throw new UncheckedIOException(new IOException("HTTP 429")); return 1000; });
        assertTrue(lines.stream().anyMatch(l -> l.contains("HTTP 429")), lines.toString());
        assertEquals(2, lines.stream().filter(l -> l.contains("real 1000")).count());
    }

    @Test void anEmptyLogSaysSo(@TempDir Path dir) throws Exception {
        var lines = BodiesCheck.report(dir, null, body -> 1);
        assertTrue(lines.get(0).contains("no calls"), lines.toString());
    }
}
