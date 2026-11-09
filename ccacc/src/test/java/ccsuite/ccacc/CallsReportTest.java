package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** What a run cost, from the proxy's call log; the gauge from the traffic; the chat reuse setting. */
class CallsReportTest {

    static final List<String> LINES = List.of(
        "{\"ts\":\"2026-11-06T12:00:00Z\",\"job\":\"A\",\"account\":\"F1\",\"model\":\"claude-sonnet-5-5\",\"session\":\"ab12cd34\",\"mode\":\"new\",\"why\":\"first call of the session\",\"turn\":1,\"promptChars\":63000,\"replyChars\":500,\"toolCalls\":1,\"ms\":9000,\"ok\":true,\"gauge5h\":10,\"gauge7d\":2}",
        "{\"ts\":\"2026-11-06T12:01:00Z\",\"job\":\"A\",\"account\":\"F1\",\"model\":\"claude-sonnet-5-5\",\"session\":\"ab12cd34\",\"mode\":\"continued\",\"turn\":2,\"promptChars\":500,\"replyChars\":400,\"toolCalls\":0,\"ms\":3000,\"ok\":true,\"gauge5h\":11,\"gauge7d\":2}",
        "{\"ts\":\"2026-11-06T12:02:00Z\",\"job\":\"A\",\"account\":\"F1\",\"model\":\"claude-sonnet-5-5\",\"session\":\"ab12cd34\",\"mode\":\"continued\",\"turn\":3,\"promptChars\":600,\"replyChars\":300,\"toolCalls\":0,\"ms\":5000,\"ok\":false,\"failure\":\"RATE_LIMITED\"}",
        "{\"ts\":\"2026-11-06T12:03:00Z\",\"job\":\"B\",\"account\":\"F2\",\"model\":\"claude-sonnet-5-5\",\"session\":\"ee99ff00\",\"mode\":\"fresh\",\"why\":\"message 12 differs at char 480\",\"turn\":1,\"promptChars\":64000,\"replyChars\":450,\"toolCalls\":1,\"ms\":8000,\"ok\":true,\"gauge5h\":20,\"gauge7d\":3}",
        "not json at all");

    @Test void aRunIsSummarisedPerJob() {
        var groups = CallsReport.groups(CallsReport.parse(LINES), "job");
        assertEquals(2, groups.size(), "the torn line is skipped");
        var a = groups.get(0);
        assertEquals("A", a.key());
        assertEquals(3, a.calls());
        assertEquals(2, a.ok());
        assertEquals(2, a.continued());
        assertEquals(64100, a.promptChars());
        assertEquals(5000, a.medianMs());
        assertEquals(17000, a.totalMs());
        assertArrayEquals(new int[]{10, 11}, a.gauge5h().get("F1"), "the points the run cost: first to last reading");
        var b = groups.get(1);
        assertEquals(1, b.fresh());
        assertEquals(0, b.continued());
    }

    @Test void theSelectionIsByJobAndTime() {
        var all = CallsReport.parse(LINES);
        assertEquals(3, CallsReport.filter(all, "A", null).size());
        assertEquals(2, CallsReport.filter(all, null, Instant.parse("2026-11-06T12:02:00Z")).size());
        assertEquals(Duration.ofMinutes(30), CallsReport.since("30m"));
        assertEquals(Duration.ofHours(2), CallsReport.since("2h"));
        assertThrows(IllegalArgumentException.class, () -> CallsReport.since("soon"));
    }

    @Test void theTextAndJsonSayTheCost() throws Exception {
        var groups = CallsReport.groups(CallsReport.parse(LINES), "job");
        var text = String.join("\n", CallsReport.text(groups, "job"));
        assertTrue(text.contains("job A:  3 calls, 2 ok, 1 failed;  continued 2"), text);
        assertTrue(text.contains("F1  5h gauge 10% -> 11%  (1 points)"), text);
        assertTrue(text.contains("new chats because: first call of the session x1"), text);
        assertTrue(text.contains("message N differs at char N x1"), "the numbers are blanked so alike reasons add up: " + text);
        var json = CallsReport.JSON.readTree(CallsReport.json(groups));
        assertEquals(1, json.get(0).path("accounts").path("F1").path("points").asInt());
        assertEquals(2, CallsReport.groups(CallsReport.parse(LINES), "account").size());
    }

