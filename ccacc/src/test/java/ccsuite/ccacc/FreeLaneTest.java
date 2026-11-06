package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPICredential;
import ccsuite.localserver.lanes.LaneFront;
import ccsuite.webclient.FreeGauge;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/** The free lane against fake gauges and a fake proxy behind it: which accounts it serves from, probe sharing, and the forwarding. */
class FreeLaneTest {

    static CCAPICredential account(String id, Integer tier, boolean active, boolean watchOnly) {
        return new CCAPICredential().id(id).email(id + "@example.org").tier(tier).key("k").orgId("o").active(active).watchOnly(watchOnly);
    }

    final AtomicInteger probes = new AtomicInteger();
    final List<HttpServer> servers = new ArrayList<>();
    LaneFront front;

    @AfterEach void stop() {
        servers.forEach(s -> s.stop(0));
        if (front != null) front.stop();
    }

    FreeLane lane(Path dir, List<CCAPICredential> accounts, LaneSettings settings) {
        var gauges = new FreeGauges(() -> accounts, (c, m) -> { probes.incrementAndGet(); return new FreeGauge.Reading(0.4, 0.1, "within_limit", Instant.now().getEpochSecond() + 3600, m, 1, 1); }, dir);
        return new FreeLane(gauges, () -> accounts, settings);
    }

    @Test void theLaneServesFromEveryFreeAccountThatIsNotMonitorOnly(@TempDir Path dir) {
        var all = List.of(account("A", 0, true, false), account("B", 0, false, false), account("C", 0, true, true), account("D", 1, true, false), account("E", 0, true, false));
        assertEquals(List.of("A", "B", "E"), lane(dir, all, new LaneSettings()).chosen(), "active or not, but not monitor-only and not tier 1");
        assertEquals(List.of("B"), lane(dir, all, new LaneSettings().freeAccounts(List.of("B", "D"))).chosen(), "named accounts: free ones only");
    }

    @Test void theProxyIsGivenTheTablesReadingAndNeverCausesAProbe(@TempDir Path dir) {
        var a = account("A", 0, true, false);
        var l = lane(dir, List.of(a), new LaneSettings());
        assertNull(l.usageOf(a), "an account never read is unknown, and asking does not probe: a probe here would hold up every request");
        assertEquals(0, probes.get());
        l.gauges.rows.put("A", new FreeGauges.Row("A", "a", 0.4, 0.1, "within_limit", Instant.now().getEpochSecond() + 3600, Instant.now(), "m", null, 1));
        assertEquals(40.0, l.usageOf(a).fiveHour().utilization(), 0.001);
        assertEquals(10.0, l.usageOf(a).sevenDay().utilization(), 0.001);
        l.gauges.rows.put("A", new FreeGauges.Row("A", "a", 0.5, 0.2, "within_limit", null, Instant.now().minus(Duration.ofHours(2)), "m", null, 1));
        assertEquals(50.0, l.usageOf(a).fiveHour().utilization(), 0.001, "an old reading is still the answer: the background reader refreshes it, not the request");
        assertEquals(0, probes.get(), "no probe in any of these");
    }

    /** A fake proxy: answers every POST with the given status and body, and says which key header it saw. */
    int upstream(int status, String body) throws Exception {
        var s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            var in = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var out = (body + " saw:" + in + " key:" + ex.getRequestHeaders().getFirst("x-api-key") + " job:" + ex.getRequestHeaders().getFirst("x-ccacc-job")).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        s.start();
        servers.add(s);
        return s.getAddress().getPort();
    }

