package ccsuite.cclocalusagestats.ingest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;

class LineParserTest {

    static List<String> lines() throws Exception {
        return Files.readAllLines(Path.of(LineParserTest.class.getResource("/transcript-sample.jsonl").toURI()));
    }

    @Test
    void usageLineKeepsEveryBucketAndTheContext() throws Exception {
        var r = LineParser.parse(lines().get(2));
        assertEquals("msg_011CgW4jtFxD7dju7dTgyPXR|req_011CgW4jsP7SwRC2G8bUNiCS", r.key());
        assertEquals(2, r.inputTokens());
        assertEquals(536, r.outputTokens());
        assertEquals(336, r.thinkingTokens());
        assertEquals(123802, r.cacheWriteTotal());
        assertEquals(123802, r.cacheWrite1h());
        assertEquals(0, r.cacheWrite5m());
        assertEquals(5618, r.cacheRead());
        assertEquals(1, r.webSearches());
        assertEquals("claude-sonnet-5-5", r.model());
        assertEquals("C:\\proj\\demo", r.cwd());
        assertEquals("s1", r.sessionId());
        assertEquals("standard", r.speed());
    }

    @Test
    void requestlessLineIsKeyedBySession() throws Exception {
        assertEquals("msg_011CgW5aaaaaaaaaaaaaaaaaaa|s1", LineParser.parse(lines().get(3)).key());
    }

    @Test
    void emptyUsageHasNoKeyButStillCountsAsActivity() throws Exception {
        var r = LineParser.parse(lines().get(4));
        assertNull(r.key());
        assertNotNull(r.ts());
    }

    @Test
    void anUnexpectedNullDoesNotDropTheLine() throws Exception {
        assertEquals("msg_011CgW7ccccccccccccccccccc|req_later", LineParser.parse(lines().get(6)).key());
    }

    @Test
    void theOpenerNamesThePrp() throws Exception {
        var r = LineParser.parse(lines().get(0));
        assertNull(r.key());
        assertEquals("63", r.prp());
    }

    @Test
    void garbageIsSkipped() {
        assertNull(LineParser.parse("not json"));
        assertNull(LineParser.parse("{\"type\":\"summary\"}"));
    }
}