    @Test void theCommandReadsAFileAndRejectsBadOptions(@TempDir Path dir) throws Exception {
        var file = dir.resolve("calls.jsonl");
        Files.write(file, LINES);
        assertEquals(0, CallsCli.run(new String[]{"--file", file.toString(), "--job", "A"}));
        assertEquals(1, CallsCli.run(new String[]{"--by", "colour"}));
        assertEquals(1, CallsCli.run(new String[]{"--since"}));
        assertEquals(0, CallsCli.run(new String[]{"--file", dir.resolve("none.jsonl").toString()}), "no log yet is not an error");
    }

    @Test void aGaugeFromTrafficIsAReadingWithoutAProbe() {
        var probes = new int[1];
        var gauges = new FreeGauges(() -> List.of(FreeLaneTest.account("F1", 0, true, false)), (c, m) -> { probes[0]++; return null; }, Path.of("target/observe-test"));
        gauges.observe("F1", 0.12, 0.03, 1793937600L);
        var row = gauges.snapshot().get(0);
        assertEquals(0.12, row.five());
        assertEquals("within_limit", row.status());
        assertTrue(row.ok());
        gauges.observe("F1", 0.13, null, null);
        assertEquals(0.03, gauges.snapshot().get(0).seven(), "the other window keeps its last reading");
        assertEquals(0, probes[0]);
    }

    @Test void chatReuseIsOffOnOrFreshAndAnOldBooleanStillReads() {
        var l = new LaneSettings();
        assertEquals("on", l.chatReuseMode(), "on by default");
        assertEquals("on", l.chatReuse("off").chatReuseMode(), "an old stored value the user never chose does not count");
        l.chatReuseExplicit(true);
        assertEquals("off", l.chatReuseMode());
        assertEquals("on", l.chatReuse("on").chatReuseMode());
        assertEquals("fresh", l.chatReuse("FRESH").chatReuseMode());
        assertEquals("on", l.chatReuse("true").chatReuseMode());
        assertEquals("off", l.chatReuse("false").chatReuseMode());
    }

    @Test void thePaidPinIsSetByCommandAndOffClearsIt(@TempDir Path dir) {
        var store = new CcaccSettingsStore(dir.resolve("settings.json"));
        var c = new LaneCommands(store, null);
        assertNull(store.load().lanes().paidPinOrNull());
        assertTrue(c.run(List.of("lanes", "set", "paidPin", "P1")).ok());
        assertEquals("P1", store.load().lanes().paidPinOrNull());
        assertTrue(c.run(List.of("lanes", "set", "paidPin", "off")).ok());
        assertNull(store.load().lanes().paidPinOrNull());
    }

    @Test void theTwoLogsAreOneReportInTimeOrder() {
        var free = "{\"ts\":\"2026-11-06T12:00:05Z\",\"job\":\"C\",\"account\":\"F1\",\"mode\":\"new\",\"ms\":9000,\"ok\":true}";
        var paid = "{\"ts\":\"2026-11-06T12:00:01Z\",\"job\":\"C\",\"account\":\"P1\",\"mode\":\"paid\",\"ms\":800,\"ok\":true,\"status\":200}";
        var sorted = CallsReport.sorted(CallsReport.parse(List.of(free, paid)));
        assertEquals("P1", sorted.get(0).path("account").asText(), "the paid call came first");
        assertEquals(2, CallsReport.groups(sorted, "account").size());
        assertEquals(1, CallsReport.groups(sorted, "job").size());
    }

    // ---- PRP 78 R6: what the monitor reads, and nothing it reads changes anything

    static String recent(int minutesAgo, String account, boolean ok, long ms, String extra) {
        return "{\"ts\":\"" + Instant.now().minus(Duration.ofMinutes(minutesAgo)) + "\",\"job\":null,\"account\":\"" + account + "\",\"model\":\"claude-sonnet-5-5\",\"session\":\"ab12cd34\",\"mode\":\"new\",\"promptChars\":63000,\"replyChars\":500,\"ms\":" + ms + ",\"ok\":" + ok + extra + "}";
    }

