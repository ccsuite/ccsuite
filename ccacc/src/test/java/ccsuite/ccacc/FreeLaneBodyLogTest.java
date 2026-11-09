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

/** PRP 87 R1/R3 through the lane: a served call is logged when the log is on, nothing is written when it is off, the forwarded body is never changed. Neutral fixtures. */
class FreeLaneBodyLogTest {

    final List<HttpServer> servers = new ArrayList<>();
    LaneFront front;

    @AfterEach void stop() {
        servers.forEach(s -> s.stop(0));
        if (front != null) front.stop();
    }

    int upstream() throws Exception {
        var s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            var in = ex.getRequestBody().readAllBytes();
            var out = ("len:" + in.length).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        s.start();
        servers.add(s);
        return s.getAddress().getPort();
    }

    FreeLane lane(Path dir, BodyLog log) throws Exception {
        var accounts = List.of(new CCAPICredential().id("A").email("a@example.org").tier(0).key("k").orgId("o").active(true).watchOnly(false));
        var gauges = new FreeGauges(() -> accounts, (c, m) -> new FreeGauge.Reading(0.0, 0.0, "within_limit", Instant.now().getEpochSecond() + 3600, m, 1, 1), dir);
        var l = new FreeLane(gauges, () -> accounts, new LaneSettings(), null);
        l.bodyLog = log;
        l.port = upstream();
        l.up = true;
        l.ids = List.of("A");
        front = new LaneFront("fclaude", 0, l, true, r -> { });
        front.start();
        return l;
    }

    static String body(String session, int turns) {
        var m = new StringBuilder();
        for (var i = 0; i < turns; i++) m.append(i == 0 ? "" : ",").append("{\"role\":\"").append(i % 2 == 0 ? "user" : "assistant").append("\",\"content\":\"turn ").append(i).append("\"}");
        return "{\"model\":\"claude-sonnet-5-5\",\"metadata\":{\"user_id\":\"{\\\"session_id\\\":\\\"" + session + "\\\"}\"},\"messages\":[" + m + "]}";
    }

    HttpResponse<String> post(String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + front.boundPort() + "/v1/messages")).header("x-api-key", "k").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The lane logs after it has closed the response, so the client may be ahead of the file: wait for the lines. */
    static List<ReplayedCall> awaitCalls(Path dir, String session, int n) throws Exception {
        var list = List.<ReplayedCall>of();
        for (var i = 0; i < 100 && list.size() < n; i++) {
            if (Files.isDirectory(dir)) list = BodyLog.replay(dir, session);
            if (list.size() < n) Thread.sleep(50);
        }
        return list;
    }

    @Test void aServedCallIsLoggedWithItsSessionAccountAndZoneAndTheBodyIsForwardedAsItCame(@TempDir Path dir) throws Exception {
        lane(dir, new BodyLog(dir.resolve("bodies"), 14));
        var b = body("s-log", 1);
        var r = post(b);
        assertEquals("len:" + b.getBytes(StandardCharsets.UTF_8).length, r.body());
        awaitCalls(dir.resolve("bodies"), "s-log", 1);   // the next call of a session only follows once the last one is answered and kept
        post(body("s-log", 3));
        var calls = awaitCalls(dir.resolve("bodies"), "s-log", 2);
        assertEquals(2, calls.size());
        assertEquals("A", calls.get(1).meta().path("account").asText());
        assertEquals("work", calls.get(1).meta().path("zone").asText());
        assertEquals(3, calls.get(1).request().path("messages").size());
    }

    @Test void withTheLogOffNothingIsWritten(@TempDir Path dir) throws Exception {
        lane(dir, null);
        assertEquals(200, post(body("s-off", 1)).statusCode());
        assertFalse(Files.exists(dir.resolve("bodies")));
    }

    @Test void aLogThatCannotBeWrittenNeverStopsTheCall(@TempDir Path dir) throws Exception {
        var file = Files.writeString(dir.resolve("file"), "x");
        lane(dir, new BodyLog(file.resolve("inside"), 14));
        assertEquals(200, post(body("s-fail", 1)).statusCode());
    }
}
