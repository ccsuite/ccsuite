package ccsuite.ccgate;

import module java.base;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PurposeCheckTest {

    @TempDir Path dir;

    static ObjectNode body(String json) throws IOException {
        return BodyRewrite.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    static Call person(String session) {
        var g = new Grant().id("ann").kind("person").purpose("editing archive videos").models(new ArrayList<>(List.of("haiku")));
        return new Call().grant(g).session(session).keyFingerprint("ab".repeat(32));
    }

    @Test void aNewUserTurnIsTheLastUserMessageWithTextOfItsOwn() throws IOException {
        assertEquals("cut the intro", PurposeCheck.newUserTurn(body("{\"messages\":[{\"role\":\"user\",\"content\":\"cut the intro\"}]}")));
        assertEquals("trim tape 3", PurposeCheck.newUserTurn(body("""
                {"messages":[{"role":"user","content":[{"type":"text","text":"<system-reminder>files changed</system-reminder>"},{"type":"text","text":"trim tape 3"}]}]}""")));
        assertNull(PurposeCheck.newUserTurn(body("""
                {"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"},{"type":"text","text":"<system-reminder>x</system-reminder>"}]}]}""")),
                "tool results and reminders are the same turn going on");
        assertNull(PurposeCheck.newUserTurn(body("{\"messages\":[{\"role\":\"assistant\",\"content\":\"done\"}]}")));
    }

    @Test void theJudgeGetsThePreviousTurnForContext() throws IOException {
        var b = body("""
                {"messages":[{"role":"user","content":"a recipe for paneer"},{"role":"assistant","content":"Sure."},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"x"}]},{"role":"assistant","content":"..."},
                {"role":"user","content":"list the spices again"}]}""");
        assertEquals("list the spices again", PurposeCheck.newUserTurn(b));
        assertEquals("a recipe for paneer", PurposeCheck.previousUserTurn(b), "tool results are skipped on the way back");
    }

    @Test void verdicts() {
        assertTrue(PurposeCheck.parse("yes: trimming footage is editing", null).within());
        var no = PurposeCheck.parse("No: a recipe has nothing to do with video editing", null);
        assertFalse(no.within());
        assertEquals("a recipe has nothing to do with video editing", no.reason());
        var unclear = PurposeCheck.parse("Well, this depends on what the spices are for and how", null);
        assertEquals("unclear", unclear.verdict(), "a rambling answer is recorded as unclear, never as a quiet yes");
        assertTrue(unclear.within(), "and it is not raised as a flag");
        assertEquals("no", PurposeCheck.parse("Thinking about it.\n**No** - recipes are not editing", null).verdict());
    }

    @Test void onlyPersonGrantsWithAPurposeAreChecked() {
        var tool = person("s1");
        tool.grant().kind("tool");
        assertFalse(PurposeCheck.applies(tool));
        var bare = person("s1");
        bare.grant().purpose(null);
        assertFalse(PurposeCheck.applies(bare));
        assertTrue(PurposeCheck.applies(person("s1")));
    }

    @Test void eachTurnIsCheckedOnceAndAFlagGivesOneNote() throws Exception {
        var judged = new AtomicInteger();
        var flags = new ArrayList<PurposeCheck.Flag>();
        var ledger = new Ledger(dir);
        var check = new PurposeCheck((purpose, previous, turn) -> {
            judged.incrementAndGet();
            return new PurposeCheck.Judgement("no", "a recipe is not video editing",
                    new LedgerEntry().account("ann-account").family("haiku").points(0.01));
        }, ledger).onFlag(flags::add);
        var call = person("s1");
        var b = body("{\"messages\":[{\"role\":\"user\",\"content\":\"a recipe for paneer\"}]}");
        check.start(call, b).run();
        assertNull(check.start(call, b), "the same turn again (a retry, a tool loop) is not checked twice");
        assertEquals(1, judged.get());
        assertEquals(1, flags.size());
        var note = check.noteFor(call);
        assertNotNull(note);
        assertTrue(note.contains("editing archive videos") && note.contains("a recipe is not video editing"));
        assertNull(check.noteFor(call), "the note is given once");
        assertNull(check.noteFor(person("another-session")));

        var lines = ledger.read(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        assertEquals(1, lines.size());
        var e = lines.get(0);
        assertEquals("purpose-check", e.outcome());
        assertEquals("no", e.purpose());
        assertEquals("ann", e.grant());
        assertFalse(e.toString().contains("paneer"), "the turn itself never reaches the ledger");

        var spend = Spend.from(ledger, 1);
        assertEquals(0, spend.today("ann", "haiku"), "the check is not charged to the grant's budget");
    }

    @Test void aFailedCheckIsRecordedAsUnchecked() throws Exception {
        var ledger = new Ledger(dir);
        var check = new PurposeCheck((purpose, previous, turn) -> { throw new IOException("no account"); }, ledger);
        check.start(person("s2"), body("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}")).run();
        var e = ledger.read(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60)).get(0);
        assertEquals("unchecked", e.purpose());
    }

    @Test void theNoteGoesAfterEverythingTheCallerSent() throws IOException {
        var b = body("{\"system\":[{\"type\":\"text\",\"text\":\"" + BodyRewrite.CLAUDE_CODE + "\"},{\"type\":\"text\",\"text\":\"big prompt\",\"cache_control\":{\"type\":\"ephemeral\"}}],\"messages\":[]}");
        BodyRewrite.addNote(b, "ccgate note: x");
        assertEquals(3, b.path("system").size());
        assertEquals("big prompt", b.path("system").get(1).path("text").asText());
        assertEquals("ccgate note: x", b.path("system").get(2).path("text").asText());
    }
}
