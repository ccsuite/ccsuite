package ccsuite.ccrelay;

import module java.base;
import module java.net.http;
import module jdk.httpserver;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class RelayTest {

    static final String SECRET = "provider-secret-for-tests";

    HttpServer gate;
    RelayServer relay;
    RelayAgent agent;
    final List<String> gateSaw = new CopyOnWriteArrayList<>();
    final HttpClient http = HttpClient.newHttpClient();

    /** A stand-in for the gate: answers with three SSE chunks 300 ms apart, and notes what it was sent. */
    @BeforeEach void up() throws IOException {
        gate = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        gate.createContext("/", ex -> {
            try (ex) {
                gateSaw.add(ex.getRequestHeaders().getFirst("x-api-key") + "|" + ex.getRequestHeaders().getFirst("x-ccgate-relay")
                        + "|" + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                ex.getResponseHeaders().set("content-type", "text/event-stream");
                ex.getResponseHeaders().set("x-ccgate-grant", "ann");
                ex.sendResponseHeaders(200, 0);
                var out = ex.getResponseBody();
                for (var i = 1; i <= 3; i++) {
                    out.write(("event: part\ndata: " + i + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(300);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        gate.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        gate.start();
    }

    @AfterEach void down() {
        if (agent != null) agent.close();
        if (relay != null) relay.stop();
        gate.stop(0);
    }

    RelayServer relay(Duration pickWait) throws IOException {
        return relay = new RelayServer(0, RelayServer.hashHex(SECRET), pickWait, Duration.ofSeconds(20), Duration.ofSeconds(2)).start();
    }

    RelayAgent agent(String secret) {
        return agent = new RelayAgent(URI.create("http://127.0.0.1:" + relay.port()), secret,
                URI.create("http://127.0.0.1:" + gate.getAddress().getPort()), 2).start();
    }

    HttpRequest.Builder call(String key) {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + relay.port() + "/v1/messages"))
                .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"m\"}"));
        return key == null ? b : b.header("x-api-key", key);
    }

    @Test void aCallTravelsToTheGateAndItsAnswerStreamsBackChunkByChunk() throws Exception {
        relay(Duration.ofSeconds(5));
        agent(SECRET);
        var r = http.send(call("ccg_example").build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, r.statusCode());
        assertEquals("ann", r.headers().firstValue("x-ccgate-grant").orElse(null), "the gate's own headers come back");
        var times = new ArrayList<Long>();
        try (var lines = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
            for (String l; (l = lines.readLine()) != null; ) if (l.startsWith("data:")) times.add(System.nanoTime());
        }
        assertEquals(3, times.size());
        assertTrue(times.get(2) - times.get(0) > 400_000_000L, "chunks arrive as they are written, not all at the end");
        assertEquals("ccg_example|1|{\"model\":\"m\"}", gateSaw.get(0), "key and body untouched, marked as coming through the relay");
    }

    @Test void healthSaysWhetherTheProviderIsConnected() throws Exception {
        relay(Duration.ofSeconds(5));
        var health = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + relay.port() + "/healthz")).build();
        assertTrue(http.send(health, HttpResponse.BodyHandlers.ofString()).body().contains("offline"));
        agent(SECRET);
        Thread.sleep(800);
        assertTrue(http.send(health, HttpResponse.BodyHandlers.ofString()).body().contains("online"));
        agent.close();
        Thread.sleep(2500);
        assertTrue(http.send(health, HttpResponse.BodyHandlers.ofString()).body().contains("offline"), "gone within one poll interval");
    }

    @Test void noKeyIsRefusedAtTheRelay() throws Exception {
        relay(Duration.ofSeconds(5));
        agent(SECRET);
        assertEquals(401, http.send(call(null).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertTrue(gateSaw.isEmpty());
    }

    @Test void withoutTheProviderConnectedTheCallerHearsProviderOffline() throws Exception {
        relay(Duration.ofMillis(500));
        var r = http.send(call("ccg_example").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, r.statusCode());
        assertTrue(r.body().contains("Provider offline"));
    }

    @Test void aWrongSecretNeverServes() throws Exception {
        relay(Duration.ofMillis(800));
        var a = agent("not-the-secret");
        assertEquals(503, http.send(call("ccg_example").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertTrue(a.problem() != null && a.problem().contains("does not accept"));
        assertTrue(gateSaw.isEmpty());
    }

    @Test void severalCallsAtOnce() throws Exception {
        relay(Duration.ofSeconds(5));
        agent(SECRET);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var fs = new ArrayList<Future<Integer>>();
            for (var i = 0; i < 5; i++) fs.add(pool.submit(() -> http.send(call("ccg_example").build(), HttpResponse.BodyHandlers.ofString()).statusCode()));
            for (var f : fs) assertEquals(200, f.get(30, TimeUnit.SECONDS));
        }
        assertEquals(5, gateSaw.size());
    }
}
