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

/** PRP 86 R2: the free lane tells the proxy, in a header of the loopback hop, the warn or close line for a request; the body is never changed. Neutral fixtures. */
class FreeLaneNoteTest {

    final List<HttpServer> servers = new ArrayList<>();
    LaneFront front;

    @AfterEach void stop() {
        servers.forEach(s -> s.stop(0));
        if (front != null) front.stop();
    }

    /** A fake proxy that answers with the note header and the length of the body it saw. */
    int upstream() throws Exception {
        var s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            var in = ex.getRequestBody().readAllBytes();
            var out = ("note:" + ex.getRequestHeaders().getFirst(FreeLane.NOTE_HEADER) + " len:" + in.length).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        s.start();
        servers.add(s);
        return s.getAddress().getPort();
    }

    FreeLane lane(Path dir, LaneSettings settings) throws Exception {
        var accounts = List.of(new CCAPICredential().id("A").email("a@example.org").tier(0).key("k").orgId("o").active(true).watchOnly(false));
        var gauges = new FreeGauges(() -> accounts, (c, m) -> new FreeGauge.Reading(0.0, 0.0, "within_limit", Instant.now().getEpochSecond() + 3600, m, 1, 1), dir);
        var l = new FreeLane(gauges, () -> accounts, settings, null);
        l.notes = new CloseNotes(settings, dir.resolve("decisions.log"));
        l.statusDir = dir.resolve("status");
        l.port = upstream();
        l.up = true;
        l.ids = List.of("A");
        front = new LaneFront("fclaude", 0, l, true, r -> { });
        front.start();
        return l;
    }

    static String body(String session, int chars) {
        return "{\"model\":\"claude-sonnet-5-5\",\"metadata\":{\"user_id\":\"{\\\"session_id\\\":\\\"" + session + "\\\"}\"},"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"" + "word ".repeat(chars / 5) + "\"}]}";
    }

    HttpResponse<String> post(String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + front.boundPort() + "/v1/messages")).header("x-api-key", "k").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Small zones so that a small fixture reaches them. */
    static LaneSettings small() {
        var s = new LaneSettings();
        s.startCostTokens = 0; s.workMarginTokens = 0;
        s.freeWarnTokens = 1_000; s.freeCloseTokens = 2_000;
        return s;
    }

    @Test void aRequestInTheWorkZoneCarriesNoNote(@TempDir Path dir) throws Exception {
        lane(dir, small());
        var r = post(body("s-work", 400));
        assertEquals(200, r.statusCode());
        assertTrue(r.body().startsWith("note:null"), r.body());
    }

    @Test void aRequestInTheCloseZoneCarriesTheOrderInTheHeaderAndItsBodyIsUnchanged(@TempDir Path dir) throws Exception {
        lane(dir, small());
        var b = body("s-close", 40_000);
        var r = post(b);
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("proj prp logs"), r.body());
        assertTrue(r.body().endsWith(" len:" + b.getBytes(StandardCharsets.UTF_8).length), "the body is forwarded as it came: " + r.body());
        assertTrue(Files.readString(dir.resolve("decisions.log")).contains("close"));
    }

    @Test void theHeaderIsOneAsciiLine(@TempDir Path dir) throws Exception {
        lane(dir, small());
        var r = post(body("s-ascii", 40_000));
        var note = r.body().substring("note:".length(), r.body().indexOf(" len:"));
        assertTrue(note.chars().allMatch(c -> c >= 32 && c < 127), note);
    }

    @Test void everyCallWritesTheSessionStatusFileWithTheServingAccountEvenInTheWorkZone(@TempDir Path dir) throws Exception {
        lane(dir, small());
        post(body("s-status", 400));
        var text = Files.readString(dir.resolve("status").resolve("s-status.txt"));
        assertTrue(text.contains("account=A") && text.contains("tokens=") && text.contains("close=") && text.contains("room="), text);
        var close = Long.parseLong(text.lines().filter(x -> x.startsWith("close=")).findFirst().orElseThrow().substring(6));
        assertTrue(close > 1_900 && close <= 2_000, "the close point is the setting scaled by the account's room (just under 100 points): " + close);
    }

    @Test void aRequestWithoutASessionIdWritesNoStatusFile(@TempDir Path dir) throws Exception {
        lane(dir, small());
        post("{\"model\":\"claude-sonnet-5-5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertFalse(Files.exists(dir.resolve("status")), "no session, no file");
    }

    @Test void aRequestWithoutASessionIdCarriesNoNote(@TempDir Path dir) throws Exception {
        lane(dir, small());
        var r = post("{\"model\":\"claude-sonnet-5-5\",\"messages\":[{\"role\":\"user\",\"content\":\"" + "word ".repeat(8_000) + "\"}]}");
        assertTrue(r.body().startsWith("note:null"), r.body());
    }
}
