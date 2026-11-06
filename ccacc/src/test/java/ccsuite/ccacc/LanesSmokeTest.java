package ccsuite.ccacc;

import module java.base;
import module java.net.http;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A live check of the two ports with the free lane really running inside the process. Skipped unless {@code -Dlanes.smoke=<a sandbox home>} names a
 * folder whose {@code ccsuite/base/credentials.json} holds ONE free account (it is used as the home of the whole run, so nothing of the real ccacc is
 * touched); {@code -Dlanes.account=<its id>}. It sends two tiny requests to that account, Sonnet and Haiku through fclaude.
 * The paid lane has no accounts in the sandbox, so pclaude answers 503 and nothing ever moves between the lanes.
 */
class LanesSmokeTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();

    static HttpResponse<String> post(int port, String model) throws Exception {
        var body = "{\"model\":\"" + model + "\",\"max_tokens\":40,\"messages\":[{\"role\":\"user\",\"content\":\"Reply with the single word: ok\"}]}";
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/messages")).header("content-type", "application/json").header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofMinutes(3)).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test @EnabledIfSystemProperty(named = "lanes.smoke", matches = ".+")
    void everyPortAnswersAndRoutesAsItsModeSays() throws Exception {
        var home = Path.of(System.getProperty("lanes.smoke"));
        assertTrue(Files.exists(home.resolve("ccsuite/base/credentials.json")), "the sandbox home needs ccsuite/base/credentials.json");
        System.setProperty("user.home", home.toString());
        var core = new Core();
        var store = new CcaccSettingsStore(home.resolve("ccsuite/ccacc/settings.json"));
        var settings = new CcaccSettings();
        settings.lanes().enabled(true).pclaudePort(18681).fclaudePort(18686).freeAccounts(List.of(System.getProperty("lanes.account")));
        var host = new LaneHost(core, store, settings, FreeGauges.standard(() -> core.creds.credentials()));
        host.start();
        try {
            System.out.println(String.join("\n", host.report()));
            assertTrue(host.free.available(), "the free lane must be up: " + host.free.problem);
            var sonnet = post(18686, "claude-sonnet-5-5");
            System.out.println("fclaude sonnet -> " + sonnet.statusCode() + " " + sonnet.body());
            assertEquals(200, sonnet.statusCode());

            var haiku = post(18686, "claude-haiku-5-5");
            System.out.println("fclaude haiku -> " + haiku.statusCode() + " " + haiku.body());
            assertEquals(200, haiku.statusCode());
            assertTrue(haiku.body().contains("\"type\":\"message\"") || haiku.body().contains("\"content\""), haiku.body());

            var opus = post(18686, "claude-opus-5-5");
            System.out.println("fclaude opus -> " + opus.statusCode() + " " + opus.body());
            assertEquals(400, opus.statusCode(), "the free port serves haiku and sonnet only");

            var paid = post(18681, "claude-sonnet-5-5");
            System.out.println("pclaude sonnet (paid lane, no paid account in the sandbox) -> " + paid.statusCode() + " " + paid.body());
            assertEquals(503, paid.statusCode(), "the paid port never falls back to the free lane");

            host.service.recent().reversed().forEach(r -> System.out.println("  " + r.line()));
            assertTrue(host.service.recent().stream().noneMatch(r -> r.lane().equals("paid") && r.status() < 400), "nothing was served by the paid lane in the sandbox");
            assertFalse(host.free.gauges.snapshot().isEmpty(), "the probe that served the proxy's selector is in the gauge table");
            host.free.gauges.snapshot().forEach(r -> System.out.println("  gauge " + FreeGauges.line(r, Instant.now())));
        } finally {
            host.stop();
        }
    }
}
