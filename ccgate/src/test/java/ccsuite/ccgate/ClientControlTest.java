package ccsuite.ccgate;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The owner's controls over callers: approval before issuing, suspension, rate (refuse or queue), throttle, kick. */
class ClientControlTest {

    @TempDir Path dir;

    GrantBook book() {
        return new GrantBook(dir.resolve("grants.json"));
    }

    Call call(Grant g, String key) {
        var fp = Keys.fingerprint(key);
        return new Call().key(key).keyFingerprint(fp).grant(g).keyRef(g.key(fp).orElseThrow()).family("haiku").modelAsked("claude-haiku-4-5");
    }

    @Test
    void issuingNeedsTheOwnersYesAndWithoutAWindowNothingIsIssued() throws IOException {
        var book = book();
        assertThrows(IllegalStateException.class, () -> new GrantCommands(book).run(List.of("grant", "add", "film")));
        assertTrue(book.current().grants().isEmpty());

        var asked = new ArrayList<String>();
        var no = new GrantCommands(book, what -> { asked.add(what); return false; });
        assertThrows(IllegalStateException.class, () -> no.run(List.of("grant", "add", "film", "--models", "haiku")));
        assertTrue(book.current().grants().isEmpty(), "a declined request issues nothing");
        assertTrue(asked.get(0).contains("film") && asked.get(0).contains("haiku"), "the window says what would be issued: " + asked);

        var yes = new GrantCommands(book, what -> true);
        yes.run(List.of("grant", "add", "film", "--per-minute", "10", "--smooth"));
        assertTrue(book.current().byId("film").orElseThrow().smooth());
        var key = yes.run(List.of("key", "issue", "film", "--label", "laptop")).lines().findFirst().orElseThrow();
        assertTrue(key.startsWith("ccg_"));
        assertEquals(1, book.current().byId("film").orElseThrow().keys().size());

        var declinedKey = new GrantCommands(book, what -> false);
        assertThrows(IllegalStateException.class, () -> declinedKey.run(List.of("key", "issue", "film")));
        assertEquals(1, book.current().byId("film").orElseThrow().keys().size(), "a declined key is not issued");
    }

    @Test
    void wideningAGrantNeedsTheOwnersYesAndKeepsItsKeys() throws IOException {
        var book = book();
        var yes = new GrantCommands(book, what -> true);
        yes.run(List.of("grant", "add", "film", "--budget", "sonnet=10", "--per-minute", "5"));
        yes.run(List.of("key", "issue", "film"));

        var seen = new ArrayList<String>();
        var no = new GrantCommands(book, what -> { seen.add(what); return false; });
        assertThrows(IllegalStateException.class, () -> no.run(List.of("grant", "edit", "film", "--budget", "sonnet=500", "--per-minute", "120")));
        assertEquals(5, book.current().byId("film").orElseThrow().perMinute(), "declined: nothing changes");
        assertTrue(seen.get(0).contains("500") && seen.get(0).contains("120"), "the window lists the changes: " + seen);
        assertThrows(IllegalStateException.class, () -> new GrantCommands(book).run(List.of("grant", "edit", "film", "--per-minute", "9")));
        assertThrows(IllegalArgumentException.class, () -> yes.run(List.of("grant", "edit", "film")), "an edit with nothing to change says so");

        yes.run(List.of("grant", "edit", "film", "--budget", "sonnet=500,haiku=150", "--daily", "sonnet=400", "--per-minute", "120", "--smooth"));
        var g = book.current().byId("film").orElseThrow();
        assertEquals(500.0, g.budget().get("sonnet"));
        assertEquals(150.0, g.budget().get("haiku"));
        assertEquals(400.0, g.daily().get("sonnet"));
        assertEquals(120, g.perMinute());
        assertTrue(g.smooth());
        assertEquals(1, g.keys().size(), "the key stays");
    }

