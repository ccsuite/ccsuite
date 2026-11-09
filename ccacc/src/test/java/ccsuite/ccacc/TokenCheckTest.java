package ccsuite.ccacc;

import module java.base;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** PRP 85, Part B: the measurement of the free estimate against the paid count, with a fake count API (nothing paid is called) and fake texts. */
class TokenCheckTest {
    static final ObjectMapper JSON = new ObjectMapper();
    HttpServer fake;
    final AtomicInteger calls = new AtomicInteger();
    Path repo, store, session;

    @BeforeEach void setUp() throws Exception {
        repo = Files.createTempDirectory("tc-repo");
        for (var f : List.of("webproxy/src/main/java/ccsuite/webproxy/TokenEstimate.java", "webproxy/src/main/java/ccsuite/webproxy/ChatSessions.java",
                "webproxy/src/main/java/ccsuite/webproxy/CCAPIProxy.java", "ccacc/src/main/java/ccsuite/ccacc/FreeEstimator.java")) {
            Files.createDirectories(repo.resolve(f).getParent());
            Files.writeString(repo.resolve(f), "class X { int field = 1; }\n".repeat(400));
        }
        Files.writeString(repo.resolve("README.md"), "A few words of prose about a thing. ".repeat(300));
        store = Files.createTempDirectory("tc-store");
        session = repo.resolve("session.jsonl");
        Files.write(session, List.of(
            "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"<system-reminder>SECRET reminder</system-reminder>\"},{\"type\":\"text\",\"text\":\"mail me at someone@real-example.org about 0123456789abcdef0123456789abcdef\"}]}}",
            "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"hmm\"},{\"type\":\"tool_use\",\"id\":\"toolu_REAL1\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}}",
            "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_REAL1\",\"content\":\"a.txt b.txt\"},{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_UNKNOWN\",\"content\":\"stray\"}]}}",
            "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Two files.\"},{\"type\":\"tool_use\",\"id\":\"toolu_REAL2\",\"name\":\"Bash\",\"input\":{\"command\":\"pwd\"}}]}}"));
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/v1/messages/count_tokens", ex -> {
            calls.incrementAndGet();
            var body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var reply = ("{\"input_tokens\":" + (body.length() / 3 + 7) + "}").getBytes(StandardCharsets.UTF_8);   // a fake tokenizer: three characters a token, seven of overhead
            ex.sendResponseHeaders(200, reply.length);
            ex.getResponseBody().write(reply);
            ex.close();
        });
        fake.start();
    }

    @AfterEach void tearDown() { fake.stop(0); }

    int run(String... extra) {
        var args = new ArrayList<>(List.of("--repo", repo.toString(), "--store", store.toString(), "--port", String.valueOf(fake.getAddress().getPort()), "--transcripts", session.toString()));
        args.addAll(List.of(extra));
        return TokenCheck.run(args.toArray(String[]::new));
    }

    @Test void aDryRunListsTheTextsAndCallsNothing() {
        assertEquals(0, run("--dry-run"));
        assertEquals(0, calls.get());
        assertFalse(Files.exists(store.resolve("token-check.jsonl")));
    }

    @Test void everyTextGetsALineWithItsEstimateItsPaidCountAndTheRatio() throws Exception {
        assertEquals(0, run());
        var lines = Files.readAllLines(store.resolve("token-check.jsonl")).stream().map(l -> { try { return JSON.readTree(l); } catch (IOException e) { throw new UncheckedIOException(e); } }).toList();
        var measured = lines.stream().filter(l -> !l.path("kind").asText().equals("baseline")).toList();
        assertEquals(1 + 4 + 2 + 1 + 1, lines.size(), "a baseline, four Java files, two JSON texts, the prose and the transcript");
        assertTrue(measured.stream().map(l -> l.path("kind").asText()).collect(Collectors.toSet()).containsAll(Set.of("java", "json", "prose", "transcript")));
        var java = measured.stream().filter(l -> l.path("name").asText().equals("ChatSessions.java")).findFirst().orElseThrow();
        assertEquals(java.path("chars").asInt(), "class X { int field = 1; }\n".length() * 400);
        assertEquals((int) Math.ceil(java.path("chars").asInt() / 2.4), java.path("estimate").asInt());
        assertEquals(java.path("paid").asInt() - java.path("overhead").asInt(), java.path("net").asInt());
        assertEquals(java.path("chars").asDouble() / java.path("net").asDouble(), java.path("ratio").asDouble(), 0.001);
        assertEquals(lines.size(), calls.get(), "one paid call per line: nothing counted twice");
    }

    @Test void actualSendsOneRealMessagePerKindAndRecordsTheServersOwnFigureNextToTheCount() throws Exception {
        var real = new AtomicInteger();
        var systems = new CopyOnWriteArrayList<Boolean>();
        fake.createContext("/v1/messages", ex -> {
            if (ex.getRequestURI().getPath().endsWith("count_tokens")) { ex.sendResponseHeaders(200, -1); ex.close(); return; }
            real.incrementAndGet();
            var body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            systems.add(body.contains("You are Claude Code") && body.contains("\"max_tokens\":1"));
            var reply = ("{\"usage\":{\"input_tokens\":" + (body.length() / 3 + 8) + ",\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}").getBytes(StandardCharsets.UTF_8);   // one more than the fake count
            ex.sendResponseHeaders(200, reply.length);
            ex.getResponseBody().write(reply);
            ex.close();
        });
        assertEquals(0, run("--actual"));
        assertEquals(1 + 4, real.get(), "the baseline and the smallest text of each of the four kinds, nothing else");
        assertTrue(systems.stream().allMatch(b -> b), "every real call carries the Claude Code system block and max_tokens 1");
        var rows = Files.readAllLines(store.resolve("token-check.jsonl")).stream().map(l -> { try { return JSON.readTree(l); } catch (IOException e) { throw new UncheckedIOException(e); } }).filter(l -> l.hasNonNull("actual")).toList();
        assertEquals(1 + 4, rows.size());
        for (var r : rows) assertEquals(r.path("paid").asInt() - r.path("actual").asInt(), r.path("countError").asInt());
        assertTrue(TokenCheckSummary.text(TokenCheckSummary.measurements(Files.readAllLines(store.resolve("token-check.jsonl")))).stream().anyMatch(l -> l.contains("differed from the real figure")));
    }

    @Test void withoutActualNoRealMessageIsSent() {
        var real = new AtomicInteger();
        fake.createContext("/v1/messages", ex -> { real.incrementAndGet(); ex.sendResponseHeaders(500, -1); ex.close(); });
        assertEquals(0, run());
        assertEquals(0, real.get(), "R6: a plain measurement only counts");
    }

    @Test void theGraphIsBuiltFromTheLogAndSaysWhichTypesAreAboveOrBelow() throws Exception {
        assertEquals(0, run());
        var html = Files.readString(store.resolve("token-check.html"));
        assertFalse(html.contains("/*DATA*/null"), "the data is embedded");
        assertTrue(html.contains("\"kinds\"") && html.contains("\"verdict\"") && html.contains("ChatSessions.java"));
        var rows = TokenCheckSummary.measurements(Files.readAllLines(store.resolve("token-check.jsonl")));
        var s = TokenCheckSummary.of(rows);
        for (var k : s.path("kinds")) assertTrue(k.path("side").asText().equals("above") || k.path("side").asText().equals("below"));
        assertTrue(s.path("verdict").asText().contains("not enough runs yet"), "one run cannot show drift over time");
        run();   // a second run: the same texts again
        var again = TokenCheckSummary.of(TokenCheckSummary.measurements(Files.readAllLines(store.resolve("token-check.jsonl"))));
        assertTrue(again.path("repeated").asBoolean());
        assertTrue(again.path("verdict").asText().contains("Stable over time"), again.path("verdict").asText());
    }

    @Test void aRatioBelowTheEstimatesIsMarkedBelowAndAboveIsMarkedAbove() throws Exception {
        var low = JSON.readTree("{\"kind\":\"json\",\"name\":\"a\",\"run\":\"r1\",\"chars\":200,\"net\":100,\"ratio\":2.0}");   // 2.0 characters per token: more tokens than the estimate reports
        var high = JSON.readTree("{\"kind\":\"prose\",\"name\":\"b\",\"run\":\"r1\",\"chars\":300,\"net\":100,\"ratio\":3.0}");
        var s = TokenCheckSummary.of(List.of(low, high));
        assertEquals("below", s.path("kinds").get(0).path("side").asText());
        assertEquals("above", s.path("kinds").get(1).path("side").asText());
        assertTrue(s.path("verdict").asText().startsWith("Diverges by type"));
    }

    @Test void aTranscriptIsCleanedOfRemindersAddressesAndIdsAndIsAValidConversation() throws Exception {
        var t = TokenCheckTexts.transcript("t", session);
        var text = t.messages().toString();
        assertFalse(text.contains("SECRET") || text.contains("real-example") || text.contains("0123456789abcdef") || text.contains("REAL1") || text.contains("thinking"));
        assertTrue(text.contains("user@example.org"));
        assertEquals("user", t.messages().get(0).path("role").asText());
        var last = t.messages().get(t.messages().size() - 1);
        assertEquals("user", last.path("role").asText(), "the last assistant message waited for a result, so it is dropped");
        assertFalse(text.contains("stray"), "a result that answers no call is dropped");
        assertTrue(t.chars() > 0);
    }

    @Test void aRefusedPaidCountIsReportedAndTheRunFails() throws Exception {
        fake.removeContext("/v1/messages/count_tokens");
        fake.createContext("/v1/messages/count_tokens", ex -> { ex.sendResponseHeaders(403, -1); ex.close(); });
        assertEquals(1, run(), "the baseline cannot be counted: the run says so and exits with an error");
    }
}