    @Test void failedSlowAndOpenCallsAreListedForTheMonitor() throws Exception {
        var calls = CallsReport.parse(List.of(recent(10, "F1", true, 4_000, ""), recent(8, "F2", false, 5_000, ",\"failure\":\"RATE_LIMITED\",\"error\":\"Rate limited\""), recent(5, "F3", true, 90_000, "")));
        var a = CallsReport.attention(calls, 60_000);
        assertEquals(1, a.failed().size());
        assertEquals(1, a.slow().size());
        var text = String.join(" ", CallsReport.attentionText(a, Optional.of(List.of("open 412 s F4 claude-sonnet-5-5 63000 chars"))));
        assertTrue(text.contains("failed (1)") && text.contains("F2") && text.contains("RATE_LIMITED"), text);
        assertTrue(text.contains("slow (1,") && text.contains("F3") && text.contains("90 s"), text);
        assertTrue(text.contains("still open (1)") && text.contains("412 s"), text);
        assertTrue(String.join(" ", CallsReport.attentionText(a, Optional.empty())).contains("open calls: unknown"), "no app answering is said, not guessed");
        var json = CallsReport.attentionJson(a, Optional.of(List.of("open 412 s F4 m 1 chars")));
        assertEquals(1, CallsReport.JSON.readTree(json).path("failed").size());
        assertEquals(1, CallsReport.JSON.readTree(json).path("open").size());
    }

    @Test void unparsedToolCallsAreCountedInTheProblemsReport() throws Exception {
        var calls = CallsReport.parse(List.of(recent(10, "F1", true, 4_000, ""), recent(8, "F2", true, 5_000, ",\"unparsed\":2,\"repaired\":1,\"retried\":2"), recent(5, "F3", true, 6_000, ",\"unparsed\":1,\"retried\":2")));
        var a = CallsReport.attention(calls, 60_000);
        assertEquals(2, a.unparsed().size());
        var text = String.join(" ", CallsReport.attentionText(a, Optional.empty()));
        assertTrue(text.contains("unparsed tool calls (3 blocks in 2 calls)") && text.contains("F2") && text.contains("2 unread, 1 repaired"), text);
        assertEquals(2, CallsReport.JSON.readTree(CallsReport.attentionJson(a, Optional.empty())).path("unparsed").size());
    }

    @Test void theCallsCommandWithSinceShowsWhatNeedsAttentionAndWritesNothing(@TempDir Path dir) throws Exception {
        var file = dir.resolve("calls.jsonl");
        Files.write(file, List.of(recent(40, "F0", false, 1000, ",\"failure\":\"OLD\""), recent(10, "F1", true, 4_000, ""), recent(8, "F2", false, 5_000, ",\"failure\":\"RATE_LIMITED\"")));
        var before = Files.readAllBytes(file);
        var out = new java.io.ByteArrayOutputStream();
        var old = System.out;
        System.setOut(new java.io.PrintStream(out, true, StandardCharsets.UTF_8));
        int code;
        try { code = CallsCli.run(new String[]{"--file", file.toString(), "--since", "15m"}, () -> Optional.of(List.of("open 400 s F9 claude-sonnet-5-5 1000 chars"))); } finally { System.setOut(old); }
        var text = out.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(text.contains("failed (1)") && text.contains("RATE_LIMITED") && !text.contains("OLD"), "only the last 15 minutes: " + text);
        assertTrue(text.contains("still open (1)"), text);
        assertArrayEquals(before, Files.readAllBytes(file), "reading the calls changes nothing");
    }

    @Test void theSettingCommandValidatesChatReuse(@TempDir Path dir) {
        var store = new CcaccSettingsStore(dir.resolve("settings.json"));
        var c = new LaneCommands(store, null);
        assertEquals("on", store.load().lanes().chatReuseMode(), "on until the user chooses");
        assertTrue(c.run(List.of("lanes", "set", "chatReuse", "fresh")).ok());
        assertEquals("fresh", store.load().lanes().chatReuseMode());
        assertTrue(c.run(List.of("lanes", "set", "chatReuse", "off")).ok());
        assertEquals("off", store.load().lanes().chatReuseMode(), "a choice of off is kept");
        assertFalse(c.run(List.of("lanes", "set", "chatReuse", "sometimes")).ok());
    }
}