    @Test
    void restrictingNeedsNoApproval() throws IOException {
        var book = book();
        var cmds = new GrantCommands(book, what -> true);
        cmds.run(List.of("grant", "add", "film"));
        var key = cmds.run(List.of("key", "issue", "film")).lines().findFirst().orElseThrow();
        var alone = new GrantCommands(book);
        alone.run(List.of("grant", "ban", "film", "--hours", "2"));
        assertTrue(book.current().byId("film").orElseThrow().suspended(Instant.now()));
        alone.run(List.of("grant", "unban", "film"));
        assertFalse(book.current().byId("film").orElseThrow().suspended(Instant.now()));
        alone.run(List.of("grant", "throttle", "film", "2000"));
        assertEquals(2000, book.current().byId("film").orElseThrow().throttleBps());
        alone.run(List.of("grant", "rate", "film", "6", "--smooth"));
        assertEquals(6, book.current().byId("film").orElseThrow().perMinute());
        alone.run(List.of("grant", "revoke", "film"));
        var g = book.current().byId("film").orElseThrow();
        assertTrue(g.revoked() && g.keys().stream().allMatch(KeyRef::revoked), "kick revokes the keys too");
        assertNotNull(key);
    }

    @Test
    void aSuspendedCallerIsRefusedUntilLifted() throws IOException {
        var book = book();
        var cmds = new GrantCommands(book, what -> true);
        cmds.run(List.of("grant", "add", "film"));
        var key = cmds.run(List.of("key", "issue", "film")).lines().findFirst().orElseThrow();
        var clients = new Clients(book, new Spend(), new Activity());
        var policy = new Policy(new Spend());
        var g = book.current().byId("film").orElseThrow();
        assertTrue(policy.check(call(g, key), Instant.now()).isEmpty());

        clients.suspend("film", Duration.ofHours(1), "too many calls");
        g = book.current().byId("film").orElseThrow();
        var refusal = policy.check(call(g, key), Instant.now()).orElseThrow();
        assertEquals(403, refusal.status());
        assertTrue(refusal.reason().contains("suspended") && refusal.reason().contains("too many calls"), refusal.reason());
        assertEquals("suspended", clients.rows().get(0).status());

        clients.lift("film");
        g = book.current().byId("film").orElseThrow();
        assertTrue(policy.check(call(g, key), Instant.now()).isEmpty());
    }

    @Test
    void aSmoothedCallerWaitsForASlotWhereAnotherIsRefusedAtOnce() {
        var spend = new Spend();
        assertTrue(spend.tick("g", 2));
        assertTrue(spend.tick("g", 2));
        var t0 = System.nanoTime();
        assertFalse(spend.tick("g", 2), "plain: refused at once");
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 200);

        t0 = System.nanoTime();
        assertFalse(spend.awaitTick("g", 2, Duration.ofMillis(300)), "smoothed: waited the whole time, then gave up");
        var waited = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(waited >= 250 && waited < 1500, "waited " + waited + " ms");
        assertTrue(spend.awaitTick("h", 0, Duration.ofMillis(10)), "no limit, no wait");
    }

    @Test
    void aThrottledAnswerArrivesNoFasterThanTheLimitAndIntact() throws IOException {
        var data = new byte[3000];
        new Random(1).nextBytes(data);
        var sink = new ByteArrayOutputStream();
        var t0 = System.nanoTime();
        try (var out = new ThrottledOutput(sink, 10_000)) {
            out.write(data, 0, 1500);
            out.write(data, 1500, 1500);
        }
        var ms = (System.nanoTime() - t0) / 1_000_000;
        assertArrayEquals(data, sink.toByteArray());
        assertTrue(ms >= 250, "3000 bytes at 10000 B/s take about 300 ms, took " + ms);
        assertTrue(ms < 2000, "but not much longer: " + ms);
    }

    @Test
    void activityCountsCallsAndRefusalsPerGrant() throws IOException {
        var book = book();
        var cmds = new GrantCommands(book, what -> true);
        cmds.run(List.of("grant", "add", "film"));
        var key = cmds.run(List.of("key", "issue", "film")).lines().findFirst().orElseThrow();
        var g = book.current().byId("film").orElseThrow();
        var activity = new Activity();
        var c = call(g, key);
        activity.begin(c);
        var during = activity.snapshot("film");
        assertEquals(1, during.inFlight());
        assertEquals(1, during.lastMinute());
        activity.refused(c);
        activity.end(c);
        var after = activity.snapshot("film");
        assertEquals(0, after.inFlight());
        assertEquals(1, after.calls());
        assertEquals(1, after.refused());
        assertNotNull(after.lastSeen());
        assertEquals(Activity.Snapshot.NONE, activity.snapshot("nobody"));
    }
}
