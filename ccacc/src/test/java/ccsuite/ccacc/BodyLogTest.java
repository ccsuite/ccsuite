package ccsuite.ccacc;

import module java.base;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** PRP 87 R1-R3: the free lane's request log: one line per call, only the new messages after the first call of a session, system and tools by hash, reset on a rewritten history. Neutral fixtures. */
class BodyLogTest {

    static final ObjectMapper J = new ObjectMapper();
    static final Instant T = Instant.parse("2026-11-08T10:00:00Z");

    static ObjectNode msg(String role, String text) { return J.createObjectNode().put("role", role).put("content", text); }

    static byte[] body(String session, String system, int tools, List<ObjectNode> messages) throws Exception {
        var b = J.createObjectNode().put("model", "claude-sonnet-5-5").put("max_tokens", 1000).put("stream", true);
        b.putObject("metadata").put("user_id", J.createObjectNode().put("session_id", session).toString());
        b.putArray("system").addObject().put("type", "text").put("text", system);
        var t = b.putArray("tools");
        for (var i = 0; i < tools; i++) t.addObject().put("name", "tool" + i).putObject("input_schema").put("type", "object");
        b.putArray("messages").addAll(messages);
        return J.writeValueAsBytes(b);
    }

    static List<ObjectNode> thread(int n) {
        var l = new ArrayList<ObjectNode>();
        for (var i = 0; i < n; i++) l.add(msg(i % 2 == 0 ? "user" : "assistant", "message number " + i));
        return l;
    }

