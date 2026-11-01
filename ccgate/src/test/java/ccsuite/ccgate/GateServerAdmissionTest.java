package ccsuite.ccgate;

import module java.base;
import module java.net.http;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The wiring in the gate's request handling: unknown key, asked or not, and what a refused call leaves in the ledger. */
class GateServerAdmissionTest {

    @TempDir Path dir;
    GateServer gate;
    int port;

    @BeforeEach void up() throws IOException {
        try (var s = new ServerSocket(0)) { port = s.getLocalPort(); }
    }

    @AfterEach void down() {
        if (gate != null) gate.stop();
    }

    int post(String key) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/messages"))
                .header("x-api-key", key).header("user-agent", "example-tool/1.0")
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"claude-haiku-5-5\",\"max_tokens\":1,\"messages\":[]}")).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    GateServer start(Approver approver) throws IOException {
        var book = new GrantBook(dir.resolve("grants.json"));
        var ledger = new Ledger(dir.resolve("ledger"));
        var pricing = Pricing.standard();
        gate = new GateServer(port, book, new Policy(Spend.from(ledger, 1)), null, ledger, pricing).approver(approver);
        gate.start();
        return gate;
    }

    @Test void aDeclinedCallIsRefusedAndLedgered() throws Exception {
        start((r, wait) -> Optional.empty());
        assertEquals(403, post("unknown-key"));
        var lines = new Ledger(dir.resolve("ledger")).read(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        assertEquals(1, lines.size());
        assertEquals("refused", lines.get(0).outcome());
    }

    @Test void noOneToAskMeansTheOldRefusal() throws Exception {
        start(null);
        assertEquals(401, post("unknown-key"));
    }

    @Test void anApprovedKeyGetsPastThePolicy() throws Exception {
        start((r, wait) -> Optional.of(Approval.of(0, 7)));
        // The forwarder is absent in this test, so a call that passed the policy ends in the gate's own 502.
        assertEquals(502, post("unknown-key"));
        assertTrue(new GrantBook(dir.resolve("grants.json")).current().byKey(Keys.fingerprint("unknown-key")).isPresent());
    }
}