    HttpResponse<String> post(LaneFront f, String model) throws Exception {
        var body = "{\"model\":\"" + model + "\"}";
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + f.boundPort() + "/v1/messages")).header("x-api-key", "client-key").header("x-ccacc-job", "run-7").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    LaneFront front(FreeLane l) throws Exception {
        front = new LaneFront("fclaude", 0, l, true, r -> { });
        front.start();
        return front;
    }

    @Test void theRequestGoesToTheProxyWithoutTheClientsKeyButWithItsJobTag(@TempDir Path dir) throws Exception {
        var l = lane(dir, List.of(account("A", 0, true, false)), new LaneSettings());
        l.port = upstream(200, "ok");
        l.up = true;
        l.ids = List.of("A");
        var r = post(front(l), "claude-sonnet-5-5");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().startsWith("ok saw:{\"model\":\"claude-sonnet-5-5\"}"), r.body());
        assertTrue(r.body().contains("key:null"), "the client's key does not reach the proxy: " + r.body());
        assertTrue(r.body().endsWith("job:run-7"), "the job tag does: " + r.body());
    }

    @Test void aRefusalOfTheWebGoesBackAsItIs(@TempDir Path dir) throws Exception {
        var l = lane(dir, List.of(account("A", 0, true, false)), new LaneSettings());
        l.port = upstream(429, "{\"error\":\"used up\"}");
        l.up = true;
        l.ids = List.of("A");
        var r = post(front(l), "claude-sonnet-5-5");
        assertEquals(429, r.statusCode());
        assertTrue(r.body().contains("used up"));
    }

    static byte[] conversation(String first, int chars) throws Exception {
        var root = FreeLane.JSON.createObjectNode().put("model", "claude-sonnet-5-5");
        root.put("system", "You are a coding assistant.");
        var messages = root.putArray("messages");
        messages.addObject().put("role", "user").put("content", first);
        messages.addObject().put("role", "assistant").put("content", "x".repeat(Math.max(0, chars - 200)));
        return FreeLane.JSON.writeValueAsBytes(root);
    }

    FreeLane laneWithRoom(Path dir, double five) {
        var l = lane(dir, List.of(account("A", 0, true, false)), new LaneSettings());
        l.ids = List.of("A");
        l.gauges.rows.put("A", new FreeGauges.Row("A", "a", five, 0.1, "within_limit", Instant.now().getEpochSecond() + 3600, Instant.now(), "m", null, 1));
        return l;
    }

    @Test void aHugeNewConversationIsRefusedWithAReasonWhenNoAccountCanCarryIt(@TempDir Path dir) throws Exception {
        var l = laneWithRoom(dir, 0.61);
        var refusal = l.guard(conversation("where are we", 475_719));
        assertNotNull(refusal);
        assertTrue(refusal.contains("too long to start on the free lane") && refusal.contains("475") && refusal.contains("Compact it first"), refusal);
        assertTrue(refusal.contains("invalid_request_error"));
    }

    @Test void aBigNewConversationThatFitsTheRoomIsAllowed(@TempDir Path dir) throws Exception {
        assertNull(laneWithRoom(dir, 0.0).guard(conversation("hello", 60_000)), "about 8 points of 100");
        assertNotNull(laneWithRoom(dir, 0.95).guard(conversation("hello", 60_000)), "5 points of room left is not enough");
    }

    @Test void aSmallRequestIsNeverRefused(@TempDir Path dir) throws Exception {
        assertNull(laneWithRoom(dir, 0.99).guard(conversation("hello", 30_000)));
    }

    @Test void aConversationTheLaneAlreadyCarriesIsNeverRefusedWhateverItsSize(@TempDir Path dir) throws Exception {
        var l = laneWithRoom(dir, 0.9);
        var first = conversation("my long session", 300_000);
        assertNotNull(l.guard(first), "a new one of that size does not fit");
        l.remember(first);
        assertNull(l.guard(conversation("my long session", 310_000)), "the same session, grown: the chat is cached, only what is new is sent");
        assertNotNull(l.guard(conversation("another session", 300_000)));
    }

    @Test void aWindowThatHasResetCountsAsEmptyAndNoReadingsMeansNoGuard(@TempDir Path dir) throws Exception {
        var l = laneWithRoom(dir, 0.99);
        l.gauges.rows.put("A", new FreeGauges.Row("A", "a", 0.99, 0.1, "within_limit", Instant.now().getEpochSecond() - 60, Instant.now(), "m", null, 1));
        assertNull(l.guard(conversation("hello", 60_000)), "the five-hour window has reset since the reading, so it counts as empty");
        var none = lane(dir, List.of(), new LaneSettings());
        assertNull(none.guard(conversation("hello", 475_000)), "no free accounts known: nothing to compare with");
    }

    @Test void aFreeAccountAddedAfterTheLaneStartedCountsTowardsTheRoom(@TempDir Path dir) throws Exception {
        var current = new ArrayList<>(List.of(account("A", 0, true, false)));
        var l = lane(dir, current, new LaneSettings());
        l.ids = List.of("A");   // as start() leaves it: the accounts there were at that moment
        l.gauges.rows.put("A", new FreeGauges.Row("A", "a", 0.95, 0.1, "within_limit", Instant.now().getEpochSecond() + 3600, Instant.now(), "m", null, 1));
        var body = conversation("hello", 60_000);
        assertNotNull(l.guard(body), "one account with 5 points left cannot start it");
        current.add(account("B", 0, true, false));
        l.gauges.rows.put("B", new FreeGauges.Row("B", "b", 0.0, 0.1, "within_limit", Instant.now().getEpochSecond() + 3600, Instant.now(), "m", null, 1));
        assertNull(l.guard(body), "a second free account with plenty of room was added: the lane can use it now, so the guard must not refuse");
        assertEquals(100.0, l.bestRoomPoints(), 0.001);
    }

    // ---- PRP 78: the ledger, the estimator, the allocation, the guard on the ledger's room, the open calls

    static final long RESETS = Instant.now().getEpochSecond() + 3600;
    final List<String> places = Collections.synchronizedList(new ArrayList<>());

    /** A stub of the proxy: says which account served (as the proxy's response header does), records the placement hint it was given, and does {@code during} while the call runs (the gauge of the reply arriving). */
    int proxy(String account, Runnable during, CountDownLatch hold) throws Exception {
        var s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            places.add(String.valueOf(ex.getRequestHeaders().getFirst("x-ccacc-place")));
            ex.getRequestBody().readAllBytes();
            try { if (hold != null) hold.await(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            during.run();
            ex.getResponseHeaders().set("x-ccacc-account", account);
            var out = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        s.start();
        servers.add(s);
        return s.getAddress().getPort();
    }

    HttpResponse<String> postBody(LaneFront f, byte[] body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + f.boundPort() + "/v1/messages")).header("x-api-key", "k").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    FreeLane ledgered(Path dir, List<CCAPICredential> accounts) {
        var gauges = new FreeGauges(() -> accounts, (c, m) -> { probes.incrementAndGet(); return new FreeGauge.Reading(0.4, 0.1, "within_limit", RESETS, m, 1, 1); }, dir);
        var l = new FreeLane(gauges, () -> accounts, new LaneSettings(), new FreeLedger(dir.resolve("ledger")));
        l.ids = accounts.stream().map(CCAPICredential::id).toList();
        l.up = true;
        return l;
    }

    void read(FreeLane l, String id, double five) {
        l.gauges.rows.put(id, new FreeGauges.Row(id, id, five, 0.1, "within_limit", RESETS, Instant.now(), "m", null, 1));
    }

    static <T> void await(java.util.function.BooleanSupplier done) throws Exception {
        for (var i = 0; i < 100 && !done.getAsBoolean(); i++) Thread.sleep(50);
        assertTrue(done.getAsBoolean(), "did not happen in 5 s");
    }

    @Test void aServedCallIsInTheLedgerWithTheGaugeBeforeAndAfterAndTheCorrectionOfTheEstimate(@TempDir Path dir) throws Exception {
        var l = ledgered(dir, List.of(account("A", 0, true, false)));
        read(l, "A", 0.40);
        l.port = proxy("A", () -> l.gauges.observe("A", 0.55, 0.03, RESETS), null);
        var body = conversation("hello", 70_000);
        assertEquals(200, postBody(front(l), body).statusCode());
        await(() -> !l.ledger.calls(null).isEmpty());
        var c = l.ledger.calls(null).get(0);
        assertEquals("A", c.account());
        assertEquals(200, c.status());
        assertEquals("new", c.cache());
        assertEquals(body.length, c.chars());
        assertEquals(body.length / 4, c.tokens(), "characters over four");
        assertEquals(0.40, c.before5(), 1e-9);
        assertEquals(0.55, c.after5(), 1e-9);
        assertEquals(RESETS, c.reset5After());
        assertEquals(15.0, c.observed(), 1e-9, "the gauge moved by 15 points");
        assertEquals(2.2 * body.length / 10_000.0, c.predicted(), 1e-9);
        assertTrue(c.alone());
        var corrections = l.ledger.corrections(null);
        assertTrue(corrections.stream().anyMatch(x -> x.source().equals("call") && Math.abs(x.correction5() - (55 - (40 + c.predicted()))) < 1e-6), "the reading differed from the estimate and the size of the correction is logged: " + corrections);
        assertEquals(45, l.estimator.room("A", Instant.now()).now(), 1e-9);
    }

    @Test void theRoomSurvivesARestartOfTheLane(@TempDir Path dir) throws Exception {
        var accounts = List.of(account("A", 0, true, false));
        var l1 = ledgered(dir, accounts);
        read(l1, "A", 0.40);
        var carry = new AtomicBoolean(true);
        l1.port = proxy("A", () -> { if (carry.get()) l1.gauges.observe("A", 0.55, 0.03, RESETS); }, null);
        var f = front(l1);
        postBody(f, conversation("first conversation", 70_000));
        carry.set(false);   // the second call comes back with no gauge: only its predicted cost is known
        postBody(f, conversation("second conversation", 30_000));
        await(() -> l1.ledger.calls(null).size() == 2);
        var now = Instant.now();
        var before = l1.estimator.room("A", now);
        assertTrue(before.now() < 45 - 6, "the second call is in the room: " + before);
        var l2 = ledgered(dir, accounts);   // a new process over the same files
        var after = l2.estimator.room("A", now);
        assertEquals(before.now(), after.now(), 1e-9, "the same room read back");
        assertEquals(before.usedWeek(), after.usedWeek(), 1e-9);
        assertEquals(l1.estimator.rate("new"), l2.estimator.rate("new"), 1e-9);
    }

    @Test void aNewConversationIsPlacedOnTheAccountWithTheMostPredictedRoomAndTheProxyIsTold(@TempDir Path dir) throws Exception {
        var l = ledgered(dir, List.of(account("A", 0, true, false), account("B", 0, true, false)));
        read(l, "A", 0.70);
        read(l, "B", 0.40);
        l.port = proxy("B", () -> { }, null);
        var f = front(l);
        postBody(f, conversation("fresh one", 20_000));
        assertEquals(List.of("B"), places, "the hint names the account with the most room");
        postBody(f, conversation("fresh one", 25_000));
        await(() -> places.size() == 2);
        assertEquals("null", places.get(1), "the same session goes on where its chat is: no hint");
        await(() -> l.ledger.calls(null).size() == 2);
        assertEquals(List.of("new", "continued"), l.ledger.calls(null).stream().map(FreeLedger.Call::cache).toList());
    }

    @Test void theGuardUsesTheLedgersRoomAndTheRefusalSaysWhatWasPredictedAndWhenRoomComesBack(@TempDir Path dir) throws Exception {
        var l = ledgered(dir, List.of(account("A", 0, true, false)));
        var now = Instant.now();
        l.estimator.observe("A", now.minusSeconds(120), 0.50, 0.1, now.getEpochSecond() + 1800, "probe");   // the last reading says 50 points are left
        l.estimator.spent("A", now.minusSeconds(60), 40);                                                   // calls since then that carried no reading
        var refusal = l.guard(conversation("hello", 60_000));
        assertNotNull(refusal, "the reading alone would allow it; the ledger knows 40 more points were spent");
        assertTrue(refusal.contains("the ledger predicts") && refusal.contains("10 points"), refusal);
        assertTrue(refusal.matches("(?s).*room comes back with the next window reset, in about \\d+ min.*"), refusal);
    }

    @Test void aRefusalIsTheLastResortOnlyWhenNoAccountFits(@TempDir Path dir) throws Exception {
        var l = ledgered(dir, List.of(account("A", 0, true, false), account("B", 0, true, false)));
        read(l, "A", 0.95);
        read(l, "B", 0.10);
        assertNull(l.guard(conversation("hello", 60_000)), "A cannot, B can: allocation succeeds, no refusal");
        read(l, "B", 0.96);
        assertNotNull(l.guard(conversation("hello", 60_000)), "neither can");
    }

    @Test void theCallsInFlightAreListedWhileTheyRunAndAHoldTurnsNewOnesAway(@TempDir Path dir) throws Exception {
        var l = ledgered(dir, List.of(account("A", 0, true, false)));
        read(l, "A", 0.10);
        var release = new CountDownLatch(1);
        l.port = proxy("A", () -> { }, release);
        var f = front(l);
        var running = java.util.concurrent.CompletableFuture.supplyAsync(() -> { try { return postBody(f, conversation("slow one", 20_000)); } catch (Exception e) { throw new IllegalStateException(e); } });
        await(() -> l.openCalls().size() == 1);
        var open = l.openCalls().get(0);
        assertEquals("A", open.account());
        assertEquals("claude-sonnet-5-5", open.model());
        assertTrue(open.chars() > 19_000);
        assertTrue(FreeLane.drainVerdict(l.openCalls(), Instant.now(), Duration.ofMinutes(5)).startsWith("busy 1"));
        l.hold(true);
        assertEquals(503, postBody(f, conversation("another", 20_000)).statusCode(), "a held lane turns a new request away: the restart script is waiting for the running ones");
        release.countDown();
        assertEquals(200, running.get().statusCode());
        await(() -> l.openCalls().isEmpty());
        assertTrue(FreeLane.drainVerdict(l.openCalls(), Instant.now(), Duration.ofMinutes(5)).startsWith("idle"));
    }

    @Test void drainWaitsForRunningCallsAndIgnoresOnesStuckOverFiveMinutes() {
        var now = Instant.parse("2026-11-07T10:00:00Z");
        var young = new FreeLane.OpenCall(1, now.minusSeconds(10), "A", "m", 1000, "new");
        var stuck = new FreeLane.OpenCall(2, now.minusSeconds(400), "B", "m", 1000, "new");
        var five = Duration.ofMinutes(5);
        assertTrue(FreeLane.drainVerdict(List.of(), now, five).startsWith("idle"));
        assertTrue(FreeLane.drainVerdict(List.of(young), now, five).startsWith("busy 1"));
        assertTrue(FreeLane.drainVerdict(List.of(young, stuck), now, five).startsWith("busy 1"), "the stuck one does not hold the restart, the young one does");
        var v = FreeLane.drainVerdict(List.of(stuck), now, five);
        assertTrue(v.startsWith("stuck 1") && v.contains("400"), "a request stuck over 5 minutes lets the restart go, and says so: " + v);
    }

    @Test void aLaneThatDidNotStartAnswers503() throws Exception {
        var l = lane(Path.of("unused"), List.of(), new LaneSettings());
        assertEquals(503, post(front(l), "claude-haiku-5-5").statusCode());
    }
}