    static List<JsonNode> lines(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            var out = new ArrayList<JsonNode>();
            for (var f : s.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) for (var l : Files.readAllLines(f)) out.add(J.readTree(l));
            return out;
        }
    }

    static List<JsonNode> calls(Path dir) throws Exception { return lines(dir).stream().filter(l -> l.path("type").asText().equals("call")).toList(); }

    static long defs(Path dir, String kind) throws Exception { return lines(dir).stream().filter(l -> l.path("type").asText().equals("def") && l.path("kind").asText().equals(kind)).count(); }

    @Test void everyCallWritesOneMetadataLineInTheFileOfItsDay(@TempDir Path dir) throws Exception {
        var log = new BodyLog(dir, 14);
        log.record(body("s1", "sys", 2, thread(1)), T, "A", 1234, "work", null);
        log.record(body("s1", "sys", 2, thread(3)), T.plusSeconds(5), "B", 2345, "warn", "a note");
        assertTrue(Files.exists(dir.resolve("bodies-2026-11-08.jsonl")));
        var c = calls(dir);
        assertEquals(2, c.size());
        assertEquals("s1", c.get(1).path("session").asText());
        assertEquals("B", c.get(1).path("account").asText());
        assertEquals("claude-sonnet-5-5", c.get(1).path("model").asText());
        assertEquals(2345, c.get(1).path("tokens").asLong());
        assertEquals("warn", c.get(1).path("zone").asText());
        assertEquals("a note", c.get(1).path("note").asText());
        assertTrue(c.get(1).path("chars").asLong() > 0);
        assertTrue(c.get(1).path("kept").asBoolean());
        assertEquals(T.plusSeconds(5).toString(), c.get(1).path("t").asText());
    }

    @Test void theFirstCallCarriesEverythingAndALaterCallOnlyItsNewMessages(@TempDir Path dir) throws Exception {
        var log = new BodyLog(dir, 14);
        log.record(body("s1", "sys", 2, thread(1)), T, "A", 1, "work", null);
        log.record(body("s1", "sys", 2, thread(3)), T.plusSeconds(1), "A", 1, "work", null);
        log.record(body("s1", "sys", 2, thread(4)), T.plusSeconds(2), "A", 1, "work", null);
        var c = calls(dir);
        assertEquals(1, c.get(0).path("messages").size());
        assertEquals(0, c.get(0).path("earlier").asInt());
        assertEquals(2, c.get(1).path("messages").size());
        assertEquals(1, c.get(1).path("earlier").asInt());
        assertFalse(c.get(1).path("reset").asBoolean());
        assertEquals(1, c.get(2).path("messages").size());
        assertEquals(3, c.get(2).path("earlier").asInt());
        assertFalse(c.get(2).path("earlierHash").asText().isEmpty());
    }

    @Test void systemAndToolsAreWrittenOnceAndAgainOnlyWhenTheyChange(@TempDir Path dir) throws Exception {
        var log = new BodyLog(dir, 14);
        log.record(body("s1", "sys", 2, thread(1)), T, "A", 1, "work", null);
        log.record(body("s1", "sys", 2, thread(3)), T.plusSeconds(1), "A", 1, "work", null);
        assertEquals(1, defs(dir, "system"));
        assertEquals(1, defs(dir, "tools"));
        log.record(body("s1", "sys changed", 2, thread(5)), T.plusSeconds(2), "A", 1, "work", null);
        assertEquals(2, defs(dir, "system"));
        assertEquals(1, defs(dir, "tools"));
        log.record(body("s1", "sys changed", 3, thread(7)), T.plusSeconds(3), "A", 1, "work", null);
        assertEquals(2, defs(dir, "tools"));
    }

    @Test void aRewrittenHistoryWritesAResetWithTheWholeList(@TempDir Path dir) throws Exception {
        var log = new BodyLog(dir, 14);
        log.record(body("s1", "sys", 1, thread(5)), T, "A", 1, "work", null);
        var compacted = new ArrayList<ObjectNode>(List.of(msg("user", "a summary of everything so far")));
        compacted.addAll(thread(5).subList(3, 5));
        log.record(body("s1", "sys", 1, compacted), T.plusSeconds(1), "A", 1, "work", null);
        var c = calls(dir);
        assertTrue(c.get(1).path("reset").asBoolean());
        assertEquals(3, c.get(1).path("messages").size());
        assertEquals(0, c.get(1).path("earlier").asInt());
    }

    @Test void replayingTheLinesRebuildsTheExactBodyOfEveryCall(@TempDir Path dir) throws Exception {
        var log = new BodyLog(dir, 14);
        var sent = new ArrayList<byte[]>();
        var compacted = new ArrayList<ObjectNode>(List.of(msg("user", "a summary")));
        compacted.addAll(thread(4).subList(2, 4));
        for (var b : List.of(body("s1", "sys", 2, thread(1)), body("s1", "sys", 2, thread(3)), body("s1", "sys 2", 2, thread(5)), body("s1", "sys 2", 4, thread(7)), body("s1", "sys 2", 4, compacted))) sent.add(b);
        for (var i = 0; i < sent.size(); i++) log.record(sent.get(i), T.plusSeconds(i), "A", 1, "work", null);
        var replayed = BodyLog.replay(dir, "s1");
        assertEquals(sent.size(), replayed.size());
        for (var i = 0; i < sent.size(); i++) assertEquals(J.readTree(sent.get(i)), replayed.get(i).request(), "call " + i);
    }

    @Test void twoSessionsWrittenInTurnDoNotMix(@TempDir Path dir) throws Exception {
        var log = new BodyLog(dir, 14);
        log.record(body("s1", "sys", 1, thread(1)), T, "A", 1, "work", null);
        log.record(body("s2", "other", 1, thread(1)), T.plusSeconds(1), "A", 1, "work", null);
        log.record(body("s1", "sys", 1, thread(3)), T.plusSeconds(2), "A", 1, "work", null);
        assertEquals(2, BodyLog.replay(dir, "s1").size());
        assertEquals(1, BodyLog.replay(dir, "s2").size());
        assertEquals(J.readTree(body("s1", "sys", 1, thread(3))), BodyLog.replay(dir, "s1").get(1).request());
    }

    @Test void aNewDayStartsFromTheWholeRequestAndYesterdayIsCompressedAndOldOnesRemoved(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("bodies-2026-11-05.jsonl"), "{}\n");
        Files.writeString(dir.resolve("bodies-2026-10-01.jsonl.gz"), "x");
        var log = new BodyLog(dir, 14);
        log.record(body("s1", "sys", 1, thread(1)), T, "A", 1, "work", null);
        log.record(body("s1", "sys", 1, thread(3)), T.plusSeconds(1), "A", 1, "work", null);
        log.record(body("s1", "sys", 1, thread(5)), T.plus(Duration.ofDays(1)), "A", 1, "work", null);
        assertFalse(Files.exists(dir.resolve("bodies-2026-11-05.jsonl")));
        assertTrue(Files.exists(dir.resolve("bodies-2026-11-05.jsonl.gz")));
        assertTrue(Files.exists(dir.resolve("bodies-2026-11-08.jsonl.gz")), "the day before today is compressed");
        assertFalse(Files.exists(dir.resolve("bodies-2026-10-01.jsonl.gz")), "older than the retention");
        var today = J.readTree(Files.readAllLines(dir.resolve("bodies-2026-11-09.jsonl")).stream().filter(l -> l.contains("\"call\"")).findFirst().orElseThrow());
        assertEquals(5, today.path("messages").size(), "a new day's file stands alone");
        assertEquals(3, BodyLog.replay(dir, "s1").size(), "compressed days are read too");
    }

    @Test void aLogThatCannotBeWrittenNeverThrows(@TempDir Path dir) throws Exception {
        var notADir = Files.writeString(dir.resolve("file"), "x");
        var log = new BodyLog(notADir.resolve("inside"), 14);
        assertDoesNotThrow(() -> log.record(body("s1", "sys", 1, thread(1)), T, "A", 1, "work", null));
        assertDoesNotThrow(() -> log.record("not json".getBytes(StandardCharsets.UTF_8), T, "A", 1, "work", null));
    }

    @Test void concurrentCallsNeverInterleaveALine(@TempDir Path dir) throws Exception {
        var log = new BodyLog(dir, 14);
        var pool = Executors.newFixedThreadPool(8);
        var tasks = new ArrayList<Future<?>>();
        for (var s = 0; s < 8; s++) {
            var session = "c" + s;
            tasks.add(pool.submit(() -> { for (var i = 0; i < 20; i++) log.record(body(session, "sys", 1, thread(2 * i + 1)), T.plusSeconds(i), "A", 1, "work", null); return null; }));
        }
        for (var t : tasks) t.get();
        pool.shutdown();
        assertEquals(160, calls(dir).size());
        for (var s = 0; s < 8; s++) assertEquals(20, BodyLog.replay(dir, "c" + s).size());
    }
}
