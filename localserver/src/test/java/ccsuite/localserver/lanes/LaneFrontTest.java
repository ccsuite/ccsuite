package ccsuite.localserver.lanes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The two ports end to end over a real loopback port, with fake lanes behind them: pclaude is the paid lane for everything, fclaude the free lane for haiku and sonnet only. */
class LaneFrontTest {

    final FakeLane paid = new FakeLane("paid"), free = new FakeLane("free");
    final List<Routed> routed = new ArrayList<>();
    final List<LaneFront> started = new ArrayList<>();
    final HttpClient http = HttpClient.newHttpClient();

    LaneFront front(String name, Lane lane, boolean limited) throws Exception {
        var f = new LaneFront(name, 0, lane, limited, routed::add);
        f.start();
        started.add(f);
        return f;
    }

    @AfterEach void stop() { started.forEach(LaneFront::stop); }

    HttpResponse<String> post(LaneFront f, String path, String model) throws Exception {
        var body = "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + f.boundPort() + path)).header("x-ccacc-job", "t1").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void pclaudeSendsEveryModelAndPathToThePaidLane() throws Exception {
        var p = front("pclaude", paid, false);
        assertEquals("answered by paid", post(p, "/v1/messages", "claude-haiku-5-5").body());
        assertEquals("answered by paid", post(p, "/v1/messages", "claude-sonnet-5-5").body());
        assertEquals("answered by paid", post(p, "/v1/messages", "claude-opus-5-5").body());
        assertEquals("answered by paid", post(p, "/v1/messages/count_tokens", "claude-sonnet-5-5").body());
        assertEquals(4, paid.got.size());
        assertTrue(free.got.isEmpty());
    }

    @Test void fclaudeSendsHaikuAndSonnetToTheFreeLane() throws Exception {
        var f = front("fclaude", free, true);
        assertEquals("answered by free", post(f, "/v1/messages", "claude-haiku-5-5").body());
        assertEquals("answered by free", post(f, "/v1/messages", "claude-sonnet-5-5").body());
        assertEquals(2, free.got.size());
        assertTrue(paid.got.isEmpty());
        assertEquals("free", routed.get(0).lane());
        assertEquals("FREE", routed.get(0).account());
        assertEquals("t1", routed.get(0).job());
    }

    @Test void fclaudeRefusesOtherModelsAndNeverUsesAnotherLane() throws Exception {
        var f = front("fclaude", free, true);
        var r = post(f, "/v1/messages", "claude-opus-5-5");
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("haiku and sonnet only"));
        assertEquals("refused", routed.get(0).lane());
        assertTrue(free.got.isEmpty() && paid.got.isEmpty());
    }

    @Test void aFailureOfTheLaneGoesBackAsItIsAndAnUnavailableLaneIs503() throws Exception {
        var f = front("fclaude", free, true);
        free.failStatus = 429;
        assertEquals(429, post(f, "/v1/messages", "claude-sonnet-5-5").statusCode());
        free.failStatus = 0;
        free.available = false;
        assertEquals(503, post(f, "/v1/messages", "claude-sonnet-5-5").statusCode());
        assertTrue(paid.got.isEmpty(), "never moved to the paid lane");
    }

    @Test void fclaudeListsItsModelsAndRefusesOtherPaths() throws Exception {
        var f = front("fclaude", free, true);
        var models = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + f.boundPort() + "/v1/models")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, models.statusCode());
        assertTrue(models.body().contains("claude-haiku-5-5"));
        var other = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + f.boundPort() + "/v1/other")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, other.statusCode());
    }
}
