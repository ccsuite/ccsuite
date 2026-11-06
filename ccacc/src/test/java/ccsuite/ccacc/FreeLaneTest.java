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

    @Test void aLaneThatDidNotStartAnswers503() throws Exception {
        var l = lane(Path.of("unused"), List.of(), new LaneSettings());
        assertEquals(503, post(front(l), "claude-haiku-5-5").statusCode());
    }
}
