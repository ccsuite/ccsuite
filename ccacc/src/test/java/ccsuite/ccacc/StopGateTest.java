package ccsuite.ccacc;

import module java.base;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PRP 86 R2: the Stop hook's decision. Blocks the stop while a close order is open and the cycle is not closed; fails open otherwise. Neutral fixtures. */
class StopGateTest {

    static final String S = "sess-1";

    static String decision(String at, String kind, String session) { return at + " " + kind + " tokens=260000 warn=200000 close=250000 hard=340000 room=100 account=AAA session=" + session; }

    static String toolUse(String at, String command) { return "{\"timestamp\":\"" + at + "\",\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"" + command + "\"}}]}}"; }

    static String text(String at, String words) { return "{\"timestamp\":\"" + at + "\",\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"" + words + "\"}]}}"; }

    @Test void noDecisionForTheSessionLetsItStop() {
        assertNull(StopGate.reason(S, List.of(decision("2026-01-01T10:00:00Z", "close", "other")), List.of(), 3));
        assertNull(StopGate.reason(S, List.of(), List.of(), 3));
    }

    @Test void aWarnAloneNeverBlocks() {
        assertNull(StopGate.reason(S, List.of(decision("2026-01-01T10:00:00Z", "warn", S)), List.of(), 3));
    }

    @Test void anOpenOrderBlocksAndNamesTheThreeSteps() {
        var r = StopGate.reason(S, List.of(decision("2026-01-01T10:00:00Z", "close", S)), List.of(text("2026-01-01T10:01:00Z", "done")), 3);
        assertNotNull(r);
        assertTrue(r.contains("proj prp logs") && r.contains("proj prp instructions") && r.toLowerCase().contains("status"), r);
    }

    @Test void bothCommandsRunAfterTheOrderLetItStop() {
        var log = List.of(decision("2026-01-01T10:00:00Z", "close", S));
        var t = List.of(toolUse("2026-01-01T10:02:00Z", "proj prp logs"), toolUse("2026-01-01T10:03:00Z", "proj prp instructions"));
        assertNull(StopGate.reason(S, log, t, 3));
    }

    @Test void oneCommandOfTwoStillBlocksAndSaysWhichIsMissing() {
        var log = List.of(decision("2026-01-01T10:00:00Z", "close", S));
        var r = StopGate.reason(S, log, List.of(toolUse("2026-01-01T10:02:00Z", "proj prp logs")), 3);
        assertNotNull(r);
        assertTrue(r.contains("proj prp instructions"), r);
    }

    @Test void commandsRunBeforeTheOrderDoNotCount() {
        var log = List.of(decision("2026-01-01T10:00:00Z", "close", S));
        var t = List.of(toolUse("2025-12-31T09:00:00Z", "proj prp logs"), toolUse("2025-12-31T09:01:00Z", "proj prp instructions"));
        assertNotNull(StopGate.reason(S, log, t, 3));
    }

    @Test void aMentionInTextIsNotARun() {
        var log = List.of(decision("2026-01-01T10:00:00Z", "close", S));
        var t = List.of(text("2026-01-01T10:02:00Z", "I will run proj prp logs and proj prp instructions"));
        assertNotNull(StopGate.reason(S, log, t, 3));
    }

    @Test void aClosedLineEndsTheOrder() {
        var log = List.of(decision("2026-01-01T10:00:00Z", "close", S), decision("2026-01-01T10:05:00Z", "closed", S));
        assertNull(StopGate.reason(S, log, List.of(), 3));
    }

    @Test void aNewerCloseAfterAClosedCycleBlocksAgain() {
        var log = List.of(decision("2026-01-01T10:00:00Z", "close", S), decision("2026-01-01T10:05:00Z", "closed", S), decision("2026-01-02T10:00:00Z", "close", S));
        assertNotNull(StopGate.reason(S, log, List.of(), 3));
    }

    @Test void theBlocksAreCappedSoTheSessionCanNeverBeStuck() {
        var log = List.of(decision("2026-01-01T10:00:00Z", "close", S), decision("2026-01-01T10:01:00Z", "block", S), decision("2026-01-01T10:02:00Z", "block", S), decision("2026-01-01T10:03:00Z", "block", S));
        assertNull(StopGate.reason(S, log, List.of(), 3));
        assertNotNull(StopGate.reason(S, log.subList(0, 3), List.of(), 3));
    }

    @Test void anyBrokenInputFailsOpen() {
        assertNull(StopGate.reason(null, List.of("x"), List.of("y"), 3));
        assertNull(StopGate.reason(S, List.of("garbage", "", "close session=" + S), List.of("{not json"), 3));
    }

    @Test void theHookAnswersWithTheBlockJsonOrNothingAndNeverThrows(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        var log = dir.resolve("close-decisions.log");
        Files.writeString(log, decision("2026-01-01T10:00:00Z", "close", S) + "\n");
        var tr = dir.resolve("t.jsonl");
        Files.writeString(tr, text("2026-01-01T10:01:00Z", "done") + "\n");
        var out = StopGate.hook("{\"session_id\":\"" + S + "\",\"transcript_path\":\"" + tr.toString().replace("\\", "\\\\") + "\"}", log, 3);
        assertTrue(out.contains("\"decision\":\"block\"") && out.contains("proj prp logs"), out);
        assertTrue(Files.readString(log).contains(" block "), "the block is recorded, for the cap");
        assertEquals("", StopGate.hook("not json at all", log, 3));
        assertEquals("", StopGate.hook("{\"session_id\":\"nobody\"}", log, 3));
    }
}
